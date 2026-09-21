package gov.rajasthan.smart.srse.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.decision.DecisionController;
import gov.rajasthan.smart.srse.decision.DecisionExceptionHandler;
import gov.rajasthan.smart.srse.execution.ExecutionService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseAdminController;
import gov.rajasthan.smart.srse.lakehouse.LakehouseBrowseService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataController;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import gov.rajasthan.smart.srse.metadata.FieldCatalogController;
import gov.rajasthan.smart.srse.metadata.FieldCatalogRepository;
import gov.rajasthan.smart.srse.metadata.FieldColumnMappingController;
import gov.rajasthan.smart.srse.metadata.FieldColumnMappingRepository;
import gov.rajasthan.smart.srse.metadata.FieldColumnMappingService;
import gov.rajasthan.smart.srse.scenario.ScenarioService;
import gov.rajasthan.smart.srse.scheme.SchemeController;
import gov.rajasthan.smart.srse.scheme.SchemeRepository;
import gov.rajasthan.smart.srse.scheme.SchemeTemplateService;
import gov.rajasthan.smart.srse.security.Authorities;
import gov.rajasthan.smart.srse.security.MockJwtAuthenticationFilter;
import gov.rajasthan.smart.srse.security.MockJwtIssuer;
import gov.rajasthan.smart.srse.security.MockJwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end RBAC through {@link SecurityConfig} + {@link MockJwtAuthenticationFilter}.
 * Controller logic is stubbed; only HTTP status from authority checks matters here.
 */
@WebMvcTest(controllers = {
        DecisionController.class,
        MockJwtIssuer.class,
        LakehouseAdminController.class,
        FieldCatalogController.class,
        FieldColumnMappingController.class,
        AnalysisColumnMetadataController.class,
        SchemeController.class
})
@Import({DecisionExceptionHandler.class, SecurityConfig.class, MockJwtService.class, MockJwtAuthenticationFilter.class})
class SecurityConfigRbacTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MockJwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ExecutionService executionService;

    @MockBean
    private ScenarioService scenarioService;

    @MockBean
    private LakehouseBrowseService browse;

    @MockBean
    private LakehouseRegistryService registry;

    @MockBean
    private FieldCatalogRepository fieldCatalogRepository;

    @MockBean
    private FieldColumnMappingRepository mappingRepository;

    @MockBean
    private FieldColumnMappingService mappingService;

    @MockBean
    private AnalysisColumnMetadataRepository analysisColumnMetadataRepository;

    @MockBean
    private SchemeRepository schemeRepository;

    @MockBean
    private SchemeTemplateService schemeTemplateService;

    private String officerToken;
    private String adminToken;

    @BeforeEach
    void tokens() {
        officerToken = jwtService.issue("officer", List.of(Authorities.STATE_OFFICER));
        adminToken = jwtService.issue("admin", List.of(Authorities.SRSE_ADMIN, Authorities.STATE_OFFICER));
    }

    @BeforeEach
    void stubHappyPaths() {
        when(browse.listCatalogs()).thenReturn(List.of("iceberg"));
        when(fieldCatalogRepository.findAll()).thenReturn(List.of());
        when(analysisColumnMetadataRepository.findAllByOrderByCatalogNameAscSchemaNameAscTableNameAscColumnNameAsc())
                .thenReturn(List.of());
        when(scenarioService.listByScheme(any())).thenReturn(List.of());
        when(schemeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void officerForbiddenOnAdminLakehouseBrowse() throws Exception {
        mockMvc.perform(get("/api/admin/lakehouse/browse/catalogs")
                        .header("Authorization", "Bearer " + officerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void officerForbiddenOnPostMetadataFields() throws Exception {
        mockMvc.perform(post("/api/metadata/fields")
                        .header("Authorization", "Bearer " + officerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fieldKey":"officer_cannot_create","displayLabel":"X","tier":"TIER_1",\
                                "dataType":"NUMBER","groupName":"","allowedValues":[],"fuzzyMatchable":false}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void officerForbiddenOnPutMapping() throws Exception {
        mockMvc.perform(put("/api/metadata/mappings/age?dataMode=SYNTHETIC")
                        .header("Authorization", "Bearer " + officerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"physicalExpression\":\"synthetic.beneficiary.age_years\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void officerForbiddenOnPutColumnMetadata() throws Exception {
        mockMvc.perform(put("/api/analysis/column-metadata")
                        .header("Authorization", "Bearer " + officerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"catalog":"c","schema":"s","table":"t","column":"col",
                                "businessName":"X","fuzzyMatchable":false,"visible":true}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void officerPermittedOnGetMetadataFields() throws Exception {
        mockMvc.perform(get("/api/metadata/fields")
                        .header("Authorization", "Bearer " + officerToken))
                .andExpect(status().isOk());
    }

    @Test
    void officerPermittedOnGetColumnMetadata() throws Exception {
        mockMvc.perform(get("/api/analysis/column-metadata")
                        .header("Authorization", "Bearer " + officerToken))
                .andExpect(status().isOk());
    }

    @Test
    void officerForbiddenOnSetSchemeTemplate() throws Exception {
        mockMvc.perform(put("/api/schemes/1/template")
                        .header("Authorization", "Bearer " + officerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\": 5}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminPermittedOnSetSchemeTemplate() throws Exception {
        mockMvc.perform(put("/api/schemes/1/template")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\": 5}"))
                .andExpect(status().isOk());
    }

    @Test
    void officerPermittedOnPostSchemes() throws Exception {
        mockMvc.perform(post("/api/schemes")
                        .header("Authorization", "Bearer " + officerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"T1\",\"name\":\"Test\",\"description\":\"d\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void officerPermittedOnDecisionEndpoints() throws Exception {
        mockMvc.perform(get("/api/decision/scenarios").param("schemeId", "1")
                        .header("Authorization", "Bearer " + officerToken))
                .andExpect(status().isOk());
    }

    @Test
    void adminPermittedOnAdminAndOfficerEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/lakehouse/browse/catalogs")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/metadata/fields")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/decision/scenarios").param("schemeId", "1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void mockLoginAdminRoleIssuesTokenThatPassesAdminBrowse() throws Exception {
        String body = mockMvc.perform(post("/api/auth/mock-login").param("role", "admin"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(body).get("token").asText();

        mockMvc.perform(get("/api/admin/lakehouse/browse/catalogs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
