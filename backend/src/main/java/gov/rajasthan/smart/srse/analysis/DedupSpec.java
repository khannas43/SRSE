package gov.rajasthan.smart.srse.analysis;

/**
 * Optional "keep latest, hide older duplicate" view-level dedup. View-only —
 * this collapses rows in the result grid; nothing is ever deleted from the
 * lakehouse. {@code table} must equal the request's source or target table.
 */
public record DedupSpec(String table, String column) {
}
