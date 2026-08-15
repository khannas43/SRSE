package gov.rajasthan.smart.srse.metadata;

import gov.rajasthan.smart.srse.compiler.FieldResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private MetadataFieldResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new MetadataFieldResolver(catalogRepository, mappingRepository, "synthetic");
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
}
