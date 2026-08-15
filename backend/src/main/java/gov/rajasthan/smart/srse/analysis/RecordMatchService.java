package gov.rajasthan.smart.srse.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.compiler.FieldResolver;
import gov.rajasthan.smart.srse.compiler.FuzzyMatchSql;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
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
    private final AnalysisSchemaService schemaService;
    private final GuardrailProperties guardrails;
    private final FieldResolver fields;
    private final AnalysisColumnMetadataRepository columnMetadata;
    private final ObjectMapper objectMapper;

    public RecordMatchService(@Qualifier("prestoJdbcTemplate") JdbcTemplate jdbc,
                              AnalysisSchemaService schemaService,
                              GuardrailProperties guardrails,
                              FieldResolver fields,
                              AnalysisColumnMetadataRepository columnMetadata,
                              ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.schemaService = schemaService;
        this.guardrails = guardrails;
        this.fields = fields;
        this.columnMetadata = columnMetadata;
        this.objectMapper = objectMapper;
    }

    public StreamingResponseBody match(RecordMatchRequest req) {
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
        StringBuilder onClause = new StringBuilder();
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
            if (onClause.length() > 0) {
                onClause.append(" AND ");
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
                onClause.append(blockingKeyExpr(srcCol)).append(" = ").append(blockingKeyExpr(tgtCol));
                if (where.length() > 0) {
                    where.append(" AND ");
                }
                where.append(FuzzyMatchSql.similarityExpr(srcCol, tgtCol)).append(" >= ?");
                params.add(threshold / 100.0);
            } else {
                onClause.append(srcCol).append(" = ").append(tgtCol);
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

        String baseSql = "SELECT " + select + " FROM " + sourceTable + " src JOIN " + targetTable
                + " tgt ON " + onClause + " WHERE " + where;

        String finalSql;
        if (req.dedup() != null) {
            String partitionCols = req.sourceCriteria().stream()
                    .map(c -> "\"source_" + c.column() + "\"")
                    .reduce((a, b) -> a + ", " + b).orElseThrow();
            finalSql = "SELECT * FROM (SELECT base.*, ROW_NUMBER() OVER ("
                    + "PARTITION BY " + partitionCols + " ORDER BY \"" + dedupAlias + "\" DESC) AS rn "
                    + "FROM (" + baseSql + ") base) ranked WHERE rn = 1";
        } else {
            finalSql = baseSql;
        }

        List<String> columns = List.copyOf(outerColumns);
        String displaySql = renderForDisplay(finalSql, params);

        return outputStream -> {
            writeLine(outputStream, Map.of("type", "meta", "columns", columns, "sql", displaySql));
            try {
                jdbc.setQueryTimeout(guardrails.queryTimeoutSeconds());
                ColumnMapRowMapper rowMapper = new ColumnMapRowMapper();
                long[] totalRows = {0};
                jdbc.query(finalSql, params.toArray(), (RowCallbackHandler) rs -> {
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
