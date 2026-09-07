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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * Live preview — compile + count + (optionally) break down a ruleset with
     * NO persistence, so the rule builder can re-run on every parameter tweak.
     */
    @PostMapping("/preview")
    public PreviewResponse preview(@RequestBody PreviewRequest req) {
        long totalCount = executionService.count(req.ruleset());
        List<BreakdownRow> breakdown = req.includeBreakdown()
                ? executionService.breakdown(req.ruleset())
                : List.of();
        return new PreviewResponse(totalCount, breakdown);
    }

    /**
     * Hard-capped row-level drill-down into the cohort a ruleset selects.
     *
     * <p>THE ONLY ENDPOINT IN SRSE THAT RETURNS ROW-LEVEL BENEFICIARY DATA.
     * Everything else on this controller answers in aggregates. It exists so
     * an officer can sanity-check WHO a ruleset is selecting — a count alone
     * cannot show that a threshold is catching the wrong people — and it is
     * bounded by {@code SRSE_COHORT_CAP} (default 1000) no matter what the
     * caller asks for. The cap is not negotiable through the API: a caller
     * requesting more gets the cap, and the response says so.
     *
     * <p>The guardrail was written and unit-tested but had no route, so the
     * capability CLAUDE.md documents was unreachable. Reachable now, with the
     * cap enforced in {@link ExecutionService} where it always was.
     */
    @PostMapping("/cohort")
    public CohortResponse cohort(@RequestBody CohortRequest req) {
        int appliedLimit = executionService.effectiveCohortLimit(req.limit());
        List<Map<String, Object>> rows = executionService.cohortSample(req.ruleset(), appliedLimit);
        // "capped" means the sample filled its limit, so the cohort is probably
        // larger than what came back — the distinction between a cohort of
        // exactly 1000 and 40 lakh truncated to 1000. /preview has the total.
        return new CohortResponse(rows, appliedLimit, rows.size() >= appliedLimit);
    }

    /**
     * Explicit save — persists the ruleset tagged to one or more schemes and
     * evaluates once to snapshot results.
     */
    @PostMapping("/scenarios")
    public SaveScenarioResponse saveScenario(@RequestBody SaveScenarioRequest req) {
        Set<Long> schemeIds = new LinkedHashSet<>(req.schemeIds());
        Scenario scenario = scenarioService.createScenario(req.name(), schemeIds, req.ruleset());
        long totalCount = executionService.count(req.ruleset());
        List<BreakdownRow> breakdown = req.includeBreakdown()
                ? executionService.breakdown(req.ruleset())
                : List.of();
        scenarioService.recordResults(scenario.getId(), totalCount, breakdown);
        return new SaveScenarioResponse(scenario.getId(), totalCount, breakdown);
    }

    @GetMapping("/scenarios")
    public List<ScenarioSummary> listScenarios(@RequestParam Long schemeId) {
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
                List.copyOf(scenario.getSchemeIds()),
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
