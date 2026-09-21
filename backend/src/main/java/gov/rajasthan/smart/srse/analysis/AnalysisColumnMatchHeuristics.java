package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadata;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Shared fuzzy / name-guess rules for Analysis — must stay aligned with
 * {@link RecordMatchService#isGroupFuzzy} and the frontend's {@code pairIsFuzzy}.
 */
public final class AnalysisColumnMatchHeuristics {

    private AnalysisColumnMatchHeuristics() {
    }

    public static boolean nameSubstringGuess(String column) {
        return column.toLowerCase(Locale.ROOT).contains("name");
    }

    /**
     * Whether a column would participate in a fuzzy group given registry metadata.
     * Registered columns decide as a bloc; the name guess applies only when nothing
     * in the set is registered.
     */
    public static boolean columnFuzzyEligible(
            String column,
            Optional<AnalysisColumnMetadata> metadata) {
        if (metadata.isPresent()) {
            return metadata.get().isFuzzyMatchable();
        }
        return nameSubstringGuess(column);
    }

    /**
     * Group-level fuzzy decision for several columns on one side (or both sides combined).
     */
    public static boolean groupWouldBeFuzzy(List<ColumnMetaRef> columns) {
        boolean anyRegistered = false;
        for (ColumnMetaRef c : columns) {
            if (c.metadata().isPresent()) {
                anyRegistered = true;
                if (c.metadata().get().isFuzzyMatchable()) {
                    return true;
                }
            }
        }
        if (anyRegistered) {
            return false;
        }
        return columns.stream().anyMatch(c -> nameSubstringGuess(c.column()));
    }

    public record ColumnMetaRef(String column, Optional<AnalysisColumnMetadata> metadata) {
    }
}
