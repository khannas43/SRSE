package gov.rajasthan.smart.srse.decision;

import gov.rajasthan.smart.srse.execution.BreakdownRow;

import java.util.List;

/**
 * Result of a preview call: computed aggregates only, nothing persisted.
 */
public record PreviewResponse(
        long totalCount,
        List<BreakdownRow> breakdown
) {}
