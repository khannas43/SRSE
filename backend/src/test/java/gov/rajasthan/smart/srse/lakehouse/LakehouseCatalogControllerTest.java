package gov.rajasthan.smart.srse.lakehouse;

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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract for the OFFICER-facing cascade.
 *
 * <p>The load-bearing property here is one of reach, not shape: every endpoint
 * must answer from {@link LakehouseRegistryService}, never from
 * {@link LakehouseBrowseService}. If any of these ever consulted the live
 * cluster directly, an officer would be offered tables no admin registered —
 * which is precisely the boundary this controller exists to enforce. Hence the
 * {@code verifyNoInteractions(browse)} assertions rather than only checking JSON.
 */
@WebMvcTest(LakehouseCatalogController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(DecisionExceptionHandler.class)
class LakehouseCatalogControllerTest {

    private static final String CATALOG = "iceberg_data";
    private static final String SCHEMA = "jan_aadhar_data_txn";
    private static final String TABLE = "tbl_txn_bankdtl";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LakehouseRegistryService registry;

    @MockBean
    private LakehouseBrowseService browse;

    @MockBean
    private MockJwtService mockJwtService;

    @Test
    void catalogsComeFromTheRegistryNotTheLiveCluster() throws Exception {
        when(registry.listCatalogs()).thenReturn(List.of(CATALOG));

        mockMvc.perform(get("/api/analysis/lakehouse/catalogs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(CATALOG));

        verifyNoInteractions(browse);
    }

    @Test
    void schemasComeFromTheRegistry() throws Exception {
        when(registry.listSchemas(CATALOG)).thenReturn(List.of(SCHEMA));

        mockMvc.perform(get("/api/analysis/lakehouse/catalogs/{c}/schemas", CATALOG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(SCHEMA));

        verifyNoInteractions(browse);
    }

    /** The layer tag travels to the officer UI so Silver/Gold can be badged. */
    @Test
    void tablesCarryTheirLayerTag() throws Exception {
        when(registry.listTables(CATALOG, SCHEMA)).thenReturn(List.of(
                new RegisteredTable(1L, CATALOG, SCHEMA, TABLE, "SILVER"),
                new RegisteredTable(2L, CATALOG, SCHEMA, "tbl_txn_doc_engine", null)));

        mockMvc.perform(get("/api/analysis/lakehouse/catalogs/{c}/schemas/{s}/tables", CATALOG, SCHEMA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value(TABLE))
                .andExpect(jsonPath("$[0].layer").value("SILVER"))
                .andExpect(jsonPath("$[1].name").value("tbl_txn_doc_engine"))
                .andExpect(jsonPath("$[1].layer").doesNotExist());

        verify(registry).listTables(CATALOG, SCHEMA);
        verifyNoInteractions(browse);
    }

    @Test
    void columnsCarryBusinessNameAndFuzzyFlag() throws Exception {
        when(registry.listColumns(CATALOG, SCHEMA, TABLE)).thenReturn(List.of(
                new LakehouseRegistryService.RegisteredColumn(
                        "account_no", "varchar", "Account Number", true, true)));

        mockMvc.perform(get(
                        "/api/analysis/lakehouse/catalogs/{c}/schemas/{s}/tables/{t}/columns",
                        CATALOG, SCHEMA, TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("account_no"))
                .andExpect(jsonPath("$[0].dataType").value("varchar"))
                .andExpect(jsonPath("$[0].businessName").value("Account Number"))
                .andExpect(jsonPath("$[0].fuzzyMatchable").value(true));

        verify(registry).listColumns(CATALOG, SCHEMA, TABLE);
    }

    /** Asking for an unregistered table is a client error, not a server error. */
    @Test
    void columnsOfAnUnregisteredTableAreABadRequest() throws Exception {
        doThrow(new IllegalArgumentException("Table is not registered for SRSE: a.b.c"))
                .when(registry).listColumns(eq(CATALOG), eq(SCHEMA), eq("not_registered"));

        mockMvc.perform(get(
                        "/api/analysis/lakehouse/catalogs/{c}/schemas/{s}/tables/{t}/columns",
                        CATALOG, SCHEMA, "not_registered"))
                .andExpect(status().isBadRequest());
    }
}
