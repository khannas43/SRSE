import type { ComparisonGroup, GroupMode, MatchCriterion, MatchGroup, RegisteredColumn, TableRef } from "@/lib/analysisApi";
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

/** One post-join comparison row as the UI holds it, before it becomes a ComparisonGroup. */
export type ComparisonPairRow = {
  id: string;
  sourceColumn: string;
  targetColumn: string;
  fuzzyThresholdPercent: number;
};

export function createComparisonPairRow(fuzzyThresholdPercent = 80): ComparisonPairRow {
  return { id: crypto.randomUUID(), sourceColumn: "", targetColumn: "", fuzzyThresholdPercent };
}

/**
 * Same precedence as the join side's pairIsFuzzy: an explicit Admin registration
 * on either column wins, otherwise the *name* substring guess decides.
 */
export function comparisonPairIsFuzzy(
  sourceRef: TableRef | undefined,
  targetRef: TableRef | undefined,
  pair: ComparisonPairRow,
  registeredFuzzyFor: (ref: TableRef, column: string) => boolean | null,
): boolean {
  if (!pair.sourceColumn || !pair.targetColumn || !sourceRef || !targetRef) return false;
  return (
    isNameColumn(pair.sourceColumn) ||
    isNameColumn(pair.targetColumn) ||
    registeredFuzzyFor(sourceRef, pair.sourceColumn) === true ||
    registeredFuzzyFor(targetRef, pair.targetColumn) === true
  );
}

/**
 * Pairs to wire groups. A multi-column COMBINE always compares as text, so these
 * single-column groups keep mode COMBINE and carry a threshold only when fuzzy.
 */
export function buildComparisonGroups(
  sourceRef: TableRef,
  targetRef: TableRef,
  pairs: ComparisonPairRow[],
  registeredFuzzyFor: (ref: TableRef, column: string) => boolean | null,
): ComparisonGroup[] {
  return pairs
    .filter((p) => p.sourceColumn && p.targetColumn)
    .map((p) => ({
      source: [{ ...sourceRef, column: p.sourceColumn, fuzzyThresholdPercent: null }],
      target: [{ ...targetRef, column: p.targetColumn, fuzzyThresholdPercent: null }],
      mode: "COMBINE" as const,
      fuzzyThresholdPercent: comparisonPairIsFuzzy(sourceRef, targetRef, p, registeredFuzzyFor)
        ? p.fuzzyThresholdPercent
        : null,
    separator: " ",
    }));
}
