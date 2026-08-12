package gov.rajasthan.smart.srse.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * Live-edits the Analytical (Presto) connection: test-connects a new pool,
 * and only on success swaps it into the running {@link SwappableDataSource}
 * — no restart. Safe because this plane is plain {@code JdbcTemplate}, never
 * an {@code EntityManagerFactory}; see {@link SwappableDataSource}'s javadoc.
 */
@Service
public class AnalyticalConnectionService {

    private final SwappableDataSource dataSource;
    private final ConnectionOverrideStore overrideStore;
    private final ConnectionPoolFactory poolFactory;
    private final ConnectionProbe connectionProbe;

    @Autowired
    public AnalyticalConnectionService(SwappableDataSource analyticalDataSource,
                                       ConnectionOverrideStore overrideStore) {
        this(analyticalDataSource, overrideStore, AnalyticalDataSourceConfig::buildPool,
                ds -> new JdbcTemplate(ds).queryForObject("SELECT 1", Integer.class));
    }

    /** Package-private: lets tests substitute a fake pool + probe instead of hitting real Presto. */
    AnalyticalConnectionService(SwappableDataSource analyticalDataSource, ConnectionOverrideStore overrideStore,
                                ConnectionPoolFactory poolFactory, ConnectionProbe connectionProbe) {
        this.dataSource = analyticalDataSource;
        this.overrideStore = overrideStore;
        this.poolFactory = poolFactory;
        this.connectionProbe = connectionProbe;
    }

    public void update(String jdbcUrl, String username, String password, String driverClassName) {
        DataSource candidate = null;
        try {
            // Pool construction itself can throw synchronously (e.g. Hikari's
            // PoolInitializationException on an unreachable host) — not just
            // the explicit probe() call — so both must be inside this
            // try/catch to map to ConnectionTestFailedException instead of a
            // raw 500.
            candidate = poolFactory.create(jdbcUrl, username, password, driverClassName);
            connectionProbe.probe(candidate);
        } catch (Exception e) {
            closeQuietly(candidate);
            throw new ConnectionTestFailedException("analytical", e.getMessage());
        }

        DataSource previous = dataSource.getDelegate();
        dataSource.swap(candidate);
        closeQuietly(previous);

        Properties props = new Properties();
        props.setProperty("analytical.jdbc-url", jdbcUrl);
        props.setProperty("analytical.username", username);
        props.setProperty("analytical.password", password);
        props.setProperty("analytical.driver-class-name", driverClassName);
        overrideStore.save(props);
    }

    private static void closeQuietly(DataSource ds) {
        if (ds instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // best-effort cleanup of a superseded/rejected pool
            }
        }
    }
}
