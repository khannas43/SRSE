package gov.rajasthan.smart.srse.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.compiler.AliasRebase;
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
        Sides sides = validateRequest(req);
        MatchQuery query = buildMatchQuery(req, sides);
        return streamResults(query);
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
        Sides sides = validateRequest(req);
        MatchQuery query = buildMatchQuery(req, sides);
        return streamCsv(query);
    }

    /**
     * Both sides' columns as the LIVE lakehouse describes them, gathered by
     * the same pass that gates them. The types travel because the SQL depends
     * on them — see {@link #planPairs}.
     */
    private record Sides(QualifiedTable sourceTable, Map<String, RegisteredColumn> sourceColumns,
                         QualifiedTable targetTable, Map<String, RegisteredColumn> targetColumns) {
    }

    private Sides validateRequest(RecordMatchRequest req) {
        QualifiedTable sourceTable = tableOf(req.sourceCriteria(), "sourceCriteria");
        QualifiedTable targetTable = tableOf(req.targetCriteria(), "targetCriteria");
        if (req.sourceCriteria().size() != req.targetCriteria().size()) {
            throw new IllegalArgumentException("sourceCriteria and targetCriteria must be the same size");
        }
        Sides sides = new Sides(
                sourceTable, describeSide(sourceTable, req.sourceCriteria()),
                targetTable, describeSide(targetTable, req.targetCriteria()));
        if (req.dedup() != null) {
            validateSideMembership(req.dedup().qualifiedTable(), sourceTable, targetTable, "dedup.table");
            registry.validateColumn(req.dedup().qualifiedColumn());
        }
        if (req.ageFilter() != null) {
            validateAgeFilter(req.ageFilter());
        }
        return sides;
    }

    private static void validateAgeFilter(AgeFilterSpec ageFilter) {
        if (!AGE_UNITS.contains(ageFilter.unit())) {
            throw new IllegalArgumentException("ageFilter.unit must be one of " + AGE_UNITS);
        }
        if (ageFilter.minAge() > ageFilter.maxAge()) {
            throw new IllegalArgumentException("ageFilter minAge must be <= maxAge");
        }
    }

    private MatchQuery buildMatchQuery(RecordMatchRequest req, Sides sides) {
        List<Object> params = new ArrayList<>();
        Set<String> outerColumns = new LinkedHashSet<>();
        StringBuilder select = new StringBuilder();
        StringBuilder onClause = new StringBuilder();
        StringBuilder where = new StringBuilder();

        // Every type-dependent decision is made once, here, and both the join
        // and the match score read the same plan — they MUST agree on which
        // pairs are fuzzy and on how each side is cast, or the score would be
        // computed over different expressions than the join matched on.
        List<CriterionPair> pairs = planPairs(req, sides);

        appendCriteriaSelects(select, outerColumns, req);
        appendJoinConditions(onClause, where, params, pairs);

        String dedupAlias = appendDedupSelect(select, outerColumns, req);
        appendMatchScoreSelect(select, outerColumns, req, pairs);
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

    /**
     * One (source, target) criterion pair with every type-dependent decision
     * already made: whether the pair is fuzzy, and the SQL each side becomes
     * after coercion.
     *
     * <p>{@code sourceRef}/{@code targetRef} are what actually goes into SQL.
     * For a fuzzy pair they are the TEXT forms (Levenshtein and {@code lower}
     * take nothing else); for an exact pair they are the two sides aligned to
     * a common type — which is the whole point: the same account number stored
     * {@code varchar} in one table and {@code bigint} in the other used to
     * fail the query outright with {@code '=' cannot be applied to varchar,
     * bigint}.
     */
    private record CriterionPair(MatchCriterion source, MatchCriterion target, boolean fuzzy,
                                 String sourceRef, String targetRef) {
    }

    /**
     * Resolves every pair once — fuzzy-or-exact, and the casts each side
     * needs. Both the join and the match score read this, so they cannot drift
     * apart, and each column's admin metadata is fetched once instead of once
     * per use.
     */
    private List<CriterionPair> planPairs(RecordMatchRequest req, Sides sides) {
        List<CriterionPair> pairs = new ArrayList<>();
        for (int i = 0; i < req.sourceCriteria().size(); i++) {
            MatchCriterion sc = req.sourceCriteria().get(i);
            MatchCriterion tc = req.targetCriteria().get(i);
            Optional<AnalysisColumnMetadata> sourceMeta = findMetadata(sc);
            Optional<AnalysisColumnMetadata> targetMeta = findMetadata(tc);

            String srcCol = "src." + sc.column();
            String tgtCol = "tgt." + tc.column();
            SqlTypeFamily srcType = familyOf(sides.sourceColumns(), sc.column());
            SqlTypeFamily tgtType = familyOf(sides.targetColumns(), tc.column());

            if (isFuzzyMatchable(sc, tc, sourceMeta, targetMeta)) {
                // CompareAs does not enter into a fuzzy pair: Levenshtein
                // similarity is a string measure, so both sides go to text
                // whatever the admin set for a direct comparison.
                pairs.add(new CriterionPair(sc, tc, true,
                        TypeCoercion.asText(srcCol, srcType), TypeCoercion.asText(tgtCol, tgtType)));
            } else {
                CompareAs mode = CompareAs.resolve(compareAsOf(sourceMeta), compareAsOf(targetMeta));
                TypeCoercion.Aligned aligned = TypeCoercion.align(srcCol, srcType, tgtCol, tgtType, mode);
                pairs.add(new CriterionPair(sc, tc, false, aligned.left(), aligned.right()));
            }
        }
        return pairs;
    }

    private static SqlTypeFamily familyOf(Map<String, RegisteredColumn> columns, String column) {
        RegisteredColumn described = columns.get(column);
        return described == null ? SqlTypeFamily.UNKNOWN : SqlTypeFamily.of(described.dataType());
    }

    private static CompareAs compareAsOf(Optional<AnalysisColumnMetadata> meta) {
        return meta.map(AnalysisColumnMetadata::getCompareAs).orElse(CompareAs.AUTO);
    }

    private void appendJoinConditions(StringBuilder onClause, StringBuilder where, List<Object> params,
                                      List<CriterionPair> pairs) {
        for (int i = 0; i < pairs.size(); i++) {
            CriterionPair pair = pairs.get(i);
            if (onClause.length() > 0) {
                onClause.append(" AND ");
            }
            if (pair.fuzzy()) {
                appendFuzzyJoin(onClause, where, params, i, pair);
            } else {
                onClause.append(pair.sourceRef()).append(" = ").append(pair.targetRef());
            }
        }
    }

    private static void appendFuzzyJoin(StringBuilder onClause, StringBuilder where, List<Object> params,
                                      int index, CriterionPair pair) {
        if (pair.source().fuzzyThresholdPercent() == null) {
            throw new IllegalArgumentException(
                    "sourceCriteria[" + index + "].fuzzyThresholdPercent is required for a name column");
        }
        double threshold = pair.source().fuzzyThresholdPercent();
        if (threshold < 0 || threshold > 100) {
            throw new IllegalArgumentException("fuzzyThresholdPercent must be between 0 and 100");
        }
        onClause.append(blockingKeyExpr(pair.sourceRef())).append(" = ")
                .append(blockingKeyExpr(pair.targetRef()));
        appendWhereClause(where, FuzzyMatchSql.similarityExpr(pair.sourceRef(), pair.targetRef()) + " >= ?");
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

    private void appendMatchScoreSelect(StringBuilder select, Set<String> outerColumns,
                                        RecordMatchRequest req, List<CriterionPair> pairs) {
        if (!req.highlightDuplicates()) {
            return;
        }
        String scoreExpr = buildMatchScoreExpr(pairs);
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

    private static String buildMatchScoreExpr(List<CriterionPair> pairs) {
        List<String> terms = new ArrayList<>();
        for (CriterionPair pair : pairs) {
            terms.add(pair.fuzzy()
                    ? FuzzyMatchSql.similarityExpr(pair.sourceRef(), pair.targetRef())
                    : "1.0");
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
    private static boolean isFuzzyMatchable(MatchCriterion sc, MatchCriterion tc,
                                            Optional<AnalysisColumnMetadata> srcMeta,
                                            Optional<AnalysisColumnMetadata> tgtMeta) {
        if (srcMeta.isPresent()) {
            return srcMeta.get().isFuzzyMatchable();
        }
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
    private Map<String, RegisteredColumn> describeSide(QualifiedTable table, List<MatchCriterion> criteria) {
        return registry.describeColumns(table, criteria.stream().map(MatchCriterion::column).toList());
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
