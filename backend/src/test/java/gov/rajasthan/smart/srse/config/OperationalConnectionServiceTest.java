package gov.rajasthan.smart.srse.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link OperationalConnectionService} using the
 * package-private {@link ConnectionPoolFactory}/{@link ConnectionProbe} seams
 * instead of a real DB2 connection.
 */
@ExtendWith(MockitoExtension.class)
class OperationalConnectionServiceTest {

    @Mock
    private ConnectionOverrideStore overrideStore;

    private CloseableDataSource candidate;

    @BeforeEach
    void setUp() {
        candidate = mock(CloseableDataSource.class);
    }

    @Test
    void successfulProbePersistsOverrideAndNeverTouchesLiveDatasource() {
        OperationalConnectionService service = new OperationalConnectionService(
                overrideStore, (url, user, pass, driver) -> candidate, ds -> { /* probe succeeds */ });

        service.update("jdbc:db2://db2:50000/SRSEDB", "db2inst1", "pw", "com.ibm.db2.jcc.DB2Driver");

        ArgumentCaptor<Properties> captor = ArgumentCaptor.forClass(Properties.class);
        verify(overrideStore).save(captor.capture());
        assertEquals("jdbc:db2://db2:50000/SRSEDB", captor.getValue().getProperty("operational.jdbc-url"));
        assertEquals("db2inst1", captor.getValue().getProperty("operational.username"));

        // The throwaway test pool is always closed, success or failure.
        verify(candidate).close();
    }

    @Test
    void failedProbePersistsNothing() {
        OperationalConnectionService service = new OperationalConnectionService(
                overrideStore, (url, user, pass, driver) -> candidate,
                ds -> { throw new RuntimeException("connection refused"); });

        assertThrows(ConnectionTestFailedException.class, () ->
                service.update("jdbc:db2://bad-host:50000/SRSEDB", "db2inst1", "pw", "com.ibm.db2.jcc.DB2Driver"));

        verify(overrideStore, never()).save(any());
        verify(candidate).close();
    }

    @Test
    void poolConstructionFailureIsMappedNotRawPropagated() {
        // Regression test: this is the exact bug found by booting the app —
        // Hikari's PoolInitializationException is thrown synchronously from
        // pool construction (a bad port), before any explicit probe() call,
        // and was bypassing the try/catch, surfacing as a raw 500 instead of
        // a mapped 400.
        OperationalConnectionService service = new OperationalConnectionService(
                overrideStore,
                (url, user, pass, driver) -> { throw new RuntimeException("PoolInitializationException"); },
                ds -> { /* never reached */ });

        assertThrows(ConnectionTestFailedException.class, () ->
                service.update("jdbc:db2://db2:59999/SRSEDB", "db2inst1", "pw", "com.ibm.db2.jcc.DB2Driver"));

        verify(overrideStore, never()).save(any());
    }

    /** DataSource + AutoCloseable, so Mockito can produce a mock satisfying both. */
    private interface CloseableDataSource extends DataSource, AutoCloseable {
        @Override
        void close();
    }
}
