package gov.rajasthan.smart.srse.metadata;

import gov.rajasthan.smart.srse.lakehouse.QualifiedColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Admin-managed metadata for one physical column of a REGISTERED lakehouse
 * table — a business-friendly label, whether it should be fuzzy-matched, and
 * whether officers see it at all. Independent of the Rule Engine's field
 * catalogue (which only covers a small curated set of fields, not the whole
 * lakehouse schema Analysis lets an officer pick from).
 *
 * <p><b>Fully qualified.</b> Keyed by {@code catalog + schema + table +
 * column}, not by table+column alone: SRSE maps several catalogs and schemas
 * at once (Silver and Gold layers), so the same {@code tbl_txn_bankdtl}
 * genuinely exists more than once and {@code bank_id} needs different
 * metadata in each. The old table+column key silently collided across layers.
 *
 * <p>Deliberately keyed by the raw column, not by Source/Target role — the
 * same physical column can be picked as either in a given match, and the
 * "Source: "/"Target: " prefixing stays a frontend display concern.
 *
 * <p>All three attributes are optional overrides, not requirements. An
 * unregistered column of a registered table is visible, falls back to an
 * auto-derived display label, and uses a name-substring guess for fuzzy
 * eligibility (see {@code RecordMatchService}) — this table only needs rows
 * for the columns worth curating or hiding.
 */
@Entity
@Table(
        name = "analysis_column_metadata",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_analysis_column_metadata",
                columnNames = {"catalog_name", "schema_name", "table_name", "column_name"})
)
public class AnalysisColumnMetadata {

    /**
     * Identifier columns are capped at {@link gov.rajasthan.smart.srse.lakehouse.LakehouseIdentifiers}'s
     * 128-character limit rather than Hibernate's default 255.
     *
     * <p>Not cosmetic: DB2 caps the total byte length of an index key, and a
     * unique key over four VARCHAR(255) columns exceeds it — the constraint
     * is rejected with SQL0613N ("too long or has too many columns"). Since no
     * identifier that reaches this table can be longer than 128 characters
     * anyway (the guard rejects it before it is ever persisted), declaring the
     * real bound both fits the index and documents the invariant.
     */
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

    @Column(name = "column_name", nullable = false, length = IDENTIFIER_LENGTH)
    private String columnName;

    private String businessName;

    /** Boxed, not primitive — see FieldCatalogEntry.fuzzyMatchable for why (DB2 ALTER TABLE safety). */
    private Boolean fuzzyMatchable = false;

    /**
     * Whether officers see this column in the Analysis tab's dropdowns.
     * Registering a table exposes all its columns, so this exists to opt
     * individual ones OUT — hence the default of TRUE, and hence boxed with
     * a null-means-visible reading, so a row written before this column
     * existed does not silently vanish from the UI.
     */
    @Column(name = "visible")
    private Boolean visible = Boolean.TRUE;

    protected AnalysisColumnMetadata() {
    }

    /**
     * Takes the address as one {@link QualifiedColumn} rather than four loose
     * strings. That is the point of this class after the multi-catalog change
     * — the four parts are only meaningful together — and it means every row
     * written here has had its identifiers validated by
     * {@code LakehouseIdentifiers} on the way in, since QualifiedColumn's
     * compact constructor does that.
     */
    public AnalysisColumnMetadata(Long id, QualifiedColumn column, String businessName,
                                  Boolean fuzzyMatchable, Boolean visible) {
        this.id = id;
        this.catalogName = column.table().catalog();
        this.schemaName = column.table().schema();
        this.tableName = column.table().table();
        this.columnName = column.column();
        this.businessName = businessName;
        this.fuzzyMatchable = fuzzyMatchable;
        this.visible = visible;
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

    public String getColumnName() {
        return columnName;
    }

    public String getBusinessName() {
        return businessName;
    }

    public boolean isFuzzyMatchable() {
        return Boolean.TRUE.equals(fuzzyMatchable);
    }

    /** Null reads as visible — see {@link #visible}. */
    public boolean isVisible() {
        return !Boolean.FALSE.equals(visible);
    }

    public QualifiedColumn toQualifiedColumn() {
        return new QualifiedColumn(
                new QualifiedTable(catalogName, schemaName, tableName), columnName);
    }
}
