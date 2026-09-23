package gov.rajasthan.smart.srse.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.compiler.AliasRebase;
import gov.rajasthan.smart.srse.compiler.ColumnGroupSql;
import gov.rajasthan.smart.srse.compiler.CompareAs;
import gov.rajasthan.smart.srse.compiler.FieldResolver;
import gov.rajasthan.smart.srse.compiler.FuzzyMatchSql;
import gov.rajasthan.smart.srse.compiler.SqlTypeFamily;
import gov.rajasthan.smart.srse.compiler.TypeCoercion;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService.RegisteredColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadata;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Stream;

/**
 * Cross-table fuzzy/exact record matching for the Analysis tab.
 *
 * CONTRACT (do not violate):
 *  - This is a deliberate, isolated exception to the Rule Engine's "never
 *    JOIN, only pre-materialized flat-catalogue fields" rule (CLAUDE.md) —
 *    Source/Target table+column identifiers are NOT routed through
 *    {@code RuleCompiler}; every one is fully qualified
 *    ({@code catalog.schema.table.column}) and passes
 *    {@link LakehouseRegistryService}'s two gates before reaching SQL text —
 *    the table must be admin-REGISTERED, and the column must exist in the
 *    LIVE lakehouse and not be hidden. Neither gate alone suffices: the
 *    registry is a snapshot of intent and can name a since-dropped table,
 *    while live introspection alone would let an officer reach any table on
 *    the cluster. Only VALUES (thresholds, age bounds) are bound parameters. The one deliberate
 *    exception within an exception: the age filter DOES resolve through the
 *    catalogue's {@link FieldResolver} (the {@code age_years} field, same as
 *    the Rule Engine) — there's no ad hoc "age column" for arbitrary tables.
 *  - Fuzzy-vs-exact per criterion pair is decided by {@link AnalysisColumnMetadataRepository}
 *    (admin-registered override) first, falling back to a name-substring
 *    guess when neither side is registered — see {@link #isFuzzyMatchable}.
 *  - The two sides of a pair are rarely the same physical type, and Presto
 *    does not coerce across type families: an account number held varchar in
 *    one table and bigint in another failed the whole query with "'=' cannot
 *    be applied to varchar, bigint". Every pair therefore goes through
 *    {@code TypeCoercion} with the columns' LIVE types (gathered by the same
 *    registry call that gates them) and the admin's per-column
 *    {@code CompareAs} setting — see {@link #planPairs}.
 *  - Read-only. No write/DELETE capability. "Dedup" is view-only — it
 *    collapses duplicate rows in the returned grid, never touches the
 *    lakehouse.
 *  - The join runs across the FULL source/target tables — no row cap, no
 *    input sampling. An earlier version pre-sampled each side to a top-500
 *    slice before a CROSS JOIN; at real (crore-scale) row counts that made
 *    matches nearly impossible to find (an arbitrary ~0.0005% slice per
 *    side), silently. Instead, every criterion pair becomes part of the
 *    JOIN's ON clause: exact pairs join on equality directly; fuzzy pairs
 *    join on a configurable case-insensitive prefix ("blocking key" — standard
 *    record-linkage technique; {@link AnalysisProperties#blockingPrefixLen()}),
 *    with the real Levenshtein-similarity check applied afterward as a WHERE
 *    filter only within already-blocked candidate pairs. This trades a small,
 *    documented amount of recall (a typo in the first N characters of a fuzzy
 *    column can be missed) for the join being a real
 *    hash join Presto can execute at scale, instead of a nested-loop cross
 *    product over a token sample.
 *  - There is no output row cap either (removed {@code SRSE_ANALYSIS_ROW_CAP}
 *    deliberately) — the only remaining safety net against a runaway/badly
 *    blocked match is {@link GuardrailProperties#queryTimeoutSeconds()}.
 *    Results stream to the client as they're produced (see below), so a
 *    timeout mid-match still leaves whatever rows already streamed visible.
 *    The browser stops RENDERING past its own limit and offers the result as
 *    a download instead; that limit is a display decision and lives there, not
 *    here. {@link #matchCsv} is what makes it safe to draw — the rows past the
 *    limit are still reachable, as a file, uncapped.
 *  - {@link #match} returns a {@link StreamingResponseBody}: request
 *    validation and SQL/param construction happen synchronously (so bad
 *    requests still fail fast with a normal exception before any response
 *    is written), but the JDBC query itself executes lazily, inside the
 *    body-writing callback, streaming one newline-delimited JSON line per
 *    row via {@link RowCallbackHandler} — never materializing the full
 *    result as an in-memory list.
 */
@Service
public class RecordMatchService {

    private static final int MAX_CRITERIA_PER_SIDE = 8;
    private static final Set<String> AGE_UNITS = Set.of("DAYS", "MONTHS", "YEARS");

    private final JdbcTemplate jdbc;
    private final LakehouseRegistryService registry;
    private final GuardrailProperties guardrails;
    private final FieldResolver fields;
    private final AnalysisColumnMetadataRepository columnMetadata;
    private final AnalysisProperties analysisProperties;
    private final ObjectMapper objectMapper;

    public RecordMatchService(@Qualifier("prestoJdbcTemplate") JdbcTemplate jdbc,
                              LakehouseRegistryService registry,
                              GuardrailProperties guardrails,
                              FieldResolver fields,
                              AnalysisColumnMetadataRepository columnMetadata,
                              AnalysisProperties analysisProperties,
                              ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.guardrails = guardrails;
        this.fields = fields;
        this.columnMetadata = columnMetadata;
        this.analysisProperties = analysisProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Validates the request and builds the Presto match query without executing it.
     */
    public MatchQuery planMatch(RecordMatchRequest req) {
        JoinPlan join = normalizeJoin(req);
        Sides sides = validateRequest(req, join);
        return buildMatchQuery(req, join, sides);
    }

    /** Display-only SQL with bound parameters rendered as literals — never re-executed. */
    public String renderQueryForDisplay(MatchQuery query) {
        return renderForDisplay(query.sql(), query.params());
    }

    /**
     * Validates hub join/display picks for a multi-target run (same rules as a
     * single-match {@linkplain RecordMatchRequest#sourceCriteria() source side}).
     */
    public QualifiedTable validateHubShape(List<MatchCriterion> hubCriteria, List<DisplayColumn> hubDisplay) {
        QualifiedTable hub = tableOf(hubCriteria, "hubCriteria");
        validateDisplayColumns(hubDisplay, hub, "hubDisplayColumns");
        return hub;
    }

    public StreamingResponseBody match(RecordMatchRequest req) {
        return streamResults(planMatch(req));
    }

    /**
     * The same match, streamed straight out as CSV.
     *
     * <p>Exists because the browser is where a large result actually hurts.
     * The NDJSON stream is parsed into row objects the grid holds and
     * recomputes over, which is bounded on the client at
     * {@code MAX_DISPLAYED_ROWS} — so past that point the officer could see a
     * count but never get the rows. This path never materialises a row in
     * JavaScript at all: the bytes go from Presto to the file.
     *
     * <p>Validation and SQL construction still happen synchronously, so a bad
     * request 400s before a single byte of the download is written.
     */
    public StreamingResponseBody matchCsv(RecordMatchRequest req) {
        return streamCsv(planMatch(req));
    }

    /**
     * Full-result aggregate match rates for {@link RecordMatchRequest#comparisonGroups()} —
     * not derivable from the NDJSON stream, which stops at 200k rows client-side.
     */
    public ComparisonSummaryResponse comparisonSummary(RecordMatchRequest req) {
        MatchQuery query = planMatch(req);
        if (req.comparisonGroups().isEmpty()) {
            return new ComparisonSummaryResponse(0, 0, 0, List.of());
        }
        JoinType joinType = JoinType.effective(req.joinType());
        boolean outerReconciliation = joinType != JoinType.INNER;
        StringBuilder agg = new StringBuilder("SELECT COUNT(*) AS total_rows");
        if (outerReconciliation) {
            agg.append(", SUM(CASE WHEN \"match_status\" = 'MATCHED' THEN 1 ELSE 0 END) AS matched_rows");
            agg.append(", SUM(CASE WHEN \"match_status\" <> 'MATCHED' THEN 1 ELSE 0 END) AS no_counterpart_rows");
        }
        for (int i = 0; i < req.comparisonGroups().size(); i++) {
            String matchCol = "\"cmp_" + i + "_match\"";
            if (outerReconciliation) {
                agg.append(", SUM(CASE WHEN \"match_status\" = 'MATCHED' AND ").append(matchCol)
                        .append(" THEN 1 ELSE 0 END) AS cmp_").append(i).append("_matches");
            } else {
                agg.append(", SUM(CASE WHEN ").append(matchCol).append(" THEN 1 ELSE 0 END) AS cmp_")
                        .append(i).append("_matches");
            }
        }
        agg.append(" FROM (").append(query.sql()).append(") comparison_summary_inner");
        Map<String, Object> row = jdbc.queryForMap(agg.toString(), query.params().toArray());
        long total = toLong(row.get("total_rows"));
        long matched = outerReconciliation ? toLong(row.get("matched_rows")) : total;
        long noCounterpart = outerReconciliation ? toLong(row.get("no_counterpart_rows")) : 0L;
        List<ComparisonSummaryResponse.ComparisonColumnSummary> columns = new ArrayList<>();
        for (int i = 0; i < req.comparisonGroups().size(); i++) {
            ComparisonGroup g = req.comparisonGroups().get(i);
            long matches = toLong(row.get("cmp_" + i + "_matches"));
            double rate = matched == 0 ? 0.0 : Math.round(matches * 1000.0 / matched) / 10.0;
            columns.add(new ComparisonSummaryResponse.ComparisonColumnSummary(
                    i, comparisonLabel(g), matches, rate));
        }
        return new ComparisonSummaryResponse(total, matched, noCounterpart, List.copyOf(columns));
    }

    private static long toLong(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static String comparisonLabel(ComparisonGroup group) {
        String src = group.source().stream().map(MatchCriterion::column).reduce((a, b) -> a + "+" + b).orElse("?");
        String tgt = group.target().stream().map(MatchCriterion::column).reduce((a, b) -> a + "+" + b).orElse("?");
        return src + " ↔ " + tgt;
    }

    /**
     * Both sides' columns as the LIVE lakehouse describes them, gathered by
     * the same pass that gates them. The types travel because the SQL depends
     * on them — see {@link #planPairs}.
     */
    private record Sides(QualifiedTable sourceTable, Map<String, RegisteredColumn> sourceColumns,
                         QualifiedTable targetTable, Map<String, RegisteredColumn> targetColumns) {
    }

    /**
     * The groups that become the ON clause, plus the same columns flattened.
     *
     * <p>Everything OUTSIDE the ON clause — the projections, the dedup
     * partition, the table each side resolves to, the type lookups — still
     * works from a flat list of columns and is indifferent to how they were
     * grouped. Only the join and the match score read {@link #groups()}.
     */
    private record JoinPlan(List<MatchGroup> groups,
                            List<MatchCriterion> sourceColumns,
                            List<MatchCriterion> targetColumns) {
    }

    /**
     * Resolves a request to groups, whichever shape it arrived in.
     *
     * <p>A request without {@code joinGroups} is zipped into single-column
     * groups, which every emitter below treats exactly as it treated a
     * criterion pair — the legacy path stays byte-identical, and the
     * same-size error it has always produced is still produced here.
     */
    private JoinPlan normalizeJoin(RecordMatchRequest req) {
        if (req.joinGroups().isEmpty()) {
            if (req.sourceCriteria().size() != req.targetCriteria().size()) {
                throw new IllegalArgumentException("sourceCriteria and targetCriteria must be the same size");
            }
            List<MatchGroup> groups = new ArrayList<>();
            for (int i = 0; i < req.sourceCriteria().size(); i++) {
                groups.add(MatchGroup.of(req.sourceCriteria().get(i), req.targetCriteria().get(i)));
            }
            return new JoinPlan(groups, req.sourceCriteria(), req.targetCriteria());
        }
        validateGroups(req.joinGroups());
        List<MatchCriterion> sourceColumns = new ArrayList<>();
        List<MatchCriterion> targetColumns = new ArrayList<>();
        for (MatchGroup g : req.joinGroups()) {
            sourceColumns.addAll(g.source());
            targetColumns.addAll(g.target());
        }
        return new JoinPlan(req.joinGroups(), List.copyOf(sourceColumns), List.copyOf(targetColumns));
    }

    private void validateGroups(List<MatchGroup> groups) {
        if (groups.size() > MAX_CRITERIA_PER_SIDE) {
            throw new IllegalArgumentException(
                    "joinGroups must have 1 to " + MAX_CRITERIA_PER_SIDE + " entries");
        }
        int anyOfSource = 0;
        int anyOfTarget = 0;
        for (int i = 0; i < groups.size(); i++) {
            MatchGroup g = groups.get(i);
            validateGroupSide(g.source(), i, "source");
            validateGroupSide(g.target(), i, "target");
            if (g.mode() == GroupMode.ANY_OF) {
                if (g.source().size() > 1) {
                    anyOfSource++;
                }
                if (g.target().size() > 1) {
                    anyOfTarget++;
                }
            }
        }
        // Each ANY_OF side becomes its own UNNEST, and two UNNESTs on one side
        // cross-multiply that side's rows before the join ever runs. The cap is
        // the only thing standing between an officer and a quiet row explosion.
        int maxAnyOf = analysisProperties.maxAnyOfGroupsPerSide();
        if (anyOfSource > maxAnyOf || anyOfTarget > maxAnyOf) {
            throw new IllegalArgumentException(
                    "at most " + maxAnyOf + " ANY_OF groups per side are allowed");
        }
    }

    private void validateGroupSide(List<MatchCriterion> columns, int index, String side) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("joinGroups[" + index + "]." + side
                    + " must have at least one column");
        }
        if (columns.size() > analysisProperties.maxGroupColumns()) {
            throw new IllegalArgumentException("joinGroups[" + index + "]." + side
                    + " must have at most " + analysisProperties.maxGroupColumns() + " columns");
        }
    }

    private void validateComparisonGroups(List<ComparisonGroup> groups, Sides sides) {
        if (groups.isEmpty()) {
            return;
        }
        if (groups.size() > MAX_CRITERIA_PER_SIDE) {
            throw new IllegalArgumentException(
                    "comparisonGroups must have 1 to " + MAX_CRITERIA_PER_SIDE + " entries");
        }
        for (int i = 0; i < groups.size(); i++) {
            ComparisonGroup g = groups.get(i);
            if (g.mode() == GroupMode.ANY_OF) {
                throw new IllegalArgumentException("comparisonGroups[" + i + "] does not support ANY_OF");
            }
            validateComparisonSide(g.source(), i, "source", sides.sourceTable());
            validateComparisonSide(g.target(), i, "target", sides.targetTable());
        }
    }

    private void validateComparisonSide(List<MatchCriterion> columns, int index, String side,
                                        QualifiedTable expectedTable) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("comparisonGroups[" + index + "]." + side
                    + " must have at least one column");
        }
        if (columns.size() > analysisProperties.maxGroupColumns()) {
            throw new IllegalArgumentException("comparisonGroups[" + index + "]." + side
                    + " must have at most " + analysisProperties.maxGroupColumns() + " columns");
        }
        for (MatchCriterion c : columns) {
            if (!c.qualifiedTable().equals(expectedTable)) {
                throw new IllegalArgumentException("comparisonGroups[" + index + "]." + side
                        + " must reference " + expectedTable.qualifiedName());
            }
            registry.validateColumn(c.qualifiedColumn());
        }
    }

    private List<ComparisonPlan> planComparisons(RecordMatchRequest req, Sides sides, List<Object> params) {
        if (req.comparisonGroups().isEmpty()) {
            return List.of();
        }
        List<ComparisonPlan> plans = new ArrayList<>();
        for (int i = 0; i < req.comparisonGroups().size(); i++) {
            ComparisonGroup g = req.comparisonGroups().get(i);
            SideSql src = comparisonSideSql(g.source(), "src", sides.sourceColumns(), g.separator());
            SideSql tgt = comparisonSideSql(g.target(), "tgt", sides.targetColumns(), g.separator());
            boolean fuzzy = isComparisonGroupFuzzy(g);
            CompareAs mode = comparisonGroupCompareAs(g);
            TypeCoercion.Aligned aligned = fuzzy
                    ? new TypeCoercion.Aligned(
                    TypeCoercion.asText(src.sql(), src.family()),
                    TypeCoercion.asText(tgt.sql(), tgt.family()))
                    : TypeCoercion.align(src.sql(), src.family(), tgt.sql(), tgt.family(), mode);
            String matchExpr;
            String scoreExpr = null;
            if (fuzzy) {
                if (g.fuzzyThresholdPercent() == null) {
                    throw new IllegalArgumentException(
                            "comparisonGroups[" + i + "].fuzzyThresholdPercent is required for a fuzzy comparison");
                }
                double threshold = g.fuzzyThresholdPercent();
                if (threshold < 0 || threshold > 100) {
                    throw new IllegalArgumentException("fuzzyThresholdPercent must be between 0 and 100");
                }
                scoreExpr = "ROUND((" + FuzzyMatchSql.similarityExpr(aligned.left(), aligned.right())
                        + ") * 100, 1)";
                matchExpr = "(" + FuzzyMatchSql.similarityExpr(aligned.left(), aligned.right()) + " >= ?)";
                double thresholdParam = threshold / 100.0;
                params.add(thresholdParam);
                plans.add(new ComparisonPlan(
                        i, aligned.left(), aligned.right(), matchExpr, scoreExpr, List.of(thresholdParam)));
            } else {
                // Both-null counts as a match; one-sided null is a mismatch — PrestoDB 0.297 IS DISTINCT FROM.
                matchExpr = "(NOT (" + aligned.left() + " IS DISTINCT FROM " + aligned.right() + "))";
                plans.add(new ComparisonPlan(
                        i, aligned.left(), aligned.right(), matchExpr, scoreExpr, List.of()));
            }
        }
        return List.copyOf(plans);
    }

    private SideSql comparisonSideSql(List<MatchCriterion> columns, String alias,
                                      Map<String, RegisteredColumn> described, String separator) {
        if (columns.size() == 1) {
            String column = columns.get(0).column();
            return new SideSql(alias + "." + column, familyOf(described, column));
        }
        List<String> refs = columns.stream().map(c -> alias + "." + c.column()).toList();
        return new SideSql(ColumnGroupSql.combine(refs, separator), SqlTypeFamily.TEXT);
    }

    private void appendComparisonSelects(StringBuilder select, Set<String> outerColumns,
                                         List<ComparisonPlan> plans, String noCounterpartWhen) {
        for (ComparisonPlan plan : plans) {
            appendExpressionSelect(select, outerColumns, plan.sourceValueExpr(), "cmp_" + plan.index() + "_source");
            appendExpressionSelect(select, outerColumns, plan.targetValueExpr(), "cmp_" + plan.index() + "_target");
            String verdict = plan.matchBooleanExpr();
            if (noCounterpartWhen != null) {
                verdict = nullVerdictWhenNoCounterpart(noCounterpartWhen, verdict);
            }
            appendExpressionSelect(select, outerColumns, verdict, "cmp_" + plan.index() + "_match");
            if (plan.scorePercentExpr() != null) {
                String score = plan.scorePercentExpr();
                if (noCounterpartWhen != null) {
                    score = "CASE WHEN " + noCounterpartWhen + " THEN NULL ELSE (" + score + ") END";
                }
                appendExpressionSelect(select, outerColumns, score, "cmp_" + plan.index() + "_score_pct");
            }
        }
    }

    private static String nullVerdictWhenNoCounterpart(String noCounterpartWhen, String verdictExpr) {
        return "CASE WHEN " + noCounterpartWhen + " THEN NULL ELSE (" + verdictExpr + ") END";
    }

    /**
     * First join-key columns on each side — used to detect rows with no counterpart
     * under outer joins. A row that satisfied the equi-join cannot have NULL on
     * either key: NULL never equals anything, so it could not have matched.
     */
    private record JoinKeyRefs(String sourceColumnSql, String targetColumnSql) {
    }

    private static JoinKeyRefs joinKeyRefs(JoinPlan join) {
        if (join.sourceColumns().isEmpty() || join.targetColumns().isEmpty()) {
            throw new IllegalArgumentException("join requires at least one source and one target column");
        }
        MatchCriterion source = join.sourceColumns().get(0);
        MatchCriterion target = join.targetColumns().get(0);
        return new JoinKeyRefs("src." + source.column(), "tgt." + target.column());
    }

    private static String noCounterpartPredicate(JoinType joinType, JoinKeyRefs keys) {
        return switch (joinType) {
            case INNER -> throw new IllegalStateException("INNER join has no unmatched rows");
            case LEFT -> keys.targetColumnSql() + " IS NULL";
            case RIGHT -> keys.sourceColumnSql() + " IS NULL";
            case FULL -> "(" + keys.sourceColumnSql() + " IS NULL OR " + keys.targetColumnSql() + " IS NULL)";
        };
    }

    private void appendMatchStatusSelect(StringBuilder select, Set<String> outerColumns,
                                         JoinType joinType, JoinKeyRefs keys) {
        appendExpressionSelect(select, outerColumns, matchStatusExpr(joinType, keys), "match_status");
    }

    private static String matchStatusExpr(JoinType joinType, JoinKeyRefs keys) {
        // See joinKeyRefs — NULL on a join key means this row has no counterpart on that side.
        return switch (joinType) {
            case INNER -> throw new IllegalStateException("match_status is not emitted for INNER joins");
            case LEFT -> "CASE WHEN " + keys.targetColumnSql() + " IS NULL THEN 'NO_TARGET' ELSE 'MATCHED' END";
            case RIGHT -> "CASE WHEN " + keys.sourceColumnSql() + " IS NULL THEN 'NO_SOURCE' ELSE 'MATCHED' END";
            case FULL -> "CASE WHEN " + keys.sourceColumnSql() + " IS NULL THEN 'NO_SOURCE' WHEN "
                    + keys.targetColumnSql() + " IS NULL THEN 'NO_TARGET' ELSE 'MATCHED' END";
        };
    }

    private void appendMismatchOnlyFilter(StringBuilder where, RecordMatchRequest req,
                                          List<ComparisonPlan> plans, List<Object> params,
                                          String noCounterpartWhen) {
        if (!req.mismatchOnly() || plans.isEmpty()) {
            return;
        }
        StringJoiner allMatch = new StringJoiner(" AND ");
        for (ComparisonPlan plan : plans) {
            allMatch.add("(" + plan.matchBooleanExpr() + ")");
            // SELECT already bound these once; WHERE reuses the same expression text.
            params.addAll(plan.matchBindValues());
        }
        String disagreement = "NOT (" + allMatch + ")";
        if (noCounterpartWhen != null) {
            // SELECT alias match_status is not visible in WHERE — use the same predicate as verdict wrapping.
            appendWhereClause(where, "(" + noCounterpartWhen + " OR " + disagreement + ")");
        } else {
            appendWhereClause(where, disagreement);
        }
    }

    private void appendExpressionSelect(StringBuilder select, Set<String> outerColumns, String expression,
                                        String outAlias) {
        if (select.length() > 0) {
            select.append(", ");
        }
        select.append("(").append(expression).append(") AS \"").append(outAlias).append('"');
        outerColumns.add(outAlias);
    }

    private CompareAs comparisonGroupCompareAs(ComparisonGroup group) {
        if (group.source().size() + group.target().size() > 2
                || group.source().size() > 1
                || group.target().size() > 1) {
            return CompareAs.TEXT;
        }
        return CompareAs.resolve(sideCompareAs(group.source()), sideCompareAs(group.target()));
    }

    private boolean isComparisonGroupFuzzy(ComparisonGroup group) {
        List<MatchCriterion> columns = new ArrayList<>(group.source());
        columns.addAll(group.target());
        boolean anyRegistered = false;
        for (MatchCriterion c : columns) {
            Optional<AnalysisColumnMetadata> meta = findMetadata(c);
            if (meta.isPresent()) {
                anyRegistered = true;
                if (meta.get().isFuzzyMatchable()) {
                    return true;
                }
            }
        }
        if (anyRegistered) {
            return false;
        }
        return columns.stream().anyMatch(c -> c.column().toLowerCase().contains("name"));
    }

    private Sides validateRequest(RecordMatchRequest req, JoinPlan join) {
        QualifiedTable sourceTable = sameTable(join.sourceColumns(), "sourceCriteria");
        QualifiedTable targetTable = sameTable(join.targetColumns(), "targetCriteria");
        validateDisplayColumns(req.sourceDisplayColumns(), sourceTable, "sourceDisplayColumns");
        validateDisplayColumns(req.targetDisplayColumns(), targetTable, "targetDisplayColumns");

        Sides sides = new Sides(
                sourceTable, describeSide(sourceTable, join.sourceColumns(), req.sourceDisplayColumns()),
                targetTable, describeSide(targetTable, join.targetColumns(), req.targetDisplayColumns()));
        if (req.dedup() != null) {
            validateSideMembership(req.dedup().qualifiedTable(), sourceTable, targetTable, "dedup.table");
            registry.validateColumn(req.dedup().qualifiedColumn());
        }
        if (req.ageFilter() != null) {
            validateAgeFilter(req.ageFilter());
        }
        validateOuterJoinRules(req, sides);
        validateComparisonGroups(req.comparisonGroups(), sides);
        return sides;
    }

    /**
     * Outer-join-specific constraints (dedup, age filter) — kept out of
     * {@link #validateRequest}'s generic checks so messages stay actionable.
     */
    private void validateOuterJoinRules(RecordMatchRequest req, Sides sides) {
        JoinType joinType = JoinType.effective(req.joinType());
        if (req.dedup() != null && (joinType == JoinType.RIGHT || joinType == JoinType.FULL)) {
            throw new IllegalArgumentException(
                    "Dedup cannot be used with a " + joinType + " join: unmatched rows have NULL "
                            + "source-side partition keys and would collapse into one row. "
                            + "Use INNER or LEFT, or turn dedup off.");
        }
        if (req.ageFilter() != null && joinType == JoinType.FULL) {
            throw new IllegalArgumentException(
                    "Age filter cannot be used with a FULL join — neither side is preserved. "
                            + "Use INNER, LEFT, or RIGHT, or turn the age filter off.");
        }
        if (req.ageFilter() != null && joinType == JoinType.LEFT) {
            validateAgeFilterOnPreservedSide(sides, "src", sides.sourceTable(),
                    sides.targetTable(), "LEFT");
        }
        if (req.ageFilter() != null && joinType == JoinType.RIGHT) {
            validateAgeFilterOnPreservedSide(sides, "tgt", sides.targetTable(),
                    sides.sourceTable(), "RIGHT");
        }
    }

    private void validateAgeFilterOnPreservedSide(Sides sides, String preservedAlias,
                                                    QualifiedTable preservedTable,
                                                    QualifiedTable nullableTable, String joinLabel) {
        String ageExpression = fields.resolveColumn("age_years");
        Set<String> ageColumns = AliasRebase.referencedColumns(ageExpression);
        if (registry.hasColumns(preservedTable, ageColumns)) {
            return;
        }
        if (registry.hasColumns(nullableTable, ageColumns)) {
            throw new IllegalArgumentException(
                    "Age filter cannot use the " + (preservedAlias.equals("src") ? "target" : "source")
                            + " table's date-of-birth column with a " + joinLabel + " join — unmatched "
                            + preservedAlias + " rows have no row on the other side to filter. "
                            + "Use INNER, or match against a table that carries the age column on the "
                            + "preserved side (" + preservedTable.qualifiedName() + ").");
        }
        throw new IllegalArgumentException(
                "The age filter cannot be applied: neither " + sides.sourceTable().qualifiedName()
                        + " nor " + sides.targetTable().qualifiedName() + " has "
                        + String.join(", ", ageColumns));
    }

    /**
     * Package-private so {@link MultiTargetRecordMatchService} validates the age
     * filter through this one definition rather than a copy that could drift.
     */
    static void validateAgeFilter(AgeFilterSpec ageFilter) {
        if (!AGE_UNITS.contains(ageFilter.unit())) {
            throw new IllegalArgumentException("ageFilter.unit must be one of " + AGE_UNITS);
        }
        if (ageFilter.minAge() > ageFilter.maxAge()) {
            throw new IllegalArgumentException("ageFilter minAge must be <= maxAge");
        }
    }

    private MatchQuery buildMatchQuery(RecordMatchRequest req, JoinPlan join, Sides sides) {
        JoinType joinType = JoinType.effective(req.joinType());
        List<Object> params = new ArrayList<>();
        Set<String> outerColumns = new LinkedHashSet<>();
        StringBuilder select = new StringBuilder();
        StringBuilder onClause = new StringBuilder();
        StringBuilder where = new StringBuilder();

        // Every type-dependent decision is made once, here, and both the join
        // and the match score read the same plan — they MUST agree on which
        // groups are fuzzy and on how each side is cast, or the score would be
        // computed over different expressions than the join matched on.
        List<UnnestSide> sourceUnnests = new ArrayList<>();
        List<UnnestSide> targetUnnests = new ArrayList<>();
        List<GroupPlan> groups = planGroups(join, sides, sourceUnnests, targetUnnests,
                joinType.preservesSourceSide(), joinType.preservesTargetSide());
        enforceEstimatedRowCeiling(sides, groups);

        // Comparison placeholders live in SELECT, which precedes ON in the final SQL —
        // bind comparison params before join params.
        List<ComparisonPlan> comparisonPlans = planComparisons(req, sides, params);

        appendCriteriaSelects(select, outerColumns, join, req);
        appendMatchedOnSelects(select, outerColumns, "src", "source_", sourceUnnests);
        appendMatchedOnSelects(select, outerColumns, "tgt", "target_", targetUnnests);
        boolean outerComparison = !req.comparisonGroups().isEmpty() && joinType != JoinType.INNER;
        JoinKeyRefs joinKeys = req.comparisonGroups().isEmpty() ? null : joinKeyRefs(join);
        String noCounterpartWhen = outerComparison ? noCounterpartPredicate(joinType, joinKeys) : null;
        if (outerComparison) {
            appendMatchStatusSelect(select, outerColumns, joinType, joinKeys);
        }
        appendComparisonSelects(select, outerColumns, comparisonPlans, noCounterpartWhen);

        String dedupAlias = appendDedupSelect(select, outerColumns, req, join);
        appendMatchScoreSelect(select, outerColumns, req, groups);
        appendJoinConditions(onClause, where, params, groups, joinType);
        appendAgeFilter(where, params, req, sides, joinType);
        appendMismatchOnlyFilter(where, req, comparisonPlans, params, noCounterpartWhen);

        if (where.length() == 0) {
            where.append("TRUE");
        }

        // Fully-qualified catalog.schema.table on both sides — the two sides
        // can live in different catalogs entirely (a Silver-vs-Gold
        // reconciliation), which Presto joins natively.
        String sourceFrom = fromSide(sides.sourceTable().qualifiedName(), "src", sourceUnnests);
        String targetFrom = fromSide(sides.targetTable().qualifiedName(), "tgt", targetUnnests);
        String baseSql = "SELECT " + select + " FROM " + sourceFrom + " " + joinType.joinKeyword() + " "
                + targetFrom + " ON " + onClause + " WHERE " + where;

        String finalSql = wrapWithDedup(baseSql, req, join, dedupAlias);
        return new MatchQuery(finalSql, params, List.copyOf(outerColumns));
    }

    /**
     * One side's FROM fragment. Without ANY_OF groups it is the bare qualified
     * table, character for character what it always was; with them the table is
     * wrapped in a subquery that pivots each ANY_OF group's columns into rows.
     */
    private static String fromSide(String qualifiedTable, String alias, List<UnnestSide> unnests) {
        if (unnests.isEmpty()) {
            return qualifiedTable + " " + alias;
        }
        StringBuilder projected = new StringBuilder("t.*");
        StringBuilder clauses = new StringBuilder();
        for (UnnestSide u : unnests) {
            projected.append(", ").append(u.keyAlias()).append(", ").append(u.matchedAlias());
            clauses.append(" ").append(u.clause());
        }
        return "(SELECT " + projected + " FROM " + qualifiedTable + " t" + clauses + ") " + alias;
    }

    private void appendCriteriaSelects(StringBuilder select, Set<String> outerColumns,
                                       JoinPlan join, RecordMatchRequest req) {
        for (MatchCriterion c : join.sourceColumns()) {
            String baseAlias = "source_" + c.column();
            appendSelect(select, outerColumns, "src", c.column(), allocateUniqueAlias(baseAlias, outerColumns));
        }
        for (MatchCriterion c : join.targetColumns()) {
            String baseAlias = "target_" + c.column();
            appendSelect(select, outerColumns, "tgt", c.column(), allocateUniqueAlias(baseAlias, outerColumns));
        }
        appendDisplaySelects(select, outerColumns, "src", "source_", req.sourceDisplayColumns());
        appendDisplaySelects(select, outerColumns, "tgt", "target_", req.targetDisplayColumns());
    }

    private void appendDisplaySelects(StringBuilder select, Set<String> outerColumns, String sqlAlias,
                                      String outPrefix, List<DisplayColumn> display) {
        for (DisplayColumn d : display) {
            String baseAlias = outPrefix + d.column();
            if (outerColumns.contains(baseAlias)) {
                continue;
            }
            String outAlias = allocateUniqueAlias(baseAlias, outerColumns);
            appendSelect(select, outerColumns, sqlAlias, d.column(), outAlias);
        }
    }

    /**
     * Projects each ANY_OF group's {@code matched_on} column.
     *
     * <p>An ANY_OF group returns the same pair once per column that matched, so
     * without this the duplicates look like a bug. With it they are the answer:
     * the row says which of the candidate columns carried the value.
     */
    private void appendMatchedOnSelects(StringBuilder select, Set<String> outerColumns, String sqlAlias,
                                        String outPrefix, List<UnnestSide> unnests) {
        for (UnnestSide u : unnests) {
            String outAlias = allocateUniqueAlias(outPrefix + u.matchedAlias(), outerColumns);
            appendSelect(select, outerColumns, sqlAlias, u.matchedAlias(), outAlias);
        }
    }

    /**
     * Reserves a unique output alias when {@code baseAlias} is already taken by
     * a non-skipped projection (e.g. {@code match_score_pct}).
     */
    static String allocateUniqueAlias(String baseAlias, Set<String> outerColumns) {
        if (!outerColumns.contains(baseAlias)) {
            return baseAlias;
        }
        int suffix = 2;
        while (outerColumns.contains(baseAlias + "_" + suffix)) {
            suffix++;
        }
        return baseAlias + "_" + suffix;
    }

    /**
     * One group resolved: whether it is fuzzy, and the SQL each side compares
     * as once folded and coerced.
     *
     * <p>{@code sourceRef}/{@code targetRef} are what actually goes into SQL.
     * For a fuzzy group they are the TEXT forms (Levenshtein and {@code lower}
     * take nothing else); for an exact one they are the two sides aligned to a
     * common type — which is the whole point: the same account number stored
     * {@code varchar} in one table and {@code bigint} in the other used to fail
     * the query outright with {@code '=' cannot be applied to varchar, bigint}.
     */
    private record GroupPlan(MatchGroup group, boolean fuzzy, String sourceRef, String targetRef) {
    }

    /** Post-join comparison projection — never routed to {@link #appendJoinConditions}. */
    private record ComparisonPlan(
            int index,
            String sourceValueExpr,
            String targetValueExpr,
            String matchBooleanExpr,
            String scorePercentExpr,
            List<Object> matchBindValues) {

        ComparisonPlan {
            matchBindValues = matchBindValues == null ? List.of() : List.copyOf(matchBindValues);
        }
    }

    /** One ANY_OF side pivoted to rows, and the CROSS JOIN UNNEST that does it. */
    private record UnnestSide(String keyAlias, String matchedAlias, String clause) {
    }

    /** A side of a group as SQL, with the type family that expression yields. */
    private record SideSql(String sql, SqlTypeFamily family) {
    }

    /**
     * Resolves every group once — fuzzy-or-exact, the folding each side needs,
     * and the casts. Both the join and the match score read this, so they
     * cannot drift apart, and each column's admin metadata is fetched once
     * instead of once per use.
     *
     * <p>ANY_OF sides are appended to {@code sourceUnnests}/{@code targetUnnests}
     * as a side effect, because the FROM clause has to know about them before
     * it can be written.
     */
    private List<GroupPlan> planGroups(JoinPlan join, Sides sides,
                                       List<UnnestSide> sourceUnnests, List<UnnestSide> targetUnnests,
                                       boolean preserveSourceSide, boolean preserveTargetSide) {
        List<GroupPlan> plans = new ArrayList<>();
        for (int i = 0; i < join.groups().size(); i++) {
            MatchGroup g = join.groups().get(i);
            SideSql src = sideSql(g, g.source(), "src", sides.sourceColumns(), i, sourceUnnests,
                    preserveSourceSide);
            SideSql tgt = sideSql(g, g.target(), "tgt", sides.targetColumns(), i, targetUnnests,
                    preserveTargetSide);

            if (isGroupFuzzy(g)) {
                // CompareAs does not enter into a fuzzy group: Levenshtein
                // similarity is a string measure, so both sides go to text
                // whatever the admin set for a direct comparison.
                plans.add(new GroupPlan(g, true,
                        TypeCoercion.asText(src.sql(), src.family()),
                        TypeCoercion.asText(tgt.sql(), tgt.family())));
            } else {
                CompareAs mode = groupCompareAs(g);
                TypeCoercion.Aligned aligned =
                        TypeCoercion.align(src.sql(), src.family(), tgt.sql(), tgt.family(), mode);
                plans.add(new GroupPlan(g, false, aligned.left(), aligned.right()));
            }
        }
        return plans;
    }

    /**
     * One side of a group as a single SQL expression.
     *
     * <p>A single column is emitted bare, exactly as before groups existed.
     * A COMBINE side is folded to text. An ANY_OF side is pivoted with UNNEST
     * and the expression becomes a reference to the unnested key, so
     * everything downstream — blocking key, similarity, equality — treats it
     * as the ordinary single column it now is.
     */
    private SideSql sideSql(MatchGroup group, List<MatchCriterion> columns, String alias,
                            Map<String, RegisteredColumn> described, int groupIndex,
                            List<UnnestSide> unnests, boolean preserveSideRows) {
        if (columns.size() == 1) {
            String column = columns.get(0).column();
            return new SideSql(alias + "." + column, familyOf(described, column));
        }
        List<String> names = columns.stream().map(MatchCriterion::column).toList();
        if (group.mode() == GroupMode.ANY_OF) {
            // Within one family Presto already unifies the array's element type
            // (integer with bigint, varchar(20) with varchar(50)); a cast there
            // would only change what the comparison means. Across families it
            // cannot, so the array goes to text.
            SqlTypeFamily first = familyOf(described, names.get(0));
            boolean uniform = names.stream().allMatch(n -> familyOf(described, n) == first);
            String keyAlias = "g" + groupIndex + "_key";
            String matchedAlias = "g" + groupIndex + "_matched_on";
            unnests.add(new UnnestSide(keyAlias, matchedAlias, ColumnGroupSql.unnestClause(
                    "t", names, !uniform, "u" + groupIndex, keyAlias, matchedAlias, preserveSideRows)));
            return new SideSql(alias + "." + keyAlias, uniform ? first : SqlTypeFamily.TEXT);
        }
        List<String> refs = names.stream().map(n -> alias + "." + n).toList();
        return new SideSql(ColumnGroupSql.combine(refs, group.separator()), SqlTypeFamily.TEXT);
    }

    private static SqlTypeFamily familyOf(Map<String, RegisteredColumn> columns, String column) {
        RegisteredColumn described = columns.get(column);
        return described == null ? SqlTypeFamily.UNKNOWN : SqlTypeFamily.of(described.dataType());
    }

    /**
     * The admin's {@code compare_as} for a group.
     *
     * <p>A multi-column COMBINE is folded with {@code array_join}, so it is
     * text by construction and nothing else can be honoured: reading a
     * concatenated name as a number would {@code TRY_CAST} every row to NULL
     * and return nothing, silently. Otherwise this is the single-column rule
     * unchanged — an explicit setting on either side wins, TEXT breaking a tie.
     */
    private CompareAs groupCompareAs(MatchGroup group) {
        if (group.mode() == GroupMode.COMBINE && !group.isSingleColumnPair()) {
            return CompareAs.TEXT;
        }
        return CompareAs.resolve(sideCompareAs(group.source()), sideCompareAs(group.target()));
    }

    private CompareAs sideCompareAs(List<MatchCriterion> columns) {
        for (MatchCriterion c : columns) {
            Optional<AnalysisColumnMetadata> meta = findMetadata(c);
            if (meta.isPresent()) {
                return meta.get().getCompareAs();
            }
        }
        return CompareAs.AUTO;
    }

    /**
     * AND-s every group into the ON clause. Never an OR: a disjunctive ON costs
     * Presto the hash join and falls back to a nested loop over the cross
     * product, which is why ANY_OF is pivoted with UNNEST in
     * {@link #sideSql} instead of being written as alternatives here.
     */
    private void appendJoinConditions(StringBuilder onClause, StringBuilder where, List<Object> params,
                                      List<GroupPlan> groups, JoinType joinType) {
        for (int i = 0; i < groups.size(); i++) {
            GroupPlan group = groups.get(i);
            if (onClause.length() > 0) {
                onClause.append(" AND ");
            }
            if (group.fuzzy()) {
                appendFuzzyJoin(onClause, where, params, i, group, joinType);
            } else {
                onClause.append(group.sourceRef()).append(" = ").append(group.targetRef());
            }
        }
    }

    /**
     * Fuzzy pairs: blocking key always ON. Levenshtein similarity stays in WHERE
     * for INNER only — for outer joins it moves to ON so unmatched preserved-side
     * rows are not filtered away (see class javadoc / CLAUDE.md Analysis section).
     */
    private void appendFuzzyJoin(StringBuilder onClause, StringBuilder where, List<Object> params,
                                 int index, GroupPlan group, JoinType joinType) {
        if (group.group().fuzzyThresholdPercent() == null) {
            throw new IllegalArgumentException(
                    "sourceCriteria[" + index + "].fuzzyThresholdPercent is required for a name column");
        }
        double threshold = group.group().fuzzyThresholdPercent();
        if (threshold < 0 || threshold > 100) {
            throw new IllegalArgumentException("fuzzyThresholdPercent must be between 0 and 100");
        }
        onClause.append(blockingKeyExpr(group.sourceRef())).append(" = ")
                .append(blockingKeyExpr(group.targetRef()));
        String similarity = FuzzyMatchSql.similarityExpr(group.sourceRef(), group.targetRef()) + " >= ?";
        if (joinType == JoinType.INNER) {
            appendWhereClause(where, similarity);
        } else {
            onClause.append(" AND ").append(similarity);
        }
        params.add(threshold / 100.0);
    }

    private String appendDedupSelect(StringBuilder select, Set<String> outerColumns, RecordMatchRequest req,
                                     JoinPlan join) {
        if (req.dedup() == null) {
            return null;
        }
        QualifiedTable sourceTable = join.sourceColumns().get(0).qualifiedTable();
        String side = req.dedup().qualifiedTable().equals(sourceTable) ? "src" : "tgt";
        String dedupAlias = "dedup_last_updated";
        appendSelect(select, outerColumns, side, req.dedup().column(), dedupAlias);
        return dedupAlias;
    }

    /**
     * Match score reflects JOIN groups only — display-only columns do not enter
     * the average, and a group counts once however many columns it folds.
     */
    private void appendMatchScoreSelect(StringBuilder select, Set<String> outerColumns,
                                        RecordMatchRequest req, List<GroupPlan> groups) {
        if (!req.highlightDuplicates()) {
            return;
        }
        String scoreExpr = buildMatchScoreExpr(groups);
        select.append(", ").append(scoreExpr).append(" AS \"match_score_pct\"");
        outerColumns.add("match_score_pct");
    }

    /**
     * Applies the age filter to each side that can actually carry it.
     *
     * <p>It used to go onto BOTH aliases unconditionally, which assumed every
     * table in the lakehouse has the catalogue's date-of-birth column. The two
     * sides of a match are arbitrary registered tables, and typically only one
     * is a person table: matching a member-id mapping table against the golden
     * citizen table emitted
     * {@code date_diff('year', CAST(src.date_of_birth AS DATE), current_date)}
     * against a table with no such column, and Presto rejected the whole query
     * — so an age filter made the match impossible to run rather than
     * narrower.
     *
     * <p>A side that does not carry the column is skipped, not silently
     * dropped from the result: the join already ties the two sides to the same
     * person, so filtering the side that HAS the date of birth constrains both.
     * If neither side carries it the filter is meaningless and this fails
     * loudly — silently returning an unfiltered cohort to an officer who asked
     * for 75-100 would be the worst outcome available.
     */
    private void appendAgeFilter(StringBuilder where, List<Object> params, RecordMatchRequest req,
                                 Sides sides, JoinType joinType) {
        if (req.ageFilter() == null) {
            return;
        }
        // The catalogue's age expression is table-qualified for the Rule
        // Engine's own table, so it has to be rebased onto each join alias —
        // see AliasRebase for why taking "everything after the last dot" was
        // wrong for a Tier-2 (DOB-derived) age expression.
        String ageExpression = fields.resolveColumn("age_years");
        Set<String> ageColumns = AliasRebase.referencedColumns(ageExpression);
        double divisor = ageDivisor(req.ageFilter().unit());
        double minYears = req.ageFilter().minAge() / divisor;
        double maxYears = req.ageFilter().maxAge() / divisor;

        List<Map.Entry<String, QualifiedTable>> sidesToFilter = switch (joinType) {
            case INNER -> List.of(
                    Map.entry("src", sides.sourceTable()),
                    Map.entry("tgt", sides.targetTable()));
            case LEFT -> List.of(Map.entry("src", sides.sourceTable()));
            case RIGHT -> List.of(Map.entry("tgt", sides.targetTable()));
            case FULL -> List.of();
        };

        boolean applied = false;
        for (Map.Entry<String, QualifiedTable> side : sidesToFilter) {
            if (!registry.hasColumns(side.getValue(), ageColumns)) {
                continue;
            }
            appendWhereClause(where,
                    AliasRebase.ontoAlias(ageExpression, side.getKey()) + " BETWEEN ? AND ?");
            params.add(minYears);
            params.add(maxYears);
            applied = true;
        }

        if (!applied) {
            throw new IllegalArgumentException(
                    "The age filter cannot be applied: neither " + sides.sourceTable().qualifiedName()
                            + " nor " + sides.targetTable().qualifiedName() + " has "
                            + String.join(", ", ageColumns)
                            + ". Match against a table that carries it, or remap the 'age_years' field "
                            + "on the Admin page to a column these tables have.");
        }
    }

    /** Appends one AND-ed clause, adding the connector only when something precedes it. */
    private static void appendWhereClause(StringBuilder where, String clause) {
        if (where.length() > 0) {
            where.append(" AND ");
        }
        where.append(clause);
    }

    private static double ageDivisor(String unit) {
        return switch (unit) {
            case "DAYS" -> 365.0;
            case "MONTHS" -> 12.0;
            default -> 1.0;
        };
    }

    private static String wrapWithDedup(String baseSql, RecordMatchRequest req, JoinPlan join,
                                        String dedupAlias) {
        if (req.dedup() == null) {
            return baseSql;
        }
        // Partitions over every source-side JOIN column, across all groups. An
        // ANY_OF group can return the same hub key several times — once per
        // candidate column that matched — and this is what collapses them.
        String partitionCols = join.sourceColumns().stream()
                .map(c -> "\"source_" + c.column() + "\"")
                .reduce((a, b) -> a + ", " + b).orElseThrow();
        return "SELECT * FROM (SELECT base.*, ROW_NUMBER() OVER ("
                + "PARTITION BY " + partitionCols + " ORDER BY \"" + dedupAlias + "\" DESC) AS rn "
                + "FROM (" + baseSql + ") base) ranked WHERE rn = 1";
    }

    private StreamingResponseBody streamResults(MatchQuery query) {
        String displaySql = renderForDisplay(query.sql(), query.params());
        return outputStream -> {
            writeLine(outputStream, Map.of("type", "meta", "columns", query.columns(), "sql", displaySql));
            try {
                jdbc.setQueryTimeout(guardrails.queryTimeoutSeconds());
                ColumnMapRowMapper rowMapper = new ColumnMapRowMapper();
                long[] totalRows = {0};
                jdbc.query(query.sql(), query.params().toArray(), (RowCallbackHandler) rs -> {
                    Map<String, Object> row = rowMapper.mapRow(rs, 0);
                    try {
                        writeLine(outputStream, Map.of("type", "row", "data", row));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    totalRows[0]++;
                });
                writeLine(outputStream, Map.of("type", "done", "totalRows", totalRows[0]));
            } catch (UncheckedIOException e) {
                throw e.getCause();
            } catch (Exception e) {
                String message = e.getMessage() != null ? e.getMessage() : e.toString();
                writeLine(outputStream, Map.of("type", "error", "message", message));
            }
        };
    }

    /**
     * Streams the result as CSV, one row at a time, holding nothing.
     *
     * <p>Unlike {@link #streamResults}, a failure part-way is NOT caught and
     * reported in-band. NDJSON can carry an {@code error} event because the
     * client parses events; a CSV cannot say anything a spreadsheet would not
     * read as data, and a file that simply stops looks exactly like a
     * complete one. Letting the exception abort the response instead means the
     * client's fetch rejects and the officer is told the export failed, rather
     * than quietly filing a truncated result.
     */
    private StreamingResponseBody streamCsv(MatchQuery query) {
        return outputStream -> {
            Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            // UTF-8 BOM: without it Excel reads the file in the local ANSI
            // codepage and mangles every Devanagari name in it.
            writer.write('\uFEFF');
            writeCsvRow(writer, query.columns().stream().map(Object.class::cast).toList());

            jdbc.setQueryTimeout(guardrails.queryTimeoutSeconds());
            ColumnMapRowMapper rowMapper = new ColumnMapRowMapper();
            jdbc.query(query.sql(), query.params().toArray(), (RowCallbackHandler) rs -> {
                Map<String, Object> row = rowMapper.mapRow(rs, 0);
                try {
                    writeCsvRow(writer, query.columns().stream().map(row::get).toList());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            writer.flush();
        };
    }

    private static void writeCsvRow(Writer writer, List<Object> values) throws IOException {
        StringJoiner line = new StringJoiner(",");
        for (Object value : values) {
            line.add(csvField(value));
        }
        // CRLF, the line ending RFC 4180 specifies and the one Excel expects
        // for a quoted field that itself contains a newline.
        writer.write(line.toString());
        writer.write("\r\n");
    }

    /** Quotes only when it has to, and doubles any quote inside — RFC 4180. */
    private static String csvField(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.indexOf('"') < 0 && text.indexOf(',') < 0
                && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    public record MatchQuery(String sql, List<Object> params, List<String> columns) {
    }

    private void writeLine(java.io.OutputStream out, Map<String, Object> payload) throws IOException {
        out.write(objectMapper.writeValueAsBytes(payload));
        out.write('\n');
        out.flush();
    }

    private String blockingKeyExpr(String columnRef) {
        return "substr(lower(" + columnRef + "), 1, " + analysisProperties.blockingPrefixLen() + ")";
    }

    /**
     * Equi-join fan-out estimate before the match query runs:
     * {@code sourceRows × targetRows / ∏ max(sourceDistinct_g, targetDistinct_g)}.
     * Distincts use the blocking-key expression on fuzzy groups. Refuses when above
     * {@link AnalysisProperties#maxEstimatedRows()}.
     */
    private void enforceEstimatedRowCeiling(Sides sides, List<GroupPlan> groups) {
        long ceiling = analysisProperties.maxEstimatedRows();
        if (ceiling <= 0 || groups.isEmpty()) {
            return;
        }
        long sourceRows = queryRowCount(sides.sourceTable());
        long targetRows = queryRowCount(sides.targetTable());
        if (sourceRows == 0 || targetRows == 0) {
            return;
        }
        long numerator = multiplyCap(sourceRows, targetRows, Long.MAX_VALUE);
        if (numerator <= 0) {
            return;
        }
        long denominator = 1;
        for (GroupPlan group : groups) {
            long sourceDistinct = sideDistinctEstimate(sides.sourceTable(), group.group().source(), group.fuzzy());
            long targetDistinct = sideDistinctEstimate(sides.targetTable(), group.group().target(), group.fuzzy());
            long groupMax = Math.max(sourceDistinct, targetDistinct);
            if (groupMax <= 0) {
                return;
            }
            denominator = multiplyCap(denominator, groupMax, Long.MAX_VALUE);
            if (denominator <= 0) {
                return;
            }
        }
        long estimate = numerator / denominator;
        if (numerator == Long.MAX_VALUE || estimate < 0) {
            estimate = ceiling + 1;
        }
        if (estimate > ceiling) {
            throw new IllegalArgumentException(
                    "Estimated match fan-out is about " + formatEstimate(estimate)
                            + " rows (limit " + formatEstimate(ceiling) + "). "
                            + "Try a longer blocking prefix (SRSE_ANALYSIS_BLOCKING_PREFIX_LEN), "
                            + "a more selective join key, or fewer folded groups.");
        }
    }

    private long queryRowCount(QualifiedTable table) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table.qualifiedName(), Long.class);
        return count == null ? 0 : Math.max(0, count);
    }

    private long sideDistinctEstimate(QualifiedTable table, List<MatchCriterion> columns, boolean fuzzy) {
        if (columns.isEmpty()) {
            return 1;
        }
        long product = 1;
        long ceiling = analysisProperties.maxEstimatedRows();
        for (MatchCriterion c : columns) {
            String ref = table.qualifiedName() + "." + c.column();
            String expr = fuzzy ? blockingKeyExpr(ref) : ref;
            product = multiplyCap(product, queryApproxDistinct(table, expr), ceiling);
        }
        return product;
    }

    private long queryApproxDistinct(QualifiedTable table, String valueExpression) {
        String sql = "SELECT CAST(approx_distinct(" + valueExpression + ") AS BIGINT) FROM "
                + table.qualifiedName();
        Long count = jdbc.queryForObject(sql, Long.class);
        return count == null ? 0 : Math.max(0, count);
    }

    private static long multiplyCap(long a, long b, long ceiling) {
        if (a <= 0 || b <= 0) {
            return 0;
        }
        if (a > ceiling / b) {
            return ceiling + 1;
        }
        return a * b;
    }

    private static String formatEstimate(long value) {
        return String.format("%,d", value);
    }

    private static String buildMatchScoreExpr(List<GroupPlan> groups) {
        List<String> terms = new ArrayList<>();
        for (GroupPlan group : groups) {
            terms.add(group.fuzzy()
                    ? FuzzyMatchSql.similarityExpr(group.sourceRef(), group.targetRef())
                    : "1.0");
        }
        String sum = String.join(" + ", terms);
        return "ROUND((" + sum + ") / " + terms.size() + " * 100, 1)";
    }

    /**
     * Whether a whole GROUP is fuzzy — a combined name is one value and gets
     * one decision, and one threshold.
     *
     * <p>Admin-registered {@link AnalysisColumnMetadata} takes precedence over
     * the name-substring guess. When a folded group's registrations DISAGREE —
     * a fuzzy {@code first_name} beside an exact {@code emp_code} — fuzzy wins.
     * The two mistakes are not symmetric: a group wrongly forced exact returns
     * almost nothing and reads to the officer as "these datasets do not
     * overlap", while one wrongly made fuzzy returns extra rows that carry
     * {@code match_score_pct} and are tunable with the threshold the officer
     * already controls. Visible over silent, the same trade
     * {@link CompareAs#resolve} makes when it lets TEXT win.
     *
     * <p>This MUST stay in sync with the frontend's own
     * fuzzy-eligibility check (analysis/page.tsx), or the officer could see a
     * Fuzzy % control that the backend then silently ignores (or vice versa: a
     * submitted threshold the backend never uses).
     */
    private boolean isGroupFuzzy(MatchGroup group) {
        List<MatchCriterion> columns = new ArrayList<>(group.source());
        columns.addAll(group.target());

        // Registered columns decide as a BLOC, and an unregistered column
        // sitting beside a registered one gets no vote — otherwise the
        // name-substring guess could overturn an explicit admin setting, which
        // is the one thing the single-column rule has always refused to do.
        boolean anyRegistered = false;
        for (MatchCriterion c : columns) {
            Optional<AnalysisColumnMetadata> meta = findMetadata(c);
            if (meta.isPresent()) {
                anyRegistered = true;
                if (meta.get().isFuzzyMatchable()) {
                    return true;
                }
            }
        }
        if (anyRegistered) {
            return false;
        }
        return columns.stream().anyMatch(c -> c.column().toLowerCase().contains("name"));
    }

    private Optional<AnalysisColumnMetadata> findMetadata(MatchCriterion c) {
        return columnMetadata.findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                c.catalog(), c.schema(), c.table(), c.column());
    }

    private void appendSelect(StringBuilder select, Set<String> outerColumns,
                              String alias, String column, String outAlias) {
        if (select.length() > 0) {
            select.append(", ");
        }
        select.append(alias).append('.').append(column).append(" AS \"").append(outAlias).append('"');
        outerColumns.add(outAlias);
    }

    /**
     * The one QUALIFIED table every criterion on a side must share.
     *
     * <p>The comparison is on the full {@code catalog.schema.table} triple,
     * not the bare table name — with Silver and Gold layers both registered,
     * two criteria naming the same {@code tbl_txn_bankdtl} can legitimately
     * be two different physical tables, and treating them as one would emit a
     * join whose ON clause silently compared a table against itself.
     */
    private static QualifiedTable tableOf(List<MatchCriterion> criteria, String label) {
        if (criteria == null || criteria.isEmpty() || criteria.size() > MAX_CRITERIA_PER_SIDE) {
            throw new IllegalArgumentException(label + " must have 1 to " + MAX_CRITERIA_PER_SIDE + " entries");
        }
        return sameTable(criteria, label);
    }

    /**
     * The same one-table rule without the per-side column cap, for a list that
     * has already been bounded as GROUPS — eight groups of four columns is a
     * legitimate thirty-two entries, and counting them against the old
     * eight-picks limit would reject a request the group caps just allowed.
     */
    private static QualifiedTable sameTable(List<MatchCriterion> criteria, String label) {
        if (criteria == null || criteria.isEmpty()) {
            throw new IllegalArgumentException(label + " must have at least one entry");
        }
        QualifiedTable table = criteria.get(0).qualifiedTable();
        for (MatchCriterion c : criteria) {
            if (!table.equals(c.qualifiedTable())) {
                throw new IllegalArgumentException(label + " must all reference the same table");
            }
        }
        return table;
    }

    /**
     * Puts one side's columns through the registry gate and keeps what the
     * gate looked up — their live types, which the emitted SQL depends on.
     *
     * <p>One batched call rather than one per criterion: resolving a table's
     * columns walks the whole catalog/schema/table hierarchy, and every
     * criterion here shares one table by {@link #tableOf}'s check.
     */
    private Map<String, RegisteredColumn> describeSide(QualifiedTable table, List<MatchCriterion> criteria,
                                                         List<DisplayColumn> display) {
        List<String> columnNames = Stream.concat(
                criteria.stream().map(MatchCriterion::column),
                display.stream().map(DisplayColumn::column))
                .distinct()
                .toList();
        return registry.describeColumns(table, columnNames);
    }

    private void validateDisplayColumns(List<DisplayColumn> columns, QualifiedTable sideTable, String label) {
        if (columns.isEmpty()) {
            return;
        }
        if (columns.size() > MAX_CRITERIA_PER_SIDE) {
            throw new IllegalArgumentException(label + " must have 0 to " + MAX_CRITERIA_PER_SIDE + " entries");
        }
        for (int i = 0; i < columns.size(); i++) {
            DisplayColumn d = columns.get(i);
            if (!sideTable.equals(d.qualifiedTable())) {
                throw new IllegalArgumentException(label + "[" + i + "] must reference " + sideTable.qualifiedName());
            }
            registry.validateColumn(d.qualifiedColumn());
        }
    }

    private void validateSideMembership(QualifiedTable table, QualifiedTable sourceTable,
                                        QualifiedTable targetTable, String label) {
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
