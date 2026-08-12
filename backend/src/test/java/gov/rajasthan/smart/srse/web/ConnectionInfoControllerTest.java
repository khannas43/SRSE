package gov.rajasthan.smart.srse.web;

import com.zaxxer.hikari.HikariDataSource;
import gov.rajasthan.smart.srse.security.MockJwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller-logic test for the read-only admin connections panel.
 * Security filters bypassed ({@code addFilters = false}) — same pattern as
 * {@link gov.rajasthan.smart.srse.scheme.SchemeControllerTest}.
 */
@WebMvcTest(ConnectionInfoController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
        "srse.datasource.analytical.jdbc-url=jdbc:presto://presto:8080/iceberg/srse",
        "srse.datasource.analytical.username=srse",
        "srse.datasource.analytical.driver-class-name=com.facebook.presto.jdbc.PrestoDriver"
})
class ConnectionInfoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HikariDataSource operational;

    @MockBean(name = "prestoJdbcTemplate")
    private JdbcTemplate presto;

    @MockBean
    private MockJwtService mockJwtService;

    @Test
    void reportsBothPlanesUpAndNeverLeaksCredentials() throws Exception {
        Connection conn = org.mockito.Mockito.mock(Connection.class);
        when(operational.getConnection()).thenReturn(conn);
        when(conn.isValid(3)).thenReturn(true);
        when(operational.getJdbcUrl()).thenReturn("jdbc:db2://db2:50000/SRSEDB");
        when(operational.getUsername()).thenReturn("db2inst1");
        when(operational.getDriverClassName()).thenReturn("com.ibm.db2.jcc.DB2Driver");
        when(presto.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        mockMvc.perform(get("/api/admin/connections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operational.status").value("up"))
                .andExpect(jsonPath("$.operational.jdbcUrl").value("jdbc:db2://db2:50000/SRSEDB"))
                .andExpect(jsonPath("$.analytical.status").value("up"))
                .andExpect(jsonPath("$.analytical.jdbcUrl").value("jdbc:presto://presto:8080/iceberg/srse"))
                .andExpect(jsonPath("$..password").doesNotExist());
    }

    @Test
    void reportsErrorStatusWhenAnalyticalPlaneUnreachable() throws Exception {
        Connection conn = org.mockito.Mockito.mock(Connection.class);
        when(operational.getConnection()).thenReturn(conn);
        when(conn.isValid(3)).thenReturn(true);
        when(presto.queryForObject(any(String.class), org.mockito.ArgumentMatchers.eq(Integer.class)))
                .thenThrow(new RuntimeException("connection refused"));

        mockMvc.perform(get("/api/admin/connections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analytical.status").value("error: connection refused"));
    }
}
