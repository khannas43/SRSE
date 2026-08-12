package gov.rajasthan.smart.srse.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private FieldColumnMappingService mappingService;

    private FieldCatalogSeedRunner runner;

    @BeforeEach
    void setUp() {
        runner = new FieldCatalogSeedRunner(catalogRepository, mappingService);
        when(catalogRepository.findByFieldKey(any())).thenReturn(Optional.empty());
        when(catalogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void insertsAllTwentySixFieldsAndSeedsBothDataModeMappingsOnFirstRun() throws Exception {
        runner.run(null);

        ArgumentCaptor<FieldCatalogEntry> catalogCaptor = ArgumentCaptor.forClass(FieldCatalogEntry.class);
        verify(catalogRepository, times(26)).save(catalogCaptor.capture());
        assertTrue(catalogCaptor.getAllValues().stream().allMatch(FieldCatalogEntry::isActive));
        assertTrue(catalogCaptor.getAllValues().stream().allMatch(e -> e.getId() == null));
        assertTrue(catalogCaptor.getAllValues().stream()
                .anyMatch(e -> "age_years".equals(e.getFieldKey())));
        assertTrue(catalogCaptor.getAllValues().stream()
                .anyMatch(e -> e.getTier() == FieldTier.TIER_3 && "is_girl_child_of_hof".equals(e.getFieldKey())));
        assertTrue(catalogCaptor.getAllValues().stream()
                .anyMatch(e -> e.getTier() == FieldTier.TIER_3 && "relationship_to_hof".equals(e.getFieldKey())
                        && e.getAllowedValues() != null && e.getAllowedValues().contains("GRANDSON")));
        // father_name/mother_name deliberately NOT in the Rule Engine catalogue —
        // their fuzzy-match UI now lives only in the Analysis tab.
        assertFalse(catalogCaptor.getAllValues().stream()
                .anyMatch(e -> "father_name".equals(e.getFieldKey())));
        assertFalse(catalogCaptor.getAllValues().stream()
                .anyMatch(e -> "mother_name".equals(e.getFieldKey())));
        assertFalse(catalogCaptor.getAllValues().stream()
                .anyMatch(e -> "age_years".equals(e.getFieldKey()) && e.isFuzzyMatchable()));
        assertTrue(catalogCaptor.getAllValues().stream()
                .anyMatch(e -> e.getTier() == FieldTier.TIER_3 && "annual_income_fy2627".equals(e.getFieldKey())
                        && "Income by Financial Year".equals(e.getGroupName())));
        assertTrue(catalogCaptor.getAllValues().stream()
                .anyMatch(e -> "class_passed".equals(e.getFieldKey()) && "Education".equals(e.getGroupName())
                        && e.getAllowedValues() != null && e.getAllowedValues().contains("GRADUATE")));
        assertEquals(10, catalogCaptor.getAllValues().stream()
                .filter(e -> "Income by Financial Year".equals(e.getGroupName()))
                .count());
        assertTrue(catalogCaptor.getAllValues().stream()
                .anyMatch(e -> "annual_income_fy1718".equals(e.getFieldKey())
                        && "Income by Financial Year".equals(e.getGroupName())));
        assertTrue(catalogCaptor.getAllValues().stream()
                .anyMatch(e -> "census_category".equals(e.getFieldKey()) && "Economic".equals(e.getGroupName())
                        && e.getAllowedValues() != null && e.getAllowedValues().contains("EWS")));
        assertTrue(catalogCaptor.getAllValues().stream()
                .anyMatch(e -> "tsp_classification".equals(e.getFieldKey())
                        && "Social Category".equals(e.getGroupName())
                        && e.getAllowedValues() != null && e.getAllowedValues().contains("NON_TSP")));

        // 26 fields x 2 data modes, delegated unconditionally to the
        // insert-if-absent service — the "don't clobber an admin edit" check
        // lives inside FieldColumnMappingService, not the runner.
        verify(mappingService, times(52)).seedIfAbsent(any(), any(), any());
        verify(mappingService).seedIfAbsent(
                eq("age_years"), eq(DataMode.SYNTHETIC), eq("beneficiary.age_years"));
        verify(mappingService).seedIfAbsent(
                eq("age_years"), eq(DataMode.LIVE), any());
    }

    @Test
    void existingCatalogEntryIsLeftUntouchedOnRerun() throws Exception {
        FieldCatalogEntry existingCatalog = new FieldCatalogEntry(
                99L, "age_years", "stale label", FieldTier.TIER_1, FieldDataType.NUMBER, "Demographic", null, false);
        when(catalogRepository.findByFieldKey("age_years")).thenReturn(Optional.of(existingCatalog));

        runner.run(null);

        // Only the other 25 fields get (re-)inserted — age_years, already
        // present, is never passed to save() at all (not even to "update" it),
        // so an admin edit to its label/active flag survives a restart.
        ArgumentCaptor<FieldCatalogEntry> catalogCaptor = ArgumentCaptor.forClass(FieldCatalogEntry.class);
        verify(catalogRepository, times(25)).save(catalogCaptor.capture());
        assertFalse(catalogCaptor.getAllValues().stream()
                .anyMatch(e -> "age_years".equals(e.getFieldKey())));

        // Mapping seeding is still delegated for every field — the runner
        // doesn't know or care whether a catalog row already existed.
        verify(mappingService, times(52)).seedIfAbsent(any(), any(), any());
    }
}
