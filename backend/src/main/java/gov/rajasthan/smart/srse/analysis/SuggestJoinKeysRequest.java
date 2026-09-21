package gov.rajasthan.smart.srse.analysis;

/**
 * Officer request for join-key hints between two registered tables.
 *
 * @param probe when {@code true}, run a bounded TABLESAMPLE overlap probe on the
 *              top metadata-ranked pairs; failures degrade to metadata-only hints
 */
public record SuggestJoinKeysRequest(
        String sourceCatalog,
        String sourceSchema,
        String sourceTable,
        String targetCatalog,
        String targetSchema,
        String targetTable,
        Boolean probe) {

    public boolean probeRequested() {
        return Boolean.TRUE.equals(probe);
    }
}
