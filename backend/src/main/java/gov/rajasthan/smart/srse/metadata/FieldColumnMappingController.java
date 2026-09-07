package gov.rajasthan.smart.srse.metadata;

import gov.rajasthan.smart.srse.compiler.FieldResolver;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin API for the field-catalogue's physical bindings (Tab 2's "link
 * lakehouse table names" panel). Every active catalogue field is listed for
 * the requested {@link DataMode} even if it has no mapping yet, so the admin
 * UI can prompt for one instead of only showing what's already bound.
 */
@RestController
@RequestMapping("/api/metadata/mappings")
public class FieldColumnMappingController {

    private final FieldCatalogRepository catalogRepository;
    private final FieldColumnMappingRepository mappingRepository;
    private final FieldColumnMappingService mappingService;

    public FieldColumnMappingController(FieldCatalogRepository catalogRepository,
                                        FieldColumnMappingRepository mappingRepository,
                                        FieldColumnMappingService mappingService) {
        this.catalogRepository = catalogRepository;
        this.mappingRepository = mappingRepository;
        this.mappingService = mappingService;
    }

    @GetMapping
    public List<MappingRowResponse> list(@RequestParam DataMode dataMode) {
        return catalogRepository.findAll().stream()
                .filter(FieldCatalogEntry::isActive)
                .map(entry -> {
                    String physicalExpression = mappingRepository
                            .findByFieldKeyAndDataMode(entry.getFieldKey(), dataMode)
                            .map(FieldColumnMapping::getPhysicalExpression)
                            .orElse(null);
                    return new MappingRowResponse(entry.getFieldKey(), entry.getDisplayLabel(),
                            physicalExpression, FieldColumnMapping.tableOf(physicalExpression));
                })
                .toList();
    }

    @PutMapping("/{fieldKey}")
    public MappingRowResponse upsert(@PathVariable String fieldKey,
                                     @RequestParam DataMode dataMode,
                                     @RequestBody UpsertMappingRequest req) {
        FieldCatalogEntry entry = catalogRepository.findByFieldKey(fieldKey)
                .orElseThrow(() -> new FieldResolver.UnknownFieldException(fieldKey));
        FieldColumnMapping saved = mappingService.upsert(fieldKey, dataMode, req.physicalExpression());
        return new MappingRowResponse(entry.getFieldKey(), entry.getDisplayLabel(),
                saved.getPhysicalExpression(), FieldColumnMapping.tableOf(saved.getPhysicalExpression()));
    }

    /**
     * Unbinds the field for this environment. The field stays in the
     * catalogue and the other environment's binding is untouched — see
     * {@link FieldColumnMappingService#delete}.
     */
    @DeleteMapping("/{fieldKey}")
    public void delete(@PathVariable String fieldKey, @RequestParam DataMode dataMode) {
        catalogRepository.findByFieldKey(fieldKey)
                .orElseThrow(() -> new FieldResolver.UnknownFieldException(fieldKey));
        mappingService.delete(fieldKey, dataMode);
    }

    public record UpsertMappingRequest(String physicalExpression) {
    }

    /**
     * @param tableName the table this binding selects FROM, or null when the
     *                  binding is an expression. Travels to the Admin page so
     *                  it can warn when one environment's fields disagree about
     *                  the table — see {@link FieldColumnMapping#tableOf}.
     */
    public record MappingRowResponse(String fieldKey, String displayLabel, String physicalExpression,
                                     String tableName) {
    }
}
