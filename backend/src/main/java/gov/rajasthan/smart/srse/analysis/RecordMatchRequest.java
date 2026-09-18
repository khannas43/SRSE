package gov.rajasthan.smart.srse.analysis;

import java.util.List;

/**
 * Cross-table fuzzy/exact record-match request from the Analysis tab.
 *
 * {@code sourceCriteria}/{@code targetCriteria} are 1-N (table, column)
 * picks each ("Add more" in the UI), same size on both sides, AND-combined in
 * the JOIN ON clause; every criterion on a given side must share that side's
 * table (see {@link MatchCriterion}). Each source criterion carries its own
 * {@code fuzzyThresholdPercent}, applied when either column in that pair's
 * name contains "name" (case-insensitive) — other pairs compare exactly.
 *
 * <p>{@code sourceDisplayColumns} / {@code targetDisplayColumns} are optional
 * projections from the same side's table only — included in SELECT, not in ON,
 * match score, dedup partition, or age filter. Null or empty lists preserve
 * legacy behaviour (every compared column is also projected).
 *
 * <p>{@code dedup} and {@code ageFilter} are optional.
 */
public record RecordMatchRequest(
        List<MatchCriterion> sourceCriteria,
        List<MatchCriterion> targetCriteria,
        List<DisplayColumn> sourceDisplayColumns,
        List<DisplayColumn> targetDisplayColumns,
        boolean highlightDuplicates,
        DedupSpec dedup,
        AgeFilterSpec ageFilter) {

    public RecordMatchRequest {
        sourceDisplayColumns = sourceDisplayColumns == null ? List.of() : sourceDisplayColumns;
        targetDisplayColumns = targetDisplayColumns == null ? List.of() : targetDisplayColumns;
    }
}
