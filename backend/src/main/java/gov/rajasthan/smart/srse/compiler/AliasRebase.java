package gov.rajasthan.smart.srse.compiler;

/**
 * Rewrites a field-catalogue physical expression so its column references
 * resolve against a JOIN alias instead of the catalogue's own table.
 *
 * <p><b>Why this exists.</b> The Analysis tab's age filter applies the
 * catalogue's {@code age_years} field to BOTH sides of an ad-hoc join
 * ({@code src} / {@code tgt}), so the expression's own table qualification has
 * to be swapped for the alias. The previous approach — take everything after
 * the last {@code '.'} — happened to work for a plain column ref
 * ({@code beneficiary.age_years} → {@code age_years}) but silently produced
 * garbage for a Tier-2 expression: the DOB form
 * {@code date_diff('year', beneficiary.date_of_birth, current_date)} has its
 * last dot inside the expression, yielding
 * {@code date_of_birth, current_date)} and emitting SQL that does not parse.
 * Fully-qualified {@code catalog.schema.table.column} mappings made the plain
 * case longer but no more fragile; the expression case was broken either way.
 *
 * <p><b>What it does.</b> Walks the expression and replaces every DOTTED
 * identifier chain with {@code alias.<lastSegment>}, leaving bare identifiers
 * (function names like {@code date_diff}, keywords like {@code current_date})
 * and everything inside single-quoted literals untouched. Both the plain and
 * the expression case then rebase correctly:
 *
 * <pre>
 *   iceberg_gold.golden.tbl_ben.age_years
 *       → src.age_years
 *   date_diff('year', beneficiary.date_of_birth, current_date)
 *       → date_diff('year', src.date_of_birth, current_date)
 * </pre>
 *
 * <p><b>Limits, deliberately.</b> This is a targeted rewriter for the
 * same-table Tier-1/Tier-2 expressions the flat-catalogue contract allows (see
 * CLAUDE.md) — not a SQL parser. A schema-qualified FUNCTION call
 * ({@code myschema.my_udf(x)}) would be rewritten as if it were a column; no
 * catalogue expression takes that shape, and a general parser is not worth
 * carrying for a case the contract already forbids. Numeric literals are safe
 * (identifiers cannot start with a digit) and quoted literals are skipped
 * outright.
 */
public final class AliasRebase {

    private AliasRebase() {
    }

    public static String ontoAlias(String expression, String alias) {
        StringBuilder out = new StringBuilder(expression.length() + 16);
        int i = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (c == '\'') {
                i = copyQuotedLiteral(expression, i, out);
            } else if (isIdentifierStart(c)) {
                i = rewriteIdentifierChain(expression, i, alias, out);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Copies a single-quoted literal verbatim, including a doubled {@code ''}
     * escape, so a dot inside a literal is never mistaken for qualification.
     *
     * @return the index just past the literal.
     */
    private static int copyQuotedLiteral(String s, int start, StringBuilder out) {
        out.append('\'');
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            out.append(c);
            i++;
            if (c == '\'') {
                if (i < s.length() && s.charAt(i) == '\'') {
                    out.append('\'');   // escaped quote — literal continues
                    i++;
                } else {
                    return i;           // closing quote
                }
            }
        }
        return i;   // unterminated literal: copied as-is, let the DB reject it
    }

    /**
     * Reads one {@code a[.b[.c]]} chain. A chain with at least one dot is a
     * qualified column reference and is rebased onto {@code alias}; a bare
     * identifier is emitted unchanged.
     *
     * @return the index just past the chain.
     */
    private static int rewriteIdentifierChain(String s, int start, String alias, StringBuilder out) {
        int i = start;
        int lastSegmentStart = start;
        boolean qualified = false;
        while (i < s.length()) {
            if (isIdentifierPart(s.charAt(i))) {
                i++;
            } else if (s.charAt(i) == '.' && i + 1 < s.length() && isIdentifierStart(s.charAt(i + 1))) {
                qualified = true;
                i++;
                lastSegmentStart = i;
            } else {
                break;
            }
        }
        if (qualified) {
            out.append(alias).append('.').append(s, lastSegmentStart, i);
        } else {
            out.append(s, start, i);
        }
        return i;
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
