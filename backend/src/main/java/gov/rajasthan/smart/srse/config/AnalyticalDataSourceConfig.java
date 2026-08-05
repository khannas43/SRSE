package gov.rajasthan.smart.srse.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * ANALYTICAL PLANE — PrestoDB 0.297 over Iceberg via JDBC.
 *
 * Executes the beneficiary simulation query. Set-based, read-only, NO ORM.
 *
 * Driver: com.facebook.presto:presto-jdbc  (PrestoDB lineage — NOT Trino).
 * watsonx.data 2.3.1 exposes PrestoDB 0.297; connecting with the Trino driver
 * (io.trino:trino-jdbc) is a classic early failure. Do not switch drivers.
 *
 * Exposed ONLY as a dedicated JdbcTemplate ("prestoJdbcTemplate"). It is not a
 * JPA datasource and must never be wired into an EntityManager.
 */
@Configuration
public class AnalyticalDataSourceConfig {

    @Bean
    @ConfigurationProperties("srse.datasource.analytical")
    public DataSource analyticalDataSource() {
        // Presto JDBC URL, e.g. jdbc:presto://host:8080/iceberg/srse
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean(name = "prestoJdbcTemplate")
    public JdbcTemplate prestoJdbcTemplate() {
        JdbcTemplate t = new JdbcTemplate(analyticalDataSource());
        // Query timeout (seconds) — guardrail; overridden from config in execution service.
        t.setQueryTimeout(30);
        return t;
    }
}
