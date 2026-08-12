package gov.rajasthan.smart.srse.compiler;

/**
 * Normalized Levenshtein similarity expression, shared by {@link RuleCompiler}
 * (column vs. a bound literal) and the Analysis tab's record-match service
 * (column vs. column, cross-table). Both operands are raw SQL text supplied
 * by the caller — a column reference or a {@code ?} placeholder — never
 * officer input concatenated directly; callers own parameter binding.
 */
public final class FuzzyMatchSql {

    private FuzzyMatchSql() {
    }

    /** Yields a 0..1 similarity fraction; caller compares it against a bound threshold. */
    public static String similarityExpr(String leftSql, String rightSql) {
        return "(1.0 - CAST(levenshtein_distance(lower(" + leftSql + "), lower(" + rightSql + ")) AS DOUBLE) "
                + "/ GREATEST(length(" + leftSql + "), length(" + rightSql + "), 1))";
    }
}
