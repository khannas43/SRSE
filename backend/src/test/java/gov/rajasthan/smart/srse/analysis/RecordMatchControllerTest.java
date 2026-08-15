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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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

    private static final String REQUEST_BODY = """
            {"sourceCriteria":[{"table":"beneficiary","column":"district","fuzzyThresholdPercent":null}],
             "targetCriteria":[{"table":"beneficiary","column":"district","fuzzyThresholdPercent":null}],
             "highlightDuplicates":false,
             "dedup":null,"ageFilter":null}
            """;

    @Test
    void matchStreamsNdjsonBody() throws Exception {
        StreamingResponseBody body = out -> {
            out.write(("{\"type\":\"meta\",\"columns\":[\"source_district\",\"target_district\"],"
                    + "\"sql\":\"SELECT ...\"}\n").getBytes(StandardCharsets.UTF_8));
            out.write(("{\"type\":\"row\",\"data\":{\"source_district\":\"Jaipur\","
                    + "\"target_district\":\"Jaipur\"}}\n").getBytes(StandardCharsets.UTF_8));
            out.write("{\"type\":\"done\",\"totalRows\":1}\n".getBytes(StandardCharsets.UTF_8));
        };
        when(matchService.match(any())).thenReturn(body);

        MvcResult mvcResult = mockMvc.perform(post("/api/analysis/match")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"type\":\"meta\"")))
                .andExpect(content().string(containsString("\"source_district\":\"Jaipur\"")))
                .andExpect(content().string(containsString("\"type\":\"done\"")));
    }

    @Test
    void invalidRequestReturns400() throws Exception {
        // Validation throws synchronously, before any StreamingResponseBody is
        // even returned — so this stays a plain, non-async 400, unchanged from
        // the pre-streaming controller contract.
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
