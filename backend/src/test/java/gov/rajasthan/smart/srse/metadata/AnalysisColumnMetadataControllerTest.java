package gov.rajasthan.smart.srse.metadata;

import gov.rajasthan.smart.srse.analysis.AnalysisSchemaService;
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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalysisColumnMetadataController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(DecisionExceptionHandler.class)
class AnalysisColumnMetadataControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnalysisColumnMetadataRepository repository;

    @MockBean
    private AnalysisSchemaService schemaService;

    @MockBean
    private MockJwtService mockJwtService;

    @Test
    void listReturnsAllRegisteredEntries() throws Exception {
        when(repository.findAll()).thenReturn(List.of(
                new AnalysisColumnMetadata(1L, "beneficiary", "father_name", "Father's Name", true)));

        mockMvc.perform(get("/api/analysis/column-metadata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].table").value("beneficiary"))
                .andExpect(jsonPath("$[0].column").value("father_name"))
                .andExpect(jsonPath("$[0].businessName").value("Father's Name"))
                .andExpect(jsonPath("$[0].fuzzyMatchable").value(true));
    }

    @Test
    void upsertValidatesAgainstLiveSchemaThenSaves() throws Exception {
        when(repository.findByTableNameAndColumnName("beneficiary", "guardian")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> {
            AnalysisColumnMetadata e = inv.getArgument(0);
            return new AnalysisColumnMetadata(9L, e.getTableName(), e.getColumnName(),
                    e.getBusinessName(), e.isFuzzyMatchable());
        });

        mockMvc.perform(put("/api/analysis/column-metadata")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"table\":\"beneficiary\",\"column\":\"guardian\","
                                + "\"businessName\":\"Guardian\",\"fuzzyMatchable\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName").value("Guardian"))
                .andExpect(jsonPath("$.fuzzyMatchable").value(true));

        verify(schemaService).validateColumn("beneficiary", "guardian");
    }

    @Test
    void upsertRejectsUnknownColumn() throws Exception {
        doThrow(new IllegalArgumentException("Unknown column: beneficiary.bogus"))
                .when(schemaService).validateColumn(eq("beneficiary"), eq("bogus"));

        mockMvc.perform(put("/api/analysis/column-metadata")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"table\":\"beneficiary\",\"column\":\"bogus\","
                                + "\"businessName\":null,\"fuzzyMatchable\":false}"))
                .andExpect(status().isBadRequest());
    }
}
