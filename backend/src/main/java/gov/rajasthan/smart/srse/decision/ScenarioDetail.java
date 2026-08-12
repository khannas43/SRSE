package gov.rajasthan.smart.srse.decision;

import gov.rajasthan.smart.srse.compiler.Ast;
import gov.rajasthan.smart.srse.execution.BreakdownRow;

import java.time.Instant;
import java.util.List;

/**
 * Full scenario payload including ruleset and optional breakdown.
 */
public record ScenarioDetail(
        Long id,
        String name,
        List<Long> schemeIds,
        Ast.PredicateSpec ruleset,
        Long totalCount,
        List<BreakdownRow> breakdown,
        Instant createdAt
) {}
