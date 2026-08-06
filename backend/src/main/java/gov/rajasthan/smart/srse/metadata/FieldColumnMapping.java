package gov.rajasthan.smart.srse.metadata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Per-environment physical binding of a catalogue field key.
 *
 * <p>CONTRACT: NEVER store a Tier-3 cross-table JOIN here — Tier 3 fields must
 * already be flattened upstream by Spark ETL before a row exists in this table
 * (per CLAUDE.md flat-catalogue contract). {@code physicalExpression} holds either
 * a bare column ref (e.g. {@code beneficiary.age_years}) for Tier 1, or a
 * same-table SQL expression for Tier 2 / pre-materialised Tier 3.
 */
@Entity
@Table(
        name = "field_column_mapping",
        uniqueConstraints = @UniqueConstraint(columnNames = {"field_key", "data_mode"})
)
public class FieldColumnMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "field_key", nullable = false)
    private String fieldKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_mode", nullable = false)
    private DataMode dataMode;

    @Column(nullable = false)
    private String physicalExpression;

    protected FieldColumnMapping() {
    }

    public FieldColumnMapping(Long id, String fieldKey, DataMode dataMode,
                              String physicalExpression) {
        this.id = id;
        this.fieldKey = fieldKey;
        this.dataMode = dataMode;
        this.physicalExpression = physicalExpression;
    }

    public Long getId() {
        return id;
    }

    public String getFieldKey() {
        return fieldKey;
    }

    public DataMode getDataMode() {
        return dataMode;
    }

    public String getPhysicalExpression() {
        return physicalExpression;
    }
}
