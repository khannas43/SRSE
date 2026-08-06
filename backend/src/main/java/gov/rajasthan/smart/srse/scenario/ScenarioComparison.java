package gov.rajasthan.smart.srse.scenario;

import java.util.List;

/**
 * Side-by-side comparison of two evaluated scenarios.
 * {@code totalCountDelta} = scenarioB.totalCount − scenarioA.totalCount.
 */
public record ScenarioComparison(
        Scenario scenarioA,
        Scenario scenarioB,
        long totalCountDelta,
        List<BreakdownDelta> breakdownDeltas
) {}
