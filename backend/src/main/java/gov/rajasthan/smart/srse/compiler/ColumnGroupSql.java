package gov.rajasthan.smart.srse.compiler;

import java.util.List;

/**
 * SQL for comparing a GROUP of columns as one value — the Analysis tab's
 * {@code COMBINE} and {@code ANY_OF} modes.
 *
 * <p>Like {@link FuzzyMatchSql}, every operand here is raw SQL text the caller
 * supplies (a column reference it already resolved through the registry's
 * gates), never officer input; callers own parameter binding.
 */
public final class ColumnGroupSql {

    private ColumnGroupSql() {
    }

    /**
     * Folds several columns into one text value.
     *
     * <p>{@code array_join(filter(...))} rather than {@code concat_ws}, for two
     * reasons, both measured against PrestoDB 0.297:
     * <ul>
     *   <li>{@code concat_ws} is <b>not a registered function</b> there —
     *       {@code Function concat_ws not registered} — so it is not an option
     *       at all, whatever its null semantics elsewhere.</li>
     *   <li>A NULL or empty middle name must not leave a doubled separator.
     *       Against Levenshtein that stray separator is a free edit charged to
     *       every row with a missing middle name, systematically depressing the
     *       similarity of exactly those records. Verified: folding
     *       {@code ['Ram', NULL, 'Sharma']} with the filter gives edit distance
     *       0 against {@code 'Ram Sharma'}; without it, 1.</li>
     * </ul>
     * Collapsing whitespace afterwards would fix only the space case; filtering
     * first is correct for any separator.
     *
     * <p>Every member is cast to VARCHAR: a combined value is textual by
     * construction, and ARRAY requires one element type.
     */
    public static String combine(List<String> columnSql, String separator) {
        if (columnSql.size() == 1) {
            return columnSql.get(0);
        }
        String members = String.join(", ", columnSql.stream()
                .map(sql -> "CAST(" + sql + " AS VARCHAR)")
                .toList());
        return "array_join(filter(ARRAY[" + members + "], x -> x IS NOT NULL AND x <> ''), '"
                + escapeLiteral(separator) + "')";
    }

    /**
     * The {@code UNNEST} that turns an ANY_OF side's N columns into N candidate
     * rows, each carrying the value and the name of the column it came from.
     *
     * <p>This exists so ANY_OF never becomes {@code a = x OR a = y} in the ON
     * clause. Presto cannot hash-join a disjunctive ON and falls back to a
     * nested loop over the cross product — the exact failure mode
     * {@code RecordMatchService} records having already removed once, and one
     * that only shows itself at crore scale.
     *
     * <p>The parallel name array is what makes the duplicate rows informative:
     * when two columns hold the same value the pair appears twice, and
     * {@code matchedAlias} says which column each match came from.
     *
     * @param sourceAlias alias of the underlying table inside the subquery
     * @param columns     column names on that side, already registry-validated
     * @param castToText  true when the columns disagree on type family, so the
     *                    array needs one common element type
     */
    public static String unnestClause(String sourceAlias, List<String> columns, boolean castToText,
                                      String unnestAlias, String keyAlias, String matchedAlias) {
        return unnestClause(sourceAlias, columns, castToText, unnestAlias, keyAlias, matchedAlias, false);
    }

    /**
     * @param preserveSideRows when true, use {@code LEFT JOIN UNNEST ... ON TRUE}
     *                         so rows with all-null ANY_OF candidates survive an
     *                         outer join on the preserved side
     */
    public static String unnestClause(String sourceAlias, List<String> columns, boolean castToText,
                                      String unnestAlias, String keyAlias, String matchedAlias,
                                      boolean preserveSideRows) {
        String values = String.join(", ", columns.stream()
                .map(c -> {
                    String ref = sourceAlias + "." + c;
                    return castToText ? "CAST(" + ref + " AS VARCHAR)" : ref;
                })
                .toList());
        String names = String.join(", ", columns.stream()
                .map(c -> "'" + escapeLiteral(c) + "'")
                .toList());
        String unnest = "UNNEST(ARRAY[" + values + "], ARRAY[" + names + "]) AS "
                + unnestAlias + " (" + keyAlias + ", " + matchedAlias + ")";
        if (preserveSideRows) {
            return "LEFT JOIN " + unnest + " ON TRUE";
        }
        return "CROSS JOIN " + unnest;
    }

    /**
     * Doubles single quotes. Column names cannot contain one — {@code
     * LakehouseIdentifiers} rejects anything that is not a bare Presto
     * identifier long before this — but a separator is officer-supplied text
     * and this is the only place it reaches SQL.
     */
    private static String escapeLiteral(String value) {
        return value.replace("'", "''");
    }
}
