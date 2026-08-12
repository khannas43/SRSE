package gov.rajasthan.smart.srse.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionOverrideStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void loadReturnsEmptyWhenFileDoesNotExist() {
        ConnectionOverrideStore store = new ConnectionOverrideStore(
                tempDir.resolve("missing.properties").toString());

        assertTrue(store.load().isEmpty());
    }

    @Test
    void saveThenLoadRoundTrips() {
        ConnectionOverrideStore store = new ConnectionOverrideStore(
                tempDir.resolve("nested/override.properties").toString());

        Properties toSave = new Properties();
        toSave.setProperty("analytical.jdbc-url", "jdbc:presto://presto:8080/iceberg/srse");
        store.save(toSave);

        Properties loaded = store.load().orElseThrow();
        assertEquals("jdbc:presto://presto:8080/iceberg/srse", loaded.getProperty("analytical.jdbc-url"));
    }

    @Test
    void saveMergesWithExistingKeysInsteadOfReplacingTheWholeFile() {
        ConnectionOverrideStore store = new ConnectionOverrideStore(
                tempDir.resolve("override.properties").toString());

        Properties operational = new Properties();
        operational.setProperty("operational.jdbc-url", "jdbc:db2://db2:50000/SRSEDB");
        store.save(operational);

        Properties analytical = new Properties();
        analytical.setProperty("analytical.jdbc-url", "jdbc:presto://presto:8080/iceberg/srse");
        store.save(analytical);

        Properties loaded = store.load().orElseThrow();
        assertEquals("jdbc:db2://db2:50000/SRSEDB", loaded.getProperty("operational.jdbc-url"));
        assertEquals("jdbc:presto://presto:8080/iceberg/srse", loaded.getProperty("analytical.jdbc-url"));
    }
}
