package gov.rajasthan.smart.srse.config;

import gov.rajasthan.smart.srse.decision.DecisionController;
import gov.rajasthan.smart.srse.decision.DecisionExceptionHandler;
import gov.rajasthan.smart.srse.execution.ExecutionService;
import gov.rajasthan.smart.srse.scenario.ScenarioService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Under srse.auth-mode=rajsewadwar, real SSO payload parsing is still an
 * unimplemented stub ({@code RajSewadwarAuthenticationFilter}) — confirms
 * this fails closed rather than silently permitting everything.
 */
@WebMvcTest(DecisionController.class)
@Import({DecisionExceptionHandler.class, SecurityConfig.class})
@TestPropertySource(properties = "srse.auth-mode=rajsewadwar")
class RajSewadwarAuthModeTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ExecutionService executionService;

    @MockBean
    private ScenarioService scenarioService;

    @Test
    void decisionEndpointRejectsEveryoneUntilRealSsoIsImplemented() throws Exception {
        // 403, not 401 — same default-anonymous-principal behavior as SecurityConfigTest.
        mockMvc.perform(get("/api/decision/scenarios").param("schemeId", "EKAL_NAARI"))
                .andExpect(status().isForbidden());
    }
}
