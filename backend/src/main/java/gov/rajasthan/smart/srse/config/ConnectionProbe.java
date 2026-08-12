package gov.rajasthan.smart.srse.config;

import javax.sql.DataSource;

/**
 * Verifies a candidate {@link DataSource} is actually reachable. Package-private
 * seam (same rationale as {@link ConnectionPoolFactory}) so the connection-editing
 * services are unit-testable without a real Presto/DB2.
 */
@FunctionalInterface
interface ConnectionProbe {
    /** Throws (any unchecked exception) if {@code ds} cannot be connected to. */
    void probe(DataSource ds);
}
