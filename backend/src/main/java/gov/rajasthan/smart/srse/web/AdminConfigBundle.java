package gov.rajasthan.smart.srse.web;

import gov.rajasthan.smart.srse.compiler.CompareAs;
import gov.rajasthan.smart.srse.metadata.DataMode;
import gov.rajasthan.smart.srse.metadata.FieldDataType;
import gov.rajasthan.smart.srse.metadata.FieldTier;

import java.time.Instant;
import java.util.List;

/**
 * Portable snapshot of everything configured on the admin456 page — connections,
 * lakehouse registrations, field catalogue, per-environment mappings, analysis
 * column overrides, and welfare-scheme tags. Natural keys only (no DB ids) so
 * the bundle round-trips across redeploys and fresh DB2 volumes.
 */
public record AdminConfigBundle(
        String schemaVersion,
        Instant exportedAt,
        String dataMode,
        ConnectionBundle connections,
        List<FieldCatalogEntry> fieldCatalog,
        List<FieldColumnMappingEntry> fieldColumnMappings,
        List<RegisteredTableEntry> registeredTables,
        List<AnalysisColumnMetadataEntry> analysisColumnMetadata,
        List<SchemeEntry> schemes
) {
    public static final String CURRENT_SCHEMA_VERSION = "1.0";

    public record ConnectionBundle(ConnectionPlane operational, ConnectionPlane analytical) {
    }

    public record ConnectionPlane(
            String jdbcUrl,
            String username,
            String password,
            String driverClassName) {
    }

    public record FieldCatalogEntry(
            String fieldKey,
            String displayLabel,
            FieldTier tier,
            FieldDataType dataType,
            String groupName,
            List<String> allowedValues,
            boolean fuzzyMatchable) {
    }

    public record FieldColumnMappingEntry(
            String fieldKey,
            DataMode dataMode,
            String physicalExpression) {
    }

    public record RegisteredTableEntry(
            String catalog,
            String schema,
            String table,
            String layer) {
    }

    public record AnalysisColumnMetadataEntry(
            String catalog,
            String schema,
            String table,
            String column,
            String businessName,
            boolean fuzzyMatchable,
            boolean visible,
            CompareAs compareAs) {
    }

    public record SchemeEntry(
            String code,
            String name,
            String description) {
    }
}
