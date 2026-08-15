package gov.rajasthan.smart.srse.metadata;

import gov.rajasthan.smart.srse.analysis.AnalysisSchemaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin API for the Analysis tab's business-name/fuzzy-matchable overrides
 * per physical {@code table.column} — see {@link AnalysisColumnMetadata}'s
 * javadoc for why this is separate from the Rule Engine's field catalogue.
 */
@RestController
@RequestMapping("/api/analysis/column-metadata")
public class AnalysisColumnMetadataController {

    private final AnalysisColumnMetadataRepository repository;
    private final AnalysisSchemaService schemaService;

    public AnalysisColumnMetadataController(AnalysisColumnMetadataRepository repository,
                                            AnalysisSchemaService schemaService) {
        this.repository = repository;
        this.schemaService = schemaService;
    }

    @GetMapping
    public List<ColumnMetadataResponse> list() {
        return repository.findAll().stream()
                .map(ColumnMetadataResponse::from)
                .toList();
    }

    @PutMapping
    public ColumnMetadataResponse upsert(@RequestBody UpsertColumnMetadataRequest req) {
        // Same live-schema allow-list discipline as everywhere else in this
        // tab — table/column are validated before anything is persisted.
        schemaService.validateColumn(req.table(), req.column());

        Long existingId = repository.findByTableNameAndColumnName(req.table(), req.column())
                .map(AnalysisColumnMetadata::getId)
                .orElse(null);
        AnalysisColumnMetadata saved = repository.save(new AnalysisColumnMetadata(
                existingId, req.table(), req.column(), req.businessName(), req.fuzzyMatchable()));
        return ColumnMetadataResponse.from(saved);
    }

    public record UpsertColumnMetadataRequest(String table, String column, String businessName,
                                               boolean fuzzyMatchable) {
    }

    public record ColumnMetadataResponse(String table, String column, String businessName,
                                          boolean fuzzyMatchable) {
        static ColumnMetadataResponse from(AnalysisColumnMetadata entity) {
            return new ColumnMetadataResponse(
                    entity.getTableName(), entity.getColumnName(),
                    entity.getBusinessName(), entity.isFuzzyMatchable());
        }
    }
}
