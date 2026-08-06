package gov.rajasthan.smart.srse.execution;

/**
 * One cell of a fixed-dimension breakdown (district × gender × age_band).
 *
 * Dimensions are locked per CLAUDE.md guardrails — not configurable.
 */
public record BreakdownRow(String district, String gender, String ageBand, long count) {}
