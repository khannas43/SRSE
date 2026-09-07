package gov.rajasthan.smart.srse.execution;

import gov.rajasthan.smart.srse.compiler.Ast;
import gov.rajasthan.smart.srse.compiler.CompiledQuery;
import gov.rajasthan.smart.srse.compiler.FieldResolver;
import gov.rajasthan.smart.srse.compiler.RuleCompiler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Analytical-plane execution — push-down count / breakdown / capped cohort
 * against Presto via {@code prestoJdbcTemplate}.
 *
 * CONTRACT (do not violate):
 *  - Count and breakdown return AGGREGATES only — never row-level projections.
 *  - Breakdown dimensions are FIXED: district, gender, age_band. (The physical
 *    table/columns behind them are NOT fixed — resolved per environment via
 *    {@link FieldResolver}, same allow-listed mechanism as everywhere else in
 *    the compiler; only age_band, which has no field-catalogue entry since
 *    it's never officer-selectable, falls back to a plain config property.)
 *  - {@link #cohortSample} is the ONLY method allowed to return row-level data,
 *    and it is ALWAYS bounded by {@link GuardrailProperties#cohortCap()}.
 *  - Officer values reach SQL only as bound parameters (compiler allow-list);
 *    this service never concatenates user input into identifiers.
 *  - Every query applies the configured statement timeout before execution.
 */
@Service
public class ExecutionService {

    private static final String BREAKDOWN_DISTRICT = "district";

    private final RuleCompiler compiler;
    private final JdbcTemplate jdbc;
    private final GuardrailProperties guardrails;
    private final FieldResolver fields;
    private final String ageBandColumn;

    public ExecutionService(RuleCompiler compiler,
                            @Qualifier("prestoJdbcTemplate") JdbcTemplate jdbc,
                            GuardrailProperties guardrails,
                            FieldResolver fields,
                            @Value("${srse.breakdown.age-band-column:age_band}") String ageBandColumn) {
        this.compiler = compiler;
        this.jdbc = jdbc;
        this.guardrails = guardrails;
        this.fields = fields;
        this.ageBandColumn = ageBandColumn;
    }

    /**
     * Aggregate-only eligible count. Never projects beneficiary rows.
     */
    public long count(Ast.PredicateSpec spec) {
        CompiledQuery q = compiler.compile(spec);
        String sql = ("SELECT COUNT(*) FROM " + resolveTable() + " WHERE %s")
                .formatted(q.predicateSql());
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
        String table = resolveTable();
        String districtExpr = fields.resolveColumn(BREAKDOWN_DISTRICT);
        String genderExpr = fields.resolveColumn("gender");
        String ageBandExpr = table + "." + ageBandColumn;
        String sql = ("SELECT " + districtExpr + " AS district, " + genderExpr + " AS gender, "
                + ageBandExpr + " AS age_band, COUNT(*) AS n "
                + "FROM " + table + " "
                + "WHERE %s "
                + "GROUP BY " + districtExpr + ", " + genderExpr + ", " + ageBandExpr)
                .formatted(q.predicateSql());
        applyTimeout();
        return jdbc.query(sql, (rs, rowNum) -> new BreakdownRow(
                rs.getString(BREAKDOWN_DISTRICT),
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
    /**
     * The limit a drill-down would actually run with, so a caller can state it
     * alongside the rows without re-deriving the cap and risking drift.
     *
     * <p>A null or non-positive request means "as many as the cap allows",
     * never "unbounded" — there is deliberately no way to express the latter.
     */
    public int effectiveCohortLimit(Integer requestedLimit) {
        int cap = guardrails.cohortCap();
        return (requestedLimit == null || requestedLimit <= 0) ? cap : Math.min(requestedLimit, cap);
    }

    public List<Map<String, Object>> cohortSample(Ast.PredicateSpec spec, int requestedLimit) {
        int effectiveLimit = effectiveCohortLimit(requestedLimit);
        CompiledQuery q = compiler.compile(spec);
        // The LIMIT is INTERPOLATED, not bound. Presto's grammar has no
        // placeholder there — "LIMIT ?" is rejected outright with
        // "SYNTAX_ERROR: mismatched input '?'" — unlike most SQL engines,
        // which is why this went unnoticed: the method had no HTTP route, and
        // its unit test mocks JdbcTemplate, so nothing ever parsed the SQL.
        //
        // This does NOT weaken CLAUDE.md's injection rule. That rule is about
        // officer VALUES, which are still every one of them bound below. This
        // is a server-computed int, already clamped by effectiveCohortLimit to
        // (0, cohortCap] — a primitive int cannot carry SQL text, and no
        // caller-supplied string reaches the statement.
        String sql = ("SELECT * FROM " + resolveTable() + " WHERE %s LIMIT " + effectiveLimit)
                .formatted(q.predicateSql());
        applyTimeout();
        return jdbc.queryForList(sql, q.params().toArray());
    }

    /**
     * Derives the flat operational table from an already-catalogued field's
     * resolved column — every Tier-1/2 field shares one table per CLAUDE.md's
     * flat-catalogue design, so "district" is as good an anchor as any.
     * Re-resolved on every call (not cached) since field mappings are
     * admin-editable live, with no restart, via FieldColumnMappingController.
     */
    private String resolveTable() {
        // Raw, not resolveColumn: the binding is being taken apart here, not
        // compared, and a coercion cast around it would strip to nonsense.
        // See FieldResolver.resolveRawColumn.
        String resolved = fields.resolveRawColumn(BREAKDOWN_DISTRICT);
        int lastDot = resolved.lastIndexOf('.');
        if (lastDot < 0) {
            throw new IllegalStateException(
                    "Expected a table-qualified column for '" + BREAKDOWN_DISTRICT + "', got: " + resolved);
        }
        return resolved.substring(0, lastDot);
    }

    private void applyTimeout() {
        jdbc.setQueryTimeout(guardrails.queryTimeoutSeconds());
    }
}
