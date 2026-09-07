package gov.rajasthan.smart.srse.decision;

import gov.rajasthan.smart.srse.compiler.Ast;

/**
 * A hard-capped row-level drill-down into the cohort a ruleset selects.
 *
 * <p>{@code limit} is a REQUEST, never a grant: {@code ExecutionService}
 * applies {@code min(limit, SRSE_COHORT_CAP)}, so asking for a million rows
 * returns the cap. Omitting it (or sending a non-positive value) means "as many
 * as the cap allows" rather than "no limit" — there is no way to express the
 * latter through this API, deliberately.
 */
public record CohortRequest(
        Ast.PredicateSpec ruleset,
        Integer limit
) {}
