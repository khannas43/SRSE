package gov.rajasthan.smart.srse.execution;

import gov.rajasthan.smart.srse.compiler.Ast;
import gov.rajasthan.smart.srse.compiler.CompiledQuery;
import gov.rajasthan.smart.srse.compiler.RuleCompiler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Analytical-plane execution — push-down count / breakdown / capped cohort
 * against Presto via {@code prestoJdbcTemplate}.
 *
 * CONTRACT (do not violate):
 *  - Count and breakdown return AGGREGATES only — never row-level projections.
 *  - Breakdown dimensions are FIXED: district, gender, age_band.
 *  - {@link #cohortSample} is the ONLY method allowed to return row-level data,
 *    and it is ALWAYS bounded by {@link GuardrailProperties#cohortCap()}.
 *  - Officer values reach SQL only as bound parameters (compiler allow-list);
 *    this service never concatenates user input into identifiers.
 *  - Every query applies the configured statement timeout before execution.
 */
@Service
public class ExecutionService {

    private final RuleCompiler compiler;
    private final JdbcTemplate jdbc;
    private final GuardrailProperties guardrails;

    public ExecutionService(RuleCompiler compiler,
                            @Qualifier("prestoJdbcTemplate") JdbcTemplate jdbc,
                            GuardrailProperties guardrails) {
        this.compiler = compiler;
        this.jdbc = jdbc;
        this.guardrails = guardrails;
    }

    /**
     * Aggregate-only eligible count. Never projects beneficiary rows.
     */
    public long count(Ast.PredicateSpec spec) {
        CompiledQuery q = compiler.compile(spec);
        String sql = """
                SELECT COUNT(*) FROM beneficiary WHERE %s
                """.formatted(q.predicateSql());
        applyTimeout();
        Long n = jdbc.queryForObject(sql, Long.class, q.params().toArray());
        return n != null ? n : 0L;
    }

    /**
     * Fixed-dimension breakdown: district × gender × age_band.
     * Aggregate-only — never row-level.
     */
    public List<BreakdownRow> breakdown(Ast.PredicateSpec spec) {
        CompiledQuery q = compiler.compile(spec);
        String sql = """
                SELECT district, gender, age_band, COUNT(*) AS n
                FROM beneficiary
                WHERE %s
                GROUP BY district, gender, age_band
                """.formatted(q.predicateSql());
        applyTimeout();
        return jdbc.query(sql, (rs, rowNum) -> new BreakdownRow(
                rs.getString("district"),
                rs.getString("gender"),
                rs.getString("age_band"),
                rs.getLong("n")
        ), q.params().toArray());
    }

    /**
     * Hard-capped row-level drill-down.
     *
     * THIS IS THE ONLY METHOD IN THIS SERVICE ALLOWED TO RETURN ROW-LEVEL DATA,
     * AND IT IS ALWAYS BOUNDED: effective limit = min(requested, cohortCap).
     * Never raise the cap via a caller argument.
     */
    public List<Map<String, Object>> cohortSample(Ast.PredicateSpec spec, int requestedLimit) {
        int effectiveLimit = Math.min(requestedLimit, guardrails.cohortCap());
        CompiledQuery q = compiler.compile(spec);
        String sql = """
                SELECT * FROM beneficiary WHERE %s LIMIT ?
                """.formatted(q.predicateSql());
        List<Object> params = new ArrayList<>(q.params());
        params.add(effectiveLimit);   // LIMIT ? is last in the SQL
        applyTimeout();
        return jdbc.queryForList(sql, params.toArray());
    }

    private void applyTimeout() {
        jdbc.setQueryTimeout(guardrails.queryTimeoutSeconds());
    }
}
