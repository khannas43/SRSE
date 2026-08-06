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
 * FieldResolver mapping keys to {@code beneficiary.<key>}.
 */
@ExtendWith(MockitoExtension.class)
class ExecutionServiceTest {

    private final FieldResolver resolver = fieldKey -> {
        if ("age_years".equals(fieldKey)) {
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
        service = new ExecutionService(compiler, jdbc, guardrails);
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
        assertTrue(sql.contains("SELECT district, gender, age_band, COUNT(*) AS n"), sql);
        assertTrue(sql.contains("FROM beneficiary"), sql);
        assertTrue(sql.contains("WHERE"), sql);
        assertTrue(sql.contains("GROUP BY district, gender, age_band"), sql);
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
    void cohortSampleHonoursRequestedLimitWhenBelowCap() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());

        service.cohortSample(ageGte18(), 25);

        ArgumentCaptor<Object[]> paramsCap = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(anyString(), paramsCap.capture());
        assertArrayEquals(new Object[]{18, 25}, paramsCap.getValue());
    }
}
