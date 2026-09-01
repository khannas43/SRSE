package gov.rajasthan.smart.srse.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.compiler.AliasRebase;
import gov.rajasthan.smart.srse.compiler.FieldResolver;
import gov.rajasthan.smart.srse.compiler.FuzzyMatchSql;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadata;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 *  - Read-only. No write/DELETE capability. "Dedup" is view-only — it
 *    collapses duplicate rows in the returned grid, never touches the
 *    lakehouse.
 *  - The join runs across the FULL source/target tables — no row cap, no
 *    input sampling. An earlier version pre-sampled each side to a top-500
 *    slice before a CROSS JOIN; at real (crore-scale) row counts that made
 *    matches nearly impossible to find (an arbitrary ~0.0005% slice per
 *    side), silently. Instead, every criterion pair becomes part of the
 *    JOIN's ON clause: exact pairs join on equality directly; fuzzy pairs
 *    join on a {@link #BLOCKING_PREFIX_LEN}-character case-insensitive
 *    prefix ("blocking key" — standard record-linkage technique), with the
 *    real Levenshtein-similarity check applied afterward as a WHERE filter
 *    only within already-blocked candidate pairs. This trades a small,
 *    documented amount of recall (a typo in the first {@link #BLOCKING_PREFIX_LEN}
 *    characters of a fuzzy column can be missed) for the join being a real
 *    hash join Presto can execute at scale, instead of a nested-loop cross
 *    product over a token sample.
 *  - There is no output row cap either (removed {@code SRSE_ANALYSIS_ROW_CAP}
 *    deliberately) — the only remaining safety net against a runaway/badly
 *    blocked match is {@link GuardrailProperties#queryTimeoutSeconds()}.
 *    Results stream to the client as they're produced (see below), so a
 *    timeout mid-match still leaves whatever rows already streamed visible.
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

    /**
     * Prefix length (case-insensitive, characters) used as the equi-join
     * blocking key for fuzzy criterion pairs. Chosen as a pragmatic default,
     * not derived from data — a longer prefix narrows candidate pairs
     * further (cheaper) but misses more early-character typos; a shorter one
     * is more forgiving but blocks fewer candidates out.
     */
    private static final int BLOCKING_PREFIX_LEN = 3;
    private static final int MAX_CRITERIA_PER_SIDE = 8;
    private static final Set<String> AGE_UNITS = Set.of("DAYS", "MONTHS", "YEARS");

    private final JdbcTemplate jdbc;
    private final LakehouseRegistryService registry;
    private final GuardrailProperties guardrails;
    private final FieldResolver fields;
    private final AnalysisColumnMetadataRepository columnMetadata;
    private final ObjectMapper objectMapper;

    public RecordMatchService(@Qualifier("prestoJdbcTemplate") JdbcTemplate jdbc,
                              LakehouseRegistryService registry,
                              GuardrailProperties guardrails,
                              FieldResolver fields,
                              AnalysisColumnMetadataRepository columnMetadata,
                              ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.guardrails = guardrails;
        this.fields = fields;
        this.columnMetadata = columnMetadata;
        this.objectMapper = objectMapper;
    }

    public StreamingResponseBody match(RecordMatchRequest req) {
        validateRequest(req);
        MatchQuery query = buildMatchQuery(req);
        return streamResults(query);
    }

    private void validateRequest(RecordMatchRequest req) {
        QualifiedTable sourceTable = validateSide(req.sourceCriteria(), "sourceCriteria");
        QualifiedTable targetTable = validateSide(req.targetCriteria(), "targetCriteria");
        if (req.sourceCriteria().size() != req.targetCriteria().size()) {
            throw new IllegalArgumentException("sourceCriteria and targetCriteria must be the same size");
        }
        if (req.dedup() != null) {
            validateSideMembership(req.dedup().qualifiedTable(), sourceTable, targetTable, "dedup.table");
            registry.validateColumn(req.dedup().qualifiedColumn());
        }
        if (req.ageFilter() != null) {
            validateAgeFilter(req.ageFilter());
        }
    }

    private static void validateAgeFilter(AgeFilterSpec ageFilter) {
        if (!AGE_UNITS.contains(ageFilter.unit())) {
            throw new IllegalArgumentException("ageFilter.unit must be one of " + AGE_UNITS);
        }
        if (ageFilter.minAge() > ageFilter.maxAge()) {
            throw new IllegalArgumentException("ageFilter minAge must be <= maxAge");
        }
    }

    private MatchQuery buildMatchQuery(RecordMatchRequest req) {
        List<Object> params = new ArrayList<>();
        Set<String> outerColumns = new LinkedHashSet<>();
        StringBuilder select = new StringBuilder();
        StringBuilder onClause = new StringBuilder();
        StringBuilder where = new StringBuilder();

        appendCriteriaSelects(select, outerColumns, req);
        appendJoinConditions(onClause, where, params, req);

        String dedupAlias = appendDedupSelect(select, outerColumns, req);
        appendMatchScoreSelect(select, outerColumns, req);
        appendAgeFilter(where, params, req);

        if (where.length() == 0) {
            where.append("TRUE");
        }

        // Fully-qualified catalog.schema.table on both sides — the two sides
        // can live in different catalogs entirely (a Silver-vs-Gold
        // reconciliation), which Presto joins natively.
        String sourceTable = req.sourceCriteria().get(0).qualifiedTable().qualifiedName();
        String targetTable = req.targetCriteria().get(0).qualifiedTable().qualifiedName();
        String baseSql = "SELECT " + select + " FROM " + sourceTable + " src JOIN " + targetTable
                + " tgt ON " + onClause + " WHERE " + where;

        String finalSql = wrapWithDedup(baseSql, req, dedupAlias);
        return new MatchQuery(finalSql, params, List.copyOf(outerColumns));
    }

    private void appendCriteriaSelects(StringBuilder select, Set<String> outerColumns, RecordMatchRequest req) {
        for (MatchCriterion c : req.sourceCriteria()) {
            appendSelect(select, outerColumns, "src", c.column(), "source_" + c.column());
        }
        for (MatchCriterion c : req.targetCriteria()) {
            appendSelect(select, outerColumns, "tgt", c.column(), "target_" + c.column());
        }
    }

    private void appendJoinConditions(StringBuilder onClause, StringBuilder where, List<Object> params,
                                      RecordMatchRequest req) {
        for (int i = 0; i < req.sourceCriteria().size(); i++) {
            MatchCriterion sc = req.sourceCriteria().get(i);
            MatchCriterion tc = req.targetCriteria().get(i);
            appendCriterionJoin(onClause, where, params, i, sc, tc);
        }
    }

    private void appendCriterionJoin(StringBuilder onClause, StringBuilder where, List<Object> params,
                                     int index, MatchCriterion sc, MatchCriterion tc) {
        String srcCol = "src." + sc.column();
        String tgtCol = "tgt." + tc.column();
        if (onClause.length() > 0) {
            onClause.append(" AND ");
        }
        if (isFuzzyMatchable(sc, tc)) {
            appendFuzzyJoin(onClause, where, params, index, sc, srcCol, tgtCol);
        } else {
            onClause.append(srcCol).append(" = ").append(tgtCol);
        }
    }

    private static void appendFuzzyJoin(StringBuilder onClause, StringBuilder where, List<Object> params,
                                      int index, MatchCriterion sc, String srcCol, String tgtCol) {
        if (sc.fuzzyThresholdPercent() == null) {
            throw new IllegalArgumentException(
                    "sourceCriteria[" + index + "].fuzzyThresholdPercent is required for a name column");
        }
        double threshold = sc.fuzzyThresholdPercent();
        if (threshold < 0 || threshold > 100) {
            throw new IllegalArgumentException("fuzzyThresholdPercent must be between 0 and 100");
        }
        onClause.append(blockingKeyExpr(srcCol)).append(" = ").append(blockingKeyExpr(tgtCol));
        appendWhereClause(where, FuzzyMatchSql.similarityExpr(srcCol, tgtCol) + " >= ?");
        params.add(threshold / 100.0);
    }

    private String appendDedupSelect(StringBuilder select, Set<String> outerColumns, RecordMatchRequest req) {
        if (req.dedup() == null) {
            return null;
        }
        QualifiedTable sourceTable = req.sourceCriteria().get(0).qualifiedTable();
        String side = req.dedup().qualifiedTable().equals(sourceTable) ? "src" : "tgt";
        String dedupAlias = "dedup_last_updated";
        appendSelect(select, outerColumns, side, req.dedup().column(), dedupAlias);
        return dedupAlias;
    }

    private void appendMatchScoreSelect(StringBuilder select, Set<String> outerColumns, RecordMatchRequest req) {
        if (!req.highlightDuplicates()) {
            return;
        }
        String scoreExpr = buildMatchScoreExpr(req);
        select.append(", ").append(scoreExpr).append(" AS \"match_score_pct\"");
        outerColumns.add("match_score_pct");
    }

    private void appendAgeFilter(StringBuilder where, List<Object> params, RecordMatchRequest req) {
        if (req.ageFilter() == null) {
            return;
        }
        // The catalogue's age expression is table-qualified for the Rule
        // Engine's own table, so it has to be rebased onto each join alias —
        // see AliasRebase for why taking "everything after the last dot" was
        // wrong for a Tier-2 (DOB-derived) age expression.
        String ageExpression = fields.resolveColumn("age_years");
        double divisor = ageDivisor(req.ageFilter().unit());
        double minYears = req.ageFilter().minAge() / divisor;
        double maxYears = req.ageFilter().maxAge() / divisor;

        // Must NOT hardcode a leading " AND ": when every criterion pair is
        // exact, nothing has written to `where` yet and an unconditional AND
        // emitted "WHERE  AND date_diff(...)" — invalid SQL. Same guarded
        // append the fuzzy path already uses.
        appendWhereClause(where, AliasRebase.ontoAlias(ageExpression, "src") + " BETWEEN ? AND ?");
        appendWhereClause(where, AliasRebase.ontoAlias(ageExpression, "tgt") + " BETWEEN ? AND ?");
        params.add(minYears);
        params.add(maxYears);
        params.add(minYears);
        params.add(maxYears);
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

    private static String wrapWithDedup(String baseSql, RecordMatchRequest req, String dedupAlias) {
        if (req.dedup() == null) {
            return baseSql;
        }
        String partitionCols = req.sourceCriteria().stream()
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

    private record MatchQuery(String sql, List<Object> params, List<String> columns) {
    }

    private void writeLine(java.io.OutputStream out, Map<String, Object> payload) throws IOException {
        out.write(objectMapper.writeValueAsBytes(payload));
        out.write('\n');
        out.flush();
    }

    private static String blockingKeyExpr(String columnRef) {
        return "substr(lower(" + columnRef + "), 1, " + BLOCKING_PREFIX_LEN + ")";
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
        Optional<AnalysisColumnMetadata> srcMeta = findMetadata(sc);
        if (srcMeta.isPresent()) {
            return srcMeta.get().isFuzzyMatchable();
        }
        Optional<AnalysisColumnMetadata> tgtMeta = findMetadata(tc);
        if (tgtMeta.isPresent()) {
            return tgtMeta.get().isFuzzyMatchable();
        }
        return sc.column().toLowerCase().contains("name") || tc.column().toLowerCase().contains("name");
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
     * Validates every criterion on one side shares one QUALIFIED table, and
     * that each column passes the registry gate; returns that table.
     *
     * <p>The comparison is on the full {@code catalog.schema.table} triple,
     * not the bare table name — with Silver and Gold layers both registered,
     * two criteria naming the same {@code tbl_txn_bankdtl} can legitimately
     * be two different physical tables, and treating them as one would emit a
     * join whose ON clause silently compared a table against itself.
     */
    private QualifiedTable validateSide(List<MatchCriterion> criteria, String label) {
        if (criteria == null || criteria.isEmpty() || criteria.size() > MAX_CRITERIA_PER_SIDE) {
            throw new IllegalArgumentException(label + " must have 1 to " + MAX_CRITERIA_PER_SIDE + " entries");
        }
        QualifiedTable table = criteria.get(0).qualifiedTable();
        for (MatchCriterion c : criteria) {
            if (!table.equals(c.qualifiedTable())) {
                throw new IllegalArgumentException(label + " must all reference the same table");
            }
        }
        // One batched gate call rather than one per criterion: resolving a
        // table's columns walks the whole catalog/schema/table hierarchy, and
        // every criterion here shares one table by the check above.
        registry.validateColumns(table, criteria.stream().map(MatchCriterion::column).toList());
        return table;
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
