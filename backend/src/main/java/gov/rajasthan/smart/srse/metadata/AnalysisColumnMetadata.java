package gov.rajasthan.smart.srse.metadata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Admin-managed metadata for a physical {@code table.column} the Analysis
 * tab's live schema introspection can surface — a business-friendly label
 * and whether it should be fuzzy-matched, independent of the Rule Engine's
 * field catalogue (which only covers a small curated set of fields, not the
 * whole lakehouse schema Analysis lets an officer pick from ad hoc).
 *
 * <p>Deliberately keyed by the raw column, not by Source/Target role — the
 * same physical column can be picked as either in a given match, and the
 * "Source: "/"Target: " prefixing stays a frontend display concern.
 *
 * <p>Both fields are optional overrides, not requirements: an unregistered
 * column falls back to an auto-derived display label and a name-substring
 * guess for fuzzy eligibility (see {@code RecordMatchService}) — this table
 * only needs entries for the columns worth curating.
 */
@Entity
@Table(
        name = "analysis_column_metadata",
        uniqueConstraints = @UniqueConstraint(columnNames = {"table_name", "column_name"})
)
public class AnalysisColumnMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(name = "column_name", nullable = false)
    private String columnName;

    private String businessName;

    /** Boxed, not primitive — see FieldCatalogEntry.fuzzyMatchable for why (DB2 ALTER TABLE safety). */
    private Boolean fuzzyMatchable = false;

    protected AnalysisColumnMetadata() {
    }

    public AnalysisColumnMetadata(Long id, String tableName, String columnName,
                                  String businessName, Boolean fuzzyMatchable) {
        this.id = id;
        this.tableName = tableName;
        this.columnName = columnName;
        this.businessName = businessName;
        this.fuzzyMatchable = fuzzyMatchable;
    }

    public Long getId() {
        return id;
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
}
