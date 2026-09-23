package gov.rajasthan.smart.srse.analysis;

/**
 * Officer request for join-key hints between two registered tables.
 *
 * @param probe when {@code true}, measure source key-likeness and run a bounded
 *              TABLESAMPLE overlap probe on the top pairs; Presto failures on
 *              either step degrade to metadata-only hints (never a 500)
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
