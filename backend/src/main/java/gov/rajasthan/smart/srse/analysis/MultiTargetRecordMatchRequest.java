package gov.rajasthan.smart.srse.analysis;

import java.util.List;

/**
 * One hub table matched against N target tables in a single streamed result.
 *
 * <p>Each target is executed as an independent two-table JOIN (hub always
 * {@code src}, target always {@code tgt}) — never an N-way join. See
 * {@link MultiTargetRecordMatchService}.
 */
public record MultiTargetRecordMatchRequest(
        List<MatchCriterion> hubCriteria,
        List<DisplayColumn> hubDisplayColumns,
        HubSide hubSide,
        List<TargetMatchSpec> targets,
        boolean highlightDuplicates,
        DedupSpec dedup,
        AgeFilterSpec ageFilter,
        boolean mismatchOnly) {

    public MultiTargetRecordMatchRequest {
        hubDisplayColumns = hubDisplayColumns == null ? List.of() : hubDisplayColumns;
        hubSide = hubSide == null ? HubSide.SOURCE : hubSide;
    }

    /** Legacy requests without {@code mismatchOnly}. */
    public MultiTargetRecordMatchRequest(
            List<MatchCriterion> hubCriteria,
            List<DisplayColumn> hubDisplayColumns,
            HubSide hubSide,
            List<TargetMatchSpec> targets,
            boolean highlightDuplicates,
            DedupSpec dedup,
            AgeFilterSpec ageFilter) {
        this(hubCriteria, hubDisplayColumns, hubSide, targets, highlightDuplicates, dedup, ageFilter, false);
    }
}
