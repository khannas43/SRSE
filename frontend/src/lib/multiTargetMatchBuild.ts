import type {
  AgeFilterSpec,
  DedupSpec,
  DisplayColumn,
  HubSide,
  JoinType,
  MatchCriterion,
  MultiTargetRecordMatchRequest,
  RecordMatchRequest,
  TableRef,
} from "@/lib/analysisApi";
import {
  buildMatchGroup,
  isCriterionRowFilled,
  isDisplayRowFilled,
  pairIsFuzzy,
  rowColumns,
  rowFolds,
  type CriterionRowModel,
  type DisplayRowModel,
} from "@/lib/analysisCriterionModel";
import { isCascadeComplete } from "@/components/LakehouseCascade";

export const MULTI_TARGET_MAX_SETS = 5;
export const ANALYSIS_MAX_GROUP_COLUMNS = 4;
export const ANALYSIS_MAX_ANYOF_GROUPS_PER_SIDE = 2;

export type TargetBlockModel = {
  label: string;
  joinRows: CriterionRowModel[];
  displayRows: DisplayRowModel[];
  joinType?: JoinType;
};

export type BuildMultiTargetParams = {
  hubRows: CriterionRowModel[];
  hubDisplayRows: DisplayRowModel[];
  hubSide: HubSide;
  targets: TargetBlockModel[];
  highlightDuplicates: boolean;
  dedup: DedupSpec | null;
  ageFilter: AgeFilterSpec | null;
  registeredFuzzyFor: (ref: TableRef, column: string) => boolean | null;
  isFuzzyMatchable: (ref: TableRef, column: string) => boolean;
  maxTargetSets?: number;
};

/** Stable JSON for byte-identical comparison with {@link JSON.stringify} on the wire payload. */
export function stableMultiTargetRequestJson(req: MultiTargetRecordMatchRequest): string {
  return JSON.stringify(req);
}

/**
 * Builds the multi-target request — same logic as the Analysis tab form. Every
 * canvas configuration must converge here so payloads stay byte-identical.
 */
export function buildMultiTargetRecordMatchRequest(
  params: BuildMultiTargetParams,
): MultiTargetRecordMatchRequest | null {
  const filledHub = params.hubRows.filter(isCriterionRowFilled);
  if (filledHub.length === 0) return null;

  const maxSets = params.maxTargetSets ?? MULTI_TARGET_MAX_SETS;
  const targets: MultiTargetRecordMatchRequest["targets"] = [];

  for (const block of params.targets) {
    const label = block.label.trim();
    if (!label) return null;
    const filledJoin = block.joinRows.filter(isCriterionRowFilled);
    const n = Math.min(filledHub.length, filledJoin.length);
    if (n === 0) continue;
    const tableRef = filledJoin[0].ref;
    if (!isCascadeComplete(tableRef)) return null;
    const filledDisplay = block.displayRows.filter(isDisplayRowFilled);
    const pairs = filledJoin.slice(0, n).map((r, i) => ({ source: filledHub[i], target: r }));
    const usesGroups = pairs.some(({ source, target }) => rowFolds(source) || rowFolds(target));
    const targetSpec: MultiTargetRecordMatchRequest["targets"][number] = {
      label,
      ...tableRef,
      joinCriteria: pairs.map(({ source, target }) => ({
        ...target.ref,
        column: target.column,
        fuzzyThresholdPercent: pairIsFuzzy(source, target, params.registeredFuzzyFor)
          ? source.fuzzyThresholdPercent
          : null,
      })),
      displayColumns:
        filledDisplay.length > 0
          ? filledDisplay.map((r) => ({ ...r.ref, column: r.column }))
          : undefined,
      joinGroups: usesGroups
        ? pairs.map(({ source, target }) =>
            buildMatchGroup(source, target, pairIsFuzzy(source, target, params.registeredFuzzyFor)),
          )
        : undefined,
    };
    if (block.joinType && block.joinType !== "INNER") {
      targetSpec.joinType = block.joinType;
    }
    targets.push(targetSpec);
  }
  if (targets.length === 0) return null;
  if (targets.length > maxSets) return null;

  const hubRef = filledHub[0].ref;
  const filledHubDisplay = params.hubDisplayRows.filter(isDisplayRowFilled);
  const req: MultiTargetRecordMatchRequest = {
    hubCriteria: filledHub.map((r) => ({
      ...r.ref,
      column: r.column,
      fuzzyThresholdPercent: params.isFuzzyMatchable(r.ref, r.column) ? r.fuzzyThresholdPercent : null,
    })),
    hubSide: params.hubSide,
    targets,
    highlightDuplicates: params.highlightDuplicates,
    dedup: params.dedup,
    ageFilter: params.ageFilter,
  };
  if (filledHubDisplay.length > 0) {
    req.hubDisplayColumns = filledHubDisplay.map((r) => ({ ...r.ref, column: r.column }));
  }
  return req;
}

/** One target's slice of a multi-target request, for {@link fetchMatchSql} preview per edge/set. */
export function singleTargetMatchRequestFromMulti(
  req: MultiTargetRecordMatchRequest,
  targetIndex: number,
): RecordMatchRequest | null {
  const target = req.targets[targetIndex];
  if (!target) return null;
  const out: RecordMatchRequest = {
    sourceCriteria: req.hubCriteria,
    targetCriteria: target.joinCriteria,
    highlightDuplicates: req.highlightDuplicates,
    dedup: req.dedup,
    ageFilter: req.ageFilter,
  };
  if (req.hubDisplayColumns && req.hubDisplayColumns.length > 0) {
    out.sourceDisplayColumns = req.hubDisplayColumns;
  }
  if (target.displayColumns && target.displayColumns.length > 0) {
    out.targetDisplayColumns = target.displayColumns;
  }
  if (target.joinGroups && target.joinGroups.length > 0) {
    out.joinGroups = target.joinGroups;
  }
  if (target.joinType && target.joinType !== "INNER") {
    out.joinType = target.joinType;
  }
  if (target.comparisonGroups && target.comparisonGroups.length > 0) {
    out.comparisonGroups = target.comparisonGroups;
  }
  if (req.mismatchOnly) {
    out.mismatchOnly = true;
  }
  return out;
}

export function validateGroupCaps(hubRows: CriterionRowModel[], targets: TargetBlockModel[]): string | null {
  const countAnyOf = (rows: CriterionRowModel[]) =>
    rows.filter(isCriterionRowFilled).filter((r) => r.mode === "ANY_OF" && rowFolds(r)).length;

  for (const row of hubRows.filter(isCriterionRowFilled)) {
    if (rowColumns(row).length > ANALYSIS_MAX_GROUP_COLUMNS) {
      return `Hub join row has more than ${ANALYSIS_MAX_GROUP_COLUMNS} columns on one side.`;
    }
  }
  if (countAnyOf(hubRows) > ANALYSIS_MAX_ANYOF_GROUPS_PER_SIDE) {
    return `Hub has more than ${ANALYSIS_MAX_ANYOF_GROUPS_PER_SIDE} ANY_OF groups.`;
  }
  for (const block of targets) {
    for (const row of block.joinRows.filter(isCriterionRowFilled)) {
      if (rowColumns(row).length > ANALYSIS_MAX_GROUP_COLUMNS) {
        return `Target "${block.label}" has a join row with more than ${ANALYSIS_MAX_GROUP_COLUMNS} columns.`;
      }
    }
    if (countAnyOf(block.joinRows) > ANALYSIS_MAX_ANYOF_GROUPS_PER_SIDE) {
      return `Target "${block.label}" has more than ${ANALYSIS_MAX_ANYOF_GROUPS_PER_SIDE} ANY_OF groups.`;
    }
  }
  return null;
}
