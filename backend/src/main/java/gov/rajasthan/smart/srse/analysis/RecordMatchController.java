package gov.rajasthan.smart.srse.analysis;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analysis")
public class RecordMatchController {

    private final RecordMatchService matchService;

    public RecordMatchController(RecordMatchService matchService) {
        this.matchService = matchService;
    }

    @PostMapping("/match")
    public RecordMatchResponse match(@RequestBody RecordMatchRequest req) {
        return matchService.match(req);
    }
}
