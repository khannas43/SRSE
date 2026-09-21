package gov.rajasthan.smart.srse.web;

import gov.rajasthan.smart.srse.metadata.DataMode;

/**
 * Human-readable environment names for the Admin connections panel.
 * {@code DATA_MODE} / {@link DataMode} stay the wire and persistence keys.
 */
final class EnvironmentLabelResolver {

    private EnvironmentLabelResolver() {
    }

    static String resolve(String configuredLabel, String dataModeConfig) {
        if (configuredLabel != null && !configuredLabel.isBlank()) {
            return configuredLabel.trim();
        }
        DataMode mode = DataMode.valueOf(dataModeConfig.trim().toUpperCase());
        return switch (mode) {
            case SYNTHETIC -> "Development";
            case LIVE -> "Production (Live)";
        };
    }
}
