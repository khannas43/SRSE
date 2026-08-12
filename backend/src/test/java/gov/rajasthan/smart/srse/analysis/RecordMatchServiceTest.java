package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.compiler.FieldResolver;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordMatchServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private AnalysisSchemaService schemaService;

    /** Mirrors StubFieldResolver's real mapping for age_years, used by the age filter. */
    private final FieldResolver fieldResolver = fieldKey -> {
        if ("age_years".equals(fieldKey)) {
            return "beneficiary.age_years";
        }
        throw new FieldResolver.UnknownFieldException(fieldKey);
    };

    /** analysisRowCap=1000, queryTimeoutSeconds=30. */
    private final GuardrailProperties guardrails = new GuardrailProperties(1000, 30, 1000);

    private RecordMatchService service;

    @BeforeEach
    void setUp() {
        service = new RecordMatchService(jdbc, schemaService, guardrails, fieldResolver);
    }

    private static MatchCriterion exact(String table, String column) {
        return new MatchCriterion(table, column, null);
    }

    private static MatchCriterion fuzzy(String table, String column, double thresholdPercent) {
        return new MatchCriterion(table, column, thresholdPercent);
    }

    private static RecordMatchRequest exactMatchRequest() {
        return new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
                false, null, null);
    }

    @Test
    void exactColumnMatchEmitsEqualityNotFuzzy() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        service.match(exactMatchRequest());

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> paramsCap = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sqlCap.capture(), paramsCap.capture());
        String sql = sqlCap.getValue();
        assertTrue(sql.contains("src.district = tgt.district"), sql);
        assertFalse(sql.contains("levenshtein_distance"), sql);
        assertEquals(0, paramsCap.getValue().length);
        verify(jdbc).setQueryTimeout(30);
    }

    @Test
    void nameColumnMatchEmitsFuzzySimilarityWithBoundThreshold() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        RecordMatchRequest req = new RecordMatchRequest(
                List.of(fuzzy("beneficiary", "father_name", 75.0)),
                List.of(exact("beneficiary", "father_name")),
                false, null, null);
        service.match(req);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> paramsCap = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sqlCap.capture(), paramsCap.capture());
        String sql = sqlCap.getValue();
        assertTrue(sql.contains("levenshtein_distance(lower(src.father_name), lower(tgt.father_name))"), sql);
        assertArrayEquals(new Object[]{0.75}, paramsCap.getValue());
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
    void addMoreProducesMultipleAndedCriteria() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        RecordMatchRequest req = new RecordMatchRequest(
                List.of(fuzzy("beneficiary", "father_name", 80.0), exact("beneficiary", "district")),
                List.of(exact("beneficiary", "father_name"), exact("beneficiary", "district")),
                false, null, null);
        service.match(req);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sqlCap.capture(), any(Object[].class));
        String sql = sqlCap.getValue();
        assertTrue(sql.contains("levenshtein_distance"), sql);
        assertTrue(sql.contains("src.district = tgt.district"), sql);
        assertTrue(sql.contains(" AND "), sql);
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
    void dedupWrapsQueryWithRowNumberPartitionedBySourceColumns() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
                false, new DedupSpec("beneficiary", "last_refreshed_at"), null);
        service.match(req);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sqlCap.capture(), any(Object[].class));
        String sql = sqlCap.getValue();
        assertTrue(sql.contains("ROW_NUMBER() OVER"), sql);
        assertTrue(sql.contains("PARTITION BY \"source_district\""), sql);
        assertTrue(sql.contains("WHERE rn = 1"), sql);
    }

    @Test
    void ageFilterResolvesAgeYearsAndBindsBothSides() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
                false, null,
                new AgeFilterSpec(18, 60, "YEARS"));
        service.match(req);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> paramsCap = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sqlCap.capture(), paramsCap.capture());
        String sql = sqlCap.getValue();
        assertTrue(sql.contains("src.age_years BETWEEN ? AND ?"), sql);
        assertTrue(sql.contains("tgt.age_years BETWEEN ? AND ?"), sql);
        // district=district is exact (no param); age filter adds 2+2 bounds (src, tgt).
        assertArrayEquals(new Object[]{18.0, 60.0, 18.0, 60.0}, paramsCap.getValue());
    }

    @Test
    void ageFilterConvertsMonthsAndDaysToFractionalYears() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        RecordMatchRequest req = new RecordMatchRequest(
                List.of(exact("beneficiary", "district")),
                List.of(exact("beneficiary", "district")),
                false, null,
                new AgeFilterSpec(12, 24, "MONTHS"));
        service.match(req);

        ArgumentCaptor<Object[]> paramsCap = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(anyString(), paramsCap.capture());
        assertArrayEquals(new Object[]{1.0, 2.0, 1.0, 2.0}, paramsCap.getValue());
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
    void reportsCappedWhenResultHitsRowCap() {
        List<Map<String, Object>> full = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            full.add(Map.of("source_district", "Jaipur"));
        }
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(full);

        RecordMatchResponse resp = service.match(exactMatchRequest());

        assertTrue(resp.capped());
        assertEquals(1000, resp.rows().size());
    }

    @Test
    void displaySqlHasNoRemainingPlaceholders() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        RecordMatchRequest req = new RecordMatchRequest(
                List.of(fuzzy("beneficiary", "father_name", 75.0)),
                List.of(exact("beneficiary", "father_name")),
                false, null, null);
        RecordMatchResponse resp = service.match(req);

        assertFalse(resp.sql().contains("?"), resp.sql());
        assertTrue(resp.sql().contains("0.75"), resp.sql());
    }
}
