package gov.rajasthan.smart.srse.config;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * A {@link DataSource} that delegates every call to a swappable underlying
 * pool. Lets {@link AnalyticalConnectionService} replace the live Presto
 * connection pool between requests with no restart — safe here specifically
 * because the analytical plane is plain {@code JdbcTemplate} (open a
 * connection, run one query, close it), unlike the operational/JPA plane
 * where an {@code EntityManagerFactory} can't be hot-swapped this way.
 */
public class SwappableDataSource implements DataSource {

    private final AtomicReference<DataSource> delegate;

    public SwappableDataSource(DataSource initial) {
        this.delegate = new AtomicReference<>(initial);
    }

    public DataSource getDelegate() {
        return delegate.get();
    }

    public void swap(DataSource next) {
        this.delegate.set(next);
    }

    private DataSource current() {
        return delegate.get();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return current().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return current().getConnection(username, password);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return current().getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        current().setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        current().setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return current().getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return current().getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return current().unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return current().isWrapperFor(iface);
    }
}
