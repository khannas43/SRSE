package gov.rajasthan.smart.srse.metadata;

import gov.rajasthan.smart.srse.compiler.FieldResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * JPA-backed {@link FieldResolver}, active whenever DATA_MODE=live — regardless
 * of Spring profile, so any environment (including client-dev) pointed at real
 * on-prem Presto/DB2 gets real Golden Layer bindings from {@link FieldColumnMapping}
 * rather than {@link StubFieldResolver}'s hardcoded synthetic column names.
 *
 * CONTRACT (do not violate):
 *  - Allow-list gate: only catalogued + active + mapped-for-this-environment
 *    field keys resolve; anything else throws {@link UnknownFieldException}.
 *  - Physical expressions come from {@link FieldColumnMapping} for the configured
 *    {@link DataMode} — never from officer input as SQL identifiers.
 *  - Results are Caffeine-cached ({@code fieldMappings}) since lookups happen on
 *    every rule compile (CLAUDE.md: Metadata / mapping service, JPA + Caffeine).
 */
@Component
@ConditionalOnProperty(name = "srse.data-mode", havingValue = "live")
public class MetadataFieldResolver implements FieldResolver {

    private final FieldCatalogRepository catalogRepository;
    private final FieldColumnMappingRepository mappingRepository;
    private final DataMode dataMode;

    public MetadataFieldResolver(FieldCatalogRepository catalogRepository,
                                 FieldColumnMappingRepository mappingRepository,
                                 @Value("${srse.data-mode}") String dataModeConfig) {
        this.catalogRepository = catalogRepository;
        this.mappingRepository = mappingRepository;
        this.dataMode = DataMode.valueOf(dataModeConfig.toUpperCase());
    }

    @Override
    @Cacheable(cacheNames = "fieldMappings", key = "#fieldKey")
    public String resolveColumn(String fieldKey) {
        FieldCatalogEntry entry = catalogRepository.findByFieldKeyAndActiveTrue(fieldKey)
                .orElseThrow(() -> new UnknownFieldException(fieldKey));

        // entry present and active — look up environment-specific binding
        return mappingRepository.findByFieldKeyAndDataMode(entry.getFieldKey(), dataMode)
                .map(FieldColumnMapping::getPhysicalExpression)
                .orElseThrow(() -> new UnknownFieldException(fieldKey));
    }
}
