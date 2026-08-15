package gov.rajasthan.smart.srse.metadata;

import gov.rajasthan.smart.srse.compiler.FieldResolver;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * Officer-facing field catalogue API — read drives the rule builder's grouped
 * parameter palette (Tab 1); create/update is the admin "register a new
 * lakehouse-backed field" flow (Tab 2). Physical column bindings are never
 * exposed here; see {@link FieldColumnMappingController} for those.
 */
@RestController
@RequestMapping("/api/metadata")
public class FieldCatalogController {

    private final FieldCatalogRepository repository;

    public FieldCatalogController(FieldCatalogRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/fields")
    public List<FieldCatalogEntryResponse> listFields() {
        return repository.findAll().stream()
                .filter(FieldCatalogEntry::isActive)
                .map(FieldCatalogEntryResponse::from)
                .toList();
    }

    @PostMapping("/fields")
    public FieldCatalogEntryResponse create(@RequestBody FieldCatalogRequest req) {
        FieldCatalogEntry saved = repository.save(new FieldCatalogEntry(new FieldCatalogEntry.FieldCatalogEntryData(
                null, req.fieldKey(), req.displayLabel(), req.tier(), req.dataType(),
                req.groupName(), joinAllowedValues(req.allowedValues()), true, req.fuzzyMatchable())));
        return FieldCatalogEntryResponse.from(saved);
    }

    @PutMapping("/fields/{fieldKey}")
    public FieldCatalogEntryResponse update(@PathVariable String fieldKey, @RequestBody FieldCatalogRequest req) {
        FieldCatalogEntry existing = repository.findByFieldKey(fieldKey)
                .orElseThrow(() -> new FieldResolver.UnknownFieldException(fieldKey));
        FieldCatalogEntry saved = repository.save(new FieldCatalogEntry(new FieldCatalogEntry.FieldCatalogEntryData(
                existing.getId(), fieldKey, req.displayLabel(), req.tier(), req.dataType(),
                req.groupName(), joinAllowedValues(req.allowedValues()), existing.isActive(), req.fuzzyMatchable())));
        return FieldCatalogEntryResponse.from(saved);
    }

    @DeleteMapping("/fields/{fieldKey}")
    public void deactivate(@PathVariable String fieldKey) {
        FieldCatalogEntry existing = repository.findByFieldKey(fieldKey)
                .orElseThrow(() -> new FieldResolver.UnknownFieldException(fieldKey));
        repository.save(new FieldCatalogEntry(new FieldCatalogEntry.FieldCatalogEntryData(
                existing.getId(), existing.getFieldKey(), existing.getDisplayLabel(), existing.getTier(),
                existing.getDataType(), existing.getGroupName(), existing.getAllowedValues(), false,
                existing.isFuzzyMatchable())));
    }

    private static String joinAllowedValues(List<String> allowedValues) {
        return allowedValues == null || allowedValues.isEmpty() ? null : String.join(",", allowedValues);
    }

    public record FieldCatalogRequest(
            String fieldKey,
            String displayLabel,
            FieldTier tier,
            FieldDataType dataType,
            String groupName,
            List<String> allowedValues,
            boolean fuzzyMatchable) {
    }

    public record FieldCatalogEntryResponse(
            Long id,
            String fieldKey,
            String displayLabel,
            FieldTier tier,
            FieldDataType dataType,
            String groupName,
            List<String> allowedValues,
            boolean fuzzyMatchable) {

        static FieldCatalogEntryResponse from(FieldCatalogEntry entry) {
            List<String> allowedValues = entry.getAllowedValues() == null
                    ? List.of()
                    : Arrays.asList(entry.getAllowedValues().split(","));
            return new FieldCatalogEntryResponse(
                    entry.getId(), entry.getFieldKey(), entry.getDisplayLabel(),
                    entry.getTier(), entry.getDataType(), entry.getGroupName(), allowedValues,
                    entry.isFuzzyMatchable());
        }
    }
}
