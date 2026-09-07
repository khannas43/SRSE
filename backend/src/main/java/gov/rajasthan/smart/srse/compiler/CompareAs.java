package gov.rajasthan.smart.srse.compiler;

/**
 * How a column should be coerced when it is compared against a column (or a
 * value) of a DIFFERENT type family — the admin override behind
 * {@code TypeCoercion}.
 *
 * <p>Set per column on the Admin page, alongside the business name, fuzzy and
 * visibility flags. It only ever matters for a MIXED comparison: two text
 * columns, or two numeric ones, are compared as they always were regardless of
 * what this says, because Presto already coerces within a family and forcing a
 * cast there would only change results.
 */
public enum CompareAs {

    /**
     * Let SRSE decide (the default, and what an unset row reads as):
     * a number-vs-text pair is compared AS NUMBERS.
     *
     * <p>That direction is deliberate. A value stored as a number on one side
     * has already lost its leading zeros and padding, so {@code '0123'} and
     * {@code 123} are the same account number recorded twice — comparing as
     * text would systematically miss exactly the rows the officer is looking
     * for, while comparing as numbers finds them. Text that is not a number
     * ({@code TRY_CAST} → NULL) simply does not match, which is the same
     * outcome it would have had, without an error.
     */
    AUTO,

    /**
     * Always compare numerically: the text side goes through
     * {@code TRY_CAST(... AS DOUBLE)}. Use for identifiers stored as text in
     * one table and as a number in another.
     */
    NUMBER,

    /**
     * Always compare as text: the non-text side goes through
     * {@code CAST(... AS VARCHAR)}. Use when the text form is the truth — a
     * code with meaningful leading zeros, or a column holding values that are
     * only sometimes numeric, where TRY_CAST would quietly null them out.
     */
    TEXT;

    /** Null (an unset column, or a row written before this existed) reads as {@link #AUTO}. */
    public static CompareAs orAuto(CompareAs value) {
        return value == null ? AUTO : value;
    }

    /**
     * The mode governing one comparison, given each side's own setting.
     *
     * <p>An explicit choice beats {@link #AUTO}, so tagging just the odd
     * column out is enough — the admin does not have to set both sides. If the
     * two sides disagree outright, TEXT wins: it is the only mode that can
     * represent every value of either column, so it can lose matches but never
     * silently nulls one side away.
     */
    public static CompareAs resolve(CompareAs left, CompareAs right) {
        CompareAs l = orAuto(left);
        CompareAs r = orAuto(right);
        if (l == TEXT || r == TEXT) {
            return TEXT;
        }
        if (l == NUMBER || r == NUMBER) {
            return NUMBER;
        }
        return AUTO;
    }
}
