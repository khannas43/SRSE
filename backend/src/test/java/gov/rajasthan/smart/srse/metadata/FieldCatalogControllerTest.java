package gov.rajasthan.smart.srse.metadata;

import gov.rajasthan.smart.srse.decision.DecisionExceptionHandler;
import gov.rajasthan.smart.srse.security.MockJwtService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller-logic tests for the field-catalogue API. Security filters
 * are bypassed ({@code addFilters = false}), same pattern as
 * {@link gov.rajasthan.smart.srse.scheme.SchemeControllerTest}.
 */
@WebMvcTest(FieldCatalogController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(DecisionExceptionHandler.class)
class FieldCatalogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FieldCatalogRepository repository;

    @MockBean
    private MockJwtService mockJwtService;

    @Test
    void listFieldsExcludesInactiveEntriesAndSplitsAllowedValues() throws Exception {
        FieldCatalogEntry active = new FieldCatalogEntry(new FieldCatalogEntry.FieldCatalogEntryData(
                1L, "district", "District", FieldTier.TIER_1, FieldDataType.STRING,
                "Demographic", "Jaipur,Jodhpur", true, false));
        FieldCatalogEntry inactive = new FieldCatalogEntry(new FieldCatalogEntry.FieldCatalogEntryData(
                2L, "legacy_field", "Legacy", FieldTier.TIER_1, FieldDataType.STRING,
                "Demographic", null, false, false));
        when(repository.findAll()).thenReturn(List.of(active, inactive));

        mockMvc.perform(get("/api/metadata/fields"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fieldKey").value("district"))
                .andExpect(jsonPath("$[0].groupName").value("Demographic"))
                .andExpect(jsonPath("$[0].allowedValues[0]").value("Jaipur"))
                .andExpect(jsonPath("$[0].allowedValues[1]").value("Jodhpur"));
    }

    @Test
    void createPersistsNewField() throws Exception {
        when(repository.save(any())).thenAnswer(inv -> {
            FieldCatalogEntry e = inv.getArgument(0);
            return new FieldCatalogEntry(new FieldCatalogEntry.FieldCatalogEntryData(
                    9L, e.getFieldKey(), e.getDisplayLabel(), e.getTier(),
                    e.getDataType(), e.getGroupName(), e.getAllowedValues(), e.isActive(), e.isFuzzyMatchable()));
        });

        String body = """
                {"fieldKey":"land_holding_sqyd","displayLabel":"Land (sq yd)","tier":"TIER_1",
                 "dataType":"NUMBER","groupName":"Assets","allowedValues":[]}
                """;

        mockMvc.perform(post("/api/metadata/fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.fieldKey").value("land_holding_sqyd"));
    }

    @Test
    void createPersistsFuzzyMatchableFlag() throws Exception {
        when(repository.save(any())).thenAnswer(inv -> {
            FieldCatalogEntry e = inv.getArgument(0);
            return new FieldCatalogEntry(new FieldCatalogEntry.FieldCatalogEntryData(
                    10L, e.getFieldKey(), e.getDisplayLabel(), e.getTier(),
                    e.getDataType(), e.getGroupName(), e.getAllowedValues(), e.isActive(), e.isFuzzyMatchable()));
        });

        String body = """
                {"fieldKey":"father_name","displayLabel":"Father's name","tier":"TIER_1",
                 "dataType":"STRING","groupName":"Family & Relationship","allowedValues":[],
                 "fuzzyMatchable":true}
                """;

        mockMvc.perform(post("/api/metadata/fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fieldKey").value("father_name"))
                .andExpect(jsonPath("$.fuzzyMatchable").value(true));
    }

    @Test
    void deactivateFlipsActiveFalseAndPreservesOtherFields() throws Exception {
        FieldCatalogEntry existing = new FieldCatalogEntry(new FieldCatalogEntry.FieldCatalogEntryData(
                3L, "class_passed", "Highest Class Passed", FieldTier.TIER_1, FieldDataType.STRING,
                "Education", "NURSERY,KG", true, false));
        when(repository.findByFieldKey("class_passed")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(delete("/api/metadata/fields/class_passed"))
                .andExpect(status().isOk());

        ArgumentCaptor<FieldCatalogEntry> captor = ArgumentCaptor.forClass(FieldCatalogEntry.class);
        verify(repository).save(captor.capture());
        FieldCatalogEntry saved = captor.getValue();
        assertFalse(saved.isActive());
        assertEquals("class_passed", saved.getFieldKey());
        assertEquals("Highest Class Passed", saved.getDisplayLabel());
    }

    @Test
    void deactivateUnknownFieldReturns400() throws Exception {
        when(repository.findByFieldKey("bogus_field")).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/metadata/fields/bogus_field"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateUnknownFieldReturns400() throws Exception {
        when(repository.findByFieldKey("bogus_field")).thenReturn(Optional.empty());

        String body = """
                {"displayLabel":"x","tier":"TIER_1","dataType":"NUMBER","groupName":"Assets","allowedValues":[]}
                """;

        mockMvc.perform(put("/api/metadata/fields/bogus_field")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
