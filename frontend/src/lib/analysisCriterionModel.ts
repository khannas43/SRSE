import type { GroupMode, MatchCriterion, MatchGroup, RegisteredColumn, TableRef } from "@/lib/analysisApi";
import { isCascadeComplete, type CascadeValue } from "@/components/LakehouseCascade";

/** Shared criterion-row shape for the Analysis form and join canvas. */
export type CriterionRowModel = {
  ref: CascadeValue;
  column: string;
  /** Loaded column list for pickers — not part of the API payload. */
  columns?: RegisteredColumn[];
  extraColumns: string[];
  fuzzyThresholdPercent: number;
  mode: GroupMode;
  separator: string;
};

export type DisplayRowModel = {
  ref: CascadeValue;
  column: string;
};

export function rowColumns(row: CriterionRowModel): string[] {
  return [row.column, ...row.extraColumns].filter(Boolean);
}

export function rowFolds(row: CriterionRowModel): boolean {
  return rowColumns(row).length > 1;
}

export function isCriterionRowFilled(row: CriterionRowModel): boolean {
  return isCascadeComplete(row.ref) && Boolean(row.column);
}

export function isDisplayRowFilled(row: DisplayRowModel): boolean {
  return isCascadeComplete(row.ref) && Boolean(row.column);
}

export function isNameColumn(column: string): boolean {
  return column.toLowerCase().includes("name");
}

export function rowCriteria(row: CriterionRowModel): MatchCriterion[] {
  return rowColumns(row).map((column) => ({ ...row.ref, column, fuzzyThresholdPercent: null }));
}

export function pairIsFuzzy(
  source: CriterionRowModel,
  target: CriterionRowModel | undefined,
  registeredFuzzyFor: (ref: TableRef, column: string) => boolean | null,
): boolean {
  const sides: Array<[CascadeValue, string]> = rowColumns(source).map((c) => [source.ref, c]);
  if (target) {
    sides.push(...rowColumns(target).map((c) => [target.ref, c] as [CascadeValue, string]));
  }
  let anyRegistered = false;
  for (const [ref, column] of sides) {
    const registered = registeredFuzzyFor(ref, column);
    if (registered !== null) {
      anyRegistered = true;
      if (registered) return true;
    }
  }
  if (anyRegistered) return false;
  return sides.some(([, column]) => isNameColumn(column));
}

export function buildMatchGroup(
  source: CriterionRowModel,
  target: CriterionRowModel,
  fuzzy: boolean,
): MatchGroup {
  return {
    source: rowCriteria(source),
    target: rowCriteria(target),
    mode: source.mode,
    fuzzyThresholdPercent: fuzzy ? source.fuzzyThresholdPercent : null,
    separator: source.mode === "COMBINE" ? source.separator : null,
  };
}
