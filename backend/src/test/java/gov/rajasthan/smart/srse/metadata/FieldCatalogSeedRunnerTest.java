package gov.rajasthan.smart.srse.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain Mockito unit tests for {@link FieldCatalogSeedRunner} — no Spring
 * context, no live DB2 (the project has no JPA test infra / H2 dependency;
 * this mirrors {@link MetadataFieldResolverTest}'s mocked-repository style).
 */
@ExtendWith(MockitoExtension.class)
class FieldCatalogSeedRunnerTest {

    @Mock
    private FieldCatalogRepository catalogRepository;

    @Mock
    private FieldColumnMappingRepository mappingRepository;

    private FieldCatalogSeedRunner runner;

    @BeforeEach
    void setUp() {
        runner = new FieldCatalogSeedRunner(catalogRepository, mappingRepository);
        when(catalogRepository.findByFieldKey(any())).thenReturn(Optional.empty());
        when(mappingRepository.findByFieldKeyAndDataMode(any(), any())).thenReturn(Optional.empty());
        when(catalogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mappingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void insertsAllElevenFieldsWithBothDataModeMappingsOnFirstRun() throws Exception {
        runner.run(null);

        ArgumentCaptor<FieldCatalogEntry> catalogCaptor = ArgumentCaptor.forClass(FieldCatalogEntry.class);
        verify(catalogRepository, times(11)).save(catalogCaptor.capture());
        assertTrue(catalogCaptor.getAllValues().stream().allMatch(FieldCatalogEntry::isActive));
        assertTrue(catalogCaptor.getAllValues().stream().allMatch(e -> e.getId() == null));
        assertTrue(catalogCaptor.getAllValues().stream()
                .anyMatch(e -> "age_years".equals(e.getFieldKey())));
        assertTrue(catalogCaptor.getAllValues().stream()
                .anyMatch(e -> e.getTier() == FieldTier.TIER_3 && "is_girl_child_of_hof".equals(e.getFieldKey())));

        ArgumentCaptor<FieldColumnMapping> mappingCaptor = ArgumentCaptor.forClass(FieldColumnMapping.class);
        verify(mappingRepository, times(22)).save(mappingCaptor.capture());
        List<FieldColumnMapping> mappings = mappingCaptor.getAllValues();
        assertTrue(mappings.stream().anyMatch(m ->
                "age_years".equals(m.getFieldKey()) && m.getDataMode() == DataMode.SYNTHETIC
                        && "beneficiary.age_years".equals(m.getPhysicalExpression())));
        assertTrue(mappings.stream().anyMatch(m ->
                "age_years".equals(m.getFieldKey()) && m.getDataMode() == DataMode.LIVE
                        && m.getPhysicalExpression().startsWith("CHANGE_ME")));
    }

    @Test
    void reRunUpdatesExistingRowsInPlaceInsteadOfDuplicating() throws Exception {
        FieldCatalogEntry existingCatalog = new FieldCatalogEntry(
                99L, "age_years", "stale label", FieldTier.TIER_1, FieldDataType.NUMBER, false);
        FieldColumnMapping existingMapping = new FieldColumnMapping(
                77L, "age_years", DataMode.LIVE, "stale.expr");
        when(catalogRepository.findByFieldKey("age_years")).thenReturn(Optional.of(existingCatalog));
        when(mappingRepository.findByFieldKeyAndDataMode("age_years", DataMode.LIVE))
                .thenReturn(Optional.of(existingMapping));

        runner.run(null);

        ArgumentCaptor<FieldCatalogEntry> catalogCaptor = ArgumentCaptor.forClass(FieldCatalogEntry.class);
        verify(catalogRepository, times(11)).save(catalogCaptor.capture());
        FieldCatalogEntry savedAge = catalogCaptor.getAllValues().stream()
                .filter(e -> "age_years".equals(e.getFieldKey()))
                .findFirst().orElseThrow();
        assertEquals(99L, savedAge.getId());
        assertTrue(savedAge.isActive());

        ArgumentCaptor<FieldColumnMapping> mappingCaptor = ArgumentCaptor.forClass(FieldColumnMapping.class);
        verify(mappingRepository, times(22)).save(mappingCaptor.capture());
        FieldColumnMapping savedLiveAge = mappingCaptor.getAllValues().stream()
                .filter(m -> "age_years".equals(m.getFieldKey()) && m.getDataMode() == DataMode.LIVE)
                .findFirst().orElseThrow();
        assertEquals(77L, savedLiveAge.getId());
    }
}
