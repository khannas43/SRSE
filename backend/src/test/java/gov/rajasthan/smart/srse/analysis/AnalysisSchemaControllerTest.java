package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.decision.DecisionExceptionHandler;
import gov.rajasthan.smart.srse.security.MockJwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalysisSchemaController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(DecisionExceptionHandler.class)
class AnalysisSchemaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnalysisSchemaService schemaService;

    @MockBean
    private MockJwtService mockJwtService;

    @Test
    void listsTables() throws Exception {
        when(schemaService.listTables()).thenReturn(List.of("beneficiary"));

        mockMvc.perform(get("/api/analysis/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("beneficiary"));
    }

    @Test
    void listsColumnsForTable() throws Exception {
        when(schemaService.listColumns("beneficiary"))
                .thenReturn(List.of(new AnalysisSchemaService.ColumnInfo("father_name", "varchar")));

        mockMvc.perform(get("/api/analysis/tables/beneficiary/columns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("father_name"))
                .andExpect(jsonPath("$[0].dataType").value("varchar"));
    }

    @Test
    void unknownTableReturns400() throws Exception {
        when(schemaService.listColumns("bogus")).thenThrow(new IllegalArgumentException("Unknown table: bogus"));

        mockMvc.perform(get("/api/analysis/tables/bogus/columns"))
                .andExpect(status().isBadRequest());
    }
}
