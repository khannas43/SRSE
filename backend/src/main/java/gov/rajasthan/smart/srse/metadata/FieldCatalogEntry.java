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

    /** UI grouping for the officer-facing parameter palette, e.g. "Demographic", "Assets". */
    private String groupName;

    /** Comma-separated option values for STRING fields; null for other data types. */
    @Column(length = 2000)
    private String allowedValues;

    /**
     * Whether the rule builder offers approximate (Levenshtein) matching for
     * this field. Boxed (not primitive) so ddl-auto=update can ALTER an
     * already-populated table to add this column — DB2 rejects
     * {@code ALTER TABLE ADD COLUMN ... NOT NULL} without a DEFAULT, which is
     * exactly what Hibernate generates for a primitive boolean field. Existing
     * rows land as NULL; {@link #isFuzzyMatchable()} treats that as false.
     */
    private Boolean fuzzyMatchable = false;

    private boolean active = true;

    protected FieldCatalogEntry() {
    }

    public FieldCatalogEntry(FieldCatalogEntryData data) {
        this.id = data.id();
        this.fieldKey = data.fieldKey();
        this.displayLabel = data.displayLabel();
        this.tier = data.tier();
        this.dataType = data.dataType();
        this.groupName = data.groupName();
        this.allowedValues = data.allowedValues();
        this.active = data.active();
        this.fuzzyMatchable = data.fuzzyMatchable();
    }

    public record FieldCatalogEntryData(
            Long id,
            String fieldKey,
            String displayLabel,
            FieldTier tier,
            FieldDataType dataType,
            String groupName,
            String allowedValues,
            boolean active,
            boolean fuzzyMatchable
    ) {
        public static FieldCatalogEntryData of(
                Long id,
                String fieldKey,
                String displayLabel,
                FieldTier tier,
                FieldDataType dataType,
                String groupName,
                String allowedValues,
                boolean active) {
            return new FieldCatalogEntryData(
                    id, fieldKey, displayLabel, tier, dataType, groupName, allowedValues, active, false);
        }
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

    public String getGroupName() {
        return groupName;
    }

    public String getAllowedValues() {
        return allowedValues;
    }

    public boolean isFuzzyMatchable() {
        return Boolean.TRUE.equals(fuzzyMatchable);
    }

    public boolean isActive() {
        return active;
    }
}
