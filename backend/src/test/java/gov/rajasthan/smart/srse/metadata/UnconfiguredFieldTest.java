package gov.rajasthan.smart.srse.metadata;

import gov.rajasthan.smart.srse.compiler.FieldResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the "unconfigured environment" path.
 *
 * <p>SRSE ships 29 {@code CHANGE_ME} placeholders in field-catalog-seed.yml, and
 * nothing used to validate them. In a LIVE deployment where the Golden Layer
 * bindings had not been filled in, the placeholder was emitted into SQL as a
 * table name; Presto resolved it against the connection's default
 * catalog/schema and reported
 * {@code Table silver_data.jan_aadhaar_txn.change_me does not exist} — naming an
 * object that appears nowhere in the Admin page, so the reader went hunting for
 * a phantom registration instead of an unmapped field. Reported from a real
 * client deployment.
 */
class UnconfiguredFieldTest {

    // ---- placeholder detection ----

    @ParameterizedTest
    @ValueSource(strings = {
            "CHANGE_ME.age_years",
            "CHANGE_ME.district",
            "change_me.district",                 // Presto lower-cases unquoted identifiers
            "silver_data.jan_aadhaar_txn.CHANGE_ME.district",
            "date_diff('year', CHANGE_ME.dob, current_date)"})
    void detectsUnconfiguredPlaceholders(String expression) {
        assertTrue(FieldColumnMapping.isPlaceholder(expression), expression);
    }

    /** Must not fire on real columns that merely contain the letters. */
    @ParameterizedTest
    @ValueSource(strings = {
            "iceberg_gold.golden_layer.tbl_beneficiary.age_years",
            "beneficiary.change_me_flag",          // longer identifier, not the token
            "beneficiary.exchange_measure",
            "warehouse.schema.tbl.changeme"})
    void doesNotFireOnRealColumns(String expression) {
        assertFalse(FieldColumnMapping.isPlaceholder(expression), expression);
    }

    @Test
    void nullExpressionIsNotAPlaceholder() {
        assertFalse(FieldColumnMapping.isPlaceholder(null));
    }

    // ---- resolver behaviour ----

    private MetadataFieldResolver resolverWith(String physicalExpression) {
        FieldCatalogRepository catalog = mock(FieldCatalogRepository.class);
        FieldColumnMappingRepository mappings = mock(FieldColumnMappingRepository.class);

        FieldCatalogEntry entry = mock(FieldCatalogEntry.class);
        when(entry.getFieldKey()).thenReturn("district");
        when(catalog.findByFieldKeyAndActiveTrue("district")).thenReturn(Optional.of(entry));
        when(mappings.findByFieldKeyAndDataMode("district", DataMode.LIVE)).thenReturn(
                Optional.of(new FieldColumnMapping(1L, "district", DataMode.LIVE, physicalExpression)));

        return new MetadataFieldResolver(catalog, mappings, "live");
    }

    /**
     * The exact deployment state that produced the report: catalogued, mapped,
     * but never bound to a real column.
     */
    @Test
    void placeholderMappingFailsWithAnActionableMessageInsteadOfReachingSql() {
        FieldResolver.UnconfiguredFieldException ex = assertThrows(
                FieldResolver.UnconfiguredFieldException.class,
                () -> resolverWith("CHANGE_ME.district").resolveColumn("district"));

        assertTrue(ex.getMessage().contains("district"), ex.getMessage());
        assertTrue(ex.getMessage().contains("CHANGE_ME.district"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Admin page"), ex.getMessage());
        assertTrue(ex.getMessage().contains("field-catalog-seed.yml"), ex.getMessage());
    }

    @Test
    void aProperlyConfiguredMappingStillResolves() {
        assertEquals("iceberg_gold.golden_layer.tbl_beneficiary.district",
                resolverWith("iceberg_gold.golden_layer.tbl_beneficiary.district")
                        .resolveColumn("district"));
    }
}
