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

/**
 * Upserts {@code field_catalog} / {@code field_column_mapping} rows from
 * {@code metadata/field-catalog-seed.yml} on every boot, keyed by field key.
 *
 * <p>This is the operational fix for a real gap: before this ran, those two
 * JPA tables had no seed/migration path in any environment (no Flyway/Liquibase,
 * no admin API) — {@link MetadataFieldResolver} would find zero rows even when
 * DATA_MODE=live correctly activated it. LIVE-mode physical expressions are
 * CHANGE_ME placeholders pending Lovadeep's Golden Layer column names; swapping
 * them is a one-file YAML edit, not a code or SQL change.
 *
 * <p>This only inserts rows into tables that already exist — it does not create
 * {@code field_catalog}/{@code field_column_mapping} themselves.
 */
@Component
public class FieldCatalogSeedRunner implements ApplicationRunner {

    private final FieldCatalogRepository catalogRepository;
    private final FieldColumnMappingRepository mappingRepository;

    public FieldCatalogSeedRunner(FieldCatalogRepository catalogRepository,
                                  FieldColumnMappingRepository mappingRepository) {
        this.catalogRepository = catalogRepository;
        this.mappingRepository = mappingRepository;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        for (SeedEntry entry : loadSeed()) {
            upsertCatalogEntry(entry);
            upsertMapping(entry.fieldKey(), DataMode.SYNTHETIC, entry.synthetic());
            upsertMapping(entry.fieldKey(), DataMode.LIVE, entry.live());
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

    private void upsertCatalogEntry(SeedEntry entry) {
        Long existingId = catalogRepository.findByFieldKey(entry.fieldKey())
                .map(FieldCatalogEntry::getId)
                .orElse(null);
        catalogRepository.save(new FieldCatalogEntry(
                existingId, entry.fieldKey(), entry.displayLabel(), entry.tier(), entry.dataType(), true));
    }

    private void upsertMapping(String fieldKey, DataMode dataMode, String physicalExpression) {
        Long existingId = mappingRepository.findByFieldKeyAndDataMode(fieldKey, dataMode)
                .map(FieldColumnMapping::getId)
                .orElse(null);
        mappingRepository.save(new FieldColumnMapping(existingId, fieldKey, dataMode, physicalExpression));
    }

    record SeedEntry(String fieldKey, String displayLabel, FieldTier tier, FieldDataType dataType,
                      String synthetic, String live) {
    }
}
