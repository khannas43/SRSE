package gov.rajasthan.smart.srse.analysis;

import java.util.List;

/**
 * One comparison in the JOIN's ON clause, over 1..N columns per side.
 *
 * <p>This is what lifts the old "both sides must have the same number of
 * column picks" rule: a group may hold one {@code full_name} on the source
 * side and {@code first_name} + {@code last_name} on the target, or the
 * reverse. Every criterion on a given side must still share that side's one
 * table — a group widens a comparison, it never adds a table.
 *
 * <p>{@code fuzzyThresholdPercent} belongs to the GROUP, not to a column: a
 * combined name is one value and gets one threshold. It is required when the
 * group is fuzzy-matchable and ignored otherwise.
 *
 * <p>{@code separator} joins the columns of a multi-column
 * {@link GroupMode#COMBINE} side and defaults to a single space. Column ORDER
 * is the officer's, and it matters: Levenshtein is order-sensitive, so
 * {@code first_name + last_name} and {@code last_name + first_name} score
 * differently against the same full name.
 *
 * <p>A group with exactly one column on each side emits byte-identical SQL to
 * the criterion pair it replaces, whichever mode it carries.
 */
public record MatchGroup(
        List<MatchCriterion> source,
        List<MatchCriterion> target,
        GroupMode mode,
        Double fuzzyThresholdPercent,
        String separator) {

    /** The separator used when a COMBINE side has more than one column and none was given. */
    public static final String DEFAULT_SEPARATOR = " ";

    public MatchGroup {
        source = source == null ? List.of() : List.copyOf(source);
        target = target == null ? List.of() : List.copyOf(target);
        mode = mode == null ? GroupMode.COMBINE : mode;
        separator = separator == null || separator.isEmpty() ? DEFAULT_SEPARATOR : separator;
    }

    /** A single-column group — the shape every legacy criterion pair normalises to. */
    public static MatchGroup of(MatchCriterion sourceColumn, MatchCriterion targetColumn) {
        return new MatchGroup(
                List.of(sourceColumn),
                List.of(targetColumn),
                GroupMode.COMBINE,
                sourceColumn.fuzzyThresholdPercent(),
                null);
    }

    /** True when neither side needs folding — the pre-groups shape. */
    public boolean isSingleColumnPair() {
        return source.size() == 1 && target.size() == 1;
    }
}
