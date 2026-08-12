package gov.rajasthan.smart.srse.scheme;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    public SchemeController(SchemeRepository repository) {
        this.repository = repository;
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

    public record CreateSchemeRequest(String code, String name, String description) {
    }

    public record SchemeResponse(Long id, String code, String name, String description) {
        static SchemeResponse from(Scheme scheme) {
            return new SchemeResponse(scheme.getId(), scheme.getCode(), scheme.getName(), scheme.getDescription());
        }
    }
}
