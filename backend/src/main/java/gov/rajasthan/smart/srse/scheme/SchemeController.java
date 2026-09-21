package gov.rajasthan.smart.srse.scheme;

import gov.rajasthan.smart.srse.compiler.Ast;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Officer-facing scheme registry — schemes are the tags saved rulesets
 * (scenario) attach to. Deliberately minimal: full admin (edit/deactivate)
 * is out of scope until the Tab 2 admin surface lands.
 */
@RestController
@RequestMapping("/api/schemes")
public class SchemeController {

    private final SchemeRepository repository;
    private final SchemeTemplateService templateService;

    public SchemeController(SchemeRepository repository, SchemeTemplateService templateService) {
        this.repository = repository;
        this.templateService = templateService;
    }

    @GetMapping
    public List<SchemeResponse> list() {
        return repository.findByActiveTrueOrderByName().stream()
                .map(SchemeResponse::from)
                .toList();
    }

    @PostMapping
    public SchemeResponse create(@RequestBody CreateSchemeRequest req) {
        Scheme scheme = new Scheme(null, req.code(), req.name(), req.description(), true, Instant.now());
        return SchemeResponse.from(repository.save(scheme));
    }

    /** Official criteria for a scheme — empty body when none nominated yet. */
    @GetMapping("/{id}/template")
    public ResponseEntity<SchemeTemplateResponse> getTemplate(@PathVariable Long id) {
        Ast.PredicateSpec ruleset = templateService.loadTemplateRuleset(id);
        if (ruleset == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(new SchemeTemplateResponse(ruleset));
    }

    /** Admin-only (see SecurityConfig): nominate a saved scenario as official criteria. */
    @PutMapping("/{id}/template")
    public void setTemplate(@PathVariable Long id, @RequestBody SetSchemeTemplateRequest req) {
        templateService.setTemplate(id, req.scenarioId());
    }

    public record CreateSchemeRequest(String code, String name, String description) {
    }

    public record SetSchemeTemplateRequest(Long scenarioId) {
    }

    public record SchemeTemplateResponse(Ast.PredicateSpec ruleset) {
    }

    public record SchemeResponse(Long id, String code, String name, String description) {
        static SchemeResponse from(Scheme scheme) {
            return new SchemeResponse(scheme.getId(), scheme.getCode(), scheme.getName(), scheme.getDescription());
        }
    }
}
