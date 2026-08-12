package gov.rajasthan.smart.srse.decision;

import gov.rajasthan.smart.srse.compiler.Ast;

/**
 * Live preview request — compiles + counts + (optionally) breaks down a
 * ruleset with NO persistence, so the rule builder can re-run on every
 * parameter tweak without accumulating scenario rows.
 */
public record PreviewRequest(
        Ast.PredicateSpec ruleset,
        boolean includeBreakdown
) {}
