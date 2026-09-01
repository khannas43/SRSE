package gov.rajasthan.smart.srse.execution;

import gov.rajasthan.smart.srse.compiler.Ast;
import gov.rajasthan.smart.srse.compiler.FieldResolver;
import gov.rajasthan.smart.srse.compiler.RuleCompiler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ExecutionService unit tests — Mockito over JdbcTemplate; no live Presto.
 *
 * Predicate fixture mirrors RuleCompilerTest: age_years &gt;= 18 via a trivial
 * FieldResolver mapping keys to {@code beneficiary.<key>} — same resolver is
 * now also used directly by ExecutionService (not just RuleCompiler) to
 * derive the table/district/gender columns, so it must map those two keys too.
 */
@ExtendWith(MockitoExtension.class)
class ExecutionServiceTest {

    private final FieldResolver resolver = fieldKey -> {
        if ("age_years".equals(fieldKey) || "district".equals(fieldKey) || "gender".equals(fieldKey)) {
            return "beneficiary." + fieldKey;
        }
        throw new FieldResolver.UnknownFieldException(fieldKey);
    };

    private final RuleCompiler compiler = new RuleCompiler(resolver);

    /** cohortCap=1000, queryTimeoutSeconds=30 — constructed directly (no Spring). */
    private final GuardrailProperties guardrails = new GuardrailProperties(1000, 30);

    @Mock
    private JdbcTemplate jdbc;

    private ExecutionService service;

    private static Ast.PredicateSpec ageGte18() {
        return new Ast.PredicateSpec(
                new Ast.PredicateNode("age_years", Ast.Operator.GTE, 18));
    }

    @BeforeEach
    void setUp() {
        service = new ExecutionService(compiler, jdbc, guardrails, resolver, "age_band");
    }

    @Test
    void countEmitsAggregateSqlAndBoundParams() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(42L);

        long n = service.count(ageGte18());

        assertEquals(42L, n);
        verify(jdbc).setQueryTimeout(30);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> paramsCap = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(sqlCap.capture(), eq(Long.class), paramsCap.capture());

        String sql = sqlCap.getValue();
        assertTrue(sql.contains("SELECT COUNT(*)"), sql);
        assertTrue(sql.contains("FROM beneficiary"), sql);
        assertTrue(sql.contains("WHERE"), sql);
        assertTrue(sql.contains("beneficiary.age_years >= ?"), sql);
        assertFalse(sql.toUpperCase().contains("SELECT *"), sql);

        assertArrayEquals(new Object[]{18}, paramsCap.getValue());
    }

    @Test
    void breakdownEmitsFixedGroupByAndBoundParams() {
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<BreakdownRow>>any(), any(Object[].class)))
                .thenReturn(List.of(new BreakdownRow("Jaipur", "FEMALE", "18-59", 10L)));

        List<BreakdownRow> rows = service.breakdown(ageGte18());

        assertEquals(1, rows.size());
        assertEquals("Jaipur", rows.get(0).district());
        verify(jdbc).setQueryTimeout(30);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> paramsCap = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sqlCap.capture(), ArgumentMatchers.<RowMapper<BreakdownRow>>any(),
                paramsCap.capture());

        String sql = sqlCap.getValue();
        assertTrue(sql.contains(
                "SELECT beneficiary.district AS district, beneficiary.gender AS gender, "
                        + "beneficiary.age_band AS age_band, COUNT(*) AS n"), sql);
        assertTrue(sql.contains("FROM beneficiary"), sql);
        assertTrue(sql.contains("WHERE"), sql);
        assertTrue(sql.contains(
                "GROUP BY beneficiary.district, beneficiary.gender, beneficiary.age_band"), sql);
        assertTrue(sql.contains("beneficiary.age_years >= ?"), sql);

        assertArrayEquals(new Object[]{18}, paramsCap.getValue());
    }

    @Test
    void cohortSampleCapsLimitAndAppendsItAsLastParam() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 1)));

        // requestedLimit far above cohortCap(1000) → effective limit must be 1000
        List<Map<String, Object>> sample = service.cohortSample(ageGte18(), 50_000);

        assertEquals(1, sample.size());
        verify(jdbc).setQueryTimeout(30);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> paramsCap = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sqlCap.capture(), paramsCap.capture());

        String sql = sqlCap.getValue();
        assertTrue(sql.contains("SELECT * FROM beneficiary"), sql);
        assertTrue(sql.contains("WHERE"), sql);
        assertTrue(sql.contains("LIMIT ?"), sql);
        assertTrue(sql.contains("beneficiary.age_years >= ?"), sql);

        // predicate params first, effective (capped) limit last
        assertArrayEquals(new Object[]{18, 1000}, paramsCap.getValue());
    }

    @Test
    void tableNameFollowsWhateverDistrictResolvesTo() {
        // Proves the table is genuinely derived per environment, not hardcoded:
        // a resolver pointing "district" at a differently-named table means
        // ALL THREE query methods must follow, not just resolve the WHERE clause.
        FieldResolver otherSchemaResolver = fieldKey -> switch (fieldKey) {
            case "age_years" -> "otherschema.age_years";
            case "district" -> "otherschema.district";
            case "gender" -> "otherschema.gender";
            default -> throw new FieldResolver.UnknownFieldException(fieldKey);
        };
        ExecutionService otherService = new ExecutionService(
                new RuleCompiler(otherSchemaResolver), jdbc, guardrails, otherSchemaResolver, "age_band");

        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        otherService.count(ageGte18());

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sqlCap.capture(), eq(Long.class), any(Object[].class));
        assertTrue(sqlCap.getValue().contains("FROM otherschema"), sqlCap.getValue());
        assertFalse(sqlCap.getValue().contains("beneficiary"), sqlCap.getValue());
    }

    @Test
    void cohortSampleHonoursRequestedLimitWhenBelowCap() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());

        service.cohortSample(ageGte18(), 25);

        ArgumentCaptor<Object[]> paramsCap = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(anyString(), paramsCap.capture());
        assertArrayEquals(new Object[]{18, 25}, paramsCap.getValue());
    }

    // ---- fully-qualified Golden Layer mappings ----

    /**
     * With the lakehouse mapped across multiple catalogs and schemas, an
     * admin binds a catalogue field to a FULLY-QUALIFIED
     * {@code catalog.schema.table.column}. resolveTable() must then yield
     * {@code catalog.schema.table} — dropping only the column — or the
     * count/breakdown queries would name a table that does not exist.
     */
    @Test
    void derivesTheQualifiedTableFromAQualifiedMapping() {
        FieldResolver qualified = fieldKey -> {
            if ("age_years".equals(fieldKey) || "district".equals(fieldKey) || "gender".equals(fieldKey)) {
                return "iceberg_gold.golden_layer.tbl_beneficiary." + fieldKey;
            }
            throw new FieldResolver.UnknownFieldException(fieldKey);
        };
        ExecutionService qualifiedService = new ExecutionService(
                new RuleCompiler(qualified), jdbc, guardrails, qualified, "age_band");
        when(jdbc.queryForObject(anyString(), eq(Long.class), ArgumentMatchers.<Object[]>any()))
                .thenReturn(42L);

        assertEquals(42L, qualifiedService.count(ageGte18()));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), eq(Long.class), ArgumentMatchers.<Object[]>any());
        assertTrue(sql.getValue().contains("FROM iceberg_gold.golden_layer.tbl_beneficiary WHERE"),
                sql.getValue());
    }

    @Test
    void breakdownQualifiesTheAgeBandColumnWithTheFullTablePath() {
        FieldResolver qualified = fieldKey -> "iceberg_gold.golden_layer.tbl_beneficiary." + fieldKey;
        ExecutionService qualifiedService = new ExecutionService(
                new RuleCompiler(qualified), jdbc, guardrails, qualified, "age_band");
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<BreakdownRow>>any(),
                ArgumentMatchers.<Object[]>any())).thenReturn(List.of());

        qualifiedService.breakdown(ageGte18());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), ArgumentMatchers.<RowMapper<BreakdownRow>>any(),
                ArgumentMatchers.<Object[]>any());
        assertTrue(sql.getValue().contains(
                "iceberg_gold.golden_layer.tbl_beneficiary.age_band AS age_band"), sql.getValue());
    }
}
