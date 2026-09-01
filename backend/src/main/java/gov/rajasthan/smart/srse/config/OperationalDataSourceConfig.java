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
        // Must list EVERY package holding an operational-plane repository.
        // `lakehouse` is here for RegisteredTableRepository: that package is
        // mostly Presto-side (LakehouseBrowseService), but the registry of
        // admin-approved tables is SRSE's own configuration data and so lives
        // on the operational plane like the rest of the metadata.
        basePackages = {
                "gov.rajasthan.smart.srse.metadata",
                "gov.rajasthan.smart.srse.scenario",
                "gov.rajasthan.smart.srse.scheme",
                "gov.rajasthan.smart.srse.lakehouse"
        },
        entityManagerFactoryRef = "operationalEmf",
        transactionManagerRef = "operationalTx"
)
public class OperationalDataSourceConfig {

    /**
     * The operational plane's package list, in ONE place.
     *
     * <p>It has to appear twice — {@code @EnableJpaRepositories} needs
     * compile-time constants in its annotation, so that list is spelled out
     * literally above and this array feeds the entity scan below.
     * {@code OperationalDataSourceConfigTest} asserts the two agree and that
     * together they cover every {@code @Entity} and repository in the app.
     *
     * <p>That guard exists because the failure mode is silent in the worst
     * way: a package missing from the ENTITY list is not a startup error —
     * the table simply never gets created and every query against it fails at
     * runtime. (A package missing from the REPOSITORY list at least fails
     * loudly at startup, which is how the omission of {@code lakehouse} was
     * caught — on deploy, not by any test.)
     */
    static final String[] OPERATIONAL_PACKAGES = {
            "gov.rajasthan.smart.srse.metadata",
            "gov.rajasthan.smart.srse.scenario",
            "gov.rajasthan.smart.srse.scheme",
            "gov.rajasthan.smart.srse.lakehouse"
    };

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
                .packages(OPERATIONAL_PACKAGES)
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
