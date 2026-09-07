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
 *
 * <p>The eviction clears the WHOLE cache rather than one key. A field now has
 * more than one entry there — the compare-ready form and the raw one
 * ({@code FieldResolver.resolveRawColumn}) — and the compare-ready form also
 * folds in the column's live lakehouse type, so "which keys does this edit
 * invalidate" is no longer answerable from the field key alone. The cache
 * holds a few dozen short strings and refills on the next compile; getting
 * this wrong means an admin's correction silently not taking effect.
 */
@Service
public class FieldColumnMappingService {

    private final FieldColumnMappingRepository repository;

    public FieldColumnMappingService(FieldColumnMappingRepository repository) {
        this.repository = repository;
    }

    @CacheEvict(cacheNames = "fieldMappings", allEntries = true)
    public FieldColumnMapping upsert(String fieldKey, DataMode dataMode, String physicalExpression) {
        Long existingId = repository.findByFieldKeyAndDataMode(fieldKey, dataMode)
                .map(FieldColumnMapping::getId)
                .orElse(null);
        return repository.save(new FieldColumnMapping(existingId, fieldKey, dataMode, physicalExpression));
    }

    /**
     * Unbinds a field for ONE environment, leaving the field itself in the
     * catalogue and the other environment's binding untouched.
     *
     * <p>The field then has no physical column for this DataMode and stops
     * resolving, which is the honest state — the Admin page shows it as not
     * configured, exactly like a CHANGE_ME placeholder, and a rule using it
     * fails with a message naming the field rather than a phantom table. Use
     * it to retire a binding without retiring the field; to remove the field
     * everywhere, delete the field itself.
     *
     * <p>Idempotent: unbinding what is already unbound is a no-op, so a
     * double-click cannot 404.
     */
    @CacheEvict(cacheNames = "fieldMappings", allEntries = true)
    public void delete(String fieldKey, DataMode dataMode) {
        repository.findByFieldKeyAndDataMode(fieldKey, dataMode).ifPresent(repository::delete);
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
