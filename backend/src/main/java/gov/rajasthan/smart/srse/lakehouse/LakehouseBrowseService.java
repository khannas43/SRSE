package gov.rajasthan.smart.srse.lakehouse;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Read-only live introspection of the lakehouse hierarchy:
 * <b>Catalog → Schema → Table → Column</b>.
 *
 * <p>This is what the Admin page's cascade browses. It replaces the older
 * single-schema view ({@code AnalysisSchemaService} read
 * {@code conn.getSchema()} and listed tables from the connection's one
 * implicit schema), which could not express the real deployment: watsonx.data
 * exposes several catalogs, each with several schemas, and SRSE maps at least
 * two layers (Silver and Gold) whose catalog/schema/table names all differ.
 *
 * <h2>Injection safety (CLAUDE.md: non-negotiable)</h2>
 * Catalog and schema names have to be INTERPOLATED, not bound — Presto has no
 * placeholder form for a catalog qualifier, so {@code ?.information_schema}
 * is not valid SQL. Two mechanisms cover that:
 * <ol>
 *   <li><b>Allow-list, primary.</b> Every level is validated against the level
 *       above before it is used: a catalog must appear in {@code SHOW CATALOGS},
 *       a schema in that catalog's {@code information_schema.schemata}, a table
 *       in that schema, a column in that table. Only names the lakehouse itself
 *       reported can ever reach SQL text — officer/admin input is matched
 *       against that list, never trusted.</li>
 *   <li><b>Identifier grammar, defence in depth.</b> {@link LakehouseIdentifiers}
 *       rejects anything that is not a bare Presto identifier, including on the
 *       first not-yet-validated use (the validation query itself must
 *       interpolate the catalog in order to run).</li>
 * </ol>
 * Everything that is a VALUE rather than an identifier — schema and table names
 * in {@code WHERE} clauses — is still a bound {@code ?} parameter, as below.
 *
 * <p>Nothing here is cached. Dropdowns are re-fetched per call so an admin
 * always sees the lakehouse as it is right now, including immediately after a
 * live connection swap (see {@code SwappableDataSource}); the previous
 * single-schema service made the same trade deliberately.
 */
@Service
public class LakehouseBrowseService {

    /**
     * Never offered as a registerable schema — it is Presto's own metadata
     * view, present in every catalog, and registering it would expose schema
     * internals as if it were beneficiary data.
     */
    private static final String INFORMATION_SCHEMA = "information_schema";

    /** System catalogs that describe the cluster rather than hold data. */
    private static final Set<String> SYSTEM_CATALOGS = Set.of("system", "jmx");

    private final JdbcTemplate jdbc;

    public LakehouseBrowseService(@Qualifier("prestoJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every data catalog the current Presto connection can see, e.g. {@code iceberg_data}. */
    public List<String> listCatalogs() {
        return jdbc.queryForList("SHOW CATALOGS", String.class).stream()
                // A catalog whose name isn't a bare identifier can't be
                // addressed by the rest of this service, so it's dropped here
                // rather than offered and then rejected on selection.
                .filter(LakehouseIdentifiers::isSafe)
                .filter(c -> !SYSTEM_CATALOGS.contains(c.toLowerCase()))
                .sorted()
                .toList();
    }

    /** Schemas inside {@code catalog}, e.g. {@code jan_aadhar_data_txn}. */
    public List<String> listSchemas(String catalog) {
        validateCatalog(catalog);
        return jdbc.queryForList(
                        "SELECT schema_name FROM " + catalog + ".information_schema.schemata "
                                + "ORDER BY schema_name",
                        String.class).stream()
                .filter(s -> !INFORMATION_SCHEMA.equalsIgnoreCase(s))
                .filter(LakehouseIdentifiers::isSafe)
                .toList();
    }

    /** Tables inside {@code catalog.schema}, e.g. {@code tbl_txn_bankdtl}. */
    public List<String> listTables(String catalog, String schema) {
        validateSchema(catalog, schema);
        return jdbc.queryForList(
                        // catalog is an interpolated identifier (validated above);
                        // schema is a VALUE here, so it binds as a parameter.
                        "SELECT table_name FROM " + catalog + ".information_schema.tables "
                                + "WHERE table_schema = ? ORDER BY table_name",
                        String.class, schema).stream()
                .filter(LakehouseIdentifiers::isSafe)
                .toList();
    }

    /** Columns of {@code catalog.schema.table}, in ordinal order, e.g. {@code bank_id}, {@code account_no}. */
    public List<ColumnInfo> listColumns(String catalog, String schema, String table) {
        validateTable(catalog, schema, table);
        return jdbc.query(
                "SELECT column_name, data_type FROM " + catalog + ".information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position",
                (rs, rowNum) -> new ColumnInfo(rs.getString("column_name"), rs.getString("data_type")),
                schema, table);
    }

    public List<ColumnInfo> listColumns(QualifiedTable table) {
        return listColumns(table.catalog(), table.schema(), table.table());
    }

    // ---- allow-list validation, one level per rung of the hierarchy ----

    /** Throws unless {@code catalog} is a real catalog on the live connection. */
    public void validateCatalog(String catalog) {
        LakehouseIdentifiers.requireSafe("catalog", catalog);
        if (!listCatalogs().contains(catalog)) {
            throw new IllegalArgumentException("Unknown catalog: " + catalog);
        }
    }

    /** Throws unless {@code schema} is a real schema of {@code catalog}. */
    public void validateSchema(String catalog, String schema) {
        LakehouseIdentifiers.requireSafe("schema", schema);
        if (!listSchemas(catalog).contains(schema)) {
            throw new IllegalArgumentException("Unknown schema: " + catalog + "." + schema);
        }
    }

    /** Throws unless {@code table} is a real table of {@code catalog.schema}. */
    public void validateTable(String catalog, String schema, String table) {
        LakehouseIdentifiers.requireSafe("table", table);
        if (!listTables(catalog, schema).contains(table)) {
            throw new IllegalArgumentException(
                    "Unknown table: " + catalog + "." + schema + "." + table);
        }
    }

    public void validateTable(QualifiedTable table) {
        validateTable(table.catalog(), table.schema(), table.table());
    }

    /** Throws unless {@code column} is a real column of {@code catalog.schema.table}. */
    public void validateColumn(String catalog, String schema, String table, String column) {
        LakehouseIdentifiers.requireSafe("column", column);
        boolean known = listColumns(catalog, schema, table).stream()
                .anyMatch(c -> c.name().equals(column));
        if (!known) {
            throw new IllegalArgumentException(
                    "Unknown column: " + catalog + "." + schema + "." + table + "." + column);
        }
    }

    public void validateColumn(QualifiedColumn column) {
        validateColumn(column.table().catalog(), column.table().schema(),
                column.table().table(), column.column());
    }

    public record ColumnInfo(String name, String dataType) {
    }
}
