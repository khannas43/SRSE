package gov.rajasthan.smart.srse.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.decision.DecisionController;
import gov.rajasthan.smart.srse.decision.DecisionExceptionHandler;
import gov.rajasthan.smart.srse.execution.ExecutionService;
import gov.rajasthan.smart.srse.scenario.ScenarioService;
import gov.rajasthan.smart.srse.security.MockJwtIssuer;
import gov.rajasthan.smart.srse.security.MockJwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unlike {@link gov.rajasthan.smart.srse.decision.DecisionControllerTest}
 * (which disables filters to test controller logic in isolation), this runs
 * the real {@link SecurityConfig} filter chain to confirm the RBAC seam
 * actually works: /api/decision/** rejects unauthenticated requests and
 * accepts a token issued by {@link MockJwtIssuer}.
 */
@WebMvcTest(controllers = {DecisionController.class, MockJwtIssuer.class})
@Import({DecisionExceptionHandler.class, SecurityConfig.class, MockJwtService.class})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ExecutionService executionService;

    @MockBean
    private ScenarioService scenarioService;

    @Test
    void decisionEndpointRejectsRequestsWithoutToken() throws Exception {
        // Spring Security's default anonymous principal lacks STATE_OFFICER,
        // so hasAuthority(...) denies with 403 (not 401 — no custom
        // AuthenticationEntryPoint is configured).
        mockMvc.perform(get("/api/decision/scenarios").param("schemeId", "1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void mockLoginThenDecisionEndpointSucceeds() throws Exception {
        String body = mockMvc.perform(post("/api/auth/mock-login"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(body).get("token").asText();

        mockMvc.perform(get("/api/decision/scenarios")
                        .param("schemeId", "1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
