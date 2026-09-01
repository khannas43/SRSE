package gov.rajasthan.smart.srse.metadata;

import gov.rajasthan.smart.srse.decision.DecisionExceptionHandler;
import gov.rajasthan.smart.srse.lakehouse.LakehouseBrowseService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.lakehouse.QualifiedColumn;
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

    private static final String CATALOG = "iceberg_data";
    private static final String SCHEMA = "jan_aadhar_data_txn";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnalysisColumnMetadataRepository repository;

    @MockBean
    private LakehouseRegistryService registry;

    @MockBean
    private LakehouseBrowseService browse;

    @MockBean
    private MockJwtService mockJwtService;

    @Test
    void listReturnsAllRegisteredEntries() throws Exception {
        when(repository.findAllByOrderByCatalogNameAscSchemaNameAscTableNameAscColumnNameAsc())
                .thenReturn(List.of(new AnalysisColumnMetadata(
                        1L, new QualifiedColumn(CATALOG, SCHEMA, "tbl_txn_bankdtl", "bank_id"),
                        "Bank ID", true, true)));

        mockMvc.perform(get("/api/analysis/column-metadata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].catalog").value(CATALOG))
                .andExpect(jsonPath("$[0].schema").value(SCHEMA))
                .andExpect(jsonPath("$[0].table").value("tbl_txn_bankdtl"))
                .andExpect(jsonPath("$[0].column").value("bank_id"))
                .andExpect(jsonPath("$[0].businessName").value("Bank ID"))
                .andExpect(jsonPath("$[0].fuzzyMatchable").value(true))
                .andExpect(jsonPath("$[0].visible").value(true));
    }

    @Test
    void upsertChecksRegistrationAndLiveSchemaThenSaves() throws Exception {
        when(repository.findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                CATALOG, SCHEMA, "tbl_txn_bankdtl", "account_no")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> {
            AnalysisColumnMetadata e = inv.getArgument(0);
            return new AnalysisColumnMetadata(9L, e.toQualifiedColumn(), e.getBusinessName(),
                    e.isFuzzyMatchable(), e.isVisible());
        });

        mockMvc.perform(put("/api/analysis/column-metadata")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalog\":\"" + CATALOG + "\",\"schema\":\"" + SCHEMA + "\","
                                + "\"table\":\"tbl_txn_bankdtl\",\"column\":\"account_no\","
                                + "\"businessName\":\"Account Number\",\"fuzzyMatchable\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName").value("Account Number"))
                .andExpect(jsonPath("$.fuzzyMatchable").value(true))
                .andExpect(jsonPath("$.visible").value(true));

        verify(registry).validateRegistered(CATALOG, SCHEMA, "tbl_txn_bankdtl");
        verify(browse).validateColumn(CATALOG, SCHEMA, "tbl_txn_bankdtl", "account_no");
    }

    /**
     * Admin must be able to address a column they previously HID, otherwise
     * hiding one would be irreversible from the UI — hence the controller
     * checks browse+registration rather than registry.validateColumn, which
     * filters hidden columns out.
     */
    @Test
    void upsertCanUnhideAPreviouslyHiddenColumn() throws Exception {
        when(repository.findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                CATALOG, SCHEMA, "tbl_txn_bankdtl", "m_id"))
                .thenReturn(Optional.of(new AnalysisColumnMetadata(
                        4L, new QualifiedColumn(CATALOG, SCHEMA, "tbl_txn_bankdtl", "m_id"),
                        null, false, false)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/api/analysis/column-metadata")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalog\":\"" + CATALOG + "\",\"schema\":\"" + SCHEMA + "\","
                                + "\"table\":\"tbl_txn_bankdtl\",\"column\":\"m_id\","
                                + "\"businessName\":null,\"fuzzyMatchable\":false,\"visible\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visible").value(true));
    }

    @Test
    void upsertRejectsColumnOfAnUnregisteredTable() throws Exception {
        doThrow(new IllegalArgumentException(
                "Table is not registered for SRSE: " + CATALOG + ".other_schema.tbl_txn_doc_engine"))
                .when(registry).validateRegistered(eq(CATALOG), eq("other_schema"), eq("tbl_txn_doc_engine"));

        mockMvc.perform(put("/api/analysis/column-metadata")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalog\":\"" + CATALOG + "\",\"schema\":\"other_schema\","
                                + "\"table\":\"tbl_txn_doc_engine\",\"column\":\"m_id\","
                                + "\"businessName\":null,\"fuzzyMatchable\":false}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upsertRejectsUnknownColumn() throws Exception {
        doThrow(new IllegalArgumentException("Unknown column: " + CATALOG + "." + SCHEMA + ".tbl_txn_bankdtl.bogus"))
                .when(browse).validateColumn(eq(CATALOG), eq(SCHEMA), eq("tbl_txn_bankdtl"), eq("bogus"));

        mockMvc.perform(put("/api/analysis/column-metadata")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalog\":\"" + CATALOG + "\",\"schema\":\"" + SCHEMA + "\","
                                + "\"table\":\"tbl_txn_bankdtl\",\"column\":\"bogus\","
                                + "\"businessName\":null,\"fuzzyMatchable\":false}"))
                .andExpect(status().isBadRequest());
    }
}
