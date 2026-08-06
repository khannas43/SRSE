package gov.rajasthan.smart.srse.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Execution guardrails bound from {@code srse.guardrails.*}.
 *
 * CONTRACT:
 *  - {@code cohortCap} hard-caps row-level drill-down; never exceeded regardless
 *    of the requested limit.
 *  - {@code queryTimeoutSeconds} is applied to every analytical query before
 *    execution.
 *
 * Relaxed binding maps {@code cohort-cap} / {@code query-timeout-seconds}
 * from application.yml. Record constructor binding is the Spring Boot 3.3
 * idiomatic form (no setters, no {@code @ConstructorBinding} needed).
 */
@Component
@ConfigurationProperties(prefix = "srse.guardrails")
public record GuardrailProperties(int cohortCap, int queryTimeoutSeconds) {}
