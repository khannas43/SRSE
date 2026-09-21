package gov.rajasthan.smart.srse.lakehouse;

import java.util.List;

/**
 * Display-layer tags for registered tables. A layer is a filter label only —
 * never part of {@code catalog.schema.table} addressing or SQL.
 */
public final class LakehouseLayers {

    /** Suggested values for admin pickers; not an exhaustive enum. */
    public static final List<String> KNOWN = List.of("BRONZE", "SILVER", "GOLD");

    /** Wire sentinel: officer/admin cascade filter for {@code layer IS NULL} rows. */
    public static final String UNTAGGED = "UNTAGGED";

    private LakehouseLayers() {
    }

    /** Required layer for register / updateLayer — rejects null/blank. */
    public static String normalise(String layer) {
        if (layer == null || layer.isBlank()) {
            throw new IllegalArgumentException(
                    "Layer is required (e.g. BRONZE, SILVER, GOLD, or another display tag)");
        }
        String trimmed = layer.trim();
        if (UNTAGGED.equalsIgnoreCase(trimmed)) {
            throw new IllegalArgumentException(
                    UNTAGGED + " is reserved for legacy untagged registrations — choose another tag");
        }
        return trimmed.toUpperCase();
    }

    /** Config import may restore older bundles with no layer tag. */
    public static String normaliseForImport(String layer) {
        if (layer == null || layer.isBlank()) {
            return null;
        }
        return layer.trim().toUpperCase();
    }

    public static boolean isUntaggedFilter(String layerParam) {
        return layerParam != null && UNTAGGED.equalsIgnoreCase(layerParam.trim());
    }

    /**
     * Parses an optional HTTP {@code layer} query param. Blank → unfiltered ({@code null}).
     * {@link #UNTAGGED} → sentinel. Otherwise normalised tag.
     */
    public static String parseFilterParam(String layerParam) {
        if (layerParam == null || layerParam.isBlank()) {
            return null;
        }
        if (isUntaggedFilter(layerParam)) {
            return UNTAGGED;
        }
        return normalise(layerParam);
    }
}
