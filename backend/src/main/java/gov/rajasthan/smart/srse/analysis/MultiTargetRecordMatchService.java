package gov.rajasthan.smart.srse.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Matches one hub table against N target tables as N independent two-table
 * JOINs — never an N-way join. Each sub-match reuses {@link RecordMatchService};
 * the hub is always built as {@code src} in SQL so dedup and age-filter logic
 * stay byte-identical to single-table mode. {@link HubSide} only flips merged
 * output column prefixes ({@code source_*} vs {@code target_*}) for the officer's
 * mental model; it does not change emitted SQL.
 *
 * <p>Partial failure: a target that fails planning or execution emits a
 * {@code progress} error and later targets still run (NDJSON). CSV export is
 * all-or-nothing — one failed target aborts the download.
 *
 * <p>{@code match_score_pct} values are per target and not comparable across
 * targets.
 */
@Service
public class MultiTargetRecordMatchService {

    private final RecordMatchService recordMatchService;
    private final JdbcTemplate jdbc;
    private final GuardrailProperties guardrails;
    private final AnalysisProperties analysisProperties;
    private final ObjectMapper objectMapper;

    public MultiTargetRecordMatchService(RecordMatchService recordMatchService,
                                         @Qualifier("prestoJdbcTemplate") JdbcTemplate jdbc,
                                         GuardrailProperties guardrails,
                                         AnalysisProperties analysisProperties,
                                         ObjectMapper objectMapper) {
        this.recordMatchService = recordMatchService;
        this.jdbc = jdbc;
        this.guardrails = guardrails;
        this.analysisProperties = analysisProperties;
        this.objectMapper = objectMapper;
    }

    public int maxTargetSets() {
        return analysisProperties.maxTargetSets();
    }

    public StreamingResponseBody matchMulti(MultiTargetRecordMatchRequest req) {
        QualifiedTable hubTable = validateBeforeStream(req);
        MergedLayout layout = MergedLayout.build(req);
        return outputStream -> {
            List<String> perTargetSql = new ArrayList<>();
            for (int i = 0; i < req.targets().size(); i++) {
                perTargetSql.add(null);
            }
            writeLine(outputStream, Map.of(
                    "type", "meta",
                    "columns", layout.supersetColumns(),
                    "targetCount", req.targets().size(),
                    "perTargetSql", perTargetSql));

            long streamStartNanos = System.nanoTime();
            long budgetMillis = analysisProperties.multiMatchBudgetSeconds() * 1000L;
            long totalRows = 0;
            List<Map<String, Object>> perTargetSummary = new ArrayList<>();

            for (int i = 0; i < req.targets().size(); i++) {
                TargetMatchSpec target = req.targets().get(i);
                if (remainingMillis(streamStartNanos, budgetMillis) <= 0) {
                    emitSkipped(outputStream, i, target.label(), perTargetSummary);
                    for (int j = i + 1; j < req.targets().size(); j++) {
                        emitSkipped(outputStream, j, req.targets().get(j).label(), perTargetSummary);
                    }
                    break;
                }

                writeLine(outputStream, Map.of(
                        "type", "progress",
                        "targetIndex", i,
                        "label", target.label(),
                        "phase", "started"));

                try {
                    RecordMatchRequest single = toSingleMatch(req, target);
                    RecordMatchService.MatchQuery query = recordMatchService.planMatch(single);
                    perTargetSql.set(i, recordMatchService.renderQueryForDisplay(query));

                    int timeoutSeconds = timeoutForTarget(streamStartNanos, budgetMillis);
                    if (timeoutSeconds <= 0) {
                        emitSkipped(outputStream, i, target.label(), perTargetSummary);
                        continue;
                    }

                    long targetRows = streamTargetRows(outputStream, query, layout, i, timeoutSeconds);
                    totalRows += targetRows;
                    perTargetSummary.add(Map.of(
                            "label", target.label(),
                            "rows", targetRows,
                            "status", "ok"));
                    writeLine(outputStream, Map.of(
                            "type", "progress",
                            "targetIndex", i,
                            "label", target.label(),
                            "phase", "done",
                            "rows", targetRows));
                } catch (Exception e) {
                    String message = e.getMessage() != null ? e.getMessage() : e.toString();
                    perTargetSummary.add(Map.of(
                            "label", target.label(),
                            "rows", 0,
                            "status", "error",
                            "message", message));
                    writeLine(outputStream, Map.of(
                            "type", "progress",
                            "targetIndex", i,
                            "label", target.label(),
                            "phase", "error",
                            "message", message));
                }
            }

            writeLine(outputStream, Map.of(
                    "type", "done",
                    "totalRows", totalRows,
                    "perTarget", perTargetSummary));
        };
    }

    /**
     * Same merged columns as NDJSON, executed sequentially. Unlike
     * {@link #matchMulti}, any planning or execution failure aborts the response
     * so the client rejects the download rather than saving a truncated file.
     */
    public ResponseEntity<StreamingResponseBody> matchMultiCsv(MultiTargetRecordMatchRequest req) {
        validateBeforeStream(req);
        MergedLayout layout = MergedLayout.build(req);
        StreamingResponseBody body = outputStream -> {
            Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            writer.write('\uFEFF');
            writeCsvRow(writer, layout.supersetColumns().stream().map(Object.class::cast).toList());

            long streamStartNanos = System.nanoTime();
            long budgetMillis = analysisProperties.multiMatchBudgetSeconds() * 1000L;

            for (int i = 0; i < req.targets().size(); i++) {
                if (remainingMillis(streamStartNanos, budgetMillis) <= 0) {
                    throw new IllegalStateException("Multi-match time budget exhausted before target " + i);
                }
                TargetMatchSpec target = req.targets().get(i);
                RecordMatchRequest single = toSingleMatch(req, target);
                RecordMatchService.MatchQuery query = recordMatchService.planMatch(single);
                int timeoutSeconds = timeoutForTarget(streamStartNanos, budgetMillis);
                if (timeoutSeconds <= 0) {
                    throw new IllegalStateException("Multi-match time budget exhausted before target " + i);
                }
                jdbc.setQueryTimeout(timeoutSeconds);
                ColumnMapRowMapper rowMapper = new ColumnMapRowMapper();
                TargetRowMapper mapper = layout.mapperForTarget(i);
                jdbc.query(query.sql(), query.params().toArray(), (RowCallbackHandler) rs -> {
                    Map<String, Object> raw = rowMapper.mapRow(rs, 0);
                    try {
                        writeCsvRow(writer, layout.supersetColumns().stream()
                                .map(mapper.mapRow(raw)::get)
                                .toList());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
            writer.flush();
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"analysis-match-multi.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    private QualifiedTable validateBeforeStream(MultiTargetRecordMatchRequest req) {
        QualifiedTable hubTable = recordMatchService.validateHubShape(req.hubCriteria(), req.hubDisplayColumns());
        if (req.targets() == null || req.targets().isEmpty()) {
            throw new IllegalArgumentException("targets must not be empty");
        }
        if (req.targets().size() > analysisProperties.maxTargetSets()) {
            throw new IllegalArgumentException("targets must have 1 to " + analysisProperties.maxTargetSets()
                    + " entries");
        }
        Set<String> labels = new LinkedHashSet<>();
        for (int i = 0; i < req.targets().size(); i++) {
            TargetMatchSpec t = req.targets().get(i);
            String label = t.label() == null ? "" : t.label().trim();
            if (label.isEmpty()) {
                throw new IllegalArgumentException("targets[" + i + "].label must be non-blank");
            }
            if (!labels.add(label)) {
                throw new IllegalArgumentException("target labels must be unique: " + label);
            }
        }
        if (req.dedup() != null && !req.dedup().qualifiedTable().equals(hubTable)) {
            throw new IllegalArgumentException(
                    "dedup must reference the hub table in multi-target mode, not a target table");
        }
        if (req.ageFilter() != null) {
            validateAgeFilter(req.ageFilter());
        }
        return hubTable;
    }

    private static void validateAgeFilter(AgeFilterSpec ageFilter) {
        Set<String> units = Set.of("DAYS", "MONTHS", "YEARS");
        if (!units.contains(ageFilter.unit())) {
            throw new IllegalArgumentException("ageFilter.unit must be one of " + units);
        }
        if (ageFilter.minAge() > ageFilter.maxAge()) {
            throw new IllegalArgumentException("ageFilter minAge must be <= maxAge");
        }
    }

    private long streamTargetRows(java.io.OutputStream outputStream, RecordMatchService.MatchQuery query,
                                  MergedLayout layout, int targetIndex, int timeoutSeconds) throws IOException {
        jdbc.setQueryTimeout(timeoutSeconds);
        ColumnMapRowMapper rowMapper = new ColumnMapRowMapper();
        TargetRowMapper mapper = layout.mapperForTarget(targetIndex);
        long[] count = {0};
        jdbc.query(query.sql(), query.params().toArray(), (RowCallbackHandler) rs -> {
            Map<String, Object> raw = rowMapper.mapRow(rs, 0);
            try {
                writeLine(outputStream, Map.of("type", "row", "data", mapper.mapRow(raw)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            count[0]++;
        });
        return count[0];
    }

    private void emitSkipped(java.io.OutputStream outputStream, int index, String label,
                             List<Map<String, Object>> perTargetSummary) throws IOException {
        perTargetSummary.add(Map.of(
                "label", label,
                "rows", 0,
                "status", "skipped",
                "reason", "time budget"));
        writeLine(outputStream, Map.of(
                "type", "progress",
                "targetIndex", index,
                "label", label,
                "phase", "skipped",
                "reason", "time budget"));
    }

    private static long remainingMillis(long streamStartNanos, long budgetMillis) {
        long elapsed = (System.nanoTime() - streamStartNanos) / 1_000_000;
        return budgetMillis - elapsed;
    }

    private int timeoutForTarget(long streamStartNanos, long budgetMillis) {
        long rem = remainingMillis(streamStartNanos, budgetMillis);
        if (rem <= 0) {
            return 0;
        }
        int cap = guardrails.queryTimeoutSeconds();
        return (int) Math.min(cap, Math.max(1, rem / 1000));
    }

    private static RecordMatchRequest toSingleMatch(MultiTargetRecordMatchRequest req, TargetMatchSpec target) {
        return new RecordMatchRequest(
                req.hubCriteria(),
                target.joinCriteria(),
                req.hubDisplayColumns(),
                target.displayColumns(),
                req.highlightDuplicates(),
                req.dedup(),
                req.ageFilter());
    }

    private void writeLine(java.io.OutputStream out, Map<String, Object> payload) throws IOException {
        out.write(objectMapper.writeValueAsBytes(payload));
        out.write('\n');
        out.flush();
    }

    private static void writeCsvRow(Writer writer, List<Object> values) throws IOException {
        StringJoiner line = new StringJoiner(",");
        for (Object value : values) {
            line.add(csvField(value));
        }
        writer.write(line.toString());
        writer.write("\r\n");
    }

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

    static String sanitizeLabel(String label) {
        String sanitized = label.replaceAll("[^A-Za-z0-9_]", "_");
        return sanitized.isEmpty() ? "set" : sanitized;
    }

    /**
     * Superset column list and per-target row mappers. Hub SQL columns always
     * live under {@code source_*} / {@code target_*} in the raw JDBC row; output
     * names follow {@link HubSide}.
     */
    private record MergedLayout(List<String> supersetColumns, List<TargetRowMapper> targetMappers) {

        static MergedLayout build(MultiTargetRecordMatchRequest req) {
            Set<String> used = new LinkedHashSet<>();
            List<String> columns = new ArrayList<>();
            columns.add("match_set_label");
            columns.add("match_set_table");

            String hubOutPrefix = req.hubSide() == HubSide.SOURCE ? "source_" : "target_";
            List<HubColumnBinding> hubBindings = new ArrayList<>();
            for (MatchCriterion c : req.hubCriteria()) {
                String out = RecordMatchService.allocateUniqueAlias(hubOutPrefix + c.column(), used);
                used.add(out);
                columns.add(out);
                hubBindings.add(new HubColumnBinding(out, "source_" + c.column()));
            }
            for (DisplayColumn d : req.hubDisplayColumns()) {
                String baseOut = hubOutPrefix + d.column();
                if (used.contains(baseOut)) {
                    continue;
                }
                String out = RecordMatchService.allocateUniqueAlias(baseOut, used);
                used.add(out);
                columns.add(out);
                hubBindings.add(new HubColumnBinding(out, "source_" + d.column()));
            }

            List<TargetRowMapper> mappers = new ArrayList<>();
            for (TargetMatchSpec target : req.targets()) {
                String sanitized = sanitizeLabel(target.label());
                String peerOutPrefix = req.hubSide() == HubSide.SOURCE
                        ? sanitized + "_target_"
                        : sanitized + "_source_";
                List<PeerColumnBinding> peerBindings = new ArrayList<>();
                for (MatchCriterion c : target.joinCriteria()) {
                    String out = RecordMatchService.allocateUniqueAlias(peerOutPrefix + c.column(), used);
                    used.add(out);
                    columns.add(out);
                    peerBindings.add(new PeerColumnBinding(out, "target_" + c.column()));
                }
                for (DisplayColumn d : target.displayColumns()) {
                    String baseOut = peerOutPrefix + d.column();
                    if (used.contains(baseOut)) {
                        continue;
                    }
                    String out = RecordMatchService.allocateUniqueAlias(baseOut, used);
                    used.add(out);
                    columns.add(out);
                    peerBindings.add(new PeerColumnBinding(out, "target_" + d.column()));
                }
                String scoreOut = null;
                if (req.highlightDuplicates()) {
                    scoreOut = RecordMatchService.allocateUniqueAlias(sanitized + "_match_score_pct", used);
                    used.add(scoreOut);
                    columns.add(scoreOut);
                }
                mappers.add(new TargetRowMapper(
                        target.label(),
                        target.qualifiedTableName(),
                        columns,
                        hubBindings,
                        peerBindings,
                        scoreOut));
            }
            return new MergedLayout(List.copyOf(columns), mappers);
        }

        TargetRowMapper mapperForTarget(int index) {
            return targetMappers.get(index);
        }
    }

    private record HubColumnBinding(String outputColumn, String sqlColumn) {
    }

    private record PeerColumnBinding(String outputColumn, String sqlColumn) {
    }

    private record TargetRowMapper(
            String label,
            String qualifiedTable,
            List<String> supersetColumns,
            List<HubColumnBinding> hubBindings,
            List<PeerColumnBinding> peerBindings,
            String scoreOutputColumn) {

        Map<String, Object> mapRow(Map<String, Object> sqlRow) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String col : supersetColumns) {
                out.put(col, null);
            }
            out.put("match_set_label", label);
            out.put("match_set_table", qualifiedTable);
            for (HubColumnBinding b : hubBindings) {
                out.put(b.outputColumn(), sqlRow.get(b.sqlColumn()));
            }
            for (PeerColumnBinding b : peerBindings) {
                out.put(b.outputColumn(), sqlRow.get(b.sqlColumn()));
            }
            if (scoreOutputColumn != null) {
                out.put(scoreOutputColumn, sqlRow.get("match_score_pct"));
            }
            return out;
        }
    }
}
