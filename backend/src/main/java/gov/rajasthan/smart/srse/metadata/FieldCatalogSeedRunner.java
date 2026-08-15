package gov.rajasthan.smart.srse.metadata;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Upserts {@code field_catalog} / {@code field_column_mapping} rows from
 * {@code metadata/field-catalog-seed.yml} on every boot, keyed by field key.
 *
 * <p>This is the operational fix for a real gap: before this ran, those two
 * JPA tables had no seed/migration path in any environment (no Flyway/Liquibase) —
 * {@link MetadataFieldResolver} would find zero rows even when DATA_MODE=live
 * correctly activated it. LIVE-mode physical expressions default to CHANGE_ME
 * placeholders in the checked-in YAML; an admin can now also override them at
 * runtime via {@link FieldColumnMappingController} without a redeploy.
 *
 * <p>This only inserts rows into tables that already exist — it does not create
 * {@code field_catalog}/{@code field_column_mapping} themselves.
 */
@Component
public class FieldCatalogSeedRunner implements ApplicationRunner {

    private final FieldCatalogRepository catalogRepository;
    private final FieldColumnMappingService mappingService;

    public FieldCatalogSeedRunner(FieldCatalogRepository catalogRepository,
                                  FieldColumnMappingService mappingService) {
        this.catalogRepository = catalogRepository;
        this.mappingService = mappingService;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        for (SeedEntry entry : loadSeed()) {
            seedCatalogEntryIfAbsent(entry);
            mappingService.seedIfAbsent(entry.fieldKey(), DataMode.SYNTHETIC, entry.synthetic());
            mappingService.seedIfAbsent(entry.fieldKey(), DataMode.LIVE, entry.live());
        }
    }

    private List<SeedEntry> loadSeed() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> loaded = loader.load(
                "field-catalog-seed", new ClassPathResource("metadata/field-catalog-seed.yml"));
        Iterable<ConfigurationPropertySource> sources = ConfigurationPropertySources.from(loaded);
        return new Binder(sources)
                .bind("fields", Bindable.listOf(SeedEntry.class))
                .orElseThrow(() -> new IllegalStateException("field-catalog-seed.yml has no 'fields' entries"));
    }

    /**
     * Bootstrap-only insert: skips fields that already exist so an admin edit
     * made via {@link FieldCatalogController} survives the next restart
     * instead of being reverted to the checked-in YAML default.
     */
    private void seedCatalogEntryIfAbsent(SeedEntry entry) {
        if (catalogRepository.findByFieldKey(entry.fieldKey()).isPresent()) {
            return;
        }
        String allowedValues = Optional.ofNullable(entry.allowedValues())
                .map(values -> String.join(",", values))
                .orElse(null);
        catalogRepository.save(new FieldCatalogEntry(new FieldCatalogEntry.FieldCatalogEntryData(
                null, entry.fieldKey(), entry.displayLabel(), entry.tier(), entry.dataType(),
                entry.groupName(), allowedValues, true, Boolean.TRUE.equals(entry.fuzzyMatchable()))));
    }

    // fuzzyMatchable is boxed (not primitive) so YAML entries that omit it bind to null,
    // not a binder error — Boolean.TRUE.equals(...) above treats null as false.
    record SeedEntry(String fieldKey, String displayLabel, FieldTier tier, FieldDataType dataType,
                      String groupName, List<String> allowedValues, Boolean fuzzyMatchable,
                      String synthetic, String live) {
    }
}
