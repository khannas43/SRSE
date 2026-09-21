package gov.rajasthan.smart.srse.scheme;

import gov.rajasthan.smart.srse.compiler.Ast;
import gov.rajasthan.smart.srse.scenario.Scenario;
import gov.rajasthan.smart.srse.scenario.ScenarioService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemeTemplateServiceTest {

    @Mock
    private SchemeRepository schemeRepository;
    @Mock
    private ScenarioService scenarioService;

    @InjectMocks
    private SchemeTemplateService service;

    @Test
    void setTemplateRejectedWhenScenarioNotTaggedToScheme() {
        Scheme scheme = new Scheme(1L, "X", "X", null, true, Instant.now());
        Scenario scenario = new Scenario(new Scenario.ScenarioData(
                9L, "s", Set.of(2L), "{}", null, null, Instant.now(), null));
        when(schemeRepository.findById(1L)).thenReturn(Optional.of(scheme));
        when(scenarioService.getScenario(9L)).thenReturn(scenario);

        assertThrows(IllegalArgumentException.class, () -> service.setTemplate(1L, 9L));
        verify(schemeRepository, never()).save(any());
    }
}
