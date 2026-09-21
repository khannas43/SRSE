package gov.rajasthan.smart.srse.analysis;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analysis")
public class RecordMatchController {

    private final RecordMatchService matchService;
    private final MultiTargetRecordMatchService multiMatchService;
    private final JoinKeySuggestService joinKeySuggestService;

    public RecordMatchController(RecordMatchService matchService,
                                 MultiTargetRecordMatchService multiMatchService,
                                 JoinKeySuggestService joinKeySuggestService) {
        this.matchService = matchService;
        this.multiMatchService = multiMatchService;
        this.joinKeySuggestService = joinKeySuggestService;
    }

    @GetMapping("/limits")
    public Map<String, Integer> limits() {
        return Map.of("maxTargetSets", multiMatchService.maxTargetSets());
    }

    /**
     * Streams the match result as newline-delimited JSON (one {@code meta}
     * line, then one {@code row} line per match, then a {@code done} or
     * {@code error} line) instead of one buffered JSON object — see
     * {@link RecordMatchService#match} for why.
     */
    @PostMapping(value = "/match", produces = "application/x-ndjson")
    public StreamingResponseBody match(@RequestBody RecordMatchRequest req) {
        return matchService.match(req);
    }

    /**
     * The same match as a CSV download — the complete result, not the slice
     * the browser is willing to hold.
     *
     * <p>The grid stops rendering past its own row limit, so above it this is
     * the ONLY way to get the rows; it is deliberately not built from what the
     * screen is showing. Streamed straight from Presto, so a result far larger
     * than the tab could survive still downloads.
     */
    @PostMapping(value = "/match.csv", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> matchCsv(@RequestBody RecordMatchRequest req) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"analysis-match.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(matchService.matchCsv(req));
    }

    /**
     * Plans the match and returns display SQL only — same query {@link #match}
     * would execute, without running it against Presto.
     */
    @PostMapping(value = "/match.sql", produces = MediaType.TEXT_PLAIN_VALUE)
    public String matchSql(@RequestBody RecordMatchRequest req) {
        return matchService.renderQueryForDisplay(matchService.planMatch(req));
    }

    /**
     * Join-key hints for two registered tables — metadata-only by default;
     * optional bounded overlap probe when {@code probe: true}.
     */
    @PostMapping("/suggest-keys")
    public List<JoinKeySuggestion> suggestKeys(@RequestBody SuggestJoinKeysRequest req) {
        return joinKeySuggestService.suggest(req);
    }

    /**
     * One hub table against N targets — N independent two-table JOINs merged
     * into one NDJSON stream. Partial per-target failure is reported in-band;
     * see {@link MultiTargetRecordMatchService}.
     */
    @PostMapping(value = "/match-multi", produces = "application/x-ndjson")
    public StreamingResponseBody matchMulti(@RequestBody MultiTargetRecordMatchRequest req) {
        return multiMatchService.matchMulti(req);
    }

    /**
     * Combined multi-target CSV — all-or-nothing (unlike the NDJSON stream).
     */
    @PostMapping(value = "/match-multi.csv", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> matchMultiCsv(@RequestBody MultiTargetRecordMatchRequest req) {
        return multiMatchService.matchMultiCsv(req);
    }
}
