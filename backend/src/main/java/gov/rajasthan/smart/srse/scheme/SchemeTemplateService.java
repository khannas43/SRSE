package gov.rajasthan.smart.srse.scheme;

import gov.rajasthan.smart.srse.compiler.Ast;
import gov.rajasthan.smart.srse.scenario.Scenario;
import gov.rajasthan.smart.srse.scenario.ScenarioNotFoundException;
import gov.rajasthan.smart.srse.scenario.ScenarioService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheme official criteria — a nominated {@link gov.rajasthan.smart.srse.scenario.Scenario},
 * not a parallel ruleset store.
 */
@Service
public class SchemeTemplateService {

    private final SchemeRepository schemeRepository;
    private final ScenarioService scenarioService;

    public SchemeTemplateService(SchemeRepository schemeRepository, ScenarioService scenarioService) {
        this.schemeRepository = schemeRepository;
        this.scenarioService = scenarioService;
    }

    public Ast.PredicateSpec loadTemplateRuleset(Long schemeId) {
        Scheme scheme = schemeRepository.findById(schemeId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown scheme: " + schemeId));
        Long templateId = scheme.getTemplateScenarioId();
        if (templateId == null) {
            return null;
        }
        return scenarioService.loadRuleset(templateId);
    }

    @Transactional
    public void setTemplate(Long schemeId, Long scenarioId) {
        Scheme scheme = schemeRepository.findById(schemeId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown scheme: " + schemeId));
        Scenario scenario;
        try {
            scenario = scenarioService.getScenario(scenarioId);
        } catch (ScenarioNotFoundException ex) {
            throw new IllegalArgumentException("Unknown scenario: " + scenarioId);
        }
        if (!scenario.getSchemeIds().contains(schemeId)) {
            throw new IllegalArgumentException(
                    "Scenario " + scenarioId + " is not tagged to scheme " + schemeId);
        }
        scheme.setTemplateScenarioId(scenarioId);
        schemeRepository.save(scheme);
    }

    @Transactional
    public void clearTemplateIfScenarioDeleted(Long scenarioId) {
        schemeRepository.findByTemplateScenarioId(scenarioId).ifPresent(scheme -> {
            scheme.setTemplateScenarioId(null);
            schemeRepository.save(scheme);
        });
    }
}
