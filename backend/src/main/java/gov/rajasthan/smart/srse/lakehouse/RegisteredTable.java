package gov.rajasthan.smart.srse.lakehouse;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A lakehouse table an admin has registered as visible to SRSE.
 *
 * <p>Operational plane (DB2/JPA) — this is SRSE's own configuration, not
 * beneficiary data. Registration is the seam between "everything the Presto
 * connection can physically reach" (browsed live via
 * {@link LakehouseBrowseService}) and "what officers are actually offered":
 * the admin walks the Catalog → Schema → Table cascade once, and only the
 * tables registered here appear in the Analysis tab.
 *
 * <p>Registering a table exposes ALL of its live columns by default — the
 * column list is never copied into DB2, it is always re-read from the
 * lakehouse, so a column added upstream shows up without re-registration.
 * Individual columns are opted OUT (and given business names / fuzzy flags)
 * through {@code AnalysisColumnMetadata}, which is keyed by the same
 * catalog/schema/table triple.
 *
 * <p>{@code layer} is a free-text tag — in practice {@code SILVER} or
 * {@code GOLD}, the two lakehouse layers SRSE maps. It is deliberately a
 * LABEL and not a structural level of the hierarchy: a Silver table and its
 * Gold counterpart are two ordinary registrations that happen to differ in
 * catalog, schema and table name, so reconciling them is just a normal
 * cross-table match with no special-casing anywhere in the query path.
 */
@Entity
@Table(
        name = "registered_table",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_registered_table",
                columnNames = {"catalog_name", "schema_name", "table_name"})
)
public class RegisteredTable {

    /** See AnalysisColumnMetadata.IDENTIFIER_LENGTH — same cap, same DB2 index-key reason. */
    private static final int IDENTIFIER_LENGTH = 128;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "catalog_name", nullable = false, length = IDENTIFIER_LENGTH)
    private String catalogName;

    @Column(name = "schema_name", nullable = false, length = IDENTIFIER_LENGTH)
    private String schemaName;

    @Column(name = "table_name", nullable = false, length = IDENTIFIER_LENGTH)
    private String tableName;

    /** SILVER / GOLD / null. Free text so a third layer needs no code change. */
    @Column(name = "layer")
    private String layer;

    protected RegisteredTable() {
    }

    public RegisteredTable(Long id, String catalogName, String schemaName, String tableName, String layer) {
        this.id = id;
        this.catalogName = catalogName;
        this.schemaName = schemaName;
        this.tableName = tableName;
        this.layer = layer;
    }

    public Long getId() {
        return id;
    }

    public String getCatalogName() {
        return catalogName;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public String getTableName() {
        return tableName;
    }

    public String getLayer() {
        return layer;
    }

    public void setLayer(String layer) {
        this.layer = layer;
    }

    /** The address form used everywhere downstream; re-validates the identifiers. */
    public QualifiedTable toQualifiedTable() {
        return new QualifiedTable(catalogName, schemaName, tableName);
    }
}
