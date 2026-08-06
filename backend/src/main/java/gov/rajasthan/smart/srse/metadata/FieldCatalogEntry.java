package gov.rajasthan.smart.srse.metadata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Officer-facing field catalogue entry — environment-agnostic.
 * Physical bindings live in {@link FieldColumnMapping} per {@link DataMode}.
 */
@Entity
@Table(name = "field_catalog")
public class FieldCatalogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String fieldKey;

    private String displayLabel;

    @Enumerated(EnumType.STRING)
    private FieldTier tier;

    @Enumerated(EnumType.STRING)
    private FieldDataType dataType;

    private boolean active = true;

    protected FieldCatalogEntry() {
    }

    public FieldCatalogEntry(Long id, String fieldKey, String displayLabel,
                             FieldTier tier, FieldDataType dataType, boolean active) {
        this.id = id;
        this.fieldKey = fieldKey;
        this.displayLabel = displayLabel;
        this.tier = tier;
        this.dataType = dataType;
        this.active = active;
    }

    public Long getId() {
        return id;
    }

    public String getFieldKey() {
        return fieldKey;
    }

    public String getDisplayLabel() {
        return displayLabel;
    }

    public FieldTier getTier() {
        return tier;
    }

    public FieldDataType getDataType() {
        return dataType;
    }

    public boolean isActive() {
        return active;
    }
}
