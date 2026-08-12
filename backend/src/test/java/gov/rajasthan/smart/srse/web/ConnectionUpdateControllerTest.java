package gov.rajasthan.smart.srse.web;

import gov.rajasthan.smart.srse.config.AnalyticalConnectionService;
import gov.rajasthan.smart.srse.config.ConnectionExceptionHandler;
import gov.rajasthan.smart.srse.config.ConnectionTestFailedException;
import gov.rajasthan.smart.srse.config.OperationalConnectionService;
import gov.rajasthan.smart.srse.security.MockJwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller-logic tests for the connection-editing API. Security
 * filters bypassed ({@code addFilters = false}) — same pattern as
 * {@link ConnectionInfoControllerTest}.
 */
@WebMvcTest(ConnectionUpdateController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ConnectionExceptionHandler.class)
class ConnectionUpdateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnalyticalConnectionService analyticalConnectionService;

    @MockBean
    private OperationalConnectionService operationalConnectionService;

    @MockBean
    private MockJwtService mockJwtService;

    private static final String BODY = """
            {"jdbcUrl": "jdbc:presto://presto:8080/iceberg/srse", "username": "srse",
             "password": "secret", "driverClassName": "com.facebook.presto.jdbc.PrestoDriver"}
            """;

    @Test
    void analyticalUpdateAppliesImmediatelyAndNeverEchoesPassword() throws Exception {
        mockMvc.perform(put("/api/admin/connections/analytical")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restartRequired").value(false))
                .andExpect(jsonPath("$.plane.status").value("up"))
                .andExpect(jsonPath("$.plane.jdbcUrl").value("jdbc:presto://presto:8080/iceberg/srse"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));

        verify(analyticalConnectionService).update(
                eq("jdbc:presto://presto:8080/iceberg/srse"), eq("srse"), eq("secret"),
                eq("com.facebook.presto.jdbc.PrestoDriver"));
    }

    @Test
    void operationalUpdateReturnsRestartRequiredAndNeverEchoesPassword() throws Exception {
        String operationalBody = """
                {"jdbcUrl": "jdbc:db2://db2:50000/SRSEDB", "username": "db2inst1",
                 "password": "secret", "driverClassName": "com.ibm.db2.jcc.DB2Driver"}
                """;

        mockMvc.perform(put("/api/admin/connections/operational")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(operationalBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restartRequired").value(true))
                .andExpect(jsonPath("$.plane").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));

        verify(operationalConnectionService).update(
                eq("jdbc:db2://db2:50000/SRSEDB"), eq("db2inst1"), eq("secret"), eq("com.ibm.db2.jcc.DB2Driver"));
    }

    @Test
    void failedTestConnectReturns400() throws Exception {
        doThrow(new ConnectionTestFailedException("analytical", "connection refused"))
                .when(analyticalConnectionService).update(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        mockMvc.perform(put("/api/admin/connections/analytical")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("connection refused")));
    }
}
