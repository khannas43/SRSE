package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.compiler.FieldResolver;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RecordMatchService service;

    @BeforeEach
    void setUp() {
        // Default: no admin override registered — every existing test below
        // relies on falling back to the name-substring guess, unchanged.
        lenient().when(columnMetadata.findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                        anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        service = new RecordMatchService(jdbc, registry, guardrails, fieldResolver, columnMetadata, objectMapper);
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

    private static String qualified(String table) {
        return CATALOG + "." + SCHEMA + "." + table;
    }

    private static RecordMatchRequest exactMatchRequest() {
        return new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
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
                        1L, CATALOG, SCHEMA, "beneficiary", "guardian", "Guardian", true, true)));

        RecordMatchRequest req = new RecordMatchRequest(
                List.of(fuzzy("beneficiary", "guardian", 70.0)),
                List.of(exact("beneficiary", "guardian")),
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
                        1L, CATALOG, SCHEMA, "beneficiary", "scheme_name", "Scheme", false, true)));

        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "scheme_name")),
                List.of(exact("beneficiary", "scheme_name")),
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
                false, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.match(req));
    }

    @Test
    void addMoreProducesBlockingKeyAndEqualityInJoinOnFuzzyCheckInWhere() throws Exception {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(fuzzy("beneficiary", "father_name", 80.0), exact("beneficiary", "district")),
                List.of(exact("beneficiary", "father_name"), exact("beneficiary", "district")),
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
                false, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.match(req));
    }

    @Test
    void rejectsDifferentTablesOnSameSide() {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "father_name"), exact("other_table", "mother_name")),
                List.of(exact("beneficiary", "father_name"), exact("beneficiary", "mother_name")),
                false, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.match(req));
    }

    @Test
    void rejectsThresholdOutOfRange() {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(fuzzy("beneficiary", "father_name", 150.0)),
                List.of(exact("beneficiary", "father_name")),
                false, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.match(req));
    }

    @Test
    void dedupWrapsQueryWithRowNumberPartitionedBySourceColumns() throws Exception {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
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
                false, null,
                new AgeFilterSpec(60, 18, "YEARS"));

        assertThrows(IllegalArgumentException.class, () -> service.match(req));
    }

    @Test
    void rejectsUnknownAgeUnit() {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
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
                false, null, null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.match(req));
        assertTrue(e.getMessage().contains("same table"), e.getMessage());
    }

    /** Every criterion passes the registry gate before its name reaches SQL text. */
    @Test
    void criterionRejectedByTheRegistryNeverReachesTheDatabase() {
        doThrow(new IllegalArgumentException("Table is not registered for SRSE: a.b.c"))
                .when(registry).validateColumns(any(), any());

        assertThrows(IllegalArgumentException.class, () -> service.match(exactMatchRequest()));
        verify(jdbc, never()).query(anyString(), any(Object[].class), any(RowCallbackHandler.class));
    }

    /** Dedup's table is compared qualified, so a same-named table in another catalog is not "the source table". */
    @Test
    void dedupTableInAnotherCatalogIsRejected() {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
                false,
                new DedupSpec("iceberg_gold", SCHEMA, "beneficiary", "last_refreshed_at"),
                null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.match(req));
        assertTrue(e.getMessage().contains("source or target table"), e.getMessage());
    }

    /**
     * Resolving a table's columns walks the whole catalog/schema/table
     * hierarchy, so the gate is called ONCE per side with all of that side's
     * columns — not once per criterion.
     */
    @Test
    void gateIsCalledOncePerSideNotOncePerCriterion() throws Exception {
        runAndCapture(new RecordMatchRequest(
                List.of(exact("beneficiary", "district"), exact("beneficiary", "gender")),
                List.of(exact("beneficiary", "district"), exact("beneficiary", "gender")),
                false, null, null));

        ArgumentCaptor<java.util.Collection<String>> columns =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(registry, times(2)).validateColumns(any(), columns.capture());
        assertEquals(List.of("district", "gender"), List.copyOf(columns.getAllValues().get(0)));
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
                jdbc, registry, guardrails, dobResolver, columnMetadata, objectMapper);

        StreamingResponseBody body = dobService.match(new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
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
                jdbc, registry, guardrails, qualifiedResolver, columnMetadata, objectMapper);

        StreamingResponseBody body = qualifiedService.match(new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
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
                false, null,
                new AgeFilterSpec(18, 60, "YEARS")));

        assertFalse(c.sql().contains("WHERE  AND"), c.sql());
        assertTrue(c.sql().contains(" AND src.age_years BETWEEN ? AND ?"), c.sql());
    }
}
