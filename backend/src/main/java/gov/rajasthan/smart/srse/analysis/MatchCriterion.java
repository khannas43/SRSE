package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.lakehouse.QualifiedColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;

/**
 * One Source or Target pick from the Analysis tab's
 * Catalog → Schema → Table → Column cascade.
 *
 * <p>Fully qualified: SRSE maps several catalogs and schemas at once (Silver
 * and Gold layers), so {@code table} alone is no longer an address — the same
 * table name genuinely exists in more than one place. A Silver↔Gold
 * reconciliation is therefore an ordinary match whose two sides happen to
 * carry different catalog/schema values; nothing in the query path
 * special-cases it.
 *
 * <p>{@link RecordMatchService} still requires every criterion on the same
 * side (all Source, or all Target) to share the same qualified table — a
 * composite match key of 1-N columns from one table, not an N-way join —
 * which keeps the underlying join a simple two-table comparison.
 *
 * <p>{@code fuzzyThresholdPercent} is set on the Source-side criterion only
 * (0..100) and applies to that (source, target) column pair when the pair is
 * fuzzy-matchable; ignored/null otherwise and on Target entries.
 */
public record MatchCriterion(String catalog, String schema, String table, String column,
                             Double fuzzyThresholdPercent) {

    /** Validates the identifiers as a side effect — see {@link QualifiedTable}. */
    public QualifiedTable qualifiedTable() {
        return new QualifiedTable(catalog, schema, table);
    }

    public QualifiedColumn qualifiedColumn() {
        return new QualifiedColumn(qualifiedTable(), column);
    }
}
