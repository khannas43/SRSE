package gov.rajasthan.smart.srse.lakehouse;

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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract for the ADMIN cascade — the endpoints that browse the live
 * lakehouse and persist registrations.
 *
 * <p>Worth testing separately from {@link LakehouseBrowseServiceTest}: the
 * service tests prove the introspection and allow-list logic, while these prove
 * the wiring the frontend actually depends on — that each rung of the cascade is
 * reachable at its URL, that path variables map to the right arguments in the
 * right order, and that a rejected identifier surfaces as 400 rather than 500.
 */
@WebMvcTest(LakehouseAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(DecisionExceptionHandler.class)
class LakehouseAdminControllerTest {

    private static final String CATALOG = "iceberg_data";
    private static final String SCHEMA = "jan_aadhar_data_txn";
    private static final String TABLE = "tbl_txn_bankdtl";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LakehouseBrowseService browse;

    @MockBean
    private LakehouseRegistryService registry;

    @MockBean
    private MockJwtService mockJwtService;

    @Test
    void browsesCatalogs() throws Exception {
        when(browse.listCatalogs()).thenReturn(List.of(CATALOG, "iceberg_gold"));

        mockMvc.perform(get("/api/admin/lakehouse/browse/catalogs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(CATALOG))
                .andExpect(jsonPath("$[1]").value("iceberg_gold"));
    }

    @Test
    void browsesSchemasOfOneCatalog() throws Exception {
        when(browse.listSchemas(CATALOG)).thenReturn(List.of(SCHEMA));

        mockMvc.perform(get("/api/admin/lakehouse/browse/catalogs/{c}/schemas", CATALOG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(SCHEMA));
    }

    /** Path variables must reach the service as (catalog, schema) — not swapped. */
    @Test
    void browsesTablesPassingCatalogAndSchemaInOrder() throws Exception {
        when(browse.listTables(CATALOG, SCHEMA)).thenReturn(List.of(TABLE));

        mockMvc.perform(get("/api/admin/lakehouse/browse/catalogs/{c}/schemas/{s}/tables", CATALOG, SCHEMA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(TABLE));

        verify(browse).listTables(CATALOG, SCHEMA);
    }

    @Test
    void browsesColumnsWithDataTypes() throws Exception {
        when(browse.listColumns(CATALOG, SCHEMA, TABLE)).thenReturn(List.of(
                new LakehouseBrowseService.ColumnInfo("bank_id", "bigint"),
                new LakehouseBrowseService.ColumnInfo("account_no", "varchar")));

        mockMvc.perform(get(
                        "/api/admin/lakehouse/browse/catalogs/{c}/schemas/{s}/tables/{t}/columns",
                        CATALOG, SCHEMA, TABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("bank_id"))
                .andExpect(jsonPath("$[0].dataType").value("bigint"))
                .andExpect(jsonPath("$[1].name").value("account_no"));

        verify(browse).listColumns(CATALOG, SCHEMA, TABLE);
    }

    @Test
    void listsRegistrationsWithQualifiedNameAndLayer() throws Exception {
        when(registry.listRegistrations()).thenReturn(List.of(
                new RegisteredTable(1L, CATALOG, SCHEMA, TABLE, "SILVER")));

        mockMvc.perform(get("/api/admin/lakehouse/registrations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].catalog").value(CATALOG))
                .andExpect(jsonPath("$[0].schema").value(SCHEMA))
                .andExpect(jsonPath("$[0].table").value(TABLE))
                .andExpect(jsonPath("$[0].layer").value("SILVER"))
                .andExpect(jsonPath("$[0].qualifiedName")
                        .value(CATALOG + "." + SCHEMA + "." + TABLE));
    }

    @Test
    void registersATable() throws Exception {
        when(registry.register(CATALOG, SCHEMA, TABLE, "silver"))
                .thenReturn(new RegisteredTable(3L, CATALOG, SCHEMA, TABLE, "SILVER"));

        mockMvc.perform(post("/api/admin/lakehouse/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalog\":\"" + CATALOG + "\",\"schema\":\"" + SCHEMA + "\","
                                + "\"table\":\"" + TABLE + "\",\"layer\":\"silver\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.layer").value("SILVER"));
    }

    @Test
    void registerRejectsATableThatIsNotInTheLiveLakehouse() throws Exception {
        doThrow(new IllegalArgumentException("Unknown table: " + CATALOG + "." + SCHEMA + ".tbl_typo"))
                .when(registry).register(eq(CATALOG), eq(SCHEMA), eq("tbl_typo"), eq(null));

        mockMvc.perform(post("/api/admin/lakehouse/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalog\":\"" + CATALOG + "\",\"schema\":\"" + SCHEMA + "\","
                                + "\"table\":\"tbl_typo\",\"layer\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unregistersById() throws Exception {
        mockMvc.perform(delete("/api/admin/lakehouse/registrations/{id}", 5))
                .andExpect(status().isOk());

        verify(registry).unregister(5L);
    }

    /** An illegal identifier must surface as 400, not leak out as a 500. */
    @Test
    void illegalCatalogIdentifierIsABadRequest() throws Exception {
        doThrow(new IllegalArgumentException("Illegal catalog identifier: bad name"))
                .when(browse).listSchemas("bad name");

        mockMvc.perform(get("/api/admin/lakehouse/browse/catalogs/{c}/schemas", "bad name"))
                .andExpect(status().isBadRequest());
    }
}
