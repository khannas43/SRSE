package gov.rajasthan.smart.srse.config;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.UncategorizedSQLException;

import java.net.ConnectException;
import java.time.Duration;
import java.net.SocketException;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataAccessExceptionHandlerTest {

    private final DataAccessExceptionHandler handler = new DataAccessExceptionHandler();

    /**
     * The exact shape observed when the Presto container OOMed: the driver
     * reports a dropped connection as a generic UncategorizedSQLException
     * wrapping a SocketException — NOT as Spring's
     * DataAccessResourceFailureException. Matching on the Spring type alone
     * would miss the very case this handler exists for.
     */
    @Test
    void prestoDroppedConnectionIsServiceUnavailable() {
        SQLException sql = new SQLException("Error executing query", null, 0,
                new SocketException("Socket closed"));
        ResponseEntity<String> res = handler.dataAccessFailure(
                new UncategorizedSQLException("StatementCallback", "SELECT COUNT(*) FROM beneficiary", sql));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        assertTrue(res.getBody().contains("Lakehouse connection unavailable"), res.getBody());
        assertTrue(res.getBody().contains("Socket closed"), res.getBody());
    }

    @Test
    void refusedConnectionIsServiceUnavailable() {
        ResponseEntity<String> res = handler.dataAccessFailure(
                new DataAccessResourceFailureException("pool down", new ConnectException("Connection refused")));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        assertTrue(res.getBody().contains("Connection refused"), res.getBody());
    }

    /**
     * A query that actually RAN and was rejected is not an outage — it must stay
     * 500, but must carry the driver's message so it is diagnosable, which is
     * exactly what the previous bare "Internal Server Error" destroyed.
     */
    @Test
    void rejectedQueryStays500ButCarriesTheRealMessage() {
        SQLException sql = new SQLException("line 1:8: Column 'bogus' cannot be resolved");
        ResponseEntity<String> res = handler.dataAccessFailure(
                new UncategorizedSQLException("StatementCallback", "SELECT bogus FROM beneficiary", sql));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.getStatusCode());
        assertTrue(res.getBody().contains("cannot be resolved"), res.getBody());
        assertFalse(res.getBody().contains("Lakehouse connection unavailable"), res.getBody());
    }

    /** A null message must not surface as the string "null". */
    @Test
    void fallsBackToTheExceptionTypeWhenThereIsNoMessage() {
        ResponseEntity<String> res = handler.dataAccessFailure(
                new DataAccessResourceFailureException(null, new SocketException()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        assertTrue(res.getBody().contains("SocketException"), res.getBody());
    }

    /**
     * A CYCLIC cause chain must terminate. Throwable.initCause rejects
     * self-causation, but a two-node cycle is perfectly constructible — so an
     * identity check against the current node is not sufficient and the walk
     * needs a depth bound. Without one this test hangs rather than fails.
     */
    @Test
    void cyclicCauseChainDoesNotLoopForever() {
        SQLException a = new SQLException("first");
        SQLException b = new SQLException("second", a);
        a.initCause(b);   // a -> b -> a

        ResponseEntity<String> res =
                assertTimeoutPreemptively(Duration.ofSeconds(5),
                        () -> handler.dataAccessFailure(
                                new UncategorizedSQLException("task", "SELECT 1", a)));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.getStatusCode());
    }
}
