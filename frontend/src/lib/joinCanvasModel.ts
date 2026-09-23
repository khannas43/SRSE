import type { DisplayColumn, HubSide, JoinType, TableRef } from "@/lib/analysisApi";
import { isCascadeComplete, type CascadeValue } from "@/components/LakehouseCascade";
import type { ComparisonPairRow, CriterionRowModel, DisplayRowModel } from "@/lib/analysisCriterionModel";
import {
  buildMultiTargetRecordMatchRequest,
  type BuildMultiTargetParams,
  type TargetBlockModel,
} from "@/lib/multiTargetMatchBuild";

export type JoinCanvasNode = {
  id: string;
  kind: "hub" | "target";
  /** Target-set label (targets only). */
  label: string;
  tableRef: CascadeValue;
  displayRows: DisplayRowModel[];
  /** Per-target join shape (targets only). Omitted → INNER. */
  joinType?: JoinType;
  /**
   * Post-join comparisons for this target. Carried through the canvas even
   * though the canvas does not author them yet: switching Form → canvas → Form
   * must not silently drop what the form configured.
   */
  comparisonPairs?: ComparisonPairRow[];
};

/**
 * One join criterion: hub side and each target side stay aligned by slot index.
 * Reordering {@link JoinCanvasState.slots} reorders hubCriteria and every
 * target's joinCriteria together — never maintain two independent lists.
 */
export type JoinCanvasSlot = {
  id: string;
  hub: CriterionRowModel;
  targetByNodeId: Record<string, CriterionRowModel>;
};

export type JoinCanvasState = {
  hubSide: HubSide;
  hubNodeId: string;
  nodes: JoinCanvasNode[];
  /** Ordered join criteria — index i maps to hubCriteria[i] and joinCriteria[i] per target. */
  slots: JoinCanvasSlot[];
};

export function createEmptyCanvasSlot(hubTable: CascadeValue, targetIds: string[]): JoinCanvasSlot {
  const emptyRow = (ref: CascadeValue): CriterionRowModel => ({
    ref,
    column: "",
    extraColumns: [],
    fuzzyThresholdPercent: 80,
    mode: "COMBINE",
    separator: " ",
  });
  const targetByNodeId: Record<string, CriterionRowModel> = {};
  for (const id of targetIds) {
    targetByNodeId[id] = emptyRow(EMPTY_TABLE_REF);
  }
  return {
    id: crypto.randomUUID(),
    hub: emptyRow(hubTable),
    targetByNodeId,
  };
}

const EMPTY_TABLE_REF: CascadeValue = {
  catalog: "",
  schema: "",
  table: "",
};

export function createInitialJoinCanvas(): JoinCanvasState {
  const hubId = crypto.randomUUID();
  const targetId = crypto.randomUUID();
  const hubTable = EMPTY_TABLE_REF;
  return {
    hubSide: "SOURCE",
    hubNodeId: hubId,
    nodes: [
      { id: hubId, kind: "hub", label: "Hub", tableRef: hubTable, displayRows: [] },
      { id: targetId, kind: "target", label: "Target 1", tableRef: EMPTY_TABLE_REF, displayRows: [] },
    ],
    slots: [createEmptyCanvasSlot(hubTable, [targetId])],
  };
}

export function canvasHubNode(state: JoinCanvasState): JoinCanvasNode | undefined {
  return state.nodes.find((n) => n.id === state.hubNodeId);
}

export function canvasTargetNodes(state: JoinCanvasState): JoinCanvasNode[] {
  return state.nodes.filter((n) => n.kind === "target");
}

export function validateJoinCanvas(state: JoinCanvasState, maxTargetSets: number): string | null {
  const hubs = state.nodes.filter((n) => n.kind === "hub");
  if (hubs.length !== 1) {
    return "Exactly one hub table is required — remove extra hub nodes or add a single hub.";
  }
  if (hubs[0].id !== state.hubNodeId) {
    return "Hub node id mismatch — pick one hub table in the centre.";
  }
  const targets = canvasTargetNodes(state);
  if (targets.length === 0) {
    return "Add at least one target table joined through the hub.";
  }
  if (targets.length > maxTargetSets) {
    return `At most ${maxTargetSets} target sets are allowed.`;
  }
  const labels = new Set<string>();
  for (const t of targets) {
    const label = t.label.trim();
    if (!label) return "Every target needs a non-empty label.";
    if (labels.has(label)) return `Duplicate target label: ${label}`;
    labels.add(label);
    if (!isCascadeComplete(t.tableRef)) return `Target "${label}" needs a registered table.`;
  }
  const hub = canvasHubNode(state);
  if (!hub || !isCascadeComplete(hub.tableRef)) {
    return "Pick the hub table through the cascade.";
  }
  for (const slot of state.slots) {
    for (const t of targets) {
      const side = slot.targetByNodeId[t.id];
      if (!side) {
        return `Join slot missing target side for "${t.label}" — edges must go hub → target only.`;
      }
    }
  }
  return null;
}

/** Form state → canvas (for round-trip tests and toggling UI mode). */
export function joinCanvasFromForm(
  hubSide: HubSide,
  hubRows: CriterionRowModel[],
  hubDisplayRows: DisplayRowModel[],
  targetBlocks: Array<{
    id: string;
    label: string;
    joinRows: CriterionRowModel[];
    displayRows: DisplayRowModel[];
    joinType?: JoinType;
    comparisonPairs?: ComparisonPairRow[];
  }>,
  hubTable: CascadeValue,
): JoinCanvasState {
  const hubId = crypto.randomUUID();
  const nodes: JoinCanvasNode[] = [
    {
      id: hubId,
      kind: "hub",
      label: "Hub",
      tableRef: isCascadeComplete(hubTable) ? { ...hubTable } : EMPTY_TABLE_REF,
      displayRows: hubDisplayRows.map((r) => ({ ...r })),
    },
  ];
  const targetIds: string[] = [];
  for (const block of targetBlocks) {
    const tid = block.id;
    targetIds.push(tid);
    const ref = block.joinRows.find((r) => isCascadeComplete(r.ref))?.ref ?? EMPTY_TABLE_REF;
    nodes.push({
      id: tid,
      kind: "target",
      label: block.label,
      tableRef: { ...ref },
      displayRows: block.displayRows.map((r) => ({ ...r })),
      joinType: block.joinType,
      comparisonPairs: block.comparisonPairs?.map((c) => ({ ...c })),
    });
  }
  const n = Math.max(hubRows.length, ...targetBlocks.map((b) => b.joinRows.length), 1);
  const slots: JoinCanvasSlot[] = [];
  for (let i = 0; i < n; i += 1) {
    const targetByNodeId: Record<string, CriterionRowModel> = {};
    for (const block of targetBlocks) {
      const row = block.joinRows[i] ?? emptyCriterionFor(block.joinRows[0]?.ref ?? EMPTY_TABLE_REF);
      targetByNodeId[block.id] = { ...row, ref: { ...row.ref } };
    }
    const hubRow = hubRows[i] ?? emptyCriterionFor(hubTable);
    slots.push({
      id: crypto.randomUUID(),
      hub: { ...hubRow, ref: { ...hubRow.ref } },
      targetByNodeId,
    });
  }
  return { hubSide, hubNodeId: hubId, nodes, slots };
}

function emptyCriterionFor(ref: CascadeValue): CriterionRowModel {
  return {
    ref: { ...ref },
    column: "",
    extraColumns: [],
    fuzzyThresholdPercent: 80,
    mode: "COMBINE",
    separator: " ",
  };
}

/** Canvas → the same {@link BuildMultiTargetParams} shape the form uses. */
export function joinCanvasToFormModels(state: JoinCanvasState): {
  hubRows: CriterionRowModel[];
  hubDisplayRows: DisplayRowModel[];
  targets: TargetBlockModel[];
} {
  const hub = canvasHubNode(state)!;
  const hubTable = hub.tableRef;
  const hubRows = state.slots.map((slot) => ({
    ...slot.hub,
    ref: { ...hubTable },
  }));
  const targets: TargetBlockModel[] = canvasTargetNodes(state).map((node) => ({
    label: node.label,
    joinRows: state.slots.map((slot) => ({
      ...(slot.targetByNodeId[node.id] ?? emptyCriterionFor(node.tableRef)),
      ref: { ...node.tableRef },
    })),
    displayRows: node.displayRows.map((r) => ({ ...r })),
    joinType: node.joinType,
    comparisonPairs: node.comparisonPairs?.map((c) => ({ ...c })),
  }));
  return {
    hubRows,
    hubDisplayRows: hub.displayRows.map((r) => ({ ...r })),
    targets,
  };
}

export function buildMultiTargetRequestFromCanvas(
  state: JoinCanvasState,
  buildParams: Omit<BuildMultiTargetParams, "hubRows" | "hubDisplayRows" | "targets" | "hubSide">,
  maxTargetSets: number,
): ReturnType<typeof buildMultiTargetRecordMatchRequest> {
  const canvasError = validateJoinCanvas(state, maxTargetSets);
  if (canvasError) return null;
  const { hubRows, hubDisplayRows, targets } = joinCanvasToFormModels(state);
  return buildMultiTargetRecordMatchRequest({
    ...buildParams,
    hubSide: state.hubSide,
    hubRows,
    hubDisplayRows,
    targets,
    maxTargetSets,
  });
}

export function reorderJoinCanvasSlots(state: JoinCanvasState, fromIndex: number, toIndex: number): JoinCanvasState {
  if (fromIndex === toIndex || fromIndex < 0 || toIndex < 0 || fromIndex >= state.slots.length) {
    return state;
  }
  const slots = [...state.slots];
  const [moved] = slots.splice(fromIndex, 1);
  slots.splice(toIndex, 0, moved);
  return { ...state, slots };
}

/** Hub display columns helper for canvas node edits. */
export function hubDisplayColumnsFromNode(node: JoinCanvasNode): DisplayColumn[] {
  return node.displayRows
    .filter((r) => isCascadeComplete(r.ref) && r.column)
    .map((r) => ({ ...r.ref, column: r.column }));
}
