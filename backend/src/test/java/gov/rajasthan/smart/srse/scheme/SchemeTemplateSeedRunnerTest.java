package gov.rajasthan.smart.srse.scheme;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.compiler.Ast;
import gov.rajasthan.smart.srse.scenario.Scenario;
import gov.rajasthan.smart.srse.scenario.ScenarioService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemeTemplateSeedRunnerTest {

    @Mock
    private SchemeRepository schemeRepository;
    @Mock
    private ScenarioService scenarioService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void skipsWhenTemplateAlreadySet() throws Exception {
        Scheme scheme = new Scheme(1L, "EKAL_NAARI", "N", null, true, Instant.now(), 99L);
        when(schemeRepository.findByCode("EKAL_NAARI")).thenReturn(Optional.of(scheme));

        new SchemeTemplateSeedRunner(schemeRepository, scenarioService, objectMapper)
                .run(new DefaultApplicationArguments(new String[0]));

        verify(scenarioService, never()).createScenario(any(), any(), any());
    }

    @Test
    void seedsOnceWhenTemplateNull() throws Exception {
        Scheme scheme = new Scheme(1L, "EKAL_NAARI", "N", null, true, Instant.now(), null);
        when(schemeRepository.findByCode("EKAL_NAARI")).thenReturn(Optional.of(scheme));
        when(scenarioService.createScenario(any(), eq(Set.of(1L)), any(Ast.PredicateSpec.class)))
                .thenReturn(new Scenario(new Scenario.ScenarioData(
                        5L, "Official criteria (seed)", Set.of(1L), "{}", null, null, Instant.now(), null)));
        when(schemeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        new SchemeTemplateSeedRunner(schemeRepository, scenarioService, objectMapper)
                .run(new DefaultApplicationArguments(new String[0]));

        ArgumentCaptor<Scheme> saved = ArgumentCaptor.forClass(Scheme.class);
        verify(schemeRepository).save(saved.capture());
        assertEquals(5L, saved.getValue().getTemplateScenarioId());
    }
}
