package gov.rajasthan.smart.srse.metadata;

import gov.rajasthan.smart.srse.compiler.FieldResolver;
import gov.rajasthan.smart.srse.lakehouse.LakehouseBrowseService;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Plain Mockito unit tests for {@link MetadataFieldResolver} — no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class MetadataFieldResolverTest {

    @Mock
    private FieldCatalogRepository catalogRepository;

    @Mock
    private FieldColumnMappingRepository mappingRepository;

    @Mock
    private LakehouseBrowseService browse;

    private MetadataFieldResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new MetadataFieldResolver(catalogRepository, mappingRepository, browse, "synthetic");
    }

    @Test
    void resolvesActiveCatalogEntryWithSyntheticMapping() {
        String fieldKey = "age_years";
        FieldCatalogEntry entry = new FieldCatalogEntry(new FieldCatalogEntry.FieldCatalogEntryData(
                1L, fieldKey, "Age (years)", FieldTier.TIER_1, FieldDataType.NUMBER, "Demographic", null, true, false));
        FieldColumnMapping mapping = new FieldColumnMapping(
                10L, fieldKey, DataMode.SYNTHETIC, "beneficiary.age_years");

        when(catalogRepository.findByFieldKeyAndActiveTrue(fieldKey))
                .thenReturn(Optional.of(entry));
        when(mappingRepository.findByFieldKeyAndDataMode(fieldKey, DataMode.SYNTHETIC))
                .thenReturn(Optional.of(mapping));

        assertEquals("beneficiary.age_years", resolver.resolveColumn(fieldKey));

        verify(catalogRepository).findByFieldKeyAndActiveTrue(fieldKey);
        verify(mappingRepository).findByFieldKeyAndDataMode(fieldKey, DataMode.SYNTHETIC);
    }

    @Test
    void inactiveCatalogEntryThrowsUnknownField() {
        String fieldKey = "age_years";
        // findByFieldKeyAndActiveTrue excludes inactive rows → empty
        when(catalogRepository.findByFieldKeyAndActiveTrue(fieldKey))
                .thenReturn(Optional.empty());

        assertThrows(FieldResolver.UnknownFieldException.class,
                () -> resolver.resolveColumn(fieldKey));

        verify(catalogRepository).findByFieldKeyAndActiveTrue(fieldKey);
        verifyNoInteractions(mappingRepository);
    }

    @Test
    void activeCatalogButNoMappingForDataModeThrowsUnknownField() {
        String fieldKey = "age_years";
        FieldCatalogEntry entry = new FieldCatalogEntry(new FieldCatalogEntry.FieldCatalogEntryData(
                1L, fieldKey, "Age (years)", FieldTier.TIER_1, FieldDataType.NUMBER, "Demographic", null, true, false));

        when(catalogRepository.findByFieldKeyAndActiveTrue(fieldKey))
                .thenReturn(Optional.of(entry));
        when(mappingRepository.findByFieldKeyAndDataMode(fieldKey, DataMode.SYNTHETIC))
                .thenReturn(Optional.empty());

        assertThrows(FieldResolver.UnknownFieldException.class,
                () -> resolver.resolveColumn(fieldKey));

        verify(catalogRepository).findByFieldKeyAndActiveTrue(fieldKey);
        verify(mappingRepository).findByFieldKeyAndDataMode(fieldKey, DataMode.SYNTHETIC);
    }

    @Test
    void missingCatalogEntryThrowsUnknownField() {
        String fieldKey = "unknown_field";
        when(catalogRepository.findByFieldKeyAndActiveTrue(fieldKey))
                .thenReturn(Optional.empty());

        assertThrows(FieldResolver.UnknownFieldException.class,
                () -> resolver.resolveColumn(fieldKey));

        verify(catalogRepository).findByFieldKeyAndActiveTrue(fieldKey);
        verifyNoInteractions(mappingRepository);
    }

    // ---- coercion against the live column type ----

    private static final String INCOME = "annual_income";
    private static final QualifiedTable GOLD =
            new QualifiedTable("iceberg_gold", "golden_layer", "tbl_beneficiary");
    private static final String INCOME_BINDING = "iceberg_gold.golden_layer.tbl_beneficiary.annual_income";

    private void stubField(String fieldKey, FieldDataType dataType, String binding) {
        FieldCatalogEntry entry = new FieldCatalogEntry(new FieldCatalogEntry.FieldCatalogEntryData(
                1L, fieldKey, "Label", FieldTier.TIER_1, dataType, "Group", null, true, false));
        when(catalogRepository.findByFieldKeyAndActiveTrue(fieldKey)).thenReturn(Optional.of(entry));
        when(mappingRepository.findByFieldKeyAndDataMode(fieldKey, DataMode.SYNTHETIC))
                .thenReturn(Optional.of(new FieldColumnMapping(10L, fieldKey, DataMode.SYNTHETIC, binding)));
    }

    private void stubLiveType(String column, String dataType) {
        when(browse.listColumns(GOLD)).thenReturn(List.of(
                new LakehouseBrowseService.ColumnInfo(column, dataType)));
    }

    /**
     * The catalogue says {@code annual_income} is a NUMBER and the officer's
     * value is bound as one, but the golden layer stores it as varchar — which
     * made every rule using the field fail with
     * "'<' cannot be applied to varchar, integer".
     */
    @Test
    void aNumericFieldOnATextColumnResolvesThroughACast() {
        stubField(INCOME, FieldDataType.NUMBER, INCOME_BINDING);
        stubLiveType(INCOME, "varchar(20)");

        assertEquals("TRY_CAST(" + INCOME_BINDING + " AS DOUBLE)", resolver.resolveColumn(INCOME));
    }

    @Test
    void aNumericFieldOnANumericColumnIsLeftAlone() {
        stubField(INCOME, FieldDataType.NUMBER, INCOME_BINDING);
        stubLiveType(INCOME, "decimal(12,2)");

        assertEquals(INCOME_BINDING, resolver.resolveColumn(INCOME));
    }

    /**
     * A Tier-2 expression is already typed by the function that produced it,
     * and there is no single column to introspect — so it is never touched,
     * and the live schema is never even consulted.
     */
    @Test
    void aTier2ExpressionIsNeverCoerced() {
        String expression = "date_diff('year', beneficiary.date_of_birth, current_date)";
        stubField("age_years", FieldDataType.NUMBER, expression);

        assertEquals(expression, resolver.resolveColumn("age_years"));
        verifyNoInteractions(browse);
    }

    /** A metadata lookup must never be the thing that takes a preview down. */
    @Test
    void anIntrospectionFailureFallsBackToTheUncastBinding() {
        stubField(INCOME, FieldDataType.NUMBER, INCOME_BINDING);
        when(browse.listColumns(GOLD)).thenThrow(new IllegalStateException("Presto unreachable"));

        assertEquals(INCOME_BINDING, resolver.resolveColumn(INCOME));
    }

    /**
     * ExecutionService derives the FROM table by stripping the last dotted
     * segment, so it asks for the raw binding — a cast around it would strip
     * to nonsense.
     */
    @Test
    void resolveRawColumnNeverCasts() {
        stubField(INCOME, FieldDataType.NUMBER, INCOME_BINDING);

        assertEquals(INCOME_BINDING, resolver.resolveRawColumn(INCOME));
        verifyNoInteractions(browse);
    }

}
