package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.decision.DecisionExceptionHandler;
import gov.rajasthan.smart.srse.security.MockJwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RecordMatchController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(DecisionExceptionHandler.class)
class RecordMatchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RecordMatchService matchService;

    @MockBean
    private MockJwtService mockJwtService;

    @Test
    void matchReturnsCompiledResult() throws Exception {
        when(matchService.match(any())).thenReturn(new RecordMatchResponse(
                List.of("source_district", "target_district"),
                List.of(Map.of("source_district", "Jaipur", "target_district", "Jaipur")),
                "SELECT ... LIMIT 1000", false));

        String body = """
                {"sourceCriteria":[{"table":"beneficiary","column":"district","fuzzyThresholdPercent":null}],
                 "targetCriteria":[{"table":"beneficiary","column":"district","fuzzyThresholdPercent":null}],
                 "highlightDuplicates":false,
                 "dedup":null,"ageFilter":null}
                """;

        mockMvc.perform(post("/api/analysis/match")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0]").value("source_district"))
                .andExpect(jsonPath("$.rows[0].source_district").value("Jaipur"))
                .andExpect(jsonPath("$.capped").value(false));
    }

    @Test
    void invalidRequestReturns400() throws Exception {
        when(matchService.match(any())).thenThrow(new IllegalArgumentException("bad request"));

        String body = """
                {"sourceCriteria":[{"table":"beneficiary","column":"district","fuzzyThresholdPercent":null}],
                 "targetCriteria":[],
                 "highlightDuplicates":false,
                 "dedup":null,"ageFilter":null}
                """;

        mockMvc.perform(post("/api/analysis/match")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
