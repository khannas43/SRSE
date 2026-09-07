package gov.rajasthan.smart.srse.decision;

import java.util.List;
import java.util.Map;

/**
 * The drill-down sample, with the cap that produced it stated alongside.
 *
 * <p>{@code appliedLimit} and {@code capped} travel with the rows because a
 * caller cannot otherwise tell a cohort of exactly 1000 people from a cohort of
 * 40 lakh truncated to its first 1000 — and presenting the second as if it were
 * the first is precisely the misreading this endpoint's cap exists to prevent.
 * Pair it with {@code /preview}'s total count for the real size.
 */
public record CohortResponse(
        List<Map<String, Object>> rows,
        int appliedLimit,
        boolean capped
) {}
