package gov.rajasthan.smart.srse.lakehouse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Officer-facing Catalog → Schema → Table → Column cascade for the Analysis
 * tab's Source/Target pickers.
 *
 * <p>Mirrors the Admin cascade's shape but NOT its reach: every level here is
 * answered from the registry, so an officer is only ever offered what an admin
 * registered. The live lakehouse is consulted only for the column list of an
 * already-registered table (see {@link LakehouseRegistryService#listColumns}).
 */
@RestController
@RequestMapping("/api/analysis/lakehouse")
public class LakehouseCatalogController {

    private final LakehouseRegistryService registry;

    public LakehouseCatalogController(LakehouseRegistryService registry) {
        this.registry = registry;
    }

    @GetMapping("/catalogs")
    public List<String> catalogs() {
        return registry.listCatalogs();
    }

    @GetMapping("/catalogs/{catalog}/schemas")
    public List<String> schemas(@PathVariable String catalog) {
        return registry.listSchemas(catalog);
    }

    @GetMapping("/catalogs/{catalog}/schemas/{schema}/tables")
    public List<TableResponse> tables(@PathVariable String catalog, @PathVariable String schema) {
        return registry.listTables(catalog, schema).stream().map(TableResponse::from).toList();
    }

    @GetMapping("/catalogs/{catalog}/schemas/{schema}/tables/{table}/columns")
    public List<LakehouseRegistryService.RegisteredColumn> columns(@PathVariable String catalog,
                                                                   @PathVariable String schema,
                                                                   @PathVariable String table) {
        return registry.listColumns(catalog, schema, table);
    }

    /** {@code layer} travels to the officer UI so Silver/Gold can be shown as a badge on the picker. */
    public record TableResponse(String name, String layer) {
        static TableResponse from(RegisteredTable entity) {
            return new TableResponse(entity.getTableName(), entity.getLayer());
        }
    }
}
