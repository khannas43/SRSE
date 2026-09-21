package gov.rajasthan.smart.srse.scheme;

import gov.rajasthan.smart.srse.compiler.Ast;
import gov.rajasthan.smart.srse.scenario.Scenario;
import gov.rajasthan.smart.srse.scenario.ScenarioService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Seeds nominated official criteria scenarios from {@code metadata/scheme-templates-seed.yml}.
 * Does not parse spreadsheets at boot — same pattern as {@link gov.rajasthan.smart.srse.metadata.FieldCatalogSeedRunner}.
 */
@Component
public class SchemeTemplateSeedRunner implements ApplicationRunner {

    private final SchemeRepository schemeRepository;
    private final ScenarioService scenarioService;
    private final ObjectMapper objectMapper;

    public SchemeTemplateSeedRunner(SchemeRepository schemeRepository,
                                    ScenarioService scenarioService,
                                    ObjectMapper objectMapper) {
        this.schemeRepository = schemeRepository;
        this.scenarioService = scenarioService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        for (TemplateSeedEntry entry : loadSeed()) {
            schemeRepository.findByCode(entry.schemeCode()).ifPresent(scheme -> {
                if (scheme.getTemplateScenarioId() != null) {
                    return;
                }
                try {
                    Ast.PredicateSpec spec = objectMapper.readValue(entry.rulesetJson(), Ast.PredicateSpec.class);
                    Scenario scenario = scenarioService.createScenario(
                            entry.scenarioName(), Set.of(scheme.getId()), spec);
                    scheme.setTemplateScenarioId(scenario.getId());
                    schemeRepository.save(scheme);
                } catch (IOException ex) {
                    throw new IllegalStateException("Invalid rulesetJson for scheme " + entry.schemeCode(), ex);
                }
            });
        }
    }

    private List<TemplateSeedEntry> loadSeed() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> loaded = loader.load(
                "scheme-templates-seed", new ClassPathResource("metadata/scheme-templates-seed.yml"));
        Iterable<ConfigurationPropertySource> sources = ConfigurationPropertySources.from(loaded);
        return new Binder(sources)
                .bind("templates", Bindable.listOf(TemplateSeedEntry.class))
                .orElse(List.of());
    }

    record TemplateSeedEntry(String schemeCode, String scenarioName, String rulesetJson) {
    }
}
