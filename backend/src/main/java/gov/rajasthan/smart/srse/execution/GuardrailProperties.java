package gov.rajasthan.smart.srse.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Execution guardrails bound from {@code srse.guardrails.*}.
 *
 * CONTRACT:
 *  - {@code cohortCap} hard-caps row-level drill-down; never exceeded regardless
 *    of the requested limit.
 *  - {@code queryTimeoutSeconds} is applied to every analytical query before
 *    execution — including the Analysis tab's record-match, whose result set
 *    is otherwise uncapped (see {@code analysis.RecordMatchService}); this is
 *    its only remaining safety net against a runaway/badly-blocked match.
 *
 * Relaxed binding maps {@code cohort-cap} / {@code query-timeout-seconds}
 * from application.yml. Record constructor binding is the Spring Boot 3.3
 * idiomatic form (no setters, no {@code @ConstructorBinding} needed).
 */
@ConfigurationProperties(prefix = "srse.guardrails")
public record GuardrailProperties(int cohortCap, int queryTimeoutSeconds) {}
