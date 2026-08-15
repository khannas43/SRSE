package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.compiler.FieldResolver;
import gov.rajasthan.smart.srse.compiler.FuzzyMatchSql;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadata;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Cross-table fuzzy/exact record matching for the Analysis tab.
 *
 * CONTRACT (do not violate):
 *  - This is a deliberate, isolated exception to the Rule Engine's "never
 *    JOIN, only pre-materialized flat-catalogue fields" rule (CLAUDE.md) —
 *    Source/Target table+column identifiers are NOT routed through
 *    {@code RuleCompiler}; they're validated against {@link AnalysisSchemaService}'s
 *    live introspection (an allow-list fetched from the lakehouse itself,
 *    not officer input) before ever reaching SQL text; only VALUES
 *    (thresholds, age bounds) are ever bound parameters. The one deliberate
 *    exception within an exception: the age filter DOES resolve through the
 *    catalogue's {@link FieldResolver} (the {@code age_years} field, same as
 *    the Rule Engine) — there's no ad hoc "age column" for arbitrary tables.
 *  - Fuzzy-vs-exact per criterion pair is decided by {@link AnalysisColumnMetadataRepository}
 *    (admin-registered override) first, falling back to a name-substring
 *    guess when neither side is registered — see {@link #isFuzzyMatchable}.
 *  - Read-only. No write/DELETE capability. "Dedup" is view-only — it
 *    collapses duplicate rows in the returned grid, never touches the
 *    lakehouse.
 *  - Result rows are always capped at {@link GuardrailProperties#analysisRowCap()}.
 *  - Each side of the underlying CROSS JOIN is itself pre-capped at
 *    {@link #JOIN_INPUT_CAP} rows before comparison — a fuzzy cross-table
 *    compare is inherently O(n*m); without this, a self-match against the
 *    full table would attempt a full n^2 comparison and risk the same kind
 *    of Presto OOM this session already hit once on a much smaller insert
 *    workload. This caps *input* rows per side, not the officer-visible
 *    result — real client-scale matching will need a smarter blocking key,
 *    tracked as a follow-up, not solved here.
 */
@Service
public class RecordMatchService {

    private static final int JOIN_INPUT_CAP = 500;
    private static final int MAX_CRITERIA_PER_SIDE = 8;
    private static final Set<String> AGE_UNITS = Set.of("DAYS", "MONTHS", "YEARS");

    private final JdbcTemplate jdbc;
    private final AnalysisSchemaService schemaService;
    private final GuardrailProperties guardrails;
    private final FieldResolver fields;
    private final AnalysisColumnMetadataRepository columnMetadata;

    public RecordMatchService(@Qualifier("prestoJdbcTemplate") JdbcTemplate jdbc,
                              AnalysisSchemaService schemaService,
                              GuardrailProperties guardrails,
                              FieldResolver fields,
                              AnalysisColumnMetadataRepository columnMetadata) {
        this.jdbc = jdbc;
        this.schemaService = schemaService;
        this.guardrails = guardrails;
        this.fields = fields;
        this.columnMetadata = columnMetadata;
    }

    public RecordMatchResponse match(RecordMatchRequest req) {
        String sourceTable = validateSide(req.sourceCriteria(), "sourceCriteria");
        String targetTable = validateSide(req.targetCriteria(), "targetCriteria");
        if (req.sourceCriteria().size() != req.targetCriteria().size()) {
            throw new IllegalArgumentException("sourceCriteria and targetCriteria must be the same size");
        }
        if (req.dedup() != null) {
            validateSideMembership(req.dedup().table(), sourceTable, targetTable, "dedup.table");
            schemaService.validateColumn(req.dedup().table(), req.dedup().column());
        }
        if (req.ageFilter() != null) {
            if (!AGE_UNITS.contains(req.ageFilter().unit())) {
                throw new IllegalArgumentException("ageFilter.unit must be one of " + AGE_UNITS);
            }
            if (req.ageFilter().minAge() > req.ageFilter().maxAge()) {
                throw new IllegalArgumentException("ageFilter minAge must be <= maxAge");
            }
        }

        List<Object> params = new ArrayList<>();
        Set<String> outerColumns = new LinkedHashSet<>();
        StringBuilder select = new StringBuilder();
        StringBuilder where = new StringBuilder();

        for (MatchCriterion c : req.sourceCriteria()) {
            appendSelect(select, outerColumns, "src", c.column(), "source_" + c.column());
        }
        for (MatchCriterion c : req.targetCriteria()) {
            appendSelect(select, outerColumns, "tgt", c.column(), "target_" + c.column());
        }

        for (int i = 0; i < req.sourceCriteria().size(); i++) {
            MatchCriterion sc = req.sourceCriteria().get(i);
            MatchCriterion tc = req.targetCriteria().get(i);
            String srcCol = "src." + sc.column();
            String tgtCol = "tgt." + tc.column();
            boolean isNameColumn = isFuzzyMatchable(sc, tc);
            if (where.length() > 0) {
                where.append(" AND ");
            }
            if (isNameColumn) {
                if (sc.fuzzyThresholdPercent() == null) {
                    throw new IllegalArgumentException(
                            "sourceCriteria[" + i + "].fuzzyThresholdPercent is required for a name column");
                }
                double threshold = sc.fuzzyThresholdPercent();
                if (threshold < 0 || threshold > 100) {
                    throw new IllegalArgumentException("fuzzyThresholdPercent must be between 0 and 100");
                }
                where.append(FuzzyMatchSql.similarityExpr(srcCol, tgtCol)).append(" >= ?");
                params.add(threshold / 100.0);
            } else {
                where.append(srcCol).append(" = ").append(tgtCol);
            }
        }
        if (where.length() == 0) {
            where.append("TRUE");
        }

        String dedupAlias = null;
        if (req.dedup() != null) {
            String side = req.dedup().table().equals(sourceTable) ? "src" : "tgt";
            dedupAlias = "dedup_last_updated";
            appendSelect(select, outerColumns, side, req.dedup().column(), dedupAlias);
        }

        if (req.highlightDuplicates()) {
            String scoreExpr = buildMatchScoreExpr(req);
            select.append(", ").append(scoreExpr).append(" AS \"match_score_pct\"");
            outerColumns.add("match_score_pct");
        }

        if (req.ageFilter() != null) {
            // The catalogue's registered "age_years" field, resolved via the SAME
            // admin-mapped FieldResolver the Rule Engine uses — the one deliberate
            // exception to this service's "never touch the field catalogue" rule,
            // since there is no ad hoc "the age column" on an arbitrary lakehouse
            // table for this tab's own schema-introspection allow-list to offer.
            // Assumes a plain table.column mapping (true for age_years today);
            // a same-table Tier-2 expression would need different handling.
            String resolved = fields.resolveColumn("age_years");
            int lastDot = resolved.lastIndexOf('.');
            String ageColumn = lastDot >= 0 ? resolved.substring(lastDot + 1) : resolved;

            double divisor = switch (req.ageFilter().unit()) {
                case "DAYS" -> 365.0;
                case "MONTHS" -> 12.0;
                default -> 1.0;
            };
            double minYears = req.ageFilter().minAge() / divisor;
            double maxYears = req.ageFilter().maxAge() / divisor;

            where.append(" AND src.").append(ageColumn).append(" BETWEEN ? AND ?");
            where.append(" AND tgt.").append(ageColumn).append(" BETWEEN ? AND ?");
            params.add(minYears);
            params.add(maxYears);
            params.add(minYears);
            params.add(maxYears);
        }

        String srcSub = "(SELECT * FROM " + sourceTable + " LIMIT " + JOIN_INPUT_CAP + ") src";
        String tgtSub = "(SELECT * FROM " + targetTable + " LIMIT " + JOIN_INPUT_CAP + ") tgt";
        String baseSql = "SELECT " + select + " FROM " + srcSub + " CROSS JOIN " + tgtSub + " WHERE " + where;

        String finalSql;
        if (req.dedup() != null) {
            String partitionCols = req.sourceCriteria().stream()
                    .map(c -> "\"source_" + c.column() + "\"")
                    .reduce((a, b) -> a + ", " + b).orElseThrow();
            finalSql = "SELECT * FROM (SELECT base.*, ROW_NUMBER() OVER ("
                    + "PARTITION BY " + partitionCols + " ORDER BY \"" + dedupAlias + "\" DESC) AS rn "
                    + "FROM (" + baseSql + ") base) ranked WHERE rn = 1 LIMIT " + guardrails.analysisRowCap();
        } else {
            finalSql = baseSql + " LIMIT " + guardrails.analysisRowCap();
        }

        jdbc.setQueryTimeout(guardrails.queryTimeoutSeconds());
        List<Map<String, Object>> rows = jdbc.queryForList(finalSql, params.toArray());

        return new RecordMatchResponse(
                List.copyOf(outerColumns),
                rows,
                renderForDisplay(finalSql, params),
                rows.size() >= guardrails.analysisRowCap());
    }

    private String buildMatchScoreExpr(RecordMatchRequest req) {
        List<String> terms = new ArrayList<>();
        for (int i = 0; i < req.sourceCriteria().size(); i++) {
            MatchCriterion sc = req.sourceCriteria().get(i);
            MatchCriterion tc = req.targetCriteria().get(i);
            String srcCol = "src." + sc.column();
            String tgtCol = "tgt." + tc.column();
            terms.add(isFuzzyMatchable(sc, tc) ? FuzzyMatchSql.similarityExpr(srcCol, tgtCol) : "1.0");
        }
        String sum = String.join(" + ", terms);
        return "ROUND((" + sum + ") / " + terms.size() + " * 100, 1)";
    }

    /**
     * Admin-registered {@link AnalysisColumnMetadata} takes precedence over
     * the name-substring guess, on either side — this MUST stay in sync with
     * the frontend's own fuzzy-eligibility check (analysis/page.tsx), or the
     * officer could see a Fuzzy % control that the backend then silently
     * ignores (or vice versa: a submitted threshold the backend never uses).
     */
    private boolean isFuzzyMatchable(MatchCriterion sc, MatchCriterion tc) {
        Optional<AnalysisColumnMetadata> srcMeta = columnMetadata.findByTableNameAndColumnName(sc.table(), sc.column());
        if (srcMeta.isPresent()) {
            return srcMeta.get().isFuzzyMatchable();
        }
        Optional<AnalysisColumnMetadata> tgtMeta = columnMetadata.findByTableNameAndColumnName(tc.table(), tc.column());
        if (tgtMeta.isPresent()) {
            return tgtMeta.get().isFuzzyMatchable();
        }
        return sc.column().toLowerCase().contains("name") || tc.column().toLowerCase().contains("name");
    }

    private void appendSelect(StringBuilder select, Set<String> outerColumns,
                              String alias, String column, String outAlias) {
        if (select.length() > 0) {
            select.append(", ");
        }
        select.append(alias).append('.').append(column).append(" AS \"").append(outAlias).append('"');
        outerColumns.add(outAlias);
    }

    /** Validates every criterion on one side shares a table; returns that table. */
    private String validateSide(List<MatchCriterion> criteria, String label) {
        if (criteria == null || criteria.isEmpty() || criteria.size() > MAX_CRITERIA_PER_SIDE) {
            throw new IllegalArgumentException(label + " must have 1 to " + MAX_CRITERIA_PER_SIDE + " entries");
        }
        String table = criteria.get(0).table();
        for (MatchCriterion c : criteria) {
            if (!table.equals(c.table())) {
                throw new IllegalArgumentException(label + " must all reference the same table");
            }
            schemaService.validateColumn(c.table(), c.column());
        }
        return table;
    }

    private void validateSideMembership(String table, String sourceTable, String targetTable, String label) {
        if (!table.equals(sourceTable) && !table.equals(targetTable)) {
            throw new IllegalArgumentException(label + " must be the request's source or target table");
        }
    }

    /** Display-only rendering with params substituted as literals — never re-executed. */
    private String renderForDisplay(String sql, List<Object> params) {
        String rendered = sql;
        for (Object param : params) {
            rendered = rendered.replaceFirst("\\?", java.util.regex.Matcher.quoteReplacement(literal(param)));
        }
        return rendered;
    }

    private String literal(Object value) {
        if (value instanceof Number) {
            return value.toString();
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }
}
