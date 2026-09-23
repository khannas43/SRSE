package gov.rajasthan.smart.srse.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MultiTargetRecordMatchServiceTest {

    @Mock
    private RecordMatchService recordMatchService;

    @Mock
    private JdbcTemplate jdbc;

    private final GuardrailProperties guardrails = new GuardrailProperties(1000, 30, 50);
    private final AnalysisProperties analysisProperties = new AnalysisProperties(5, 120, 4, 2, 10, 3, 50_000_000L, 10);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MultiTargetRecordMatchService service;

    private static final String CATALOG = "iceberg_data";
    private static final String SCHEMA = "jan_aadhar_data_txn";

    @BeforeEach
    void setUp() {
        service = new MultiTargetRecordMatchService(
                recordMatchService, jdbc, guardrails, analysisProperties, objectMapper);
        lenient().when(recordMatchService.renderQueryForDisplay(any()))
                .thenReturn("SELECT ...");
    }

    private static MatchCriterion hub(String table, String column) {
        return new MatchCriterion(CATALOG, SCHEMA, table, column, null);
    }

    private static MatchCriterion tgt(String table, String column) {
        return new MatchCriterion(CATALOG, SCHEMA, table, column, null);
    }

    private static TargetMatchSpec target(String label, String table, List<MatchCriterion> join) {
        return new TargetMatchSpec(label, CATALOG, SCHEMA, table, join, List.of());
    }

    private static TargetMatchSpec target(String label, String table, List<MatchCriterion> join, JoinType joinType) {
        return new TargetMatchSpec(label, CATALOG, SCHEMA, table, join, List.of(), List.of(), joinType);
    }

    private MultiTargetRecordMatchRequest twoTargetRequest(HubSide hubSide) {
        return new MultiTargetRecordMatchRequest(
                List.of(hub("golden", "jan_aadhaar")),
                List.of(),
                hubSide,
                List.of(
                        target("Bank", "bank_txn", List.of(tgt("bank_txn", "ja_id"))),
                        target("Ration", "ration", List.of(tgt("ration", "aadhaar_no")))),
                false, null, null);
    }

    private RecordMatchService.MatchQuery queryWithSql(String sql) {
        return new RecordMatchService.MatchQuery(sql, List.of(), List.of("source_jan_aadhaar", "target_ja_id"));
    }

    private void stubHubValidation() {
        when(recordMatchService.validateHubShape(any(), any()))
                .thenReturn(new QualifiedTable(CATALOG, SCHEMA, "golden"));
    }

    private void stubJdbcRow(Map<String, String> columns) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(md);
        List<String> names = List.copyOf(columns.keySet());
        when(md.getColumnCount()).thenReturn(names.size());
        for (int i = 0; i < names.size(); i++) {
            int col = i + 1;
            String name = names.get(i);
            lenient().when(md.getColumnLabel(col)).thenReturn(name);
            lenient().when(md.getColumnName(col)).thenReturn(name);
            lenient().when(rs.getObject(col)).thenReturn(columns.get(name));
            lenient().when(rs.getObject(name)).thenReturn(columns.get(name));
        }
        doAnswer(inv -> {
            inv.getArgument(2, RowCallbackHandler.class).processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), any(Object[].class), any(RowCallbackHandler.class));
    }

    private String streamOutput(MultiTargetRecordMatchRequest req) throws Exception {
        StreamingResponseBody body = service.matchMulti(req);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * The SQL used to be promised on the {@code meta} line, which is serialised
     * and flushed before a single target has been planned — so every client
     * received a list of nulls and the officer's SQL panel was silently blank.
     * Each target announces its own query on its {@code started} event instead.
     */
    @Test
    void eachTargetsSqlRidesItsOwnStartedEvent() throws Exception {
        stubHubValidation();
        when(recordMatchService.planMatch(any()))
                .thenReturn(queryWithSql("JOIN bank ON hub"))
                .thenReturn(queryWithSql("JOIN ration ON hub"));
        when(recordMatchService.renderQueryForDisplay(any()))
                .thenAnswer(inv -> "RENDERED " + inv.getArgument(0, RecordMatchService.MatchQuery.class).sql());
        stubJdbcRow(Map.of("source_jan_aadhaar", "1", "target_ja_id", "1"));

        String out = streamOutput(twoTargetRequest(HubSide.SOURCE));

        String meta = out.lines().findFirst().orElseThrow();
        assertTrue(meta.contains("\"type\":\"meta\""), meta);
        assertFalse(meta.contains("perTargetSql"), meta);

        List<String> started = out.lines().filter(l -> l.contains("\"phase\":\"started\"")).toList();
        assertEquals(2, started.size());
        assertTrue(started.get(0).contains("RENDERED JOIN bank ON hub"), started.get(0));
        assertTrue(started.get(1).contains("RENDERED JOIN ration ON hub"), started.get(1));
    }

    /** A target that never planned has no query to show, so it skips straight to error. */
    @Test
    void planningFailureEmitsNoStartedEventForThatTarget() throws Exception {
        stubHubValidation();
        when(recordMatchService.planMatch(any()))
                .thenReturn(queryWithSql("SQL1"))
                .thenThrow(new IllegalArgumentException("ration table dropped"));
        stubJdbcRow(Map.of("source_jan_aadhaar", "1", "target_ja_id", "1"));

        String out = streamOutput(twoTargetRequest(HubSide.SOURCE));

        assertEquals(1, out.lines().filter(l -> l.contains("\"phase\":\"started\"")).count());
        assertTrue(out.contains("\"phase\":\"error\""), out);
    }

    @Test
    void twoTargetsEmitTwoSeparateJoinsNotNWay() throws Exception {
        stubHubValidation();
        when(recordMatchService.planMatch(any()))
                .thenReturn(queryWithSql("JOIN bank ON hub"))
                .thenReturn(queryWithSql("JOIN ration ON hub"));
        stubJdbcRow(Map.of("source_jan_aadhaar", "1", "target_ja_id", "1"));

        streamOutput(twoTargetRequest(HubSide.SOURCE));

        ArgumentCaptor<RecordMatchRequest> captor = ArgumentCaptor.forClass(RecordMatchRequest.class);
        verify(recordMatchService, times(2)).planMatch(captor.capture());
        assertEquals("ja_id", captor.getAllValues().get(0).targetCriteria().get(0).column());
        assertEquals("aadhaar_no", captor.getAllValues().get(1).targetCriteria().get(0).column());
        verify(jdbc, times(2)).query(anyString(), any(Object[].class), any(RowCallbackHandler.class));
    }

    @Test
    void hubSideTargetFlipsOutputPrefixesNotSql() throws Exception {
        stubHubValidation();
        when(recordMatchService.planMatch(any())).thenReturn(queryWithSql("JOIN bank ON hub"));
        stubJdbcRow(Map.of("source_jan_aadhaar", "H", "target_ja_id", "T"));

        String out = streamOutput(new MultiTargetRecordMatchRequest(
                List.of(hub("golden", "jan_aadhaar")),
                List.of(),
                HubSide.TARGET,
                List.of(target("Bank", "bank_txn", List.of(tgt("bank_txn", "ja_id")))),
                false, null, null));

        assertTrue(out.contains("\"target_jan_aadhaar\""), out);
        assertTrue(out.contains("\"Bank_source_ja_id\""), out);
        verify(recordMatchService).planMatch(any());
    }

    @Test
    void planningFailureOnSecondTargetStillStreamsFirst() throws Exception {
        stubHubValidation();
        when(recordMatchService.planMatch(any()))
                .thenReturn(queryWithSql("SQL1"))
                .thenThrow(new IllegalArgumentException("ration table dropped"));
        stubJdbcRow(Map.of("source_jan_aadhaar", "1", "target_ja_id", "1"));

        String out = streamOutput(twoTargetRequest(HubSide.SOURCE));

        assertTrue(out.contains("\"phase\":\"done\""), out);
        assertTrue(out.contains("\"phase\":\"error\""), out);
        assertTrue(out.contains("ration table dropped"), out);
        verify(jdbc, times(1)).query(anyString(), any(Object[].class), any(RowCallbackHandler.class));
    }

    @Test
    void executionFailureOnSecondTargetReportsError() throws Exception {
        stubHubValidation();
        when(recordMatchService.planMatch(any()))
                .thenReturn(queryWithSql("SQL1"))
                .thenReturn(queryWithSql("SQL2"));
        doAnswer(inv -> {
            if ("SQL2".equals(inv.getArgument(0))) {
                throw new RuntimeException("timeout");
            }
            RowCallbackHandler handler = inv.getArgument(2);
            ResultSet rs = mock(ResultSet.class);
            ResultSetMetaData md = mock(ResultSetMetaData.class);
            when(rs.getMetaData()).thenReturn(md);
            when(md.getColumnCount()).thenReturn(1);
            when(md.getColumnLabel(1)).thenReturn("source_jan_aadhaar");
            when(rs.getObject(1)).thenReturn("1");
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), any(Object[].class), any(RowCallbackHandler.class));

        String out = streamOutput(twoTargetRequest(HubSide.SOURCE));

        assertTrue(out.contains("\"phase\":\"error\""), out);
        assertTrue(out.contains("timeout"), out);
    }

    @Test
    void twoTargetsBothProjectingNameGetDistinctPrefixedColumns() throws Exception {
        stubHubValidation();
        when(recordMatchService.planMatch(any()))
                .thenReturn(new RecordMatchService.MatchQuery("SQL", List.of(), List.of("source_jan_aadhaar", "target_name")))
                .thenReturn(new RecordMatchService.MatchQuery("SQL", List.of(), List.of("source_jan_aadhaar", "target_name")));
        stubJdbcRow(Map.of("source_jan_aadhaar", "1", "target_name", "X"));

        MultiTargetRecordMatchRequest req = new MultiTargetRecordMatchRequest(
                List.of(hub("golden", "jan_aadhaar")),
                List.of(),
                HubSide.SOURCE,
                List.of(
                        target("Bank", "bank_txn", List.of(tgt("bank_txn", "name"))),
                        target("Ration", "ration", List.of(tgt("ration", "name")))),
                false, null, null);
        String out = streamOutput(req);

        assertTrue(out.contains("Bank_target_name"), out);
        assertTrue(out.contains("Ration_target_name"), out);
    }

    /**
     * An ANY_OF group's matched_on column has to reach the merged grid, or the
     * duplicate rows it produces (one per candidate column that matched) look
     * like a bug instead of the answer.
     */
    @Test
    void anyOfMatchedOnColumnsLandInTheMergedSuperset() throws Exception {
        stubHubValidation();
        when(recordMatchService.planMatch(any())).thenReturn(queryWithSql("JOIN bank ON hub"));
        stubJdbcRow(Map.of("source_jan_aadhaar", "1", "target_ja_id", "1",
                "target_g0_matched_on", "ja_id"));

        MatchGroup group = new MatchGroup(
                List.of(hub("golden", "jan_aadhaar")),
                List.of(tgt("bank_txn", "ja_id"), tgt("bank_txn", "legacy_id")),
                GroupMode.ANY_OF, null, null);
        String out = streamOutput(new MultiTargetRecordMatchRequest(
                List.of(hub("golden", "jan_aadhaar")),
                List.of(),
                HubSide.SOURCE,
                List.of(new TargetMatchSpec("Bank", CATALOG, SCHEMA, "bank_txn",
                        List.of(), List.of(), List.of(group))),
                false, null, null));

        assertTrue(out.contains("\"Bank_target_g0_matched_on\""), out);
        assertTrue(out.contains("\"Bank_target_ja_id\""), out);
        assertTrue(out.contains("\"Bank_target_legacy_id\""), out);
    }

    /** A target's groups name their own hub-side columns; they must be projected too. */
    @Test
    void groupHubColumnsAreProjectedOnceAcrossTargets() throws Exception {
        stubHubValidation();
        when(recordMatchService.planMatch(any())).thenReturn(queryWithSql("SQL"));
        stubJdbcRow(Map.of("source_jan_aadhaar", "1", "source_member_name", "A", "target_ja_id", "1"));

        MatchGroup group = new MatchGroup(
                List.of(hub("golden", "jan_aadhaar"), hub("golden", "member_name")),
                List.of(tgt("bank_txn", "ja_id")),
                GroupMode.COMBINE, null, null);
        String out = streamOutput(new MultiTargetRecordMatchRequest(
                List.of(hub("golden", "jan_aadhaar")),
                List.of(),
                HubSide.SOURCE,
                List.of(new TargetMatchSpec("Bank", CATALOG, SCHEMA, "bank_txn",
                        List.of(), List.of(), List.of(group))),
                false, null, null));

        assertTrue(out.contains("\"source_member_name\""), out);
        assertEquals(1, out.lines().filter(l -> l.contains("\"type\":\"meta\"")).count(), out);
    }

    @Test
    void dedupOnTargetTableRejectedBeforeStream() {
        stubHubValidation();

        MultiTargetRecordMatchRequest req = new MultiTargetRecordMatchRequest(
                List.of(hub("golden", "jan_aadhaar")),
                List.of(),
                HubSide.SOURCE,
                List.of(target("Bank", "bank_txn", List.of(tgt("bank_txn", "ja_id")))),
                false,
                new DedupSpec(CATALOG, SCHEMA, "bank_txn", "updated_at"),
                null);

        assertThrows(IllegalArgumentException.class, () -> service.matchMulti(req));
    }

    @Test
    void rejectsEmptyTargetsTooManyBlankAndDuplicateLabels() {
        stubHubValidation();

        assertThrows(IllegalArgumentException.class, () -> service.matchMulti(new MultiTargetRecordMatchRequest(
                List.of(hub("golden", "id")), List.of(), HubSide.SOURCE, List.of(), false, null, null)));

        List<TargetMatchSpec> six = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> target("T" + i, "t" + i, List.of(tgt("t" + i, "c"))))
                .toList();
        assertThrows(IllegalArgumentException.class, () -> service.matchMulti(new MultiTargetRecordMatchRequest(
                List.of(hub("golden", "id")), List.of(), HubSide.SOURCE, six, false, null, null)));

        assertThrows(IllegalArgumentException.class, () -> service.matchMulti(new MultiTargetRecordMatchRequest(
                List.of(hub("golden", "id")),
                List.of(),
                HubSide.SOURCE,
                List.of(target("  ", "a", List.of(tgt("a", "c")))),
                false, null, null)));

        assertThrows(IllegalArgumentException.class, () -> service.matchMulti(new MultiTargetRecordMatchRequest(
                List.of(hub("golden", "id")),
                List.of(),
                HubSide.SOURCE,
                List.of(
                        target("Same", "a", List.of(tgt("a", "c"))),
                        target("Same", "b", List.of(tgt("b", "c")))),
                false, null, null)));
    }

    @Test
    void budgetExhaustedSkipsRemainingTargets() throws Exception {
        service = new MultiTargetRecordMatchService(
                recordMatchService, jdbc, guardrails, new AnalysisProperties(5, 0, 4, 2, 10, 3, 50_000_000L, 10), objectMapper);
        stubHubValidation();

        String out = streamOutput(twoTargetRequest(HubSide.SOURCE));

        assertTrue(out.contains("\"phase\":\"skipped\""), out);
        assertTrue(out.contains("time budget"), out);
        verify(recordMatchService, times(0)).planMatch(any());
    }

    @Test
    void perTargetLeftJoinTypePassedToRecordMatchService() throws Exception {
        stubHubValidation();
        MultiTargetRecordMatchRequest req = new MultiTargetRecordMatchRequest(
                List.of(hub("golden", "jan_aadhaar")),
                List.of(),
                HubSide.SOURCE,
                List.of(target("Bank", "bank_txn", List.of(tgt("bank_txn", "ja_id")), JoinType.LEFT)),
                false, null, null);
        when(recordMatchService.planMatch(any())).thenReturn(queryWithSql("LEFT JOIN"));
        stubJdbcRow(Map.of("source_jan_aadhaar", "x", "target_ja_id", "y"));
        streamOutput(req);
        ArgumentCaptor<RecordMatchRequest> cap = ArgumentCaptor.forClass(RecordMatchRequest.class);
        verify(recordMatchService).planMatch(cap.capture());
        assertEquals(JoinType.LEFT, cap.getValue().joinType());
    }

    @Test
    void omittedJoinTypeUsesInnerSubMatch() throws Exception {
        stubHubValidation();
        when(recordMatchService.planMatch(any())).thenReturn(queryWithSql("JOIN"));
        stubJdbcRow(Map.of("source_jan_aadhaar", "x", "target_ja_id", "y"));
        streamOutput(twoTargetRequest(HubSide.SOURCE));
        ArgumentCaptor<RecordMatchRequest> cap = ArgumentCaptor.forClass(RecordMatchRequest.class);
        verify(recordMatchService, times(2)).planMatch(cap.capture());
        assertTrue(cap.getAllValues().stream().allMatch(r -> r.joinType() == null));
    }

    @Test
    void dedupWithRightJoinTargetRejectedBeforeStream() {
        stubHubValidation();
        DedupSpec dedup = new DedupSpec(CATALOG, SCHEMA, "golden", "updated_at");
        MultiTargetRecordMatchRequest req = new MultiTargetRecordMatchRequest(
                List.of(hub("golden", "jan_aadhaar")),
                List.of(),
                HubSide.SOURCE,
                List.of(target("Bank", "bank_txn", List.of(tgt("bank_txn", "ja_id")), JoinType.RIGHT)),
                false, dedup, null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.matchMulti(req));
        assertTrue(ex.getMessage().contains("Dedup"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Bank"), ex.getMessage());
    }

    @Test
    void ageFilterWithFullJoinTargetRejectedBeforeStream() {
        stubHubValidation();
        MultiTargetRecordMatchRequest req = new MultiTargetRecordMatchRequest(
                List.of(hub("golden", "jan_aadhaar")),
                List.of(),
                HubSide.SOURCE,
                List.of(target("Bank", "bank_txn", List.of(tgt("bank_txn", "ja_id")), JoinType.FULL)),
                false, null, new AgeFilterSpec(18, 60, "YEARS"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.matchMulti(req));
        assertTrue(ex.getMessage().contains("Age filter"), ex.getMessage());
        assertTrue(ex.getMessage().contains("FULL"), ex.getMessage());
    }
}
