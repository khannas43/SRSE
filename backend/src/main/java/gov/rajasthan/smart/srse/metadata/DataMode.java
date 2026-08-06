package gov.rajasthan.smart.srse.metadata;

/**
 * Matches {@code srse.data-mode} / {@code DATA_MODE} config values
 * ({@code synthetic} | {@code live}). Parse with {@code valueOf(config.toUpperCase())}.
 */
public enum DataMode {
    SYNTHETIC,
    LIVE
}
