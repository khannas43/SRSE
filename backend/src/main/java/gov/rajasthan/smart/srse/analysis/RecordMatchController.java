package gov.rajasthan.smart.srse.analysis;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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
}
