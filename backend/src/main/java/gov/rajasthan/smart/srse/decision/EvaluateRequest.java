package gov.rajasthan.smart.srse.decision;

import gov.rajasthan.smart.srse.compiler.Ast;

/**
 * Full-ruleset evaluate request — caller sends the complete PredicateSpec on every call
 * (no base-ruleset + parameter-override model).
 */
public record EvaluateRequest(
        String schemeId,
        String name,
        Ast.PredicateSpec ruleset,
        boolean includeBreakdown
) {}
