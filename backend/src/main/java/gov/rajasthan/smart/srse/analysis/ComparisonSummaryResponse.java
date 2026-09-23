package gov.rajasthan.smart.srse.analysis;

import java.util.List;

/** Aggregate match rates for post-join comparison columns (full join result, not a client sample). */
public record ComparisonSummaryResponse(
        long totalRows,
        long matchedRows,
        long noCounterpartRows,
        List<ComparisonColumnSummary> columns) {

    /** Legacy empty response when no comparison groups were requested. */
    public ComparisonSummaryResponse(long totalRows, List<ComparisonColumnSummary> columns) {
        this(totalRows, totalRows, 0, columns);
    }

    public record ComparisonColumnSummary(
            int index,
            String label,
            long matchCount,
            /** Match rate over {@link ComparisonSummaryResponse#matchedRows()} only. */
            double matchRatePercent) {
    }
}
