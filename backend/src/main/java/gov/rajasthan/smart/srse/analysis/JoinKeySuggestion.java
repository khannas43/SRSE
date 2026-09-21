package gov.rajasthan.smart.srse.analysis;

/**
 * One ranked join-key hint. Accepting it verbatim must compile as an exact match pair.
 */
public record JoinKeySuggestion(
        String sourceColumn,
        String targetColumn,
        String sourceType,
        String targetType,
        String reason) {
}
