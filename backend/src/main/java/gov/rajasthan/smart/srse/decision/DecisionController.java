package gov.rajasthan.smart.srse.decision;

import gov.rajasthan.smart.srse.execution.BreakdownRow;
import gov.rajasthan.smart.srse.execution.ExecutionService;
import gov.rajasthan.smart.srse.scenario.Scenario;
import gov.rajasthan.smart.srse.scenario.ScenarioComparison;
import gov.rajasthan.smart.srse.scenario.ScenarioService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST decision-service seam (CLAUDE.md locked decision #6) — DMN-shaped rules
 * behind this REST boundary so a future CP4BA/ODM swap only replaces what is
 * BEHIND this controller, never the caller contract.
 */
@RestController
@RequestMapping("/api/decision")
public class DecisionController {

    private final ExecutionService executionService;
    private final ScenarioService scenarioService;

    public DecisionController(ExecutionService executionService,
                              ScenarioService scenarioService) {
        this.executionService = executionService;
        this.scenarioService = scenarioService;
    }

    @PostMapping("/evaluate")
    public EvaluateResponse evaluate(@RequestBody EvaluateRequest req) {
        Scenario scenario = scenarioService.createScenario(
                req.name(), req.schemeId(), req.ruleset());
        long totalCount = executionService.count(req.ruleset());
        List<BreakdownRow> breakdown = req.includeBreakdown()
                ? executionService.breakdown(req.ruleset())
                : List.of();
        scenarioService.recordResults(scenario.getId(), totalCount, breakdown);
        return new EvaluateResponse(scenario.getId(), totalCount, breakdown);
    }

    @GetMapping("/scenarios")
    public List<ScenarioSummary> listScenarios(@RequestParam String schemeId) {
        return scenarioService.listByScheme(schemeId).stream()
                .map(ScenarioSummary::from)
                .toList();
    }

    @GetMapping("/scenarios/{id}")
    public ScenarioDetail getScenario(@PathVariable Long id) {
        Scenario scenario = scenarioService.getScenario(id);
        return new ScenarioDetail(
                scenario.getId(),
                scenario.getName(),
                scenario.getSchemeId(),
                scenarioService.loadRuleset(id),
                scenario.getTotalCount(),
                scenarioService.loadBreakdown(id),
                scenario.getCreatedAt());
    }

    @GetMapping("/compare")
    public CompareResponse compare(@RequestParam Long a, @RequestParam Long b) {
        ScenarioComparison comparison = scenarioService.compare(a, b);
        return new CompareResponse(
                ScenarioSummary.from(comparison.scenarioA()),
                ScenarioSummary.from(comparison.scenarioB()),
                comparison.totalCountDelta(),
                comparison.breakdownDeltas());
    }
}
