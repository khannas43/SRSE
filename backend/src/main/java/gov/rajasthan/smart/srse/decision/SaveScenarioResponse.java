package gov.rajasthan.smart.srse.decision;

import gov.rajasthan.smart.srse.execution.BreakdownRow;

import java.util.List;

/**
 * Result of a save-scenario call: persisted scenario id plus computed aggregates.
 */
public record SaveScenarioResponse(
        Long scenarioId,
        long totalCount,
        List<BreakdownRow> breakdown
) {}
