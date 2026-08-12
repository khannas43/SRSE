package gov.rajasthan.smart.srse.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Applies a persisted connection override (if one exists) as the
 * highest-precedence property source, BEFORE {@code @ConfigurationProperties}
 * binding happens — so {@code OperationalDataSourceConfig} /
 * {@code AnalyticalDataSourceConfig} need no code changes to pick it up, and
 * env-var config keeps working exactly as before when no override file
 * exists.
 *
 * <p>Registered via {@code META-INF/spring.factories} (the mechanism Boot 3.x
 * still uses for {@link EnvironmentPostProcessor} — the newer
 * {@code AutoConfiguration.imports} file is specific to {@code @AutoConfiguration}
 * classes, not this SPI).
 *
 * <p>Runs before the Spring context exists, so it constructs its own
 * {@link ConnectionOverrideStore} directly rather than injecting the
 * {@code @Component}-managed one the runtime update services use.
 */
public class ConnectionOverrideEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String DEFAULT_PATH = "connection-overrides.properties";

    /** override-file key -> Spring property key it feeds. */
    private static final Map<String, String> KEY_MAPPING = Map.ofEntries(
            Map.entry("operational.jdbc-url", "srse.datasource.operational.jdbc-url"),
            Map.entry("operational.username", "srse.datasource.operational.username"),
            Map.entry("operational.password", "srse.datasource.operational.password"),
            Map.entry("operational.driver-class-name", "srse.datasource.operational.driver-class-name"),
            Map.entry("analytical.jdbc-url", "srse.datasource.analytical.jdbc-url"),
            Map.entry("analytical.username", "srse.datasource.analytical.username"),
            Map.entry("analytical.password", "srse.datasource.analytical.password"),
            Map.entry("analytical.driver-class-name", "srse.datasource.analytical.driver-class-name")
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String path = environment.getProperty("srse.connection-override-path", DEFAULT_PATH);
        new ConnectionOverrideStore(path).load().ifPresent(props -> {
            Map<String, Object> mapped = mapToSpringProperties(props);
            if (!mapped.isEmpty()) {
                environment.getPropertySources().addFirst(new MapPropertySource("connectionOverrides", mapped));
            }
        });
    }

    static Map<String, Object> mapToSpringProperties(Properties props) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> mapping : KEY_MAPPING.entrySet()) {
            String value = props.getProperty(mapping.getKey());
            if (value != null && !value.isBlank()) {
                out.put(mapping.getValue(), value);
            }
        }
        return out;
    }
}
