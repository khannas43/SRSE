package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.compiler.CompareAs;
import gov.rajasthan.smart.srse.compiler.SqlTypeFamily;
import gov.rajasthan.smart.srse.compiler.TypeCoercion;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService.RegisteredColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadata;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Metadata-first join-key hints for the Analysis tab. Every column returned
 * passes {@link LakehouseRegistryService#listColumns} — the same gate the match
 * engine uses — so a suggestion never names a column the picker cannot select.
 *
 * <p>Overlap probing samples the <strong>source</strong> side only
 * ({@code TABLESAMPLE BERNOULLI}) and scans the <strong>target</strong> table
 * in full for each probed pair — a semi-join bounded by
 * {@link AnalysisProperties#maxProbedPairs()} and
 * {@link GuardrailProperties#queryTimeoutSeconds()}. Expect one full target
 * scan per probed pair (costly by design; officer-triggered only).
 *
 * <p>Key-likeness ({@code approx_distinct/count} on the source) uses one
 * <strong>full-table</strong> aggregate on the source — not sampled: a sample
 * inflates low-cardinality ratios by ~1/p and would mis-rank mid-cardinality
 * columns as keys.
 */
@Service
public class JoinKeySuggestService {

    private static final Logger log = LoggerFactory.getLogger(JoinKeySuggestService.class);

    /** Bernoulli percentage on the source side only; stated in probe reasons. */
    static final int PROBE_SAMPLE_PERCENT = 10;

    /**
     * Columns below this {@code approx_distinct / count} ratio on the source are
     * treated as attributes (e.g. district), not join keys (e.g. id).
     */
    static final double MIN_SOURCE_KEY_DISTINCTNESS = 0.5;

    private final LakehouseRegistryService registry;
    private final AnalysisColumnMetadataRepository columnMetadata;
    private final AnalysisProperties analysisProperties;
    private final GuardrailProperties guardrails;
    private final JdbcTemplate jdbc;

    public JoinKeySuggestService(LakehouseRegistryService registry,
                                 AnalysisColumnMetadataRepository columnMetadata,
                                 AnalysisProperties analysisProperties,
                                 GuardrailProperties guardrails,
                                 @Qualifier("prestoJdbcTemplate") JdbcTemplate jdbc) {
        this.registry = registry;
        this.columnMetadata = columnMetadata;
        this.analysisProperties = analysisProperties;
        this.guardrails = guardrails;
        this.jdbc = jdbc;
    }

    public List<JoinKeySuggestion> suggest(SuggestJoinKeysRequest req) {
        QualifiedTable source = new QualifiedTable(req.sourceCatalog(), req.sourceSchema(), req.sourceTable());
        QualifiedTable target = new QualifiedTable(req.targetCatalog(), req.targetSchema(), req.targetTable());

        List<RegisteredColumn> sourceCols = registry.listColumns(
                source.catalog(), source.schema(), source.table());
        List<RegisteredColumn> targetCols = registry.listColumns(
                target.catalog(), target.schema(), target.table());

        Map<String, Double> sourceDistinctness = req.probeRequested()
                ? loadSourceDistinctnessRatios(source, sourceCols)
                : Map.of();

        List<ScoredPair> ranked = rankMetadataPairs(source, target, sourceCols, targetCols, sourceDistinctness);
        ranked.sort(Comparator.comparingInt(ScoredPair::score).reversed()
                .thenComparing(p -> p.source().name())
                .thenComparing(p -> p.target().name()));

        if (req.probeRequested() && !ranked.isEmpty()) {
            try {
                applyProbeScores(source, target, ranked);
                ranked.sort(Comparator.comparingInt(ScoredPair::score).reversed()
                        .thenComparing(p -> p.source().name())
                        .thenComparing(p -> p.target().name()));
            } catch (RuntimeException ex) {
                log.warn("Join-key overlap probe failed — returning metadata-only suggestions: {}", ex.getMessage());
            }
        }

        return ranked.stream()
                .limit(20)
                .map(ScoredPair::toSuggestion)
                .toList();
    }

    private List<ScoredPair> rankMetadataPairs(
            QualifiedTable source,
            QualifiedTable target,
            List<RegisteredColumn> sourceCols,
            List<RegisteredColumn> targetCols,
            Map<String, Double> sourceDistinctness) {
        List<ScoredPair> pairs = new ArrayList<>();
        for (RegisteredColumn s : sourceCols) {
            SqlTypeFamily sFamily = SqlTypeFamily.of(s.dataType());
            if (sFamily == SqlTypeFamily.UNKNOWN) {
                continue;
            }
            for (RegisteredColumn t : targetCols) {
                SqlTypeFamily tFamily = SqlTypeFamily.of(t.dataType());
                if (tFamily == SqlTypeFamily.UNKNOWN || !typesCompatible(sFamily, tFamily)) {
                    continue;
                }
                int score = scorePair(s, t, sFamily, tFamily, sourceDistinctness);
                if (score <= 0) {
                    continue;
                }
                pairs.add(new ScoredPair(s, t, sFamily, tFamily, score, reasonFor(s, t, sFamily, tFamily, score, null)));
            }
        }
        return pairs;
    }

    private static boolean typesCompatible(SqlTypeFamily left, SqlTypeFamily right) {
        if (left == SqlTypeFamily.UNKNOWN || right == SqlTypeFamily.UNKNOWN) {
            return false;
        }
        TypeCoercion.align("l", left, "r", right, CompareAs.AUTO);
        return true;
    }

    static int scorePair(RegisteredColumn s, RegisteredColumn t,
                         SqlTypeFamily sFamily, SqlTypeFamily tFamily,
                         Map<String, Double> sourceDistinctness) {
        int base;
        if (s.name().equalsIgnoreCase(t.name()) && sFamily == tFamily) {
            base = 400;
        } else if (businessNamesMatch(s, t)) {
            base = 300;
        } else {
            String ns = normalizeKeyName(s.name());
            String nt = normalizeKeyName(t.name());
            if (ns.equals(nt) && !ns.isEmpty()) {
                base = 250;
            } else if (ns.length() >= 4 && nt.length() >= 4 && (ns.contains(nt) || nt.contains(ns))) {
                base = 200;
            } else if (AnalysisColumnMatchHeuristics.nameSubstringGuess(s.name())
                    && AnalysisColumnMatchHeuristics.nameSubstringGuess(t.name())) {
                base = 100;
            } else {
                base = 50;
            }
        }
        return applyDistinctnessToScore(base, sourceDistinctness.getOrDefault(s.name(), 1.0));
    }

    /**
     * Low-cardinality source columns (district) must not outrank true keys (id)
     * when name matching alone would tie them at 1.0 containment.
     */
    static int applyDistinctnessToScore(int baseScore, double sourceDistinctnessRatio) {
        double ratio = Math.min(1.0, sourceDistinctnessRatio);
        if (ratio < MIN_SOURCE_KEY_DISTINCTNESS) {
            return Math.min(baseScore, 49);
        }
        return baseScore + (int) Math.round(ratio * 100);
    }

    private static boolean businessNamesMatch(RegisteredColumn s, RegisteredColumn t) {
        if (s.businessName() == null || t.businessName() == null) {
            return false;
        }
        return s.businessName().trim().equalsIgnoreCase(t.businessName().trim());
    }

    static String normalizeKeyName(String name) {
        String s = name.toLowerCase(Locale.ROOT);
        for (String suffix : List.of("_id", "_no", "_num")) {
            if (s.endsWith(suffix)) {
                s = s.substring(0, s.length() - suffix.length());
            }
        }
        return s.replace("_", "");
    }

    private String reasonFor(RegisteredColumn s, RegisteredColumn t,
                             SqlTypeFamily sFamily, SqlTypeFamily tFamily,
                             int score, Double overlapRatio) {
        if (overlapRatio != null) {
            int pct = (int) Math.round(overlapRatio * 100);
            return pct + "% of sampled source values found in target (source TABLESAMPLE BERNOULLI "
                    + PROBE_SAMPLE_PERCENT + "%, target scanned in full; approx_distinct estimate — not a row count)";
        }
        if (score >= 400) {
            return "Same column name, both " + sFamily.name();
        }
        if (score >= 300) {
            return "Business name match";
        }
        if (score >= 250) {
            return "Normalised name match (" + normalizeKeyName(s.name()) + ")";
        }
        return "Compatible types (" + sFamily.name() + " / " + tFamily.name() + ")";
    }

    private Map<String, Double> loadSourceDistinctnessRatios(QualifiedTable source, List<RegisteredColumn> cols) {
        if (cols.isEmpty()) {
            return Map.of();
        }
        List<String> names = cols.stream().map(RegisteredColumn::name).toList();
        String sql = buildSourceDistinctnessSql(source, cols);
        jdbc.setQueryTimeout(guardrails.queryTimeoutSeconds());
        List<Map<String, Double>> rows = jdbc.query(sql, (rs, rowNum) -> readDistinctnessRow(rs, names));
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /** Full source-table scan — one aggregate row, all visible columns. */
    static String buildSourceDistinctnessSql(QualifiedTable source, List<RegisteredColumn> cols) {
        StringBuilder exprs = new StringBuilder();
        for (RegisteredColumn c : cols) {
            if (!exprs.isEmpty()) {
                exprs.append(", ");
            }
            exprs.append("LEAST(1.0, CAST(approx_distinct(").append(c.name())
                    .append(") AS DOUBLE) / NULLIF(CAST(count(*) AS DOUBLE), 0)) AS d_")
                    .append(c.name().replace('.', '_'));
        }
        return "SELECT " + exprs + " FROM " + source.qualifiedName();
    }

    private static Map<String, Double> readDistinctnessRow(ResultSet rs, List<String> columnNames) throws SQLException {
        Map<String, Double> out = new HashMap<>();
        for (String col : columnNames) {
            String alias = "d_" + col.replace('.', '_');
            double v = rs.getDouble(alias);
            if (!rs.wasNull()) {
                out.put(col, v);
            }
        }
        return out;
    }

    private void applyProbeScores(QualifiedTable source, QualifiedTable target, List<ScoredPair> ranked) {
        int limit = Math.min(analysisProperties.maxProbedPairs(), ranked.size());
        jdbc.setQueryTimeout(guardrails.queryTimeoutSeconds());
        for (int i = 0; i < limit; i++) {
            ScoredPair pair = ranked.get(i);
            Double ratio = probeOverlap(source, target, pair);
            if (ratio != null) {
                int bonus = (int) Math.round(ratio * 150);
                pair.score += bonus;
                pair.reason = reasonFor(pair.source(), pair.target(), pair.sourceFamily(), pair.targetFamily(),
                        pair.score, ratio);
            }
        }
    }

    private Double probeOverlap(QualifiedTable source, QualifiedTable target, ScoredPair pair) {
        CompareAs leftMode = compareAs(source, pair.source().name());
        CompareAs rightMode = compareAs(target, pair.target().name());
        CompareAs mode = CompareAs.resolve(leftMode, rightMode);
        TypeCoercion.Aligned aligned = TypeCoercion.align(
                "s.v", pair.sourceFamily(),
                "t.v", pair.targetFamily(),
                mode);

        String sql = buildProbeOverlapSql(
                source, target, pair.source(), pair.target(), aligned);
        Double ratio = jdbc.queryForObject(sql, Double.class);
        if (ratio == null || ratio.isNaN()) {
            return null;
        }
        // HyperLogLog in approx_distinct can slightly exceed 1.0 (e.g. 88,749 vs 86,000 rows) — not a correctness guard.
        return Math.min(1.0, ratio);
    }

    /**
     * Semi-join overlap: distinct sampled source values with a target match /
     * distinct sampled source values. Target is not sampled.
     */
    static String buildProbeOverlapSql(
            QualifiedTable source,
            QualifiedTable target,
            RegisteredColumn sourceCol,
            RegisteredColumn targetCol,
            TypeCoercion.Aligned aligned) {
        return """
                SELECT CAST(approx_distinct(CASE WHEN t.v IS NOT NULL THEN s.v END) AS DOUBLE)
                     / NULLIF(CAST(approx_distinct(s.v) AS DOUBLE), 0)
                FROM (
                  SELECT %s AS v FROM %s TABLESAMPLE BERNOULLI (%d) WHERE %s IS NOT NULL
                ) s
                LEFT JOIN (
                  SELECT %s AS v FROM %s WHERE %s IS NOT NULL
                ) t ON %s = %s
                """.formatted(
                sourceCol.name(), source.qualifiedName(), PROBE_SAMPLE_PERCENT, sourceCol.name(),
                targetCol.name(), target.qualifiedName(), targetCol.name(),
                aligned.left(), aligned.right());
    }

    private CompareAs compareAs(QualifiedTable table, String column) {
        return columnMetadata
                .findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                        table.catalog(), table.schema(), table.table(), column)
                .map(AnalysisColumnMetadata::getCompareAs)
                .orElse(CompareAs.AUTO);
    }

    static final class ScoredPair {
        private final RegisteredColumn source;
        private final RegisteredColumn target;
        private final SqlTypeFamily sourceFamily;
        private final SqlTypeFamily targetFamily;
        private int score;
        private String reason;

        private ScoredPair(RegisteredColumn source, RegisteredColumn target,
                           SqlTypeFamily sourceFamily, SqlTypeFamily targetFamily,
                           int score, String reason) {
            this.source = source;
            this.target = target;
            this.sourceFamily = sourceFamily;
            this.targetFamily = targetFamily;
            this.score = score;
            this.reason = reason;
        }

        RegisteredColumn source() {
            return source;
        }

        RegisteredColumn target() {
            return target;
        }

        SqlTypeFamily sourceFamily() {
            return sourceFamily;
        }

        SqlTypeFamily targetFamily() {
            return targetFamily;
        }

        int score() {
            return score;
        }

        JoinKeySuggestion toSuggestion() {
            return new JoinKeySuggestion(
                    source.name(), target.name(), source.dataType(), target.dataType(), reason);
        }
    }
}
