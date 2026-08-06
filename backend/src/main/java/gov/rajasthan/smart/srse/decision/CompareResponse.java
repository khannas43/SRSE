package gov.rajasthan.smart.srse.decision;

import gov.rajasthan.smart.srse.scenario.BreakdownDelta;

import java.util.List;

/**
 * Side-by-side comparison of two evaluated scenarios.
 */
public record CompareResponse(
        ScenarioSummary scenarioA,
        ScenarioSummary scenarioB,
        long totalCountDelta,
        List<BreakdownDelta> breakdownDeltas
) {}
