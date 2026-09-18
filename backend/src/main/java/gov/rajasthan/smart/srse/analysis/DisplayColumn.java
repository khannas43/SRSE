package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.lakehouse.QualifiedColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;

/**
 * A column projected in the match result's SELECT list only — never part of the
 * JOIN ON clause and never compared to the other side.
 *
 * <p>Same fully-qualified table address shape as {@link MatchCriterion} without
 * {@code fuzzyThresholdPercent}, which only applies to compared pairs.
 */
public record DisplayColumn(String catalog, String schema, String table, String column) {

    /** Validates the identifiers as a side effect — see {@link QualifiedTable}. */
    public QualifiedTable qualifiedTable() {
        return new QualifiedTable(catalog, schema, table);
    }

    public QualifiedColumn qualifiedColumn() {
        return new QualifiedColumn(qualifiedTable(), column);
    }
}
