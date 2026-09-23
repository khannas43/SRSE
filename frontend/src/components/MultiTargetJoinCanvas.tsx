"use client";

import { useMemo, useState } from "react";
import LakehouseCascade, {
  EMPTY_CASCADE,
  isCascadeComplete,
  type CascadeFetchers,
  type CascadeValue,
} from "@/components/LakehouseCascade";
import {
  fetchMatchSql,
  listAnalysisColumns,
  type HubSide,
  type JoinType,
  type MultiTargetRecordMatchRequest,
  type RegisteredColumn,
  type TableRef,
} from "@/lib/analysisApi";
import {
  type ComparisonPairRow,
  isCriterionRowFilled,
  pairIsFuzzy,
  rowColumns,
  rowFolds,
  type CriterionRowModel,
} from "@/lib/analysisCriterionModel";
import { ComparisonPairsEditor } from "@/components/ComparisonPairsEditor";
import type { GroupMode } from "@/lib/analysisApi";
import {
  buildMultiTargetRequestFromCanvas,
  canvasHubNode,
  canvasTargetNodes,
  createEmptyCanvasSlot,
  createInitialJoinCanvas,
  reorderJoinCanvasSlots,
  validateJoinCanvas,
  type JoinCanvasState,
} from "@/lib/joinCanvasModel";
import {
  ANALYSIS_MAX_ANYOF_GROUPS_PER_SIDE,
  ANALYSIS_MAX_GROUP_COLUMNS,
  singleTargetMatchRequestFromMulti,
  validateGroupCaps,
} from "@/lib/multiTargetMatchBuild";
import { phaseLabel, type TargetRunStatus } from "@/lib/multiTargetProgress";

const fieldLabelStyle = { display: "block", marginBottom: "0.3rem", fontSize: "0.82rem" } as const;

const TARGET_JOIN_OPTIONS: { value: JoinType; label: string }[] = [
  { value: "INNER", label: "Only matching records" },
  { value: "LEFT", label: "All hub records" },
  { value: "RIGHT", label: "All target records" },
  { value: "FULL", label: "All records from both" },
];

type Props = Readonly<{
  fetchers: CascadeFetchers;
  maxTargetSets: number;
  hubSide: HubSide;
  onHubSideChange: (side: HubSide) => void;
  canvas: JoinCanvasState;
  onCanvasChange: (next: JoinCanvasState) => void;
  buildExtras: Omit<
    Parameters<typeof buildMultiTargetRequestFromCanvas>[1],
    never
  >;
  targetRunStatus: TargetRunStatus[];
  onReportError: (message: string) => void;
  registeredFuzzyFor: (ref: TableRef, column: string) => boolean | null;
  businessNameFor: (ref: TableRef, column: string) => string | null;
}>;

function countAnyOfGroups(rows: CriterionRowModel[]): number {
  return rows.filter(isCriterionRowFilled).filter((r) => r.mode === "ANY_OF" && rowFolds(r)).length;
}

function emptySideForTable(ref: CascadeValue): CriterionRowModel {
  return {
    ref,
    column: "",
    extraColumns: [],
    fuzzyThresholdPercent: 80,
    mode: "COMBINE",
    separator: " ",
    columns: [],
  };
}

function JoinSlotSideEditor({
  idPrefix,
  side,
  columns,
  disabled,
  modeEditable,
  anyOfHubCount,
  onChange,
  onLoadColumns,
}: Readonly<{
  idPrefix: string;
  side: CriterionRowModel;
  columns: RegisteredColumn[];
  disabled?: boolean;
  modeEditable: boolean;
  anyOfHubCount: number;
  onChange: (patch: Partial<CriterionRowModel>) => void;
  onLoadColumns?: () => void;
}>) {
  const atColumnCap = rowColumns(side).length >= ANALYSIS_MAX_GROUP_COLUMNS;
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "0.35rem" }}>
      <ColumnSelect
        id={`${idPrefix}-primary`}
        columns={columns}
        value={side.column}
        disabled={disabled}
        onChange={(column) => onChange({ column })}
      />
      {side.extraColumns.map((extra, extraIndex) => (
        <div key={`${idPrefix}-extra-${extraIndex}`} style={{ display: "flex", gap: "0.35rem" }}>
          <ColumnSelect
            id={`${idPrefix}-extra-${extraIndex}`}
            columns={columns}
            value={extra}
            disabled={disabled}
            onChange={(column) =>
              onChange({
                extraColumns: side.extraColumns.map((c, k) => (k === extraIndex ? column : c)),
              })
            }
          />
          <button
            type="button"
            className="srse-btn srse-btn-ghost srse-btn-sm"
            disabled={disabled}
            onClick={() =>
              onChange({ extraColumns: side.extraColumns.filter((_, k) => k !== extraIndex) })
            }
          >
            Remove
          </button>
        </div>
      ))}
      <button
        type="button"
        className="srse-btn srse-btn-ghost srse-btn-sm"
        disabled={disabled || atColumnCap}
        onClick={() => onChange({ extraColumns: [...side.extraColumns, ""] })}
      >
        + Add column
      </button>
      {modeEditable && (
        <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap", alignItems: "flex-end" }}>
          <div>
            <label htmlFor={`${idPrefix}-mode`} className="srse-text-muted" style={fieldLabelStyle}>
              Compare as
            </label>
            <select
              id={`${idPrefix}-mode`}
              className="srse-select"
              value={side.mode}
              disabled={
                disabled
                || (side.mode !== "ANY_OF"
                  && anyOfHubCount >= ANALYSIS_MAX_ANYOF_GROUPS_PER_SIDE)
              }
              onChange={(e) => {
                const mode = e.target.value as GroupMode;
                if (mode === "ANY_OF" && anyOfHubCount >= ANALYSIS_MAX_ANYOF_GROUPS_PER_SIDE && side.mode !== "ANY_OF") {
                  return;
                }
                onChange({ mode });
              }}
            >
              <option value="COMBINE">Combine columns</option>
              <option value="ANY_OF">Match any one of</option>
            </select>
          </div>
          {side.mode === "COMBINE" && rowFolds(side) && (
            <div>
              <label htmlFor={`${idPrefix}-sep`} className="srse-text-muted" style={fieldLabelStyle}>
                Separator
              </label>
              <input
                id={`${idPrefix}-sep`}
                className="srse-input"
                style={{ width: 48 }}
                value={side.separator}
                onChange={(e) => onChange({ separator: e.target.value })}
              />
            </div>
          )}
        </div>
      )}
      {onLoadColumns && columns.length === 0 && (
        <button type="button" className="srse-btn srse-btn-ghost srse-btn-sm" onClick={onLoadColumns}>
          Load columns
        </button>
      )}
      {rowFolds(side) && side.mode === "COMBINE" && (
        <span className="srse-text-muted" style={{ fontSize: "0.68rem" }}>
          Multi-column COMBINE compares as text (not numeric).
        </span>
      )}
    </div>
  );
}

function ColumnSelect({
  id,
  columns,
  value,
  disabled,
  onChange,
}: Readonly<{
  id: string;
  columns: RegisteredColumn[];
  value: string;
  disabled?: boolean;
  onChange: (column: string) => void;
}>) {
  return (
    <select id={id} className="srse-select" style={{ width: "100%" }} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
      <option value="">— column —</option>
      {columns.map((c) => (
        <option key={c.name} value={c.name}>
          {c.name}
          {c.businessName ? ` (${c.businessName})` : ""}
        </option>
      ))}
    </select>
  );
}

export function MultiTargetJoinCanvas({
  fetchers,
  maxTargetSets,
  hubSide,
  onHubSideChange,
  canvas,
  onCanvasChange,
  buildExtras,
  targetRunStatus,
  onReportError,
  registeredFuzzyFor,
  businessNameFor,
}: Props) {
  const [canvasError, setCanvasError] = useState<string | null>(null);
  const [edgeSql, setEdgeSql] = useState<Record<string, string>>({});
  const [edgeSqlLoading, setEdgeSqlLoading] = useState<string | null>(null);

  const hub = canvasHubNode(canvas);
  const targets = canvasTargetNodes(canvas);

  const builtRequest: MultiTargetRecordMatchRequest | null = useMemo(() => {
    return buildMultiTargetRequestFromCanvas(canvas, buildExtras, maxTargetSets);
  }, [canvas, buildExtras, maxTargetSets]);

  function updateTargetComparisons(nodeId: string, next: ComparisonPairRow[]) {
    onCanvasChange({
      ...canvas,
      nodes: canvas.nodes.map((n) => (n.id === nodeId ? { ...n, comparisonPairs: next } : n)),
    });
  }

  function syncHubSide(side: HubSide) {
    onHubSideChange(side);
    onCanvasChange({ ...canvas, hubSide: side });
  }

  async function loadColumns(ref: CascadeValue): Promise<RegisteredColumn[]> {
    if (!isCascadeComplete(ref)) return [];
    try {
      return await listAnalysisColumns(ref);
    } catch (err: unknown) {
      onReportError(err instanceof Error ? err.message : String(err));
      return [];
    }
  }

  function updateHubTable(ref: CascadeValue) {
    onCanvasChange({
      ...canvas,
      nodes: canvas.nodes.map((n) =>
        n.id === canvas.hubNodeId ? { ...n, tableRef: ref, displayRows: n.displayRows.map((d) => ({ ...d, ref })) } : n,
      ),
      slots: canvas.slots.map((s) => ({
        ...s,
        hub: { ...s.hub, ref, column: "", extraColumns: [], columns: [] },
      })),
    });
  }

  function updateTargetTable(nodeId: string, ref: CascadeValue) {
    onCanvasChange({
      ...canvas,
      nodes: canvas.nodes.map((n) =>
        n.id === nodeId ? { ...n, tableRef: ref, displayRows: n.displayRows.map((d) => ({ ...d, ref })) } : n,
      ),
      slots: canvas.slots.map((s) => ({
        ...s,
        targetByNodeId: {
          ...s.targetByNodeId,
          [nodeId]: {
            ...(s.targetByNodeId[nodeId] ?? s.hub),
            ref,
            column: "",
            extraColumns: [],
            columns: [],
          },
        },
      })),
    });
  }

  function updateSlotHub(slotId: string, patch: Partial<CriterionRowModel>, columns?: RegisteredColumn[]) {
    onCanvasChange({
      ...canvas,
      slots: canvas.slots.map((s) =>
        s.id === slotId
          ? {
              ...s,
              hub: {
                ...s.hub,
                ...patch,
                ...(columns ? { columns } : {}),
              },
            }
          : s,
      ),
    });
  }

  function updateSlotTarget(
    slotId: string,
    targetId: string,
    patch: Partial<CriterionRowModel>,
    columns?: RegisteredColumn[],
  ) {
    onCanvasChange({
      ...canvas,
      slots: canvas.slots.map((s) =>
        s.id === slotId
          ? {
              ...s,
              targetByNodeId: {
                ...s.targetByNodeId,
                [targetId]: {
                  ...(s.targetByNodeId[targetId] ?? s.hub),
                  ...patch,
                  ...(columns ? { columns } : {}),
                },
              },
            }
          : s,
      ),
    });
  }

  function addTarget() {
    if (targets.length >= maxTargetSets) return;
    const id = crypto.randomUUID();
    const label = `Target ${targets.length + 1}`;
    const hubTable = hub?.tableRef ?? EMPTY_CASCADE;
    const emptySide: CriterionRowModel = {
      ref: EMPTY_CASCADE,
      column: "",
      extraColumns: [],
      fuzzyThresholdPercent: 80,
      mode: "COMBINE",
      separator: " ",
      columns: [],
    };
    const nextSlots =
      canvas.slots.length === 0
        ? [createEmptyCanvasSlot(hubTable, [id])]
        : canvas.slots.map((s) => ({
            ...s,
            targetByNodeId: { ...s.targetByNodeId, [id]: { ...emptySide } },
          }));
    onCanvasChange({
      ...canvas,
      nodes: [
        ...canvas.nodes,
        { id, kind: "target", label, tableRef: EMPTY_CASCADE, displayRows: [], joinType: "INNER" as JoinType },
      ],
      slots: nextSlots,
    });
  }

  function removeTarget(nodeId: string) {
    onCanvasChange({
      ...canvas,
      nodes: canvas.nodes.filter((n) => n.id !== nodeId),
      slots: canvas.slots.map((s) => {
        const next = { ...s.targetByNodeId };
        delete next[nodeId];
        return { ...s, targetByNodeId: next };
      }),
    });
  }

  function addSlot() {
    const hubTable = hub?.tableRef ?? EMPTY_CASCADE;
    const targetIds = targets.map((t) => t.id);
    onCanvasChange({
      ...canvas,
      slots: [...canvas.slots, createEmptyCanvasSlot(hubTable, targetIds)],
    });
  }

  function validateBeforeRun(): string | null {
    const structural = validateJoinCanvas(canvas, maxTargetSets);
    if (structural) return structural;
    const { hubRows, targets: targetModels } = (() => {
      const m = canvas;
      const hubNode = canvasHubNode(m)!;
      return {
        hubRows: m.slots.map((s) => ({ ...s.hub, ref: hubNode.tableRef })),
        targets: canvasTargetNodes(m).map((node) => ({
          label: node.label,
          joinRows: m.slots.map((s) => ({
            ...(s.targetByNodeId[node.id] ?? s.hub),
            ref: node.tableRef,
          })),
          displayRows: node.displayRows,
        })),
      };
    })();
    return validateGroupCaps(hubRows, targetModels);
  }

  async function previewTargetSql(targetIndex: number, label: string) {
    if (!builtRequest) {
      setCanvasError("Complete hub, targets, and join edges before previewing SQL.");
      return;
    }
    const single = singleTargetMatchRequestFromMulti(builtRequest, targetIndex);
    if (!single) return;
    setEdgeSqlLoading(label);
    try {
      const sql = await fetchMatchSql(single);
      setEdgeSql((prev) => ({ ...prev, [label]: sql }));
    } catch (err: unknown) {
      onReportError(err instanceof Error ? err.message : String(err));
    } finally {
      setEdgeSqlLoading(null);
    }
  }

  const validationMessage = validateBeforeRun();
  const hubRowsForAnyOf = useMemo(() => canvas.slots.map((s) => s.hub), [canvas.slots]);
  const anyOfHubCount = useMemo(() => countAnyOfGroups(hubRowsForAnyOf), [hubRowsForAnyOf]);

  return (
    <div style={{ width: "100%" }}>
      <p className="srse-text-muted" style={{ fontSize: "0.82rem", lineHeight: 1.5 }}>
        Join canvas — hub in the centre, targets around it, one edge per join criterion. Each target is an
        independent hub↔target match (never an N-way join); pick a join type per target (LEFT keeps unmatched hub
        rows with empty target columns).
      </p>

      <div style={{ display: "flex", gap: "0.75rem", flexWrap: "wrap", marginBottom: "1rem", alignItems: "center" }}>
        <span className="srse-text-muted" style={{ fontSize: "0.82rem" }}>Hub is the</span>
        <label className="srse-checkbox-label" htmlFor="canvas-hub-side-source">
          <input
            id="canvas-hub-side-source"
            type="radio"
            name="canvas-hub-side"
            checked={hubSide === "SOURCE"}
            onChange={() => syncHubSide("SOURCE")}
          />
          {" "}
          Source side
        </label>
        <label className="srse-checkbox-label" htmlFor="canvas-hub-side-target">
          <input
            id="canvas-hub-side-target"
            type="radio"
            name="canvas-hub-side"
            checked={hubSide === "TARGET"}
            onChange={() => syncHubSide("TARGET")}
          />
          {" "}
          Target side
        </label>
      </div>

      {canvasError && <p className="srse-text-danger">{canvasError}</p>}
      {validationMessage && (
        <p className="srse-text-muted" style={{ fontSize: "0.78rem" }}>
          {validationMessage}
        </p>
      )}

      <div
        style={{
          display: "grid",
          gridTemplateColumns: "1fr",
          gap: "1rem",
          justifyItems: "center",
          marginBottom: "1rem",
        }}
      >
        {hub && (
          <section className="srse-card" style={{ width: "min(520px, 100%)" }}>
            <h3 className="srse-card-title" style={{ marginTop: 0 }}>
              Hub table
            </h3>
            <LakehouseCascade
              value={hub.tableRef}
              onChange={updateHubTable}
              fetchers={fetchers}
              idPrefix="canvas-hub"
              onError={onReportError}
            />
          </section>
        )}

        <div
          style={{
            display: "flex",
            flexWrap: "wrap",
            gap: "1rem",
            justifyContent: "center",
            width: "100%",
          }}
        >
          {targets.map((node, targetIndex) => {
            const run = targetRunStatus.find((s) => s.label === node.label.trim());
            const streamSql = run?.sql;
            return (
              <section key={node.id} className="srse-card" style={{ flex: "1 1 280px", maxWidth: 420 }}>
                <div style={{ display: "flex", gap: "0.5rem", alignItems: "center", marginBottom: "0.5rem" }}>
                  <input
                    aria-label={`Label for target ${targetIndex + 1}`}
                    className="srse-input"
                    style={{ flex: 1 }}
                    value={node.label}
                    onChange={(e) =>
                      onCanvasChange({
                        ...canvas,
                        nodes: canvas.nodes.map((n) => (n.id === node.id ? { ...n, label: e.target.value } : n)),
                      })
                    }
                  />
                  {targets.length > 1 && (
                    <button type="button" className="srse-btn srse-btn-ghost srse-btn-sm" onClick={() => removeTarget(node.id)}>
                      Remove
                    </button>
                  )}
                </div>
                <label htmlFor={`canvas-target-join-${node.id}`} className="srse-text-muted" style={fieldLabelStyle}>
                  Join type
                </label>
                <select
                  id={`canvas-target-join-${node.id}`}
                  className="srse-select"
                  style={{ width: "100%", marginBottom: "0.5rem" }}
                  value={node.joinType ?? "INNER"}
                  onChange={(e) =>
                    onCanvasChange({
                      ...canvas,
                      nodes: canvas.nodes.map((n) =>
                        n.id === node.id ? { ...n, joinType: e.target.value as JoinType } : n,
                      ),
                    })
                  }
                >
                  {TARGET_JOIN_OPTIONS.map((opt) => (
                    <option key={opt.value} value={opt.value}>
                      {opt.label}
                    </option>
                  ))}
                </select>
                <LakehouseCascade
                  value={node.tableRef}
                  onChange={(ref) => updateTargetTable(node.id, ref)}
                  fetchers={fetchers}
                  idPrefix={`canvas-target-${node.id}`}
                  compact
                  onError={onReportError}
                />
                {run && (
                  <p className="srse-text-muted" style={{ fontSize: "0.75rem", marginTop: "0.5rem" }}>
                    <strong>Run:</strong>{" "}
                    {run.phase === "error" ? (
                      <span className="srse-text-danger">{phaseLabel(run)}</span>
                    ) : run.phase === "skipped" ? (
                      <span title="Shared time budget exhausted — this set was not executed">{phaseLabel(run)}</span>
                    ) : (
                      phaseLabel(run)
                    )}
                  </p>
                )}
                <button
                  type="button"
                  className="srse-btn srse-btn-ghost srse-btn-sm"
                  style={{ marginTop: "0.35rem" }}
                  disabled={edgeSqlLoading === node.label || !builtRequest}
                  onClick={() => previewTargetSql(targetIndex, node.label.trim())}
                >
                  {edgeSqlLoading === node.label ? "Planning…" : "Preview SQL"}
                </button>
                {(edgeSql[node.label.trim()] || streamSql) && (
                  <pre
                    style={{
                      marginTop: "0.35rem",
                      fontSize: "0.68rem",
                      maxHeight: 120,
                      overflow: "auto",
                      whiteSpace: "pre-wrap",
                    }}
                  >
                    {streamSql ?? edgeSql[node.label.trim()]}
                  </pre>
                )}
                <div
                  style={{
                    marginTop: "0.6rem",
                    paddingTop: "0.6rem",
                    borderTop: "1px solid var(--srse-border)",
                  }}
                >
                  <div className="srse-text-muted" style={{ fontSize: "0.75rem", marginBottom: "0.35rem" }}>
                    Compare columns (after join) — hub ↔ {node.label.trim() || `Target ${targetIndex + 1}`}
                  </div>
                  <ComparisonPairsEditor
                    pairs={node.comparisonPairs ?? []}
                    onChange={(next) => updateTargetComparisons(node.id, next)}
                    sourceRef={hub && isCascadeComplete(hub.tableRef) ? hub.tableRef : undefined}
                    targetRef={isCascadeComplete(node.tableRef) ? node.tableRef : undefined}
                    sourceColumns={canvas.slots[0]?.hub.columns ?? []}
                    targetColumns={canvas.slots[0]?.targetByNodeId[node.id]?.columns ?? []}
                    registeredFuzzyFor={registeredFuzzyFor}
                    defaultThreshold={canvas.slots[0]?.hub.fuzzyThresholdPercent ?? 80}
                  />
                </div>
              </section>
            );
          })}
        </div>
      </div>

      <button
        type="button"
        className="srse-btn srse-btn-ghost srse-btn-sm"
        disabled={targets.length >= maxTargetSets}
        onClick={addTarget}
      >
        + Add target ({targets.length}/{maxTargetSets})
      </button>

      <h3 className="srse-card-title" style={{ marginTop: "1.25rem" }}>
        Join edges (ordered — the first hub column pairs with each target&apos;s first column, the second with the second, and so on)
      </h3>
      <p className="srse-text-muted" style={{ fontSize: "0.78rem" }}>
        Max {ANALYSIS_MAX_GROUP_COLUMNS} columns per side per group; max {ANALYSIS_MAX_ANYOF_GROUPS_PER_SIDE} ANY_OF
        groups per side.
      </p>

      {canvas.slots.map((slot, slotIndex) => {
        const slotShowsGroupMode =
          rowFolds(slot.hub) || Object.values(slot.targetByNodeId).some((t) => rowFolds(t));
        return (
        <section key={slot.id} className="srse-card" style={{ marginBottom: "0.75rem" }}>
          <div style={{ display: "flex", gap: "0.5rem", alignItems: "center", marginBottom: "0.5rem" }}>
            <strong>Edge {slotIndex + 1}</strong>
            <button
              type="button"
              className="srse-btn srse-btn-ghost srse-btn-sm"
              disabled={slotIndex === 0}
              aria-label={`Move edge ${slotIndex + 1} up`}
              onClick={() => onCanvasChange(reorderJoinCanvasSlots(canvas, slotIndex, slotIndex - 1))}
            >
              ↑
            </button>
            <button
              type="button"
              className="srse-btn srse-btn-ghost srse-btn-sm"
              disabled={slotIndex >= canvas.slots.length - 1}
              aria-label={`Move edge ${slotIndex + 1} down`}
              onClick={() => onCanvasChange(reorderJoinCanvasSlots(canvas, slotIndex, slotIndex + 1))}
            >
              ↓
            </button>
          </div>
          <div style={{ display: "flex", gap: "1rem", flexWrap: "wrap" }}>
            <div style={{ flex: "1 1 220px" }}>
              <span className="srse-text-muted" style={fieldLabelStyle}>
                Hub columns
              </span>
              <JoinSlotSideEditor
                idPrefix={`canvas-slot-${slot.id}-hub`}
                side={slot.hub}
                columns={slot.hub.columns ?? []}
                disabled={!hub || !isCascadeComplete(hub.tableRef)}
                modeEditable={slotShowsGroupMode}
                anyOfHubCount={
                  slot.hub.mode === "ANY_OF" && rowFolds(slot.hub) ? anyOfHubCount - 1 : anyOfHubCount
                }
                onChange={(patch) => updateSlotHub(slot.id, patch)}
                onLoadColumns={
                  hub && isCascadeComplete(hub.tableRef)
                    ? () => loadColumns(hub.tableRef).then((cols) => updateSlotHub(slot.id, {}, cols))
                    : undefined
                }
              />
            </div>
            {targets.map((node) => {
              const side = slot.targetByNodeId[node.id] ?? emptySideForTable(node.tableRef);
              const pairedSide = { ...side, ref: node.tableRef };
              const paired =
                isCriterionRowFilled(slot.hub)
                && isCriterionRowFilled(pairedSide);
              const fuzzy = paired && pairIsFuzzy(slot.hub, pairedSide, registeredFuzzyFor);
              const targetRowsForAnyOf = canvas.slots.map((s) => s.targetByNodeId[node.id] ?? emptySideForTable(node.tableRef));
              const anyOfTargetCount = countAnyOfGroups(targetRowsForAnyOf);
              const targetShowsGroupMode = rowFolds(pairedSide) || rowFolds(slot.hub);
              return (
                <div key={node.id} style={{ flex: "1 1 220px" }}>
                  <span className="srse-text-muted" style={fieldLabelStyle}>
                    {node.label.trim() || "Target"} columns
                  </span>
                  <JoinSlotSideEditor
                    idPrefix={`canvas-slot-${slot.id}-tgt-${node.id}`}
                    side={pairedSide}
                    columns={side.columns ?? []}
                    disabled={!isCascadeComplete(node.tableRef)}
                    modeEditable={targetShowsGroupMode}
                    anyOfHubCount={
                      pairedSide.mode === "ANY_OF" && rowFolds(pairedSide) ? anyOfTargetCount - 1 : anyOfTargetCount
                    }
                    onChange={(patch) => updateSlotTarget(slot.id, node.id, patch)}
                    onLoadColumns={
                      isCascadeComplete(node.tableRef)
                        ? () => loadColumns(node.tableRef).then((cols) => updateSlotTarget(slot.id, node.id, {}, cols))
                        : undefined
                    }
                  />
                  {fuzzy && (
                    <label className="srse-text-muted" style={{ display: "block", fontSize: "0.72rem", marginTop: "0.25rem" }}>
                      Fuzzy % (group)
                      <input
                        type="number"
                        className="srse-input"
                        style={{ width: 64, marginLeft: "0.35rem" }}
                        min={0}
                        max={100}
                        value={slot.hub.fuzzyThresholdPercent}
                        onChange={(e) => updateSlotHub(slot.id, { fuzzyThresholdPercent: Number(e.target.value) })}
                      />
                    </label>
                  )}
                </div>
              );
            })}
          </div>
        </section>
      );
      })}

      <button type="button" className="srse-btn srse-btn-ghost srse-btn-sm" onClick={addSlot}>
        + Add join edge
      </button>
    </div>
  );
}

export { createInitialJoinCanvas };
