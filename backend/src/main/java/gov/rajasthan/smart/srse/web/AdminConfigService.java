package gov.rajasthan.smart.srse.web;

import com.zaxxer.hikari.HikariDataSource;
import gov.rajasthan.smart.srse.compiler.CompareAs;
import gov.rajasthan.smart.srse.compiler.FieldResolver;
import gov.rajasthan.smart.srse.config.AnalyticalConnectionService;
import gov.rajasthan.smart.srse.config.ConnectionOverrideStore;
import gov.rajasthan.smart.srse.config.OperationalConnectionService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.lakehouse.QualifiedColumn;
import gov.rajasthan.smart.srse.lakehouse.RegisteredTableRepository;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadata;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import gov.rajasthan.smart.srse.metadata.FieldCatalogEntry;
import gov.rajasthan.smart.srse.metadata.FieldCatalogRepository;
import gov.rajasthan.smart.srse.metadata.FieldColumnMapping;
import gov.rajasthan.smart.srse.metadata.FieldColumnMappingRepository;
import gov.rajasthan.smart.srse.metadata.FieldColumnMappingService;
import gov.rajasthan.smart.srse.scheme.Scheme;
import gov.rajasthan.smart.srse.scheme.SchemeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Export/import of the admin456 configuration bundle. Lets a team redeploy SRSE
 * without re-entering connections, lakehouse registrations, field mappings, or
 * analysis column overrides by hand.
 */
@Service
public class AdminConfigService {

    private final HikariDataSource operational;
    private final ConnectionOverrideStore overrideStore;
    private final AnalyticalConnectionService analyticalConnectionService;
    private final OperationalConnectionService operationalConnectionService;
    private final FieldCatalogRepository catalogRepository;
    private final FieldColumnMappingRepository mappingRepository;
    private final FieldColumnMappingService mappingService;
    private final RegisteredTableRepository registeredTableRepository;
    private final LakehouseRegistryService registryService;
    private final AnalysisColumnMetadataRepository columnMetadataRepository;
    private final SchemeRepository schemeRepository;

    private final String dataMode;
    private final String analyticalUrl;
    private final String analyticalUsername;
    private final String analyticalDriverClassName;

    public AdminConfigService(
            DataSource operational,
            ConnectionOverrideStore overrideStore,
            AnalyticalConnectionService analyticalConnectionService,
            OperationalConnectionService operationalConnectionService,
            FieldCatalogRepository catalogRepository,
            FieldColumnMappingRepository mappingRepository,
            FieldColumnMappingService mappingService,
            RegisteredTableRepository registeredTableRepository,
            LakehouseRegistryService registryService,
            AnalysisColumnMetadataRepository columnMetadataRepository,
            SchemeRepository schemeRepository,
            @Value("${srse.data-mode}") String dataMode,
            @Value("${srse.datasource.analytical.jdbc-url}") String analyticalUrl,
            @Value("${srse.datasource.analytical.username}") String analyticalUsername,
            @Value("${srse.datasource.analytical.driver-class-name}") String analyticalDriverClassName) {
        this.operational = (HikariDataSource) operational;
        this.overrideStore = overrideStore;
        this.analyticalConnectionService = analyticalConnectionService;
        this.operationalConnectionService = operationalConnectionService;
        this.catalogRepository = catalogRepository;
        this.mappingRepository = mappingRepository;
        this.mappingService = mappingService;
        this.registeredTableRepository = registeredTableRepository;
        this.registryService = registryService;
        this.columnMetadataRepository = columnMetadataRepository;
        this.schemeRepository = schemeRepository;
        this.dataMode = dataMode;
        this.analyticalUrl = analyticalUrl;
        this.analyticalUsername = analyticalUsername;
        this.analyticalDriverClassName = analyticalDriverClassName;
    }

    public AdminConfigBundle export() {
        Properties overrides = overrideStore.load().orElseGet(Properties::new);
        AdminConfigBundle.ConnectionPlane operationalPlane = planeFromOverrides(
                overrides, "operational",
                operational.getJdbcUrl(), operational.getUsername(), operational.getDriverClassName());
        AdminConfigBundle.ConnectionPlane analyticalPlane = planeFromOverrides(
                overrides, "analytical",
                analyticalUrl, analyticalUsername, analyticalDriverClassName);

        List<AdminConfigBundle.FieldCatalogEntry> fields = catalogRepository.findAll().stream()
                .filter(FieldCatalogEntry::isActive)
                .map(this::toFieldCatalogEntry)
                .toList();

        List<AdminConfigBundle.FieldColumnMappingEntry> mappings = mappingRepository.findAll().stream()
                .map(m -> new AdminConfigBundle.FieldColumnMappingEntry(
                        m.getFieldKey(), m.getDataMode(), m.getPhysicalExpression()))
                .toList();

        List<AdminConfigBundle.RegisteredTableEntry> tables = registeredTableRepository.findAll().stream()
                .map(t -> new AdminConfigBundle.RegisteredTableEntry(
                        t.getCatalogName(), t.getSchemaName(), t.getTableName(), t.getLayer()))
                .toList();

        List<AdminConfigBundle.AnalysisColumnMetadataEntry> columnMetadata =
                columnMetadataRepository.findAllByOrderByCatalogNameAscSchemaNameAscTableNameAscColumnNameAsc()
                        .stream()
                        .map(this::toColumnMetadataEntry)
                        .toList();

        List<AdminConfigBundle.SchemeEntry> schemes = schemeRepository.findByActiveTrueOrderByName().stream()
                .map(s -> new AdminConfigBundle.SchemeEntry(s.getCode(), s.getName(), s.getDescription()))
                .toList();

        return new AdminConfigBundle(
                AdminConfigBundle.CURRENT_SCHEMA_VERSION,
                Instant.now(),
                dataMode,
                new AdminConfigBundle.ConnectionBundle(operationalPlane, analyticalPlane),
                fields,
                mappings,
                tables,
                columnMetadata,
                schemes);
    }

    @Transactional
    @CacheEvict(cacheNames = "fieldMappings", allEntries = true)
    public ImportResult importConfig(AdminConfigBundle bundle, ImportOptions options) {
        validateSchemaVersion(bundle);

        ImportResult.Builder result = ImportResult.builder();
        if (bundle.connections() != null) {
            applyConnections(bundle.connections(), options, result);
        }

        if (bundle.fieldCatalog() != null) {
            for (AdminConfigBundle.FieldCatalogEntry entry : bundle.fieldCatalog()) {
                upsertFieldCatalog(entry);
                result.fieldCatalog(entry.fieldKey());
            }
        }

        if (bundle.registeredTables() != null) {
            for (AdminConfigBundle.RegisteredTableEntry entry : bundle.registeredTables()) {
                registryService.importRegistration(entry.catalog(), entry.schema(), entry.table(), entry.layer());
                result.registeredTable(qualifiedTable(entry));
            }
        }

        if (bundle.fieldColumnMappings() != null) {
            for (AdminConfigBundle.FieldColumnMappingEntry entry : bundle.fieldColumnMappings()) {
                if (entry.physicalExpression() == null || entry.physicalExpression().isBlank()) {
                    continue;
                }
                ensureFieldExists(entry.fieldKey());
                mappingService.upsert(entry.fieldKey(), entry.dataMode(), entry.physicalExpression());
                result.fieldMapping(entry.fieldKey() + "@" + entry.dataMode());
            }
        }

        if (bundle.analysisColumnMetadata() != null) {
            for (AdminConfigBundle.AnalysisColumnMetadataEntry entry : bundle.analysisColumnMetadata()) {
                upsertColumnMetadata(entry);
                result.columnMetadata(qualifiedColumn(entry));
            }
        }

        if (bundle.schemes() != null) {
            for (AdminConfigBundle.SchemeEntry entry : bundle.schemes()) {
                upsertScheme(entry);
                result.scheme(entry.code());
            }
        }

        return result.build();
    }

    private void validateSchemaVersion(AdminConfigBundle bundle) {
        if (bundle.schemaVersion() == null || bundle.schemaVersion().isBlank()) {
            throw new IllegalArgumentException("schemaVersion is required");
        }
        if (!AdminConfigBundle.CURRENT_SCHEMA_VERSION.equals(bundle.schemaVersion())) {
            throw new IllegalArgumentException(
                    "Unsupported schemaVersion: " + bundle.schemaVersion()
                            + " (expected " + AdminConfigBundle.CURRENT_SCHEMA_VERSION + ")");
        }
    }

    private void applyConnections(AdminConfigBundle.ConnectionBundle connections,
                                  ImportOptions options,
                                  ImportResult.Builder result) {
        if (connections.operational() != null) {
            AdminConfigBundle.ConnectionPlane plane = connections.operational();
            if (options.testConnections()) {
                operationalConnectionService.update(
                        plane.jdbcUrl(), plane.username(), plane.password(), plane.driverClassName());
            } else {
                persistConnectionOverride("operational", plane);
            }
            result.operationalRestartRequired(true);
        }
        if (connections.analytical() != null) {
            AdminConfigBundle.ConnectionPlane plane = connections.analytical();
            if (options.testConnections()) {
                analyticalConnectionService.update(
                        plane.jdbcUrl(), plane.username(), plane.password(), plane.driverClassName());
            } else {
                persistConnectionOverride("analytical", plane);
            }
        }
    }

    private void persistConnectionOverride(String prefix, AdminConfigBundle.ConnectionPlane plane) {
        Properties props = new Properties();
        props.setProperty(prefix + ".jdbc-url", plane.jdbcUrl());
        props.setProperty(prefix + ".username", plane.username());
        props.setProperty(prefix + ".password", plane.password() == null ? "" : plane.password());
        props.setProperty(prefix + ".driver-class-name", plane.driverClassName());
        overrideStore.save(props);
    }

    private void upsertFieldCatalog(AdminConfigBundle.FieldCatalogEntry entry) {
        FieldCatalogEntry existing = catalogRepository.findByFieldKey(entry.fieldKey()).orElse(null);
        String allowedValues = entry.allowedValues() == null || entry.allowedValues().isEmpty()
                ? null
                : String.join(",", entry.allowedValues());
        FieldCatalogEntry saved = new FieldCatalogEntry(new FieldCatalogEntry.FieldCatalogEntryData(
                existing == null ? null : existing.getId(),
                entry.fieldKey(),
                entry.displayLabel(),
                entry.tier(),
                entry.dataType(),
                entry.groupName(),
                allowedValues,
                true,
                entry.fuzzyMatchable()));
        catalogRepository.save(saved);
    }

    private void ensureFieldExists(String fieldKey) {
        catalogRepository.findByFieldKey(fieldKey)
                .filter(FieldCatalogEntry::isActive)
                .orElseThrow(() -> new FieldResolver.UnknownFieldException(fieldKey));
    }

    private void upsertColumnMetadata(AdminConfigBundle.AnalysisColumnMetadataEntry entry) {
        Long existingId = columnMetadataRepository
                .findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                        entry.catalog(), entry.schema(), entry.table(), entry.column())
                .map(AnalysisColumnMetadata::getId)
                .orElse(null);
        AnalysisColumnMetadata entity = new AnalysisColumnMetadata(
                existingId,
                new QualifiedColumn(entry.catalog(), entry.schema(), entry.table(), entry.column()),
                entry.businessName(),
                entry.fuzzyMatchable(),
                entry.visible());
        entity.setCompareAs(CompareAs.orAuto(entry.compareAs()));
        columnMetadataRepository.save(entity);
    }

    private void upsertScheme(AdminConfigBundle.SchemeEntry entry) {
        Scheme existing = schemeRepository.findByCode(entry.code()).orElse(null);
        Scheme saved = new Scheme(
                existing == null ? null : existing.getId(),
                entry.code(),
                entry.name(),
                entry.description(),
                true,
                existing == null ? Instant.now() : existing.getCreatedAt());
        schemeRepository.save(saved);
    }

    private AdminConfigBundle.FieldCatalogEntry toFieldCatalogEntry(FieldCatalogEntry entry) {
        List<String> allowedValues = entry.getAllowedValues() == null
                ? List.of()
                : Arrays.asList(entry.getAllowedValues().split(","));
        return new AdminConfigBundle.FieldCatalogEntry(
                entry.getFieldKey(),
                entry.getDisplayLabel(),
                entry.getTier(),
                entry.getDataType(),
                entry.getGroupName(),
                allowedValues,
                entry.isFuzzyMatchable());
    }

    private AdminConfigBundle.AnalysisColumnMetadataEntry toColumnMetadataEntry(AnalysisColumnMetadata entity) {
        return new AdminConfigBundle.AnalysisColumnMetadataEntry(
                entity.getCatalogName(),
                entity.getSchemaName(),
                entity.getTableName(),
                entity.getColumnName(),
                entity.getBusinessName(),
                entity.isFuzzyMatchable(),
                entity.isVisible(),
                entity.getCompareAs());
    }

    private static AdminConfigBundle.ConnectionPlane planeFromOverrides(
            Properties overrides, String prefix, String defaultUrl, String defaultUser, String defaultDriver) {
        return new AdminConfigBundle.ConnectionPlane(
                overrides.getProperty(prefix + ".jdbc-url", defaultUrl),
                overrides.getProperty(prefix + ".username", defaultUser),
                overrides.getProperty(prefix + ".password", ""),
                overrides.getProperty(prefix + ".driver-class-name", defaultDriver));
    }

    private static String qualifiedTable(AdminConfigBundle.RegisteredTableEntry entry) {
        return entry.catalog() + "." + entry.schema() + "." + entry.table();
    }

    private static String qualifiedColumn(AdminConfigBundle.AnalysisColumnMetadataEntry entry) {
        return qualifiedTable(new AdminConfigBundle.RegisteredTableEntry(
                entry.catalog(), entry.schema(), entry.table(), null))
                + "." + entry.column();
    }

    public record ImportOptions(boolean testConnections) {
        public static ImportOptions defaults() {
            return new ImportOptions(true);
        }
    }

    public record ImportResult(
            int fieldCatalogCount,
            int fieldMappingCount,
            int registeredTableCount,
            int columnMetadataCount,
            int schemeCount,
            boolean operationalRestartRequired,
            List<String> importedFieldKeys,
            List<String> importedMappings,
            List<String> importedTables,
            List<String> importedColumns,
            List<String> importedSchemes) {

        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private int fieldCatalogCount;
            private int fieldMappingCount;
            private int registeredTableCount;
            private int columnMetadataCount;
            private int schemeCount;
            private boolean operationalRestartRequired;
            private final java.util.ArrayList<String> importedFieldKeys = new java.util.ArrayList<>();
            private final java.util.ArrayList<String> importedMappings = new java.util.ArrayList<>();
            private final java.util.ArrayList<String> importedTables = new java.util.ArrayList<>();
            private final java.util.ArrayList<String> importedColumns = new java.util.ArrayList<>();
            private final java.util.ArrayList<String> importedSchemes = new java.util.ArrayList<>();

            void fieldCatalog(String key) {
                fieldCatalogCount++;
                importedFieldKeys.add(key);
            }

            void fieldMapping(String key) {
                fieldMappingCount++;
                importedMappings.add(key);
            }

            void registeredTable(String key) {
                registeredTableCount++;
                importedTables.add(key);
            }

            void columnMetadata(String key) {
                columnMetadataCount++;
                importedColumns.add(key);
            }

            void scheme(String key) {
                schemeCount++;
                importedSchemes.add(key);
            }

            void operationalRestartRequired(boolean value) {
                operationalRestartRequired = operationalRestartRequired || value;
            }

            ImportResult build() {
                return new ImportResult(
                        fieldCatalogCount,
                        fieldMappingCount,
                        registeredTableCount,
                        columnMetadataCount,
                        schemeCount,
                        operationalRestartRequired,
                        List.copyOf(importedFieldKeys),
                        List.copyOf(importedMappings),
                        List.copyOf(importedTables),
                        List.copyOf(importedColumns),
                        List.copyOf(importedSchemes));
            }
        }
    }
}
