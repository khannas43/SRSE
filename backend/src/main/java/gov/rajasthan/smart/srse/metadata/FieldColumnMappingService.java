package gov.rajasthan.smart.srse.metadata;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

/**
 * Upserts a single field's physical binding for one {@link DataMode}. This is
 * the one place that writes {@link FieldColumnMapping} — used both by the
 * boot-time YAML seed ({@link FieldCatalogSeedRunner}) and by the admin
 * mapping-editor API ({@link FieldColumnMappingController}), so there is only
 * one upsert-by-key code path to keep correct.
 *
 * <p>{@code @CacheEvict} matters for LIVE mode: {@link MetadataFieldResolver}
 * caches resolved columns in the {@code fieldMappings} cache, so an admin
 * edit here must invalidate that entry or the compiler keeps using the stale
 * physical expression until the cache naturally expires.
 */
@Service
public class FieldColumnMappingService {

    private final FieldColumnMappingRepository repository;

    public FieldColumnMappingService(FieldColumnMappingRepository repository) {
        this.repository = repository;
    }

    @CacheEvict(cacheNames = "fieldMappings", key = "#fieldKey")
    public FieldColumnMapping upsert(String fieldKey, DataMode dataMode, String physicalExpression) {
        Long existingId = repository.findByFieldKeyAndDataMode(fieldKey, dataMode)
                .map(FieldColumnMapping::getId)
                .orElse(null);
        return repository.save(new FieldColumnMapping(existingId, fieldKey, dataMode, physicalExpression));
    }

    /**
     * Bootstrap-only insert used by {@link FieldCatalogSeedRunner}: creates the
     * row if (and only if) it doesn't exist yet. Unlike {@link #upsert}, this
     * never overwrites an existing row — so an admin edit made via
     * {@link FieldColumnMappingController} survives the next restart instead
     * of being silently reverted to the checked-in YAML default.
     */
    public void seedIfAbsent(String fieldKey, DataMode dataMode, String physicalExpression) {
        if (repository.findByFieldKeyAndDataMode(fieldKey, dataMode).isEmpty()) {
            repository.save(new FieldColumnMapping(null, fieldKey, dataMode, physicalExpression));
        }
    }
}
