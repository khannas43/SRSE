package gov.rajasthan.smart.srse.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * OPERATIONAL PLANE — DB2 via IBM JCC + Spring Data JPA.
 *
 * Holds SRSE's own data: field catalogue, field->column mappings, rulesets
 * (AST as JSON), and scenario snapshots. This is the PRIMARY datasource.
 *
 * The analytical plane (Presto) is deliberately a SEPARATE, non-JPA datasource
 * — see {@link AnalyticalDataSourceConfig}. Never conflate the two.
 */
@Configuration
@EnableJpaRepositories(
        basePackages = {
                "gov.rajasthan.smart.srse.metadata",
                "gov.rajasthan.smart.srse.scenario",
                "gov.rajasthan.smart.srse.scheme"
        },
        entityManagerFactoryRef = "operationalEmf",
        transactionManagerRef = "operationalTx"
)
public class OperationalDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("srse.datasource.operational")
    public DataSource operationalDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    public LocalContainerEntityManagerFactoryBean operationalEmf(
            EntityManagerFactoryBuilder builder,
            @org.springframework.beans.factory.annotation.Value("${spring.jpa.hibernate.ddl-auto:none}")
            String ddlAuto) {
        Map<String, Object> props = new HashMap<>();
        // Manually-constructed EMF via EntityManagerFactoryBuilder does not inherit
        // spring.jpa.hibernate.ddl-auto the way Boot's auto-configured EMF would —
        // read and set hibernate.hbm2ddl.auto explicitly (default "none" = safe).
        props.put("hibernate.hbm2ddl.auto", ddlAuto);
        return builder
                .dataSource(operationalDataSource())
                .packages(
                        "gov.rajasthan.smart.srse.metadata",
                        "gov.rajasthan.smart.srse.scenario",
                        "gov.rajasthan.smart.srse.scheme")
                .persistenceUnit("operational")
                .properties(props)
                .build();
    }

    @Bean
    @Primary
    public PlatformTransactionManager operationalTx(
            @org.springframework.beans.factory.annotation.Qualifier("operationalEmf")
            EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
