package gov.rajasthan.smart.srse.metadata;

import gov.rajasthan.smart.srse.compiler.FieldResolver;
import gov.rajasthan.smart.srse.config.CacheConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Minimal Spring context: CacheConfig + manually wired {@link MetadataFieldResolver}.
 * Verifies {@code @Cacheable} hits the repository only once for the same key.
 * No DB2, no full application context, no profile switching.
 *
 * Injects {@link FieldResolver} (not the concrete class) because {@code @EnableCaching}
 * wraps the bean in a JDK interface proxy.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = MetadataFieldResolverCachingTest.TestConfig.class)
class MetadataFieldResolverCachingTest {

    @Autowired
    private FieldResolver resolver;

    @Autowired
    private FieldCatalogRepository catalogRepository;

    @Autowired
    private FieldColumnMappingRepository mappingRepository;

    @Test
    void resolveColumnCachesByFieldKey() {
        String fieldKey = "age_years";
        FieldCatalogEntry entry = new FieldCatalogEntry(
                1L, fieldKey, "Age (years)", FieldTier.TIER_1, FieldDataType.NUMBER, true);
        FieldColumnMapping mapping = new FieldColumnMapping(
                10L, fieldKey, DataMode.SYNTHETIC, "beneficiary.age_years");

        when(catalogRepository.findByFieldKeyAndActiveTrue(fieldKey))
                .thenReturn(Optional.of(entry));
        when(mappingRepository.findByFieldKeyAndDataMode(fieldKey, DataMode.SYNTHETIC))
                .thenReturn(Optional.of(mapping));

        assertEquals("beneficiary.age_years", resolver.resolveColumn(fieldKey));
        assertEquals("beneficiary.age_years", resolver.resolveColumn(fieldKey));

        verify(catalogRepository, times(1)).findByFieldKeyAndActiveTrue(fieldKey);
        verify(mappingRepository, times(1)).findByFieldKeyAndDataMode(fieldKey, DataMode.SYNTHETIC);
    }

    @Configuration
    @Import(CacheConfig.class)
    static class TestConfig {

        private final FieldCatalogRepository catalogRepository = mock(FieldCatalogRepository.class);
        private final FieldColumnMappingRepository mappingRepository =
                mock(FieldColumnMappingRepository.class);

        @Bean
        FieldCatalogRepository catalogRepository() {
            return catalogRepository;
        }

        @Bean
        FieldColumnMappingRepository mappingRepository() {
            return mappingRepository;
        }

        @Bean
        MetadataFieldResolver metadataFieldResolver() {
            return new MetadataFieldResolver(catalogRepository, mappingRepository, "synthetic");
        }
    }
}
