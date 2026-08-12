package gov.rajasthan.smart.srse.analysis;

import java.util.List;

/**
 * Cross-table fuzzy/exact record-match request from the Analysis tab.
 *
 * {@code sourceCriteria}/{@code targetCriteria} are 1-N (table, column)
 * picks each ("Add more" in the UI), same size on both sides, AND-combined;
 * every criterion on a given side must share that side's table (see
 * {@link MatchCriterion}). Each source criterion carries its own
 * {@code fuzzyThresholdPercent}, applied when either column in that pair's
 * name contains "name" (case-insensitive) — other pairs compare exactly.
 * {@code dedup} and {@code ageFilter} are optional.
 */
public record RecordMatchRequest(
        List<MatchCriterion> sourceCriteria,
        List<MatchCriterion> targetCriteria,
        boolean highlightDuplicates,
        DedupSpec dedup,
        AgeFilterSpec ageFilter) {
}
