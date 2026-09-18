package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.compiler.CompareAs;
import gov.rajasthan.smart.srse.compiler.FieldResolver;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService.RegisteredColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadata;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordMatchServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private LakehouseRegistryService registry;

    @Mock
    private AnalysisColumnMetadataRepository columnMetadata;

    /** Mirrors StubFieldResolver's real mapping for age_years, used by the age filter. */
    private final FieldResolver fieldResolver = fieldKey -> {
        if ("age_years".equals(fieldKey)) {
            return "beneficiary.age_years";
        }
        throw new FieldResolver.UnknownFieldException(fieldKey);
    };

    /** queryTimeoutSeconds=30. */
    private final GuardrailProperties guardrails = new GuardrailProperties(1000, 30);
    private final AnalysisProperties analysisProperties = new AnalysisProperties(5, 120, 4, 2);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RecordMatchService service;

    @BeforeEach
    void setUp() {
        // Default: no admin override registered — every existing test below
        // relies on falling back to the name-substring guess, unchanged.
        lenient().when(columnMetadata.findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                        anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        // Default: both sides carry whatever column the age expression names.
        // That is what every age test below assumed before the filter learned
        // to check — the tests that care about a side WITHOUT it say so.
        lenient().when(registry.hasColumns(any(), any())).thenReturn(true);
        service = new RecordMatchService(jdbc, registry, guardrails, fieldResolver, columnMetadata, analysisProperties, objectMapper);
    }

    /** Every criterion in these tests lives in one catalog+schema unless a test says otherwise. */
    private static final String CATALOG = "iceberg_data";
    private static final String SCHEMA = "jan_aadhar_data_txn";

    private static MatchCriterion exact(String table, String column) {
        return new MatchCriterion(CATALOG, SCHEMA, table, column, null);
    }

    private static MatchCriterion fuzzy(String table, String column, double thresholdPercent) {
        return new MatchCriterion(CATALOG, SCHEMA, table, column, thresholdPercent);
    }

    private static DisplayColumn display(String table, String column) {
        return new DisplayColumn(CATALOG, SCHEMA, table, column);
    }

    private static String qualified(String table) {
        return CATALOG + "." + SCHEMA + "." + table;
    }

    private static RecordMatchRequest exactMatchRequest() {
        return new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
                null, null,
                false, null, null);
    }

    private record Captured(String sql, Object[] params) {}

    /** Executes the streamed body against a throwaway sink and captures the SQL/params bound to {@code jdbc.query}. */
    private Captured runAndCapture(RecordMatchRequest req) throws Exception {
        StreamingResponseBody body = service.match(req);
        body.writeTo(new ByteArrayOutputStream());
        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> paramsCap = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sqlCap.capture(), paramsCap.capture(), any(RowCallbackHandler.class));
        return new Captured(sqlCap.getValue(), paramsCap.getValue());
    }

    /** Executes the streamed body and returns the raw NDJSON bytes written, for wire-format assertions. */
    private String writtenOutput(RecordMatchRequest req) throws Exception {
        StreamingResponseBody body = service.match(req);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void exactColumnMatchEmitsEqualityNotFuzzy() throws Exception {
        Captured c = runAndCapture(exactMatchRequest());
        assertTrue(c.sql().contains("src.district = tgt.district"), c.sql());
        assertFalse(c.sql().contains("levenshtein_distance"), c.sql());
        assertEquals(0, c.params().length);
        verify(jdbc).setQueryTimeout(30);
    }

    @Test
    void nameColumnMatchEmitsFuzzySimilarityWithBoundThreshold() throws Exception {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(fuzzy("beneficiary", "father_name", 75.0)),
                List.of(exact("beneficiary", "father_name")),
                null, null,
                false, null, null);
        Captured c = runAndCapture(req);
        assertTrue(c.sql().contains("levenshtein_distance(lower(src.father_name), lower(tgt.father_name))"), c.sql());
        assertArrayEquals(new Object[]{0.75}, c.params());
    }

    @Test
    void registeredFuzzyOverrideAppliesToNonNameColumn() throws Exception {
        // "guardian" has no "name" substring — substring guess alone would
        // treat this as exact. A registered fuzzy=true entry must override it.
        when(columnMetadata.findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                CATALOG, SCHEMA, "beneficiary", "guardian"))
                .thenReturn(Optional.of(new AnalysisColumnMetadata(
                        1L, new QualifiedColumn(CATALOG, SCHEMA, "beneficiary", "guardian"),
                        "Guardian", true, true)));

        RecordMatchRequest req = new RecordMatchRequest(
                List.of(fuzzy("beneficiary", "guardian", 70.0)),
                List.of(exact("beneficiary", "guardian")),
                null, null,
                false, null, null);
        Captured c = runAndCapture(req);
        assertTrue(c.sql().contains("levenshtein_distance"), c.sql());
    }

    @Test
    void registeredNonFuzzyOverrideAppliesToNameColumn() throws Exception {
        // "scheme_name" contains "name" — substring guess alone would fuzzy-
        // match it. A registered fuzzy=false entry must override that too.
        when(columnMetadata.findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                CATALOG, SCHEMA, "beneficiary", "scheme_name"))
                .thenReturn(Optional.of(new AnalysisColumnMetadata(
                        1L, new QualifiedColumn(CATALOG, SCHEMA, "beneficiary", "scheme_name"),
                        "Scheme", false, true)));

        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "scheme_name")),
                List.of(exact("beneficiary", "scheme_name")),
                null, null,
                false, null, null);
        Captured c = runAndCapture(req);
        assertTrue(c.sql().contains("src.scheme_name = tgt.scheme_name"), c.sql());
        assertFalse(c.sql().contains("levenshtein_distance"), c.sql());
    }

    @Test
    void nameColumnWithoutThresholdIsRejected() {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "father_name")),
                List.of(exact("beneficiary", "father_name")),
                null, null,
                false, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.match(req));
    }

    @Test
    void addMoreProducesBlockingKeyAndEqualityInJoinOnFuzzyCheckInWhere() throws Exception {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(fuzzy("beneficiary", "father_name", 80.0), exact("beneficiary", "district")),
                List.of(exact("beneficiary", "father_name"), exact("beneficiary", "district")),
                null, null,
                false, null, null);
        String sql = runAndCapture(req).sql();

        int onIdx = sql.indexOf(" ON ");
        int whereIdx = sql.indexOf(" WHERE ");
        assertTrue(onIdx > 0 && whereIdx > onIdx, sql);
        String onClause = sql.substring(onIdx, whereIdx);
        String whereClause = sql.substring(whereIdx);

        assertTrue(onClause.contains("substr(lower(src.father_name), 1, 3) = substr(lower(tgt.father_name), 1, 3)"), sql);
        assertTrue(onClause.contains("src.district = tgt.district"), sql);
        assertTrue(onClause.contains(" AND "), sql);
        assertFalse(onClause.contains("levenshtein_distance"), sql);
        assertTrue(whereClause.contains("levenshtein_distance"), sql);
    }

    @Test
    void rejectsMismatchedCriteriaSizes() {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "father_name"), exact("beneficiary", "mother_name")),
                List.of(exact("beneficiary", "father_name")),
                null, null,
                false, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.match(req));
    }

    @Test
    void rejectsDifferentTablesOnSameSide() {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "father_name"), exact("other_table", "mother_name")),
                List.of(exact("beneficiary", "father_name"), exact("beneficiary", "mother_name")),
                null, null,
                false, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.match(req));
    }

    @Test
    void rejectsThresholdOutOfRange() {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(fuzzy("beneficiary", "father_name", 150.0)),
                List.of(exact("beneficiary", "father_name")),
                null, null,
                false, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.match(req));
    }

    @Test
    void dedupWrapsQueryWithRowNumberPartitionedBySourceColumns() throws Exception {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
                null, null,
                false, new DedupSpec(CATALOG, SCHEMA, "beneficiary", "last_refreshed_at"), null);
        String sql = runAndCapture(req).sql();

        assertTrue(sql.contains("ROW_NUMBER() OVER"), sql);
        assertTrue(sql.contains("PARTITION BY \"source_district\""), sql);
        assertTrue(sql.contains("WHERE rn = 1"), sql);
    }

    @Test
    void ageFilterResolvesAgeYearsAndBindsBothSides() throws Exception {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
                null, null,
                false, null,
                new AgeFilterSpec(18, 60, "YEARS"));
        Captured c = runAndCapture(req);

        assertTrue(c.sql().contains("src.age_years BETWEEN ? AND ?"), c.sql());
        assertTrue(c.sql().contains("tgt.age_years BETWEEN ? AND ?"), c.sql());
        // district=district is exact (no param); age filter adds 2+2 bounds (src, tgt).
        assertArrayEquals(new Object[]{18.0, 60.0, 18.0, 60.0}, c.params());
    }

    @Test
    void ageFilterConvertsMonthsAndDaysToFractionalYears() throws Exception {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
                null, null,
                false, null,
                new AgeFilterSpec(12, 24, "MONTHS"));
        Captured c = runAndCapture(req);
        assertArrayEquals(new Object[]{1.0, 2.0, 1.0, 2.0}, c.params());
    }

    @Test
    void rejectsInvertedAgeBounds() {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
                null, null,
                false, null,
                new AgeFilterSpec(60, 18, "YEARS"));

        assertThrows(IllegalArgumentException.class, () -> service.match(req));
    }

    @Test
    void rejectsUnknownAgeUnit() {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
                null, null,
                false, null,
                new AgeFilterSpec(1, 2, "DECADES"));

        assertThrows(IllegalArgumentException.class, () -> service.match(req));
    }

    @Test
    void streamsMultipleRowsAsSeparateNdjsonLinesFollowedByDone() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(md);
        when(md.getColumnCount()).thenReturn(1);
        when(md.getColumnLabel(1)).thenReturn("source_district");
        when(rs.getObject(1)).thenReturn("Jaipur");

        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            handler.processRow(rs);
            handler.processRow(rs);
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), any(Object[].class), any(RowCallbackHandler.class));

        String[] lines = writtenOutput(exactMatchRequest()).strip().split("\n");

        assertEquals(5, lines.length, String.join("\n", lines)); // meta + 3 rows + done
        assertTrue(lines[0].contains("\"type\":\"meta\""), lines[0]);
        assertTrue(lines[1].contains("\"type\":\"row\""), lines[1]);
        assertTrue(lines[2].contains("\"type\":\"row\""), lines[2]);
        assertTrue(lines[3].contains("\"type\":\"row\""), lines[3]);
        assertTrue(lines[4].contains("\"type\":\"done\""), lines[4]);
        assertTrue(lines[4].contains("\"totalRows\":3"), lines[4]);
    }

    @Test
    void queryFailureProducesErrorLineInsteadOfPropagating() throws Exception {
        doThrow(new RuntimeException("presto timeout"))
                .when(jdbc).query(anyString(), any(Object[].class), any(RowCallbackHandler.class));

        String output = writtenOutput(exactMatchRequest());
        assertTrue(output.contains("\"type\":\"error\""), output);
        assertTrue(output.contains("presto timeout"), output);
    }

    @Test
    void displaySqlHasNoRemainingPlaceholders() throws Exception {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(fuzzy("beneficiary", "father_name", 75.0)),
                List.of(exact("beneficiary", "father_name")),
                null, null,
                false, null, null);
        String output = writtenOutput(req);
        String metaLine = output.strip().split("\n")[0];

        assertFalse(metaLine.contains("?"), metaLine);
        assertTrue(metaLine.contains("0.75"), metaLine);
    }

    // ---- Silver ↔ Gold: the two sides in different catalogs/schemas ----

    private static MatchCriterion in(String catalog, String schema, String table, String column) {
        return new MatchCriterion(catalog, schema, table, column, null);
    }

    /**
     * The whole point of qualifying identifiers: an admin registers a Silver
     * table and its Gold counterpart under different catalog/schema names,
     * and reconciling them is an ordinary two-table match that Presto joins
     * across catalogs natively.
     */
    @Test
    void matchesAcrossTwoDifferentCatalogs() throws Exception {
        Captured c = runAndCapture(new RecordMatchRequest(
                List.of(in("iceberg_silver", "jan_aadhar_data_txn", "tbl_txn_bankdtl", "account_no")),
                List.of(in("iceberg_gold", "golden_layer", "tbl_beneficiary_bank", "account_no")),
                null, null,
                false, null, null));

        assertTrue(c.sql().contains(
                "FROM iceberg_silver.jan_aadhar_data_txn.tbl_txn_bankdtl src"), c.sql());
        assertTrue(c.sql().contains(
                "JOIN iceberg_gold.golden_layer.tbl_beneficiary_bank tgt"), c.sql());
        assertTrue(c.sql().contains("src.account_no = tgt.account_no"), c.sql());
    }

    @Test
    void singleSidedMatchStillEmitsTheFullyQualifiedTable() throws Exception {
        Captured c = runAndCapture(exactMatchRequest());

        assertTrue(c.sql().contains("FROM " + qualified("beneficiary") + " src"), c.sql());
        assertTrue(c.sql().contains("JOIN " + qualified("beneficiary") + " tgt"), c.sql());
    }

    /**
     * Same bare table name, different catalog — these are two DIFFERENT
     * physical tables, so grouping them as one side would emit a join whose
     * ON clause silently compared a table against itself.
     */
    @Test
    void sameTableNameInDifferentCatalogsIsRejectedOnOneSide() {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(in("iceberg_silver", "s", "tbl_txn_bankdtl", "bank_id"),
                        in("iceberg_gold", "s", "tbl_txn_bankdtl", "m_id")),
                List.of(in("iceberg_silver", "s", "tbl_txn_bankdtl", "bank_id"),
                        in("iceberg_silver", "s", "tbl_txn_bankdtl", "m_id")),
                null, null,
                false, null, null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.match(req));
        assertTrue(e.getMessage().contains("same table"), e.getMessage());
    }

    /** Every criterion passes the registry gate before its name reaches SQL text. */
    @Test
    void criterionRejectedByTheRegistryNeverReachesTheDatabase() {
        doThrow(new IllegalArgumentException("Table is not registered for SRSE: a.b.c"))
                .when(registry).describeColumns(any(), any());

        assertThrows(IllegalArgumentException.class, () -> service.match(exactMatchRequest()));
        verify(jdbc, never()).query(anyString(), any(Object[].class), any(RowCallbackHandler.class));
    }

    /** Dedup's table is compared qualified, so a same-named table in another catalog is not "the source table". */
    @Test
    void dedupTableInAnotherCatalogIsRejected() {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
                null, null,
                false,
                new DedupSpec("iceberg_gold", SCHEMA, "beneficiary", "last_refreshed_at"),
                null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.match(req));
        assertTrue(e.getMessage().contains("source or target table"), e.getMessage());
    }

    /**
     * Resolving a table's columns walks the whole catalog/schema/table
     * hierarchy, so the gate is called ONCE per side with all of that side's
     * columns — not once per criterion. It is describeColumns rather than
     * validateColumns because the same lookup also yields the live types the
     * emitted SQL depends on.
     */
    @Test
    void gateIsCalledOncePerSideNotOncePerCriterion() throws Exception {
        runAndCapture(new RecordMatchRequest(
                List.of(exact("beneficiary", "district"), exact("beneficiary", "gender")),
                List.of(exact("beneficiary", "district"), exact("beneficiary", "gender")),
                null, null,
                false, null, null));

        ArgumentCaptor<java.util.Collection<String>> columns =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(registry, times(2)).describeColumns(any(), columns.capture());
        assertEquals(List.of("district", "gender"), List.copyOf(columns.getAllValues().get(0)));
    }

    // ---- age filter on sides that cannot carry it ----

    /**
     * The two sides of a match are arbitrary registered tables and typically
     * only one is a person table. Reported from a client deployment: matching
     * a member-id mapping table against the golden citizen table emitted
     * date_diff(... CAST(src.date_of_birth AS DATE) ...) against a table with
     * no date_of_birth, and Presto rejected the whole query — so setting an
     * age filter made the match impossible to run rather than narrower.
     */
    @Test
    void ageFilterSkipsASideWithoutTheDateOfBirthColumn() throws Exception {
        FieldResolver dobResolver = fieldKey -> {
            if ("age_years".equals(fieldKey)) {
                return "date_diff('year', CAST(golden.gold.citizen_360.date_of_birth AS DATE), current_date)";
            }
            throw new FieldResolver.UnknownFieldException(fieldKey);
        };
        service = new RecordMatchService(jdbc, registry, guardrails, dobResolver, columnMetadata, analysisProperties, objectMapper);
        when(registry.hasColumns(eq(new QualifiedTable(CATALOG, SCHEMA, "tbl_txn_member_id")), any()))
                .thenReturn(false);
        when(registry.hasColumns(eq(new QualifiedTable(CATALOG, SCHEMA, "citizen_360")), any()))
                .thenReturn(true);

        Captured c = runAndCapture(new RecordMatchRequest(
                List.of(exact("tbl_txn_member_id", "member_id")),
                List.of(exact("citizen_360", "jan_member_id")),
                null, null,
                false, null, new AgeFilterSpec(75, 100, "YEARS")));

        assertTrue(c.sql().contains("CAST(tgt.date_of_birth AS DATE)"), c.sql());
        assertFalse(c.sql().contains("src.date_of_birth"), c.sql());
    }

    /**
     * The params must follow the clauses that were actually emitted. Four were
     * previously bound unconditionally, so the moment one side stopped being
     * emitted the surviving clause would have silently read the wrong pair —
     * a filter that runs and returns the wrong cohort, which is worse than one
     * that fails.
     */
    @Test
    void ageFilterBindsOnlyTheBoundsItEmitted() throws Exception {
        when(registry.hasColumns(eq(new QualifiedTable(CATALOG, SCHEMA, "tbl_txn_member_id")), any()))
                .thenReturn(false);

        Captured c = runAndCapture(new RecordMatchRequest(
                List.of(exact("tbl_txn_member_id", "member_id")),
                List.of(exact("beneficiary", "member_id")),
                null, null,
                false, null, new AgeFilterSpec(75, 100, "YEARS")));

        assertArrayEquals(new Object[]{75.0, 100.0}, c.params());
    }

    /**
     * Silently returning an unfiltered cohort to an officer who asked for
     * 75-100 is the worst outcome available, so this fails loudly instead.
     */
    @Test
    void ageFilterOnTwoTablesThatCannotCarryItIsRejected() {
        when(registry.hasColumns(any(), any())).thenReturn(false);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.match(new RecordMatchRequest(
                List.of(exact("tbl_txn_member_id", "member_id")),
                        List.of(exact("tbl_txn_bankdtl", "member_id")),
                        null, null,
                        false, null, new AgeFilterSpec(75, 100, "YEARS"))));

        assertTrue(e.getMessage().contains("age filter cannot be applied"), e.getMessage());
        assertTrue(e.getMessage().contains("age_years"), e.getMessage());
    }

    // ---- CSV download: the same match, streamed straight to a file ----

    /** Executes the CSV body against a sink and returns exactly what was written. */
    private String csvOutput(RecordMatchRequest req) throws Exception {
        StreamingResponseBody body = service.matchCsv(req);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Feeds one fabricated row through whatever RowCallbackHandler is registered. */
    private void stubOneRow(Map<String, Object> row) throws Exception {
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnCount()).thenReturn(row.size());
        List<String> names = List.copyOf(row.keySet());
        for (int i = 0; i < names.size(); i++) {
            when(md.getColumnLabel(i + 1)).thenReturn(names.get(i));
        }
        ResultSet rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(md);
        for (int i = 0; i < names.size(); i++) {
            when(rs.getObject(i + 1)).thenReturn(row.get(names.get(i)));
        }
        doAnswer(inv -> {
            ((RowCallbackHandler) inv.getArgument(2)).processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), any(Object[].class), any(RowCallbackHandler.class));
    }

    @Test
    void csvWritesAHeaderRowAndTheValues() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source_district", "Jaipur");
        row.put("target_district", "Jaipur");
        stubOneRow(row);

        String csv = csvOutput(exactMatchRequest());

        assertTrue(csv.contains("source_district,target_district\r\n"), csv);
        assertTrue(csv.contains("Jaipur,Jaipur\r\n"), csv);
        verify(jdbc).setQueryTimeout(30);
    }

    /**
     * Without the BOM Excel reads the file in the local ANSI codepage and
     * mangles every Devanagari name in it — which is most of this data.
     */
    @Test
    void csvStartsWithAUtf8Bom() throws Exception {
        stubOneRow(new LinkedHashMap<>(Map.of("source_district", "जयपुर")));

        String csv = csvOutput(exactMatchRequest());

        assertEquals('﻿', csv.charAt(0));
        assertTrue(csv.contains("जयपुर"), csv);
    }

    /** RFC 4180: quote when it has to, double the quotes inside, null is empty. */
    @Test
    void csvQuotesSeparatorsQuotesAndNewlines() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source_district", "Jaipur, Rajasthan");
        row.put("target_district", "He said \"hello\"");
        stubOneRow(row);

        String csv = csvOutput(exactMatchRequest());

        assertTrue(csv.contains("\"Jaipur, Rajasthan\""), csv);
        assertTrue(csv.contains("\"He said \"\"hello\"\"\""), csv);
    }

    /**
     * The download is the only route to the rows once the grid bows out, so it
     * must be built from the query — not from anything the screen holds — and
     * carry no row cap of its own.
     */
    @Test
    void csvRunsTheSameUncappedQueryAsTheGrid() throws Exception {
        stubOneRow(new LinkedHashMap<>(Map.of("source_district", "Jaipur")));

        csvOutput(exactMatchRequest());

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCap.capture(), any(Object[].class), any(RowCallbackHandler.class));
        assertTrue(sqlCap.getValue().contains("src.district = tgt.district"), sqlCap.getValue());
        assertFalse(sqlCap.getValue().toUpperCase().contains("LIMIT"), sqlCap.getValue());
    }

    /** A rejected criterion must stop the download before a byte is written. */
    @Test
    void csvValidatesBeforeStreaming() {
        doThrow(new IllegalArgumentException("Table is not registered for SRSE: a.b.c"))
                .when(registry).describeColumns(any(), any());

        assertThrows(IllegalArgumentException.class, () -> service.matchCsv(exactMatchRequest()));
        verify(jdbc, never()).query(anyString(), any(Object[].class), any(RowCallbackHandler.class));
    }

    // ---- column groups: 1 column on one side vs N on the other (PR 3) ----

    private static MatchGroup combine(List<MatchCriterion> source, List<MatchCriterion> target,
                                      Double thresholdPercent) {
        return new MatchGroup(source, target, GroupMode.COMBINE, thresholdPercent, null);
    }

    private static MatchGroup anyOf(List<MatchCriterion> source, List<MatchCriterion> target) {
        return new MatchGroup(source, target, GroupMode.ANY_OF, null, null);
    }

    private static RecordMatchRequest grouped(List<MatchGroup> groups) {
        return new RecordMatchRequest(List.of(), List.of(), null, null, groups, false, null, null);
    }

    /**
     * The whole point of normalising legacy requests into single-column groups:
     * a group that folds nothing must emit what the criterion pair emitted, or
     * every test above is testing a path officers no longer take.
     */
    @Test
    void singleColumnGroupEmitsTheSameSqlAsTheLegacyPair() throws Exception {
        Captured grouped = runAndCapture(grouped(List.of(combine(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
                null))));
        reset(jdbc);
        Captured legacy = runAndCapture(exactMatchRequest());

        assertEquals(legacy.sql(), grouped.sql());
    }

    @Test
    void combineFoldsTheManySideIntoOneJoinPredicate() throws Exception {
        Captured c = runAndCapture(grouped(List.of(combine(
                List.of(exact("txn", "addr_full")),
                List.of(exact("golden", "line1"), exact("golden", "line2")),
                null))));

        assertTrue(c.sql().contains(
                "src.addr_full = array_join(filter(ARRAY[CAST(tgt.line1 AS VARCHAR), "
                        + "CAST(tgt.line2 AS VARCHAR)], x -> x IS NOT NULL AND x <> ''), ' ')"),
                c.sql());
        // One predicate, not two: the fold is the comparison.
        assertEquals(1, countOccurrences(onClauseOf(c.sql()), " = "), c.sql());
    }

    /**
     * A NULL middle name must not leave a doubled separator. Against Levenshtein
     * that stray character is an edit charged to every row with a missing middle
     * name — exactly the records the officer is trying to match.
     */
    @Test
    void combineFiltersNullAndEmptyMembersOutOfTheFold() throws Exception {
        Captured c = runAndCapture(grouped(List.of(combine(
                List.of(exact("txn", "addr_full")),
                List.of(exact("golden", "line1"), exact("golden", "line2"), exact("golden", "line3")),
                null))));

        assertTrue(c.sql().contains("x -> x IS NOT NULL AND x <> ''"), c.sql());
        assertFalse(c.sql().contains("concat_ws"), c.sql());
    }

    /** Exact matching is case-sensitive today; folding must not quietly change that. */
    @Test
    void exactCombineIsNotLowercased() throws Exception {
        Captured c = runAndCapture(grouped(List.of(combine(
                List.of(exact("txn", "addr")),
                List.of(exact("golden", "line1"), exact("golden", "line2")),
                null))));

        assertFalse(onClauseOf(c.sql()).contains("lower("), c.sql());
    }

    /**
     * A folded value is text by construction. Reading it as a number would
     * TRY_CAST a concatenated name to NULL on every row and return nothing —
     * so a multi-column COMBINE overrides the type alignment entirely.
     */
    @Test
    void multiColumnCombineComparesAsTextEvenAgainstANumericColumn() throws Exception {
        stubTypes("txn", "ref_no", "bigint");
        stubTypes("golden", "part_a", "varchar(10)", "part_b", "varchar(10)");

        Captured c = runAndCapture(grouped(List.of(combine(
                List.of(exact("txn", "ref_no")),
                List.of(exact("golden", "part_a"), exact("golden", "part_b")),
                null))));

        assertTrue(c.sql().contains("CAST(src.ref_no AS VARCHAR) = array_join("), c.sql());
        assertFalse(c.sql().contains("TRY_CAST(array_join"), c.sql());
    }

    @Test
    void anyOfPivotsTheManySideWithUnnestAndProjectsMatchedOn() throws Exception {
        Captured c = runAndCapture(grouped(List.of(anyOf(
                List.of(exact("txn", "account_no")),
                List.of(exact("golden", "ja_id"), exact("golden", "legacy_id"))))));

        assertTrue(c.sql().contains("CROSS JOIN UNNEST(ARRAY[t.ja_id, t.legacy_id], "
                + "ARRAY['ja_id', 'legacy_id']) AS u0 (g0_key, g0_matched_on)"), c.sql());
        assertTrue(c.sql().contains("src.account_no = tgt.g0_key"), c.sql());
        assertTrue(c.sql().contains("tgt.g0_matched_on AS \"target_g0_matched_on\""), c.sql());
        // The pivot lives in a subquery that still exposes the whole table, so
        // display columns and the age filter reach it exactly as before.
        assertTrue(c.sql().contains("JOIN (SELECT t.*, g0_key, g0_matched_on FROM "
                + qualified("golden") + " t CROSS JOIN UNNEST("), c.sql());
        assertTrue(c.sql().contains(") tgt ON "), c.sql());
        // The hub side has no ANY_OF group, so it stays a bare table reference.
        assertTrue(c.sql().contains("FROM " + qualified("txn") + " src JOIN ("), c.sql());
    }

    /**
     * The non-negotiable. A disjunctive ON costs Presto the hash join and drops
     * it to a nested loop over the cross product — the failure this service's
     * javadoc records having already removed once.
     */
    @Test
    void noGroupShapeEverEmitsOrInTheOnClause() throws Exception {
        List<List<MatchGroup>> shapes = List.of(
                List.of(anyOf(List.of(exact("txn", "a")),
                        List.of(exact("golden", "x"), exact("golden", "y")))),
                List.of(anyOf(List.of(exact("txn", "a"), exact("txn", "b")),
                        List.of(exact("golden", "x"), exact("golden", "y")))),
                List.of(combine(List.of(exact("txn", "full_name")),
                        List.of(exact("golden", "first_name"), exact("golden", "last_name")), 85.0)),
                List.of(anyOf(List.of(exact("txn", "a")), List.of(exact("golden", "x"), exact("golden", "y"))),
                        combine(List.of(exact("txn", "district")), List.of(exact("golden", "district")), null)));

        for (List<MatchGroup> shape : shapes) {
            reset(jdbc);
            Captured c = runAndCapture(grouped(shape));
            assertFalse(onClauseOf(c.sql()).contains(" OR "), c.sql());
        }
    }

    /** Both sides may be ANY_OF — each gets its own UNNEST, neither becomes an OR. */
    @Test
    void anyOfOnBothSidesUnnestsBoth() throws Exception {
        Captured c = runAndCapture(grouped(List.of(anyOf(
                List.of(exact("txn", "a1"), exact("txn", "a2")),
                List.of(exact("golden", "b1"), exact("golden", "b2"))))));

        assertEquals(2, countOccurrences(c.sql(), "CROSS JOIN UNNEST("), c.sql());
        assertTrue(c.sql().contains("src.g0_key = tgt.g0_key"), c.sql());
    }

    /** Within one family Presto unifies the array's element type; across families it cannot. */
    @Test
    void anyOfCastsToTextOnlyWhenTheCandidateColumnsDisagreeOnFamily() throws Exception {
        stubTypes("txn", "account_no", "varchar(20)");
        stubTypes("golden", "ja_id", "bigint", "legacy_id", "varchar(30)");

        Captured c = runAndCapture(grouped(List.of(anyOf(
                List.of(exact("txn", "account_no")),
                List.of(exact("golden", "ja_id"), exact("golden", "legacy_id"))))));

        assertTrue(c.sql().contains("ARRAY[CAST(t.ja_id AS VARCHAR), CAST(t.legacy_id AS VARCHAR)]"), c.sql());
    }

    @Test
    void fuzzyGroupBlocksAndScoresOverTheFoldedValue() throws Exception {
        Captured c = runAndCapture(new RecordMatchRequest(
                List.of(), List.of(),
                null, null,
                List.of(combine(
                        List.of(exact("txn", "full_name")),
                        List.of(exact("golden", "first_name"), exact("golden", "last_name")),
                        85.0)),
                true, null, null));

        assertTrue(c.sql().contains("substr(lower(array_join("), c.sql());
        assertTrue(c.sql().contains("levenshtein_distance(lower(src.full_name)"), c.sql());
        assertEquals(1, c.params().length);
        assertEquals(0.85, (Double) c.params()[0], 1e-9);
    }

    /** A group scores once however many columns it folds — N single-column groups score as before. */
    @Test
    void matchScoreAveragesOverGroupsNotColumns() throws Exception {
        Captured c = runAndCapture(new RecordMatchRequest(
                List.of(), List.of(),
                null, null,
                List.of(
                        combine(List.of(exact("txn", "full_name")),
                                List.of(exact("golden", "first_name"), exact("golden", "last_name")), 80.0),
                        combine(List.of(exact("txn", "district")), List.of(exact("golden", "district")), null)),
                true, null, null));

        assertTrue(c.sql().contains(") / 2 * 100, 1) AS \"match_score_pct\""), c.sql());
    }

    @Test
    void dedupPartitionsOverEverySourceSideJoinColumnAcrossGroups() throws Exception {
        Captured c = runAndCapture(new RecordMatchRequest(
                List.of(), List.of(),
                null, null,
                List.of(
                        combine(List.of(exact("txn", "a"), exact("txn", "b")),
                                List.of(exact("golden", "x")), null),
                        combine(List.of(exact("txn", "c")), List.of(exact("golden", "y")), null)),
                false,
                new DedupSpec(CATALOG, SCHEMA, "golden", "updated_at"),
                null));

        assertTrue(c.sql().contains("PARTITION BY \"source_a\", \"source_b\", \"source_c\""), c.sql());
    }

    @Test
    void rejectsAGroupWithNoColumnsOnASide() {
        RecordMatchRequest req = grouped(List.of(
                combine(List.of(exact("txn", "a")), List.of(), null)));
        assertThrows(IllegalArgumentException.class, () -> service.match(req));
    }

    @Test
    void rejectsMoreColumnsInAGroupThanTheCapAllows() {
        RecordMatchRequest req = grouped(List.of(combine(
                List.of(exact("txn", "a")),
                List.of(exact("golden", "w"), exact("golden", "x"), exact("golden", "y"),
                        exact("golden", "z"), exact("golden", "zz")),
                null)));
        assertThrows(IllegalArgumentException.class, () -> service.match(req));
    }

    @Test
    void rejectsMoreAnyOfGroupsOnOneSideThanTheCapAllows() {
        RecordMatchRequest req = grouped(List.of(
                anyOf(List.of(exact("txn", "a")), List.of(exact("golden", "x1"), exact("golden", "x2"))),
                anyOf(List.of(exact("txn", "b")), List.of(exact("golden", "y1"), exact("golden", "y2"))),
                anyOf(List.of(exact("txn", "c")), List.of(exact("golden", "z1"), exact("golden", "z2")))));
        assertThrows(IllegalArgumentException.class, () -> service.match(req));
    }

    @Test
    void rejectsGroupColumnsFromDifferentTablesOnOneSide() {
        RecordMatchRequest req = grouped(List.of(combine(
                List.of(exact("txn", "a")),
                List.of(exact("golden", "x"), exact("other", "y")),
                null)));
        assertThrows(IllegalArgumentException.class, () -> service.match(req));
    }

    /** Eight groups of four columns is 32 picks — the old per-side cap of 8 must not reject it. */
    @Test
    void groupsMayTotalMoreColumnsThanTheLegacyPerSideCap() throws Exception {
        List<MatchGroup> groups = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            groups.add(combine(
                    List.of(exact("txn", "s" + i)),
                    List.of(exact("golden", "t" + i + "a"), exact("golden", "t" + i + "b"),
                            exact("golden", "t" + i + "c"), exact("golden", "t" + i + "d")),
                    null));
        }
        Captured c = runAndCapture(grouped(groups));
        assertEquals(8, countOccurrences(onClauseOf(c.sql()), "array_join("), c.sql());
    }

    // ---- fuzzy eligibility across a folded group ----

    /** Registers one column's Admin fuzzy flag. */
    private void stubFuzzyFlag(String table, String column, boolean fuzzyMatchable) {
        when(columnMetadata.findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                CATALOG, SCHEMA, table, column))
                .thenReturn(Optional.of(new AnalysisColumnMetadata(
                        1L, new QualifiedColumn(CATALOG, SCHEMA, table, column),
                        null, fuzzyMatchable, true)));
    }

    /**
     * The case the group model made reachable and the single-column rule never
     * had to answer: two registered columns in one group disagreeing.
     *
     * <p>Fuzzy wins. A group wrongly forced exact returns almost nothing and
     * reads to the officer as "these datasets do not overlap" — a wrong answer
     * that looks like an answer. Wrongly fuzzy returns extra rows that carry a
     * match score and are tunable with the threshold they already control.
     */
    @Test
    void conflictingRegistrationsInOneGroupResolveToFuzzy() throws Exception {
        stubFuzzyFlag("golden", "emp_code", false);
        stubFuzzyFlag("golden", "given_part", true);

        // emp_code is listed FIRST deliberately: under the old "first
        // registered column wins" rule this group would have come out exact.
        Captured c = runAndCapture(grouped(List.of(combine(
                List.of(exact("txn", "ref")),
                List.of(exact("golden", "emp_code"), exact("golden", "given_part")),
                85.0))));

        assertTrue(c.sql().contains("levenshtein_distance"), c.sql());
    }

    /** Same rule across the two SIDES of a group, not just within one side. */
    @Test
    void conflictingRegistrationsAcrossTheTwoSidesResolveToFuzzy() throws Exception {
        // Source side registered exact, target side registered fuzzy — the old
        // rule short-circuited on the source and returned exact.
        stubFuzzyFlag("txn", "ref", false);
        stubFuzzyFlag("golden", "ref", true);

        Captured c = runAndCapture(grouped(List.of(combine(
                List.of(exact("txn", "ref")),
                List.of(exact("golden", "ref")),
                85.0))));

        assertTrue(c.sql().contains("levenshtein_distance"), c.sql());
    }

    /**
     * Registered columns decide as a bloc: an UNREGISTERED "*name*" column
     * beside a registered exact one must not drag the group back to fuzzy, or
     * the admin's explicit setting would be overturned by the guess.
     */
    @Test
    void anUnregisteredNameColumnDoesNotOutvoteARegisteredExactOne() throws Exception {
        stubFuzzyFlag("golden", "scheme_code", false);

        Captured c = runAndCapture(grouped(List.of(combine(
                List.of(exact("txn", "ref")),
                List.of(exact("golden", "scheme_code"), exact("golden", "holder_name")),
                null))));

        assertFalse(c.sql().contains("levenshtein_distance"), c.sql());
    }

    /** With nothing registered anywhere in the group, the name guess still applies. */
    @Test
    void anUnregisteredGroupStillFallsBackToTheNameGuess() throws Exception {
        Captured c = runAndCapture(grouped(List.of(combine(
                List.of(exact("txn", "ref")),
                List.of(exact("golden", "part_a"), exact("golden", "holder_name")),
                85.0))));

        assertTrue(c.sql().contains("levenshtein_distance"), c.sql());
    }

    /** The ON clause only — the SELECT list and WHERE legitimately contain other text. */
    private static String onClauseOf(String sql) {
        int on = sql.indexOf(" ON ");
        int where = sql.lastIndexOf(" WHERE ");
        return on < 0 ? "" : sql.substring(on, where > on ? where : sql.length());
    }

    // ---- mixed column types (CLAUDE.md: two tables rarely agree on a type) ----

    /**
     * Stubs the live types the registry reports for one table. Without a stub
     * a mocked describeColumns returns an empty map, which reads as "types
     * unknown" — deliberately the same as the pre-coercion behaviour, so every
     * test above stays about what it was about.
     */
    private void stubTypes(String table, String... columnAndType) {
        Map<String, RegisteredColumn> described = new LinkedHashMap<>();
        for (int i = 0; i < columnAndType.length; i += 2) {
            described.put(columnAndType[i], new RegisteredColumn(
                    columnAndType[i], columnAndType[i + 1], null, false, true));
        }
        lenient().when(registry.describeColumns(eq(new QualifiedTable(CATALOG, SCHEMA, table)), any()))
                .thenReturn(described);
    }

    private void stubCompareAs(String table, String column, CompareAs mode) {
        AnalysisColumnMetadata meta = new AnalysisColumnMetadata(
                1L, new QualifiedColumn(CATALOG, SCHEMA, table, column), null, false, true);
        meta.setCompareAs(mode);
        when(columnMetadata.findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                CATALOG, SCHEMA, table, column)).thenReturn(Optional.of(meta));
    }

    /**
     * The reported failure. An account number held as varchar in one table and
     * bigint in the other made Presto reject the whole query with
     * "'=' cannot be applied to varchar, bigint" — the officer saw a broken
     * match, not a match with no rows.
     */
    @Test
    void varcharVersusBigintJoinsThroughACast() throws Exception {
        stubTypes("txn_bank", "account_no", "varchar(20)");
        stubTypes("golden_bank", "account_no", "bigint");

        Captured c = runAndCapture(new RecordMatchRequest(
                List.of(exact("txn_bank", "account_no")),
                List.of(exact("golden_bank", "account_no")),
                null, null,
                false, null, null));

        assertTrue(c.sql().contains("TRY_CAST(src.account_no AS DOUBLE) = tgt.account_no"), c.sql());
    }

    /** Presto coerces integer↔bigint itself; a cast would only add noise. */
    @Test
    void twoNumericColumnsAreComparedDirectly() throws Exception {
        stubTypes("txn_bank", "account_no", "integer");
        stubTypes("golden_bank", "account_no", "bigint");

        Captured c = runAndCapture(new RecordMatchRequest(
                List.of(exact("txn_bank", "account_no")),
                List.of(exact("golden_bank", "account_no")),
                null, null,
                false, null, null));

        assertTrue(c.sql().contains("src.account_no = tgt.account_no"), c.sql());
        assertFalse(c.sql().contains("CAST"), c.sql());
    }

    /** The admin override wins, and one side carrying it is enough. */
    @Test
    void aTextOverrideOnOneSideForcesATextComparison() throws Exception {
        stubTypes("txn_bank", "account_no", "varchar(20)");
        stubTypes("golden_bank", "account_no", "bigint");
        stubCompareAs("txn_bank", "account_no", CompareAs.TEXT);

        Captured c = runAndCapture(new RecordMatchRequest(
                List.of(exact("txn_bank", "account_no")),
                List.of(exact("golden_bank", "account_no")),
                null, null,
                false, null, null));

        assertTrue(c.sql().contains("src.account_no = CAST(tgt.account_no AS VARCHAR)"), c.sql());
    }

    /**
     * lower() and levenshtein_distance take text only — a numeric side used to
     * fail with "Unexpected parameters", in the blocking key before the
     * similarity check was even reached.
     */
    @Test
    void aFuzzyPairCastsANumericSideToTextForBothBlockingAndSimilarity() throws Exception {
        stubTypes("txn_bank", "father_name", "varchar(60)");
        stubTypes("golden_bank", "father_name", "bigint");

        Captured c = runAndCapture(new RecordMatchRequest(
                List.of(fuzzy("txn_bank", "father_name", 80.0)),
                List.of(exact("golden_bank", "father_name")),
                null, null,
                false, null, null));

        assertTrue(c.sql().contains("substr(lower(CAST(tgt.father_name AS VARCHAR)), 1, 3)"), c.sql());
        assertTrue(c.sql().contains(
                "levenshtein_distance(lower(src.father_name), lower(CAST(tgt.father_name AS VARCHAR)))"),
                c.sql());
    }

    /**
     * The score and the join must be computed over the SAME expressions — a
     * score built from the raw columns would fail to compile for exactly the
     * pairs the join had just been fixed to handle.
     */
    @Test
    void theMatchScoreUsesTheSameCoercedReferencesAsTheJoin() throws Exception {
        stubTypes("txn_bank", "father_name", "varchar(60)");
        stubTypes("golden_bank", "father_name", "bigint");

        Captured c = runAndCapture(new RecordMatchRequest(
                List.of(fuzzy("txn_bank", "father_name", 80.0)),
                List.of(exact("golden_bank", "father_name")),
                null, null,
                true, null, null));

        assertTrue(c.sql().contains("match_score_pct"), c.sql());
        assertFalse(c.sql().contains("lower(tgt.father_name)"), c.sql());
    }

    // ---- age filter over a Tier-2 (DOB-derived) age mapping ----

    /**
     * Regression: with age_years mapped as a DOB expression, the age filter
     * used to take everything after the LAST dot — which lands inside the
     * expression — and emit
     * "src.date_of_birth, current_date) BETWEEN ? AND ?", SQL that does not
     * parse. The expression must be rebased onto each alias instead.
     */
    @Test
    void ageFilterRebasesADobDerivedAgeExpressionOntoBothAliases() throws Exception {
        FieldResolver dobResolver = fieldKey -> {
            if ("age_years".equals(fieldKey)) {
                return "date_diff('year', beneficiary.date_of_birth, current_date)";
            }
            throw new FieldResolver.UnknownFieldException(fieldKey);
        };
        RecordMatchService dobService = new RecordMatchService(
                jdbc, registry, guardrails, dobResolver, columnMetadata, analysisProperties, objectMapper);

        StreamingResponseBody body = dobService.match(new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
                null, null,
                false, null,
                new AgeFilterSpec(18, 60, "YEARS")));
        body.writeTo(new ByteArrayOutputStream());

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCap.capture(), any(Object[].class), any(RowCallbackHandler.class));
        String sql = sqlCap.getValue();

        assertTrue(sql.contains("date_diff('year', src.date_of_birth, current_date) BETWEEN ? AND ?"), sql);
        assertTrue(sql.contains("date_diff('year', tgt.date_of_birth, current_date) BETWEEN ? AND ?"), sql);
        // The old truncation emitted the expression's tail as a bare clause:
        // "AND src.date_of_birth, current_date) BETWEEN ...".
        assertFalse(sql.contains("AND src.date_of_birth,"), sql);
    }

    /** A fully-qualified plain mapping still collapses to alias + bare column. */
    @Test
    void ageFilterRebasesAFullyQualifiedPlainAgeColumn() throws Exception {
        FieldResolver qualifiedResolver = fieldKey -> {
            if ("age_years".equals(fieldKey)) {
                return "iceberg_gold.golden_layer.tbl_beneficiary.age_years";
            }
            throw new FieldResolver.UnknownFieldException(fieldKey);
        };
        RecordMatchService qualifiedService = new RecordMatchService(
                jdbc, registry, guardrails, qualifiedResolver, columnMetadata, analysisProperties, objectMapper);

        StreamingResponseBody body = qualifiedService.match(new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
                null, null,
                false, null,
                new AgeFilterSpec(18, 60, "YEARS")));
        body.writeTo(new ByteArrayOutputStream());

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCap.capture(), any(Object[].class), any(RowCallbackHandler.class));
        String sql = sqlCap.getValue();

        assertTrue(sql.contains("src.age_years BETWEEN ? AND ?"), sql);
        assertTrue(sql.contains("tgt.age_years BETWEEN ? AND ?"), sql);
        assertFalse(sql.contains("iceberg_gold.golden_layer.tbl_beneficiary.age_years BETWEEN"), sql);
    }

    /**
     * Regression: the age filter used to hardcode a leading " AND ". With
     * every criterion pair exact, nothing else writes to the WHERE clause, so
     * that produced "WHERE  AND date_diff(...)" — invalid SQL. Only ever
     * caught by asserting on the connector, since both the age expression and
     * the bounds looked right on their own.
     */
    @Test
    void ageFilterOverExactCriteriaEmitsNoDanglingAnd() throws Exception {
        Captured c = runAndCapture(new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
                null, null,
                false, null,
                new AgeFilterSpec(18, 60, "YEARS")));

        assertFalse(c.sql().contains("WHERE  AND"), c.sql());
        assertFalse(c.sql().contains("WHERE AND"), c.sql());
        assertTrue(c.sql().contains("WHERE src.age_years BETWEEN ? AND ?"), c.sql());
    }

    /** With a fuzzy pair already in the WHERE clause, the age filter must AND onto it. */
    @Test
    void ageFilterAndsOntoAnExistingFuzzyPredicate() throws Exception {
        Captured c = runAndCapture(new RecordMatchRequest(
                List.of(fuzzy("beneficiary", "father_name", 80.0)),
                List.of(exact("beneficiary", "father_name")),
                null, null,
                false, null,
                new AgeFilterSpec(18, 60, "YEARS")));

        assertFalse(c.sql().contains("WHERE  AND"), c.sql());
        assertTrue(c.sql().contains(" AND src.age_years BETWEEN ? AND ?"), c.sql());
    }

    // ---- display-only columns (PR 1: join vs select split) ----

    @Test
    void displayColumnsProjectedWithoutExtraJoinPredicates() throws Exception {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "jan_aadhaar")),
                List.of(exact("beneficiary", "ja_id")),
                List.of(display("beneficiary", "member_name_en"), display("beneficiary", "district_code")),
                List.of(display("beneficiary", "account_holder"), display("beneficiary", "ifsc")),
                false, null, null);
        Captured c = runAndCapture(req);

        int onIdx = c.sql().indexOf(" ON ");
        int whereIdx = c.sql().indexOf(" WHERE ");
        String onClause = c.sql().substring(onIdx, whereIdx);
        assertEquals(1, onClause.split("=", -1).length - 1, onClause);
        assertTrue(c.sql().contains("src.member_name_en AS \"source_member_name_en\""), c.sql());
        assertTrue(c.sql().contains("src.district_code AS \"source_district_code\""), c.sql());
        assertTrue(c.sql().contains("tgt.account_holder AS \"target_account_holder\""), c.sql());
        assertTrue(c.sql().contains("tgt.ifsc AS \"target_ifsc\""), c.sql());
    }

    @Test
    void displayColumnDuplicatingJoinCriterionIsProjectedOnce() throws Exception {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "jan_aadhaar")),
                List.of(exact("beneficiary", "ja_id")),
                List.of(display("beneficiary", "jan_aadhaar"), display("beneficiary", "ifsc")),
                List.of(),
                false, null, null);
        Captured c = runAndCapture(req);

        assertEquals(1, countOccurrences(c.sql(), "src.jan_aadhaar AS \"source_jan_aadhaar\""), c.sql());
        assertTrue(c.sql().contains("src.ifsc AS \"source_ifsc\""), c.sql());
    }

    @Test
    void displayColumnOnWrongTableIsRejected() {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
                List.of(display("other_table", "ifsc")),
                List.of(),
                false, null, null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.match(req));
        assertTrue(e.getMessage().contains("sourceDisplayColumns[0]"), e.getMessage());
    }

    @Test
    void displayColumnRejectedByRegistryNeverReachesDatabase() {
        doThrow(new IllegalArgumentException("Column not available"))
                .when(registry).validateColumn(any(QualifiedColumn.class));

        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
                List.of(display("beneficiary", "ifsc")),
                List.of(),
                false, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.match(req));
        verify(jdbc, never()).query(anyString(), any(Object[].class), any(RowCallbackHandler.class));
    }

    @Test
    void matchScoreIgnoresDisplayColumns() throws Exception {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(fuzzy("beneficiary", "father_name", 75.0)),
                List.of(exact("beneficiary", "father_name")),
                List.of(display("beneficiary", "ifsc")),
                List.of(display("beneficiary", "branch_code")),
                true, null, null);
        Captured c = runAndCapture(req);

        assertTrue(c.sql().contains("match_score_pct"), c.sql());
        assertTrue(c.sql().contains("/ 1 * 100"), c.sql());
        assertFalse(c.sql().contains("levenshtein_distance(lower(src.ifsc)"), c.sql());
    }

    @Test
    void dedupPartitionUsesJoinSourceKeysOnlyNotDisplayColumns() throws Exception {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "jan_aadhaar")),
                List.of(exact("beneficiary", "ja_id")),
                List.of(display("beneficiary", "ifsc")),
                List.of(),
                false, new DedupSpec(CATALOG, SCHEMA, "beneficiary", "last_refreshed_at"), null);
        String sql = runAndCapture(req).sql();

        assertTrue(sql.contains("PARTITION BY \"source_jan_aadhaar\""), sql);
        assertFalse(sql.contains("PARTITION BY \"source_ifsc\""), sql);
        assertTrue(sql.contains("src.ifsc AS \"source_ifsc\""), sql);
    }

    @Test
    void displayColumnsAppearInStreamMetaColumns() throws Exception {
        stubOneRow(new LinkedHashMap<>(Map.of(
                "source_jan_aadhaar", "1",
                "target_ja_id", "1",
                "source_ifsc", "SBIN0001")));

        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "jan_aadhaar")),
                List.of(exact("beneficiary", "ja_id")),
                List.of(display("beneficiary", "ifsc")),
                List.of(),
                false, null, null);
        String output = writtenOutput(req);

        assertTrue(output.contains("\"source_ifsc\""), output);
    }

    @Test
    void gateIncludesDisplayColumnNamesInSingleDescribeCall() throws Exception {
        runAndCapture(new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
                List.of(display("beneficiary", "ifsc")),
                List.of(),
                false, null, null));

        ArgumentCaptor<java.util.Collection<String>> columns =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(registry, times(2)).describeColumns(any(), columns.capture());
        assertEquals(List.of("district", "ifsc"), List.copyOf(columns.getAllValues().get(0)));
    }

    @Test
    void allocateUniqueAliasSuffixesWhenBaseIsTaken() {
        Set<String> used = new LinkedHashSet<>(List.of("source_foo", "match_score_pct"));
        assertEquals("source_foo_2", RecordMatchService.allocateUniqueAlias("source_foo", used));
    }

    @Test
    void duplicateJoinCriteriaColumnsGetDistinctSelectAliases() throws Exception {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "district"), exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district"), exact("beneficiary", "district")),
                null, null,
                false, null, null);
        Captured c = runAndCapture(req);
        assertTrue(c.sql().contains("src.district AS \"source_district\""), c.sql());
        assertTrue(c.sql().contains("src.district AS \"source_district_2\""), c.sql());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
