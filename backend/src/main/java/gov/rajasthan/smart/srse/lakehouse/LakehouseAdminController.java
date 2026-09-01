package gov.rajasthan.smart.srse.lakehouse;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin API behind the Catalog → Schema → Table → Column cascade.
 *
 * <p>The {@code /browse/**} endpoints read the LIVE lakehouse — everything the
 * current Presto connection can reach — so an admin can discover what exists.
 * The {@code /registrations} endpoints persist the subset officers may use.
 * Officer-facing code never calls anything on this controller; it reads
 * {@link LakehouseCatalogController} instead.
 */
@RestController
@RequestMapping("/api/admin/lakehouse")
public class LakehouseAdminController {

    private final LakehouseBrowseService browse;
    private final LakehouseRegistryService registry;

    public LakehouseAdminController(LakehouseBrowseService browse, LakehouseRegistryService registry) {
        this.browse = browse;
        this.registry = registry;
    }

    // ---- live browse: the cascade ----

    @GetMapping("/browse/catalogs")
    public List<String> catalogs() {
        return browse.listCatalogs();
    }

    @GetMapping("/browse/catalogs/{catalog}/schemas")
    public List<String> schemas(@PathVariable String catalog) {
        return browse.listSchemas(catalog);
    }

    @GetMapping("/browse/catalogs/{catalog}/schemas/{schema}/tables")
    public List<String> tables(@PathVariable String catalog, @PathVariable String schema) {
        return browse.listTables(catalog, schema);
    }

    @GetMapping("/browse/catalogs/{catalog}/schemas/{schema}/tables/{table}/columns")
    public List<LakehouseBrowseService.ColumnInfo> columns(@PathVariable String catalog,
                                                           @PathVariable String schema,
                                                           @PathVariable String table) {
        return browse.listColumns(catalog, schema, table);
    }

    // ---- registrations ----

    @GetMapping("/registrations")
    public List<RegistrationResponse> registrations() {
        return registry.listRegistrations().stream().map(RegistrationResponse::from).toList();
    }

    @PostMapping("/registrations")
    public RegistrationResponse register(@RequestBody RegisterTableRequest req) {
        return RegistrationResponse.from(
                registry.register(req.catalog(), req.schema(), req.table(), req.layer()));
    }

    @DeleteMapping("/registrations/{id}")
    public void unregister(@PathVariable long id) {
        registry.unregister(id);
    }

    public record RegisterTableRequest(String catalog, String schema, String table, String layer) {
    }

    public record RegistrationResponse(Long id, String catalog, String schema, String table,
                                       String layer, String qualifiedName) {
        static RegistrationResponse from(RegisteredTable entity) {
            return new RegistrationResponse(
                    entity.getId(), entity.getCatalogName(), entity.getSchemaName(),
                    entity.getTableName(), entity.getLayer(),
                    entity.toQualifiedTable().qualifiedName());
        }
    }
}
