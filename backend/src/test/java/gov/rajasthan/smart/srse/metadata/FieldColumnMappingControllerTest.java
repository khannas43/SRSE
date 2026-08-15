package gov.rajasthan.smart.srse.metadata;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller-logic tests for the admin field-mapping API. Security
 * filters bypassed ({@code addFilters = false}) — same pattern as
 * {@link gov.rajasthan.smart.srse.scheme.SchemeControllerTest}.
 */
@WebMvcTest(FieldColumnMappingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(DecisionExceptionHandler.class)
class FieldColumnMappingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FieldCatalogRepository catalogRepository;

    @MockBean
    private FieldColumnMappingRepository mappingRepository;

    @MockBean
    private FieldColumnMappingService mappingService;

    @MockBean
    private MockJwtService mockJwtService;

    @Test
    void listShowsNullPhysicalExpressionWhenFieldHasNoMappingYet() throws Exception {
        FieldCatalogEntry mapped = new FieldCatalogEntry(new FieldCatalogEntry.FieldCatalogEntryData(
                1L, "age_years", "Age", FieldTier.TIER_1, FieldDataType.NUMBER, "Demographic", null, true, false));
        FieldCatalogEntry unmapped = new FieldCatalogEntry(new FieldCatalogEntry.FieldCatalogEntryData(
                2L, "has_vehicle", "Owns a vehicle", FieldTier.TIER_1, FieldDataType.BOOLEAN, "Assets", null, true, false));
        when(catalogRepository.findAll()).thenReturn(List.of(mapped, unmapped));
        when(mappingRepository.findByFieldKeyAndDataMode("age_years", DataMode.LIVE))
                .thenReturn(Optional.of(new FieldColumnMapping(10L, "age_years", DataMode.LIVE, "golden.age_years")));
        when(mappingRepository.findByFieldKeyAndDataMode("has_vehicle", DataMode.LIVE))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/metadata/mappings").param("dataMode", "LIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fieldKey").value("age_years"))
                .andExpect(jsonPath("$[0].physicalExpression").value("golden.age_years"))
                .andExpect(jsonPath("$[1].fieldKey").value("has_vehicle"))
                .andExpect(jsonPath("$[1].physicalExpression").doesNotExist());
    }

    @Test
    void upsertDelegatesToServiceAndReturnsUpdatedRow() throws Exception {
        FieldCatalogEntry entry = new FieldCatalogEntry(new FieldCatalogEntry.FieldCatalogEntryData(
                1L, "age_years", "Age", FieldTier.TIER_1, FieldDataType.NUMBER, "Demographic", null, true, false));
        when(catalogRepository.findByFieldKey("age_years")).thenReturn(Optional.of(entry));
        when(mappingService.upsert(eq("age_years"), eq(DataMode.LIVE), eq("golden.age_years")))
                .thenReturn(new FieldColumnMapping(10L, "age_years", DataMode.LIVE, "golden.age_years"));

        mockMvc.perform(put("/api/metadata/mappings/age_years")
                        .param("dataMode", "LIVE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"physicalExpression\": \"golden.age_years\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.physicalExpression").value("golden.age_years"));

        verify(mappingService).upsert("age_years", DataMode.LIVE, "golden.age_years");
    }

    @Test
    void upsertUnknownFieldReturns400() throws Exception {
        when(catalogRepository.findByFieldKey("bogus_field")).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/metadata/mappings/bogus_field")
                        .param("dataMode", "LIVE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"physicalExpression\": \"x\"}"))
                .andExpect(status().isBadRequest());
    }
}
