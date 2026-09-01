package gov.rajasthan.smart.srse.lakehouse;

import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadata;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The registered subset of the lakehouse — the officer-facing allow-list.
 *
 * <p>Two collaborators, two different jobs, and the distinction is the whole
 * point of this class:
 * <ul>
 *   <li>{@link LakehouseBrowseService} answers "what does the lakehouse
 *       physically contain right now?" — live, uncached, everything the
 *       connection can reach. Only the Admin cascade browses it.</li>
 *   <li>{@link RegisteredTableRepository} answers "what has an admin chosen
 *       to expose?" — persisted in DB2, small, stable. Everything
 *       officer-facing reads this.</li>
 * </ul>
 *
 * <p>Registration is at TABLE granularity and columns are never copied into
 * DB2: {@link #listColumns} re-reads the live column list on every call and
 * merely decorates it with admin metadata. So a column added upstream appears
 * without re-registration, and a column dropped upstream disappears instead
 * of lingering as a stale row that would compile into a broken query.
 *
 * <p><b>Two gates, not one.</b> {@link #validateColumn} checks BOTH that the
 * table is registered AND that the column really exists in the lakehouse. The
 * registry alone is not sufficient — it is a snapshot of an admin's intent and
 * can name a table that has since been dropped — and the live schema alone is
 * not sufficient either, since it would let an officer reach any table on the
 * cluster. Both must agree before an identifier reaches SQL text.
 */
@Service
public class LakehouseRegistryService {

    private final RegisteredTableRepository registrations;
    private final AnalysisColumnMetadataRepository columnMetadata;
    private final LakehouseBrowseService browse;

    public LakehouseRegistryService(RegisteredTableRepository registrations,
                                    AnalysisColumnMetadataRepository columnMetadata,
                                    LakehouseBrowseService browse) {
        this.registrations = registrations;
        this.columnMetadata = columnMetadata;
        this.browse = browse;
    }

    // ---- admin: registration ----

    /**
     * Registers {@code catalog.schema.table}, or updates its layer tag if it
     * is already registered. Validated against the LIVE lakehouse first, so a
     * typo'd or non-existent table can never enter the registry and become an
     * officer-visible dead end.
     */
    @Transactional
    public RegisteredTable register(String catalog, String schema, String table, String layer) {
        browse.validateTable(catalog, schema, table);
        RegisteredTable existing = registrations
                .findByCatalogNameAndSchemaNameAndTableName(catalog, schema, table)
                .orElse(null);
        if (existing != null) {
            existing.setLayer(normaliseLayer(layer));
            return registrations.save(existing);
        }
        return registrations.save(
                new RegisteredTable(null, catalog, schema, table, normaliseLayer(layer)));
    }

    @Transactional
    public void unregister(long id) {
        registrations.deleteById(id);
    }

    public List<RegisteredTable> listRegistrations() {
        return registrations.findAllByOrderByCatalogNameAscSchemaNameAscTableNameAsc();
    }

    private static String normaliseLayer(String layer) {
        if (layer == null || layer.isBlank()) {
            return null;
        }
        // Uppercased so SILVER/Silver/silver group as one layer in the UI;
        // still free text, so a third layer needs no code change.
        return layer.trim().toUpperCase();
    }

    // ---- officer-facing cascade: only registered entries ----

    public List<String> listCatalogs() {
        return registrations.findDistinctCatalogNames();
    }

    public List<String> listSchemas(String catalog) {
        return registrations.findDistinctSchemaNames(catalog);
    }

    public List<RegisteredTable> listTables(String catalog, String schema) {
        return registrations.findByCatalogNameAndSchemaNameOrderByTableName(catalog, schema);
    }

    /**
     * Live columns of a registered table, decorated with admin metadata and
     * with columns the admin opted out already filtered away.
     */
    public List<RegisteredColumn> listColumns(String catalog, String schema, String table) {
        validateRegistered(catalog, schema, table);
        Map<String, AnalysisColumnMetadata> byColumn = columnMetadata
                .findByCatalogNameAndSchemaNameAndTableName(catalog, schema, table).stream()
                .collect(java.util.stream.Collectors.toMap(
                        AnalysisColumnMetadata::getColumnName, Function.identity()));

        return browse.listColumns(catalog, schema, table).stream()
                .map(c -> {
                    AnalysisColumnMetadata meta = byColumn.get(c.name());
                    return new RegisteredColumn(
                            c.name(), c.dataType(),
                            meta != null ? meta.getBusinessName() : null,
                            meta != null && meta.isFuzzyMatchable(),
                            meta == null || meta.isVisible());
                })
                .filter(RegisteredColumn::visible)
                .toList();
    }

    // ---- gates ----

    /** Throws unless an admin has registered {@code catalog.schema.table}. */
    public void validateRegistered(String catalog, String schema, String table) {
        LakehouseIdentifiers.requireSafe("catalog", catalog);
        LakehouseIdentifiers.requireSafe("schema", schema);
        LakehouseIdentifiers.requireSafe("table", table);
        if (!registrations.existsByCatalogNameAndSchemaNameAndTableName(catalog, schema, table)) {
            throw new IllegalArgumentException(
                    "Table is not registered for SRSE: " + catalog + "." + schema + "." + table);
        }
    }

    public void validateRegistered(QualifiedTable table) {
        validateRegistered(table.catalog(), table.schema(), table.table());
    }

    /**
     * Throws unless the table is registered AND the column exists live AND
     * the admin has not hidden it. This is the single gate every ad-hoc
     * identifier passes through before {@code RecordMatchService} puts it in
     * SQL text.
     *
     * <p>Prefer {@link #validateColumns} when checking several columns of the
     * same table — see there for why.
     */
    public void validateColumn(QualifiedColumn column) {
        validateColumns(column.table(), List.of(column.column()));
    }

    /**
     * Batch form of {@link #validateColumn} for several columns of ONE table.
     *
     * <p>Exists for latency, not convenience. Resolving a table's column list
     * walks the whole hierarchy — {@code SHOW CATALOGS}, then the catalog's
     * schemata, then the schema's tables, then the columns themselves — so
     * validating a match's criteria one at a time issued that walk once per
     * criterion (up to 8 per side, both sides) before the match query even
     * started. Since every criterion on a side shares one table by
     * construction, one walk covers them all.
     */
    public void validateColumns(QualifiedTable table, Collection<String> columns) {
        validateRegistered(table);
        Set<String> available = listColumns(table.catalog(), table.schema(), table.table()).stream()
                .map(RegisteredColumn::name)
                .collect(java.util.stream.Collectors.toSet());
        for (String column : columns) {
            LakehouseIdentifiers.requireSafe("column", column);
            if (!available.contains(column)) {
                throw new IllegalArgumentException(
                        "Unknown or hidden column: " + table.qualifiedName() + "." + column);
            }
        }
    }

    /**
     * @param visible retained on the record so {@link #listColumns} can filter
     *                on it; always {@code true} in what that method returns.
     */
    public record RegisteredColumn(String name, String dataType, String businessName,
                                   boolean fuzzyMatchable, boolean visible) {
    }
}
