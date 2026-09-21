package gov.rajasthan.smart.srse.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Analysis-tab limits bound from {@code srse.analysis.*}.
 *
 * <p>{@code maxTargetSets} caps how many target tables one multi-match run may
 * include. {@code multiMatchBudgetSeconds} is the wall-clock budget for the
 * whole multi-target stream — not {@code N × queryTimeoutSeconds}; each
 * sub-match still respects {@code srse.guardrails.query-timeout-seconds} via
 * {@code min(per-target timeout, remaining budget)}.
 *
 * <p>{@code maxGroupColumns} caps how many columns one side of a
 * {@link MatchGroup} may fold. {@code maxAnyOfGroupsPerSide} caps
 * {@link GroupMode#ANY_OF} groups on a side: each one becomes its own
 * {@code UNNEST}, and two on the same side cross-multiply that side's rows
 * before the join runs.
 */
@ConfigurationProperties(prefix = "srse.analysis")
public record AnalysisProperties(
        int maxTargetSets,
        int multiMatchBudgetSeconds,
        int maxGroupColumns,
        int maxAnyOfGroupsPerSide,
        int maxProbedPairs) {
}
