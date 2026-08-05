package gov.rajasthan.smart.srse.compiler;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Rule-to-SQL compiler — the hard core of SRSE.
 *
 * Walks a {@link Ast.PredicateSpec} and emits a single parameterised Presto
 * WHERE-clause fragment plus an ordered parameter list ({@link CompiledQuery}).
 *
 * CONTRACT (do not violate):
 *  - Never emits a JOIN or on-the-fly cross-table calculation. Tier-3 logic is
 *    pre-materialised upstream and appears as a flat column via FieldResolver.
 *  - Values are ALWAYS bound parameters ('?'), never string-concatenated.
 *  - Field keys resolve to columns ONLY through FieldResolver (allow-list).
 *
 * This is a skeleton: the scalar operators are implemented to prove the shape;
 * BETWEEN / IN edge cases and value-type validation are the first build task
 * (design doc §6.1). Unit tests live in src/test (RuleCompilerTest).
 */
@Component
public class RuleCompiler {

    private final FieldResolver fields;

    public RuleCompiler(FieldResolver fields) {
        this.fields = fields;
    }

    public CompiledQuery compile(Ast.PredicateSpec spec) {
        List<Object> params = new ArrayList<>();
        String sql = emit(spec.root(), params);
        return new CompiledQuery(sql, params);
    }

    private String emit(Ast.Node node, List<Object> params) {
        // Java 17 pattern-matching switch over the sealed Node hierarchy.
        return switch (node) {
            case Ast.GroupNode g -> emitGroup(g, params);
            case Ast.PredicateNode p -> emitPredicate(p, params);
        };
    }

    private String emitGroup(Ast.GroupNode g, List<Object> params) {
        String joiner = g.op() == Ast.BoolOp.AND ? " AND " : " OR ";
        StringJoiner sj = new StringJoiner(joiner, "(", ")");
        for (Ast.Node child : g.children()) {
            sj.add(emit(child, params));
        }
        return sj.toString();
    }

    @SuppressWarnings("unchecked")
    private String emitPredicate(Ast.PredicateNode p, List<Object> params) {
        String col = fields.resolveColumn(p.fieldKey());   // allow-list resolution
        return switch (p.operator()) {
            case EQ  -> bind(col + " = ?",  p.value(), params);
            case NE  -> bind(col + " <> ?", p.value(), params);
            case LT  -> bind(col + " < ?",  p.value(), params);
            case LTE -> bind(col + " <= ?", p.value(), params);
            case GT  -> bind(col + " > ?",  p.value(), params);
            case GTE -> bind(col + " >= ?", p.value(), params);

            case IS_TRUE  -> col + " = TRUE";
            case IS_FALSE -> col + " = FALSE";
            case IS_NULL  -> col + " IS NULL";
            case NOT_NULL -> col + " IS NOT NULL";

            case IN     -> emitIn(col, (List<Object>) p.value(), params, false);
            case NOT_IN -> emitIn(col, (List<Object>) p.value(), params, true);

            case BETWEEN -> emitBetween(col, (List<Object>) p.value(), params);
        };
    }

    private String bind(String frag, Object value, List<Object> params) {
        params.add(value);
        return frag;
    }

    private String emitIn(String col, List<Object> values, List<Object> params, boolean negate) {
        if (values == null || values.isEmpty()) {
            // Empty IN: emit a constant-false (or true for NOT_IN) to stay safe.
            return negate ? "TRUE" : "FALSE";
        }
        StringJoiner ph = new StringJoiner(", ", "(", ")");
        for (Object v : values) {
            ph.add("?");
            params.add(v);
        }
        return col + (negate ? " NOT IN " : " IN ") + ph;
    }

    private String emitBetween(String col, List<Object> bounds, List<Object> params) {
        if (bounds == null || bounds.size() != 2) {
            throw new IllegalArgumentException("BETWEEN requires exactly two bounds");
        }
        params.add(bounds.get(0));
        params.add(bounds.get(1));
        return col + " BETWEEN ? AND ?";
    }
}
