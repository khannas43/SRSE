package gov.rajasthan.smart.srse.config;

import javax.sql.DataSource;

/**
 * Builds a connection pool from admin-supplied settings. Package-private
 * seam so {@link AnalyticalConnectionService}/{@link OperationalConnectionService}
 * can be unit-tested with a fake pool instead of a real Presto/DB2 connection
 * — the project has no in-memory-DB test infra (see {@code FieldCatalogSeedRunnerTest}).
 */
@FunctionalInterface
interface ConnectionPoolFactory {
    DataSource create(String jdbcUrl, String username, String password, String driverClassName);
}
