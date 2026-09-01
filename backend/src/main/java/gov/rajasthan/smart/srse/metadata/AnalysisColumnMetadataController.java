package gov.rajasthan.smart.srse.metadata;

import gov.rajasthan.smart.srse.lakehouse.LakehouseBrowseService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.lakehouse.QualifiedColumn;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin API for the Analysis tab's per-column overrides — business name,
 * fuzzy-matchable, and visibility — keyed by the full
 * {@code catalog.schema.table.column} address. See
 * {@link AnalysisColumnMetadata}'s javadoc for why this is separate from the
 * Rule Engine's field catalogue, and why the key had to become qualified.
 */
@RestController
@RequestMapping("/api/analysis/column-metadata")
public class AnalysisColumnMetadataController {

    private final AnalysisColumnMetadataRepository repository;
    private final LakehouseRegistryService registry;
    private final LakehouseBrowseService browse;

    public AnalysisColumnMetadataController(AnalysisColumnMetadataRepository repository,
                                            LakehouseRegistryService registry,
                                            LakehouseBrowseService browse) {
        this.repository = repository;
        this.registry = registry;
        this.browse = browse;
    }

    @GetMapping
    public List<ColumnMetadataResponse> list() {
        return repository.findAllByOrderByCatalogNameAscSchemaNameAscTableNameAscColumnNameAsc()
                .stream().map(ColumnMetadataResponse::from).toList();
    }

    @PutMapping
    public ColumnMetadataResponse upsert(@RequestBody UpsertColumnMetadataRequest req) {
        // Two checks, deliberately NOT registry.validateColumn(): that one
        // filters hidden columns out, so it would reject the very request
        // that flips a hidden column back to visible. Admin needs to address
        // any real column of a registered table, hidden or not.
        registry.validateRegistered(req.catalog(), req.schema(), req.table());
        browse.validateColumn(req.catalog(), req.schema(), req.table(), req.column());

        Long existingId = repository
                .findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                        req.catalog(), req.schema(), req.table(), req.column())
                .map(AnalysisColumnMetadata::getId)
                .orElse(null);
        AnalysisColumnMetadata saved = repository.save(new AnalysisColumnMetadata(
                existingId, new QualifiedColumn(req.catalog(), req.schema(), req.table(), req.column()),
                req.businessName(), req.fuzzyMatchable(), req.visible()));
        return ColumnMetadataResponse.from(saved);
    }

    public record UpsertColumnMetadataRequest(String catalog, String schema, String table, String column,
                                              String businessName, boolean fuzzyMatchable, Boolean visible) {
        /** Null {@code visible} reads as visible — see {@link AnalysisColumnMetadata}. */
        public UpsertColumnMetadataRequest {
            visible = !Boolean.FALSE.equals(visible);
        }
    }

    public record ColumnMetadataResponse(String catalog, String schema, String table, String column,
                                         String businessName, boolean fuzzyMatchable, boolean visible) {
        static ColumnMetadataResponse from(AnalysisColumnMetadata entity) {
            return new ColumnMetadataResponse(
                    entity.getCatalogName(), entity.getSchemaName(), entity.getTableName(),
                    entity.getColumnName(), entity.getBusinessName(),
                    entity.isFuzzyMatchable(), entity.isVisible());
        }
    }
}
