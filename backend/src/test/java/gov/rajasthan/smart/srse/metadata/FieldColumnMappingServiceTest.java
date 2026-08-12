package gov.rajasthan.smart.srse.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain Mockito unit tests for {@link FieldColumnMappingService} — mirrors
 * {@link gov.rajasthan.smart.srse.scenario.ScenarioServiceTest}'s style.
 */
@ExtendWith(MockitoExtension.class)
class FieldColumnMappingServiceTest {

    @Mock
    private FieldColumnMappingRepository repository;

    private FieldColumnMappingService service;

    @BeforeEach
    void setUp() {
        service = new FieldColumnMappingService(repository);
        // lenient: not every test exercises save (e.g. the skip-if-present case)
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void upsertCreatesWhenAbsent() {
        when(repository.findByFieldKeyAndDataMode("age_years", DataMode.LIVE))
                .thenReturn(Optional.empty());

        FieldColumnMapping saved = service.upsert("age_years", DataMode.LIVE, "golden.age_years");

        ArgumentCaptor<FieldColumnMapping> captor = ArgumentCaptor.forClass(FieldColumnMapping.class);
        verify(repository).save(captor.capture());
        assertNull(captor.getValue().getId());
        assertEquals("golden.age_years", saved.getPhysicalExpression());
    }

    @Test
    void upsertOverwritesExistingRowInPlace() {
        FieldColumnMapping existing = new FieldColumnMapping(5L, "age_years", DataMode.LIVE, "CHANGE_ME.age_years");
        when(repository.findByFieldKeyAndDataMode("age_years", DataMode.LIVE))
                .thenReturn(Optional.of(existing));

        FieldColumnMapping saved = service.upsert("age_years", DataMode.LIVE, "golden.age_years");

        ArgumentCaptor<FieldColumnMapping> captor = ArgumentCaptor.forClass(FieldColumnMapping.class);
        verify(repository).save(captor.capture());
        assertEquals(5L, captor.getValue().getId());
        assertEquals("golden.age_years", saved.getPhysicalExpression());
    }

    @Test
    void seedIfAbsentSkipsWhenAlreadyPresent() {
        FieldColumnMapping existing = new FieldColumnMapping(5L, "age_years", DataMode.LIVE, "admin.edited.value");
        when(repository.findByFieldKeyAndDataMode("age_years", DataMode.LIVE))
                .thenReturn(Optional.of(existing));

        service.seedIfAbsent("age_years", DataMode.LIVE, "CHANGE_ME.age_years");

        verify(repository, never()).save(any());
    }

    @Test
    void seedIfAbsentInsertsWhenMissing() {
        when(repository.findByFieldKeyAndDataMode("age_years", DataMode.LIVE))
                .thenReturn(Optional.empty());

        service.seedIfAbsent("age_years", DataMode.LIVE, "CHANGE_ME.age_years");

        verify(repository, times(1)).save(any());
    }

    @Test
    void upsertIsAnnotatedToEvictTheFieldMappingsCacheEntry() throws NoSuchMethodException {
        Method upsert = FieldColumnMappingService.class
                .getMethod("upsert", String.class, DataMode.class, String.class);
        Annotation evict = upsert.getAnnotation(org.springframework.cache.annotation.CacheEvict.class);
        assertNotNull(evict, "upsert() must evict the fieldMappings cache so MetadataFieldResolver "
                + "doesn't keep serving a stale physical expression after an admin edit");
    }
}
