package gov.rajasthan.smart.srse.decision;

import gov.rajasthan.smart.srse.execution.BreakdownRow;

import java.util.List;

/**
 * Result of an evaluate call: persisted scenario id plus computed aggregates.
 */
public record EvaluateResponse(
        Long scenarioId,
        long totalCount,
        List<BreakdownRow> breakdown
) {}
