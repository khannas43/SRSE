package gov.rajasthan.smart.srse.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Reads/writes the connection-override properties file used to persist admin
 * edits to the Operational (DB2) / Analytical (Presto) connections across
 * restarts, WITHOUT storing them as rows in either datasource — see
 * {@link ConnectionOverrideEnvironmentPostProcessor} for why that would be
 * circular for the operational plane. Single load/save code path, used both
 * by that boot-time post-processor and by the runtime update services
 * ({@link AnalyticalConnectionService}, {@link OperationalConnectionService}).
 */
@Component
public class ConnectionOverrideStore {

    private final Path path;

    public ConnectionOverrideStore(
            @Value("${srse.connection-override-path:connection-overrides.properties}") String path) {
        this.path = Path.of(path);
    }

    public Optional<Properties> load() {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Optional.of(props);
    }

    /** Merges {@code updates} into whatever is already on disk and rewrites the file. */
    public void save(Properties updates) {
        Properties merged = load().orElseGet(Properties::new);
        merged.putAll(updates);
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(path)) {
                merged.store(out, "SRSE connection overrides — written by the admin UI, not hand-edited");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
