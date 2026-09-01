package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.lakehouse.QualifiedColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;

/**
 * Optional "keep latest, hide older duplicate" view-level dedup. View-only —
 * this collapses rows in the result grid; nothing is ever deleted from the
 * lakehouse. The qualified table must equal the request's Source or Target
 * table (qualified comparison, not by bare name — the same table name can
 * exist in both the Silver and Gold catalog).
 */
public record DedupSpec(String catalog, String schema, String table, String column) {

    public QualifiedTable qualifiedTable() {
        return new QualifiedTable(catalog, schema, table);
    }

    public QualifiedColumn qualifiedColumn() {
        return new QualifiedColumn(qualifiedTable(), column);
    }
}
