package gov.rajasthan.smart.srse.analysis;

import java.util.List;

/**
 * Post-join column comparison — projected in SELECT, never in the JOIN ON clause.
 *
 * <p>Kept separate from {@link MatchGroup} so comparison pairs cannot be routed
 * into the join emitter by mistake (the same failure shape as fuzzy-in-WHERE).
 * Value folding reuses {@link gov.rajasthan.smart.srse.compiler.ColumnGroupSql}
 * via {@link GroupMode#COMBINE} only — ANY_OF is not supported for comparisons.
 */
public record ComparisonGroup(
        List<MatchCriterion> source,
        List<MatchCriterion> target,
        GroupMode mode,
        Double fuzzyThresholdPercent,
        String separator) {

    public ComparisonGroup {
        source = source == null ? List.of() : List.copyOf(source);
        target = target == null ? List.of() : List.copyOf(target);
        mode = mode == null ? GroupMode.COMBINE : mode;
        separator = separator == null || separator.isEmpty() ? MatchGroup.DEFAULT_SEPARATOR : separator;
    }

    public static ComparisonGroup of(MatchCriterion sourceColumn, MatchCriterion targetColumn) {
        return new ComparisonGroup(
                List.of(sourceColumn),
                List.of(targetColumn),
                GroupMode.COMBINE,
                null,
                null);
    }
}
