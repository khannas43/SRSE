package gov.rajasthan.smart.srse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * SRSE — Scheme Rule Simulation Engine backend.
 *
 * Standalone Java 17 / Spring Boot 3.x microservice. Two data planes:
 *   - operational (DB2 / JPA)  — see {@code config.OperationalDataSourceConfig}
 *   - analytical  (Presto / JDBC) — see {@code config.AnalyticalDataSourceConfig}
 */
@SpringBootApplication
@EnableCaching
public class SrseApplication {
    public static void main(String[] args) {
        SpringApplication.run(SrseApplication.class, args);
    }
}
