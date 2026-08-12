package gov.rajasthan.smart.srse.config;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
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

    private volatile DataSource delegate;

    public SwappableDataSource(DataSource initial) {
        this.delegate = initial;
    }

    public DataSource getDelegate() {
        return delegate;
    }

    public void swap(DataSource next) {
        this.delegate = next;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return delegate.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return delegate.getConnection(username, password);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }
}
