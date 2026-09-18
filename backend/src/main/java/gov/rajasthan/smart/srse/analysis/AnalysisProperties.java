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
 */
@ConfigurationProperties(prefix = "srse.analysis")
public record AnalysisProperties(int maxTargetSets, int multiMatchBudgetSeconds) {
}
