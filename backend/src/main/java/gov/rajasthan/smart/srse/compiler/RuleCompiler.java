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
        // Java 17: if/instanceof pattern matching (JEP 394). Type-pattern
        // switch expressions require Java 21 and are not used here.
        if (node instanceof Ast.GroupNode g) {
            return emitGroup(g, params);
        }
        if (node instanceof Ast.PredicateNode p) {
            return emitPredicate(p, params);
        }
        throw new IllegalStateException("Unexpected node: " + node);
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

            case FUZZY_MATCH -> emitFuzzyMatch(col, (List<Object>) p.value(), params);
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

    /**
     * Approximate name match — normalized Levenshtein similarity via
     * PrestoDB's built-in {@code levenshtein_distance}, entirely in
     * parameterised SQL: no join, no string concatenation. {@code value} is
     * {@code [name, thresholdPercent]}; the name is bound twice (it appears
     * twice in the expression) and the threshold once, as a 0..1 fraction.
     */
    private String emitFuzzyMatch(String col, List<Object> value, List<Object> params) {
        if (value == null || value.size() != 2) {
            throw new IllegalArgumentException("FUZZY_MATCH requires [name, thresholdPercent]");
        }
        Object nameObj = value.get(0);
        if (!(nameObj instanceof String name) || name.isBlank()) {
            throw new IllegalArgumentException("FUZZY_MATCH requires a non-blank name");
        }
        double thresholdPct = ((Number) value.get(1)).doubleValue();
        if (thresholdPct < 0 || thresholdPct > 100) {
            throw new IllegalArgumentException("FUZZY_MATCH threshold must be between 0 and 100");
        }
        params.add(name);
        params.add(name);
        params.add(thresholdPct / 100.0);
        return FuzzyMatchSql.similarityExpr(col, "?") + " >= ?";
    }
}
