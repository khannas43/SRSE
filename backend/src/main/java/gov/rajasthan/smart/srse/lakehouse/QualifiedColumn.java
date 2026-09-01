package gov.rajasthan.smart.srse.lakehouse;

/**
 * A fully-qualified lakehouse column: {@code catalog.schema.table.column}.
 * See {@link QualifiedTable} for why bare names are no longer addresses.
 */
public record QualifiedColumn(QualifiedTable table, String column) {

    public QualifiedColumn {
        LakehouseIdentifiers.requireSafe("column", column);
    }

    public QualifiedColumn(String catalog, String schema, String table, String column) {
        this(new QualifiedTable(catalog, schema, table), column);
    }

    public String qualifiedName() {
        return table.qualifiedName() + "." + column;
    }

    @Override
    public String toString() {
        return qualifiedName();
    }
}
