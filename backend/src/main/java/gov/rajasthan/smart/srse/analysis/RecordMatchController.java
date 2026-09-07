package gov.rajasthan.smart.srse.analysis;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/analysis")
public class RecordMatchController {

    private final RecordMatchService matchService;

    public RecordMatchController(RecordMatchService matchService) {
        this.matchService = matchService;
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
}
