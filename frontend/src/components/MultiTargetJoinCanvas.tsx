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
  type MultiTargetRecordMatchRequest,
  type RegisteredColumn,
  type TableRef,
} from "@/lib/analysisApi";
import {
  isCriterionRowFilled,
  pairIsFuzzy,
  type CriterionRowModel,
} from "@/lib/analysisCriterionModel";
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
      nodes: [...canvas.nodes, { id, kind: "target", label, tableRef: EMPTY_CASCADE, displayRows: [] }],
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

  return (
    <div style={{ width: "100%" }}>
      <p className="srse-text-muted" style={{ fontSize: "0.82rem", lineHeight: 1.5 }}>
        Join canvas — hub in the centre, targets around it, one edge per join criterion. Multi-target runs{" "}
        <strong>INNER</strong> joins only (N independent hub↔target matches). Package 4 join types apply to
        two-table match, not here.
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
                  {edgeSqlLoading === node.label ? "Planning…" : "Preview SQL (match.sql)"}
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
        Join edges (ordered — index aligns hubCriteria with each target&apos;s joinCriteria)
      </h3>
      <p className="srse-text-muted" style={{ fontSize: "0.78rem" }}>
        Max {ANALYSIS_MAX_GROUP_COLUMNS} columns per side per group; max {ANALYSIS_MAX_ANYOF_GROUPS_PER_SIDE} ANY_OF
        groups per side.
      </p>

      {canvas.slots.map((slot, slotIndex) => (
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
            <div style={{ flex: "1 1 200px" }}>
              <span className="srse-text-muted" style={fieldLabelStyle}>
                Hub column
              </span>
              <ColumnSelect
                id={`canvas-slot-${slot.id}-hub-col`}
                columns={slot.hub.columns ?? []}
                value={slot.hub.column}
                disabled={!hub || !isCascadeComplete(hub.tableRef)}
                onChange={(column) => updateSlotHub(slot.id, { column })}
              />
              {hub && isCascadeComplete(hub.tableRef) && (slot.hub.columns?.length ?? 0) === 0 && (
                <button
                  type="button"
                  className="srse-btn srse-btn-ghost srse-btn-sm"
                  style={{ marginTop: "0.25rem" }}
                  onClick={() => {
                    loadColumns(hub.tableRef).then((cols) => updateSlotHub(slot.id, {}, cols));
                  }}
                >
                  Load columns
                </button>
              )}
            </div>
            {targets.map((node) => {
              const side = slot.targetByNodeId[node.id];
              const paired = side && isCriterionRowFilled(slot.hub) && isCriterionRowFilled({ ...side, ref: node.tableRef });
              const fuzzy =
                paired && pairIsFuzzy(slot.hub, { ...side, ref: node.tableRef }, registeredFuzzyFor);
              return (
                <div key={node.id} style={{ flex: "1 1 200px" }}>
                  <span className="srse-text-muted" style={fieldLabelStyle}>
                    {node.label.trim() || "Target"} column
                  </span>
                  <ColumnSelect
                    id={`canvas-slot-${slot.id}-tgt-${node.id}`}
                    columns={side?.columns ?? []}
                    value={side?.column ?? ""}
                    disabled={!isCascadeComplete(node.tableRef)}
                    onChange={(column) => updateSlotTarget(slot.id, node.id, { column })}
                  />
                  {isCascadeComplete(node.tableRef) && (side?.columns?.length ?? 0) === 0 && (
                    <button
                      type="button"
                      className="srse-btn srse-btn-ghost srse-btn-sm"
                      style={{ marginTop: "0.25rem" }}
                      onClick={() => {
                        loadColumns(node.tableRef).then((cols) => updateSlotTarget(slot.id, node.id, {}, cols));
                      }}
                    >
                      Load columns
                    </button>
                  )}
                  {fuzzy && (
                    <label className="srse-text-muted" style={{ display: "block", fontSize: "0.72rem", marginTop: "0.25rem" }}>
                      Fuzzy %
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
      ))}

      <button type="button" className="srse-btn srse-btn-ghost srse-btn-sm" onClick={addSlot}>
        + Add join edge
      </button>
    </div>
  );
}

export { createInitialJoinCanvas };
