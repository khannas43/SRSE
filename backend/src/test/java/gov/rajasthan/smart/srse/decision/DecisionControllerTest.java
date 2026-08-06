package gov.rajasthan.smart.srse.decision;

import gov.rajasthan.smart.srse.compiler.Ast;
import gov.rajasthan.smart.srse.execution.BreakdownRow;
import gov.rajasthan.smart.srse.execution.ExecutionService;
import gov.rajasthan.smart.srse.scenario.Scenario;
import gov.rajasthan.smart.srse.scenario.ScenarioNotFoundException;
import gov.rajasthan.smart.srse.scenario.ScenarioService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller-logic tests for the decision-service seam.
 * Security filters are bypassed ({@code addFilters = false}); auth is verified
 * separately via curl against a running instance.
 */
@WebMvcTest(DecisionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(DecisionExceptionHandler.class)
class DecisionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ExecutionService executionService;

    @MockBean
    private ScenarioService scenarioService;

    @Test
    void evaluateWithBreakdownReturns200AndCorrectShape() throws Exception {
        Scenario created = new Scenario(
                42L, "age-gte-18", "EKAL_NAARI",
                "{\"root\":{}}", null, null,
                Instant.parse("2026-01-15T10:00:00Z"), null);
        List<BreakdownRow> breakdown = List.of(
                new BreakdownRow("JAIPUR", "F", "18-59", 100L));

        when(scenarioService.createScenario(eq("age-gte-18"), eq("EKAL_NAARI"), any(Ast.PredicateSpec.class)))
                .thenReturn(created);
        when(executionService.count(any(Ast.PredicateSpec.class))).thenReturn(100L);
        when(executionService.breakdown(any(Ast.PredicateSpec.class))).thenReturn(breakdown);
        when(scenarioService.recordResults(eq(42L), eq(100L), eq(breakdown))).thenReturn(created);

        // Jackson-polymorphic AST: type discriminator on the Node proves end-to-end
        // HTTP→PredicateSpec deserialization (not just unit-level ObjectMapper).
        String body = """
                {
                  "schemeId": "EKAL_NAARI",
                  "name": "age-gte-18",
                  "includeBreakdown": true,
                  "ruleset": {
                    "root": {
                      "type": "PREDICATE",
                      "fieldKey": "age_years",
                      "operator": "GTE",
                      "value": 18
                    }
                  }
                }
                """;

        mockMvc.perform(post("/api/decision/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarioId").value(42))
                .andExpect(jsonPath("$.totalCount").value(100))
                .andExpect(jsonPath("$.breakdown[0].district").value("JAIPUR"))
                .andExpect(jsonPath("$.breakdown[0].gender").value("F"))
                .andExpect(jsonPath("$.breakdown[0].ageBand").value("18-59"))
                .andExpect(jsonPath("$.breakdown[0].count").value(100));

        verify(scenarioService).createScenario(eq("age-gte-18"), eq("EKAL_NAARI"), any(Ast.PredicateSpec.class));
        verify(executionService).count(any(Ast.PredicateSpec.class));
        verify(executionService).breakdown(any(Ast.PredicateSpec.class));
        verify(scenarioService).recordResults(eq(42L), eq(100L), eq(breakdown));
    }

    @Test
    void getScenarioUnknownIdReturns404WithMessage() throws Exception {
        when(scenarioService.getScenario(999L))
                .thenThrow(new ScenarioNotFoundException(999L));

        mockMvc.perform(get("/api/decision/scenarios/999"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Scenario not found: 999")));
    }

    @Test
    void compareUnevaluatedReturns409() throws Exception {
        when(scenarioService.compare(anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("Scenario 1 has not been evaluated yet"));

        mockMvc.perform(get("/api/decision/compare")
                        .param("a", "1")
                        .param("b", "2"))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("has not been evaluated yet")));
    }
}
