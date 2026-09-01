package gov.rajasthan.smart.srse.lakehouse;

/**
 * A fully-qualified lakehouse table: {@code catalog.schema.table}.
 *
 * <p>Exists because SRSE now spans MULTIPLE catalogs and schemas at once
 * (Silver and Gold layer registrations live under different catalog/schema
 * names), so a bare table name is no longer an address — the same
 * {@code tbl_txn_bankdtl} can exist in both layers. Every identifier that
 * reaches SQL text travels as one of these, never as a loose string.
 */
public record QualifiedTable(String catalog, String schema, String table) {

    public QualifiedTable {
        LakehouseIdentifiers.requireSafe("catalog", catalog);
        LakehouseIdentifiers.requireSafe("schema", schema);
        LakehouseIdentifiers.requireSafe("table", table);
    }

    /** {@code catalog.schema.table} — safe to interpolate; the compact constructor already validated each part. */
    public String qualifiedName() {
        return catalog + "." + schema + "." + table;
    }

    /** Human-facing form used in dropdown labels and error messages. */
    public String displayName() {
        return catalog + " › " + schema + " › " + table;
    }

    @Override
    public String toString() {
        return qualifiedName();
    }
}
