package gov.rajasthan.smart.srse.decision;

/**
 * Officer-facing guardrail limits for the Rules preview sample picker.
 */
public record DecisionLimitsResponse(int previewSampleSize, int cohortCap) {}
