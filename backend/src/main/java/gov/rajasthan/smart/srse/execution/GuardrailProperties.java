package gov.rajasthan.smart.srse.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Execution guardrails bound from {@code srse.guardrails.*}.
 *
 * CONTRACT:
 *  - {@code cohortCap} hard-caps row-level drill-down; never exceeded regardless
 *    of the requested limit.
 *  - {@code queryTimeoutSeconds} is applied to every analytical query before
 *    execution.
 *  - {@code analysisRowCap} hard-caps the Analysis tab's record-match result
 *    grid, same contract as {@code cohortCap} but for that tab's own
 *    execution path (see {@code analysis.RecordMatchService}).
 *
 * Relaxed binding maps {@code cohort-cap} / {@code query-timeout-seconds} /
 * {@code analysis-row-cap} from application.yml. Record constructor binding
 * is the Spring Boot 3.3 idiomatic form (no setters, no
 * {@code @ConstructorBinding} needed).
 */
@ConfigurationProperties(prefix = "srse.guardrails")
public record GuardrailProperties(int cohortCap, int queryTimeoutSeconds, int analysisRowCap) {}
