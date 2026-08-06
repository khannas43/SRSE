package gov.rajasthan.smart.srse.scenario;

/**
 * Per-cell comparison of two scenario breakdowns.
 * {@code delta} = {@code countB} − {@code countA}.
 */
public record BreakdownDelta(
        String district,
        String gender,
        String ageBand,
        long countA,
        long countB,
        long delta
) {}
