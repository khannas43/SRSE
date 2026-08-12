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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AnalyticalConnectionService} using the package-private
 * {@link ConnectionPoolFactory}/{@link ConnectionProbe} seams instead of a
 * real Presto connection — mirrors {@link gov.rajasthan.smart.srse.scenario.ScenarioServiceTest}'s
 * mocked-collaborator style.
 */
@ExtendWith(MockitoExtension.class)
class AnalyticalConnectionServiceTest {

    @Mock
    private ConnectionOverrideStore overrideStore;

    private DataSource initialPool;
    private SwappableDataSource swappable;

    @BeforeEach
    void setUp() {
        initialPool = mock(DataSource.class);
        swappable = new SwappableDataSource(initialPool);
    }

    @Test
    void successfulUpdateSwapsPoolAndPersistsOverride() {
        DataSource newPool = mock(DataSource.class);
        AnalyticalConnectionService service = new AnalyticalConnectionService(
                swappable, overrideStore,
                (url, user, pass, driver) -> newPool,
                ds -> { /* probe succeeds */ });

        service.update("jdbc:presto://presto:8080/iceberg/srse", "srse", "", "com.facebook.presto.jdbc.PrestoDriver");

        assertSame(newPool, swappable.getDelegate());

        ArgumentCaptor<Properties> captor = ArgumentCaptor.forClass(Properties.class);
        verify(overrideStore).save(captor.capture());
        assertEquals("jdbc:presto://presto:8080/iceberg/srse", captor.getValue().getProperty("analytical.jdbc-url"));
        assertEquals("srse", captor.getValue().getProperty("analytical.username"));
    }

    @Test
    void failedProbeLeavesLivePoolAndOverrideFileUntouched() {
        DataSource badPool = mock(DataSource.class);
        AnalyticalConnectionService service = new AnalyticalConnectionService(
                swappable, overrideStore,
                (url, user, pass, driver) -> badPool,
                ds -> { throw new RuntimeException("connection refused"); });

        assertThrows(ConnectionTestFailedException.class, () ->
                service.update("jdbc:presto://bad-host:8080/iceberg/srse", "srse", "", "com.facebook.presto.jdbc.PrestoDriver"));

        assertSame(initialPool, swappable.getDelegate());
        verify(overrideStore, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void poolConstructionFailureIsMappedNotRawPropagated() {
        // Regression test: Hikari can throw synchronously from pool
        // construction itself (e.g. PoolInitializationException on an
        // unreachable host), not just from the probe — a real bug found by
        // booting the app against a bad DB2 port and getting a raw 500.
        AnalyticalConnectionService service = new AnalyticalConnectionService(
                swappable, overrideStore,
                (url, user, pass, driver) -> { throw new RuntimeException("PoolInitializationException"); },
                ds -> { /* never reached */ });

        assertThrows(ConnectionTestFailedException.class, () ->
                service.update("jdbc:presto://bad-host:9999/iceberg/srse", "srse", "", "com.facebook.presto.jdbc.PrestoDriver"));

        assertSame(initialPool, swappable.getDelegate());
        verify(overrideStore, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void oldPoolIsClosedOnSuccessfulSwap() throws Exception {
        CloseableDataSource oldPool = mock(CloseableDataSource.class);
        SwappableDataSource swappableWithCloseablePool = new SwappableDataSource(oldPool);
        DataSource newPool = mock(DataSource.class);
        AnalyticalConnectionService service = new AnalyticalConnectionService(
                swappableWithCloseablePool, overrideStore,
                (url, user, pass, driver) -> newPool,
                ds -> { /* probe succeeds */ });

        service.update("jdbc:presto://presto:8080/iceberg/srse", "srse", "", "com.facebook.presto.jdbc.PrestoDriver");

        verify(oldPool).close();
    }

    /** DataSource + AutoCloseable, so Mockito can produce a mock satisfying both. */
    private interface CloseableDataSource extends DataSource, AutoCloseable {
        @Override
        void close();
    }
}
