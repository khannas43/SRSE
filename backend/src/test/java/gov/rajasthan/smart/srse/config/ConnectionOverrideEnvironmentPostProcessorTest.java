package gov.rajasthan.smart.srse.config;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused unit test for the override-file -> Spring-property key mapping —
 * deliberately not a full Spring-context boot test (out of proportion for
 * what's being verified here).
 */
class ConnectionOverrideEnvironmentPostProcessorTest {

    @Test
    void mapsOverrideKeysOntoDatasourceConfigurationProperties() {
        Properties props = new Properties();
        props.setProperty("operational.jdbc-url", "jdbc:db2://new-host:50000/SRSEDB");
        props.setProperty("analytical.username", "new-user");

        Map<String, Object> mapped = ConnectionOverrideEnvironmentPostProcessor.mapToSpringProperties(props);

        assertEquals("jdbc:db2://new-host:50000/SRSEDB", mapped.get("srse.datasource.operational.jdbc-url"));
        assertEquals("new-user", mapped.get("srse.datasource.analytical.username"));
        assertEquals(2, mapped.size());
    }

    @Test
    void blankOrMissingValuesAreOmittedSoEnvVarFallbackStillApplies() {
        Properties props = new Properties();
        props.setProperty("operational.jdbc-url", "");
        // analytical.jdbc-url intentionally absent

        Map<String, Object> mapped = ConnectionOverrideEnvironmentPostProcessor.mapToSpringProperties(props);

        assertTrue(mapped.isEmpty());
        assertFalse(mapped.containsKey("srse.datasource.operational.jdbc-url"));
    }
}
