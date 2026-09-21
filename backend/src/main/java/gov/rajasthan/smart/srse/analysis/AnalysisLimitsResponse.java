package gov.rajasthan.smart.srse.analysis;

/**
 * Read-only Analysis guardrails for the Admin page and officer UI pickers.
 */
public record AnalysisLimitsResponse(
        int maxTargetSets,
        int multiMatchBudgetSeconds,
        int maxGroupColumns,
        int maxAnyOfGroupsPerSide,
        int maxProbedPairs,
        int blockingPrefixLen,
        long maxEstimatedRows) {
}
