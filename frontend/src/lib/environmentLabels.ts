import type { DataMode } from "@/lib/decisionApi";

/**
 * Display labels for SRSE environments. Wire values stay {@code SYNTHETIC} /
 * {@code LIVE} ({@code DATA_MODE} / {@code field_column_mapping.data_mode}).
 *
 * Two distinct uses — do not conflate in the Admin UI:
 * - **Running environment** — what deployment this instance is ({@link runningEnvironmentLabel}).
 * - **Binding set** — which mapping rows the editor loads ({@link bindingSetLabel}).
 */

export const BINDING_SET_LABELS: Record<DataMode, string> = {
  SYNTHETIC: "Development",
  LIVE: "Production (Live)",
};

/** Label for the mapping editor radios (which {@code field_column_mapping} set). */
export function bindingSetLabel(dataMode: DataMode): string {
  return BINDING_SET_LABELS[dataMode];
}

/** Label for the connections panel (this running deployment). */
export function runningEnvironmentLabel(
  environmentLabel: string | undefined,
  dataMode: string,
): string {
  const trimmed = environmentLabel?.trim();
  if (trimmed) {
    return trimmed;
  }
  const mode = dataMode.trim().toUpperCase();
  if (mode === "LIVE") {
    return BINDING_SET_LABELS.LIVE;
  }
  if (mode === "SYNTHETIC") {
    return BINDING_SET_LABELS.SYNTHETIC;
  }
  return dataMode;
}
