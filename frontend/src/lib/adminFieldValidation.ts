import type { FieldDataType } from "@/lib/decisionApi";

/** Matches seeded catalogue keys (snake_case identifier). */
export const FIELD_KEY_PATTERN = /^[a-z][a-z0-9_]*$/;

export const FIELD_KEY_VALIDATION_MESSAGE =
  "Field key must be non-empty snake_case: lowercase letters, digits, and underscores, starting with a letter (e.g. age_years).";

export const ALLOWED_VALUES_CSV_MESSAGE =
  "Allowed values must be comma-separated with no empty entries — remove double commas and trailing commas (e.g. use a, b, c not a,,b or a,b,).";

export const TIER_FIELD_HELP =
  "Tier 1 — direct column (e.g. age → beneficiary.age_years). UI-mappable. " +
  "Tier 2 — same-table expression (e.g. date_diff('year', dob, current_date)). UI-mappable. " +
  "Tier 3 — cross-table / relationship / temporal (e.g. is_girl_child_of_hof, annual_income_3yr_avg). " +
  "Pre-materialised upstream by Spark ETL / REL-01 into a flat Golden Layer column, then exposed to SRSE as an ordinary Tier-1 field. " +
  "SRSE never joins to compute Tier 3 — choosing it without that upstream flat column creates a field that cannot resolve.";

export const ALLOWED_VALUES_FIELD_HELP =
  "Comma-separated list for STRING fields only (e.g. MALE, FEMALE, OTHER). " +
  "This drives the officer's dropdown in the rule builder — each value becomes one selectable option.";

export const PHYSICAL_EXPRESSION_FIELD_HELP =
  "Tier 1: fully qualified catalog.schema.table.column (e.g. iceberg_gold.golden_layer.tbl_beneficiary.age_years). " +
  "Tier 2: a same-table Presto expression (e.g. date_diff('year', CAST(dob AS DATE), current_date)). " +
  "Pick from lakehouse… fills in a Tier-1 column reference; Tier-2 expressions must be typed by hand.";

export function validateFieldKey(raw: string): string | null {
  const trimmed = raw.trim();
  if (!trimmed) {
    return "Field key is required.";
  }
  if (!FIELD_KEY_PATTERN.test(trimmed)) {
    return FIELD_KEY_VALIDATION_MESSAGE;
  }
  return null;
}

export function validateDisplayLabel(raw: string): string | null {
  if (!raw.trim()) {
    return "Display label is required.";
  }
  return null;
}

/**
 * Parses allowed-values CSV. Rejects double/trailing commas that would become blank
 * dropdown options in the rule builder.
 */
export function parseAllowedValuesCsv(
  raw: string,
): { ok: true; values: string[] } | { ok: false; message: string } {
  const trimmed = raw.trim();
  if (!trimmed) {
    return { ok: true, values: [] };
  }
  const parts = trimmed.split(",");
  for (const part of parts) {
    if (part.trim() === "") {
      return { ok: false, message: ALLOWED_VALUES_CSV_MESSAGE };
    }
  }
  return { ok: true, values: parts.map((v) => v.trim()) };
}

export function allowedValuesForSubmit(
  dataType: FieldDataType,
  raw: string,
): { values: string[]; clientError: string | null } {
  if (dataType !== "STRING") {
    return { values: [], clientError: null };
  }
  const parsed = parseAllowedValuesCsv(raw);
  if (!parsed.ok) {
    return { values: [], clientError: parsed.message };
  }
  return { values: parsed.values, clientError: null };
}

export const ALLOWED_VALUES_IGNORED_NOTE =
  "Allowed values apply to STRING fields only; they are ignored for this data type.";
