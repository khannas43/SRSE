package gov.rajasthan.smart.srse.web;

import gov.rajasthan.smart.srse.decision.DecisionExceptionHandler;
import gov.rajasthan.smart.srse.metadata.DataMode;
import gov.rajasthan.smart.srse.metadata.FieldDataType;
import gov.rajasthan.smart.srse.metadata.FieldTier;
import gov.rajasthan.smart.srse.security.MockJwtService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminConfigController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(DecisionExceptionHandler.class)
class AdminConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminConfigService configService;

    @MockBean
    private MockJwtService mockJwtService;

    @Test
    void exportsConfigWithAttachmentHeader() throws Exception {
        AdminConfigBundle bundle = sampleBundle();
        when(configService.export()).thenReturn(bundle);

        mockMvc.perform(get("/api/admin/config/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.fieldCatalog[0].fieldKey").value("age_years"));
    }

    @Test
    void importsConfig() throws Exception {
        AdminConfigService.ImportResult result = new AdminConfigService.ImportResult(
                1, 1, 1, 1, 1, true,
                List.of("age_years"), List.of("age_years@LIVE"),
                List.of("iceberg.srse.beneficiary"), List.of("iceberg.srse.beneficiary.age_years"),
                List.of("PENSION_01"));
        when(configService.importConfig(any(), eq(new AdminConfigService.ImportOptions(true))))
                .thenReturn(result);

        mockMvc.perform(post("/api/admin/config/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "schemaVersion": "1.0",
                                  "exportedAt": "2026-09-11T00:00:00Z",
                                  "dataMode": "live",
                                  "connections": null,
                                  "fieldCatalog": [],
                                  "fieldColumnMappings": [],
                                  "registeredTables": [],
                                  "analysisColumnMetadata": [],
                                  "schemes": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fieldCatalogCount").value(1))
                .andExpect(jsonPath("$.operationalRestartRequired").value(true));

        verify(configService).importConfig(any(), eq(new AdminConfigService.ImportOptions(true)));
    }

    private static AdminConfigBundle sampleBundle() {
        return new AdminConfigBundle(
                "1.0",
                Instant.parse("2026-09-11T00:00:00Z"),
                "synthetic",
                null,
                List.of(new AdminConfigBundle.FieldCatalogEntry(
                        "age_years", "Age", FieldTier.TIER_1, FieldDataType.NUMBER,
                        "Demographic", List.of(), false)),
                List.of(new AdminConfigBundle.FieldColumnMappingEntry(
                        "age_years", DataMode.LIVE, "iceberg.srse.beneficiary.age_years")),
                List.of(new AdminConfigBundle.RegisteredTableEntry(
                        "iceberg", "srse", "beneficiary", "GOLD")),
                List.of(),
                List.of(new AdminConfigBundle.SchemeEntry("PENSION_01", "Pension", "Test")));
    }
}
