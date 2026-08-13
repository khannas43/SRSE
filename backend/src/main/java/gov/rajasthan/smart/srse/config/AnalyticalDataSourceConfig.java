package gov.rajasthan.smart.srse.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ANALYTICAL PLANE — PrestoDB 0.297 over Iceberg via JDBC.
 *
 * Executes the beneficiary simulation query. Set-based, read-only, NO ORM.
 *
 * Driver: com.facebook.presto:presto-jdbc  (PrestoDB lineage — NOT Trino).
 * watsonx.data 2.3.1 exposes PrestoDB 0.297; connecting with the Trino driver
 * (io.trino:trino-jdbc) is a classic early failure. Do not switch drivers.
 *
 * Exposed as a {@link SwappableDataSource} wrapping the boot-time pool, so
 * {@link AnalyticalConnectionService} can swap the live connection pool
 * between requests with no restart — safe specifically because this plane is
 * plain {@code JdbcTemplate}, not JPA (see {@link SwappableDataSource}'s
 * javadoc for why the operational plane can't do the same). Never wire this
 * into an EntityManager.
 */
@Configuration
public class AnalyticalDataSourceConfig {

    @Value("${srse.datasource.analytical-ssl.enabled:false}")
    private boolean sslEnabled;

    @Value("${srse.datasource.analytical-ssl.trust-store-path:}")
    private String sslTrustStorePath;

    @Value("${srse.datasource.analytical-ssl.trust-store-password:}")
    private String sslTrustStorePassword;

    @Bean
    @ConfigurationProperties("srse.datasource.analytical")
    public HikariDataSource analyticalHikariDataSource() {
        // Presto JDBC URL, e.g. jdbc:presto://host:8080/iceberg/srse.
        // Full property binding here (not just url/user/password/driver) so
        // local-profile resilience tuning (initialization-fail-timeout,
        // connection-timeout — see application.yml) keeps applying at boot.
        HikariDataSource ds = DataSourceBuilder.create().type(HikariDataSource.class).build();
        applySslProperties(ds);
        return ds;
    }

    /**
     * SSL/TrustStore are Presto-JDBC-specific connection properties, added
     * only when actually enabled — the driver rejects SSLTrustStorePath as
     * soon as it's present at all, even blank, so these can't just default
     * to empty strings the way ordinary Hikari properties can (see
     * application.yml's srse.datasource.analytical-ssl comment).
     */
    private void applySslProperties(HikariDataSource ds) {
        if (!sslEnabled) {
            return;
        }
        ds.addDataSourceProperty("SSL", "true");
        if (!sslTrustStorePath.isBlank()) {
            ds.addDataSourceProperty("SSLTrustStorePath", sslTrustStorePath);
        }
        if (!sslTrustStorePassword.isBlank()) {
            ds.addDataSourceProperty("SSLTrustStorePassword", sslTrustStorePassword);
        }
    }

    @Bean
    public SwappableDataSource analyticalDataSource(
            // Explicit @Qualifier, not just parameter-name matching: the
            // operational DataSource bean is declared to return DataSource but
            // is @Primary and actually IS a HikariDataSource at runtime, so
            // Spring's by-type autowiring treats it as an ambiguous candidate
            // here too — and @Primary wins ties over name-matching, silently
            // wiring this to the WRONG pool without the qualifier. (Found by
            // booting the app: Presto queries were executing against DB2.)
            @Qualifier("analyticalHikariDataSource") HikariDataSource analyticalHikariDataSource) {
        return new SwappableDataSource(analyticalHikariDataSource);
    }

    @Bean(name = "prestoJdbcTemplate")
    public JdbcTemplate prestoJdbcTemplate(SwappableDataSource analyticalDataSource) {
        JdbcTemplate t = new JdbcTemplate(analyticalDataSource);
        // Query timeout (seconds) — guardrail; overridden from config in execution service.
        t.setQueryTimeout(30);
        return t;
    }

    /**
     * Builds a standalone Presto connection pool from just the fields the
     * admin connection-editor exposes ({@code jdbcUrl}/{@code username}/
     * {@code password}/{@code driverClassName}). Used by
     * {@link AnalyticalConnectionService}'s live-swap path — deliberately
     * simpler than the boot-time bean above: an admin-triggered swap
     * test-connects synchronously before taking effect, so the boot-time
     * resilience tuning (fail-fast timeouts for a not-yet-up container) isn't
     * relevant here.
     */
    static HikariDataSource buildPool(String jdbcUrl, String username, String password,
                                      String driverClassName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName(driverClassName);
        return new HikariDataSource(config);
    }
}
