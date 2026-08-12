package gov.rajasthan.smart.srse.decision;

import gov.rajasthan.smart.srse.compiler.Ast;

import java.util.List;

/**
 * Explicit "save this parameter combination" request — persists the ruleset
 * tagged to one or more schemes and re-evaluates once to snapshot results.
 */
public record SaveScenarioRequest(
        String name,
        List<Long> schemeIds,
        Ast.PredicateSpec ruleset,
        boolean includeBreakdown
) {}
