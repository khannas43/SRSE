"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  downloadMultiTargetMatchCsv,
  downloadRecordMatchCsv,
  fetchAnalysisLimits,
  listAnalysisCatalogs,
  listAnalysisColumns,
  listAnalysisSchemas,
  listAnalysisTables,
  listColumnMetadata,
  qualifiedTableName,
  runMultiTargetMatchStream,
  runRecordMatchStream,
  type AgeUnit,
  type ColumnMetadata,
  type CompareAs,
  type HubSide,
  type MatchProgressEvent,
  type MultiTargetRecordMatchRequest,
  type RecordMatchRequest,
  type RegisteredColumn,
  type TableRef,
} from "@/lib/analysisApi";
import { AnalysisResultsGrid } from "@/components/AnalysisResultsGrid";
import LakehouseCascade, {
  EMPTY_CASCADE,
  isCascadeComplete,
  type CascadeFetchers,
  type CascadeValue,
} from "@/components/LakehouseCascade";

const fieldLabelStyle = { display: "block", marginBottom: "0.3rem", fontSize: "0.82rem" } as const;

/**
 * The officer's cascade is backed by the REGISTRY, not the live cluster —
 * every level offers only what an admin registered on the Admin page. (The
 * Admin page passes live-browse fetchers to this same component; see
 * LakehouseCascade's javadoc.)
 */
const REGISTRY_FETCHERS: CascadeFetchers = {
  listCatalogs: listAnalysisCatalogs,
  listSchemas: listAnalysisSchemas,
  listTables: listAnalysisTables,
};

// A picked table is now a three-part address, not a name: with the Silver and
// Gold layers both registered, the same table name exists under more than one
// catalog, so every comparison and metadata lookup keys on all three parts.
type CriterionRow = {
  id: string;
  ref: CascadeValue;
  column: string;
  columns: RegisteredColumn[];
  fuzzyThresholdPercent: number;
};

type DisplayRow = {
  id: string;
  ref: CascadeValue;
  column: string;
  columns: RegisteredColumn[];
};

function createEmptyRow(): CriterionRow {
  return {
    id: crypto.randomUUID(),
    ref: EMPTY_CASCADE,
    column: "",
    columns: [],
    fuzzyThresholdPercent: 80,
  };
}

function createEmptyDisplayRow(defaultRef: CascadeValue): DisplayRow {
  return {
    id: crypto.randomUUID(),
    ref: isCascadeComplete(defaultRef) ? { ...defaultRef } : EMPTY_CASCADE,
    column: "",
    columns: [],
  };
}

type TargetBlock = {
  id: string;
  label: string;
  joinRows: CriterionRow[];
  displayRows: DisplayRow[];
};

function createTargetBlock(defaultLabel: string): TargetBlock {
  return {
    id: crypto.randomUUID(),
    label: defaultLabel,
    joinRows: [createEmptyRow()],
    displayRows: [],
  };
}

function metadataKey(ref: TableRef, column: string): string {
  return `${qualifiedTableName(ref)}.${column}`;
}

/** A row is usable only once all four levels are picked. */
function isRowFilled(row: CriterionRow): boolean {
  return isCascadeComplete(row.ref) && Boolean(row.column);
}

function isNameColumn(column: string): boolean {
  return column.toLowerCase().includes("name");
}

// Same auto-detect-by-name-substring pattern as fuzzy-on-"*name*" — no manual
// column picker, checked in priority order against the Target table's columns.
const LAST_UPDATED_HINTS = ["updated", "refresh", "modified", "date"];

function detectLastUpdatedColumn(columns: RegisteredColumn[]): string | null {
  for (const hint of LAST_UPDATED_HINTS) {
    const match = columns.find((c) => c.name.toLowerCase().includes(hint));
    if (match) return match.name;
  }
  return null;
}

/**
 * Above this the grid is not rendered at all and the result is offered as a
 * CSV download instead. The rows already buffered are dropped at that point,
 * so the tab's memory does not grow with a result it has decided not to show
 * — and the CSV is streamed fresh from the backend, never rebuilt from what
 * the screen happens to be holding, so it is the COMPLETE result.
 */
const MAX_DISPLAYED_ROWS = 10000;

/**
 * Reading stops here even though rows are no longer being kept: the count
 * shown to the officer comes from having read the stream, and a match with
 * crores of rows would otherwise stream for as long as the timeout allows.
 * Past this the count is reported as a lower bound ("200000+"); the CSV
 * download is still complete, since the backend re-runs the query uncapped.
 */
const MAX_ROWS_TO_PARSE = 200000;

function updateRowById(rows: CriterionRow[], id: string, patch: Partial<CriterionRow>): CriterionRow[] {
  return rows.map((r) => (r.id === id ? { ...r, ...patch } : r));
}

function removeRowById(rows: CriterionRow[], id: string): CriterionRow[] {
  return rows.length <= 1 ? rows : rows.filter((r) => r.id !== id);
}

/**
 * Display-only mirror of the backend's SqlTypeFamily. Used to warn an officer
 * that the two sides of a pair are stored differently — the query itself is
 * built by TypeCoercion on the server, which is the authority. Anything this
 * cannot classify simply produces no hint rather than a wrong one.
 */
function typeFamilyOf(dataType: string | undefined): "text" | "number" | "other" {
  if (!dataType) return "other";
  const base = dataType.trim().toLowerCase().split(/[( ]/)[0];
  if (["varchar", "char", "character", "string"].includes(base)) return "text";
  if (
    ["bigint", "integer", "int", "smallint", "tinyint", "double", "real", "float", "decimal", "numeric"]
      .includes(base)
  ) {
    return "number";
  }
  return "other";
}

function dataTypeOf(row: CriterionRow): string | undefined {
  return row.columns.find((c) => c.name === row.column)?.dataType;
}

/**
 * "These two are stored differently, and here is how they will be compared" —
 * shown only for a text-vs-number pair, which is the one SRSE actually casts
 * and the one that used to fail the query outright.
 */
function mixedTypeHint(
  row: CriterionRow,
  paired: CriterionRow | undefined,
  compareAsFor: (ref: TableRef, column: string) => CompareAs,
): string | null {
  if (!paired || !row.column || !paired.column) return null;
  const own = typeFamilyOf(dataTypeOf(row));
  const other = typeFamilyOf(dataTypeOf(paired));
  if (own === "other" || other === "other" || own === other) return null;

  const override =
    compareAsFor(row.ref, row.column) !== "AUTO"
      ? compareAsFor(row.ref, row.column)
      : compareAsFor(paired.ref, paired.column);
  const mode = override === "TEXT" ? "text" : "numbers";
  return `Types differ (${dataTypeOf(row)} vs ${dataTypeOf(paired)}) — compared as ${mode}.`;
}

type CriterionBoxProps = Readonly<{
  title: string;
  boxId: string;
  rows: CriterionRow[];
  showFuzzy: boolean;
  pairedRows?: CriterionRow[];
  isFuzzyMatchable: (ref: TableRef, column: string) => boolean;
  businessNameFor: (ref: TableRef, column: string) => string | null;
  compareAsFor: (ref: TableRef, column: string) => CompareAs;
  onTableChange: (rowId: string, ref: CascadeValue) => void;
  onColumnChange: (rowId: string, column: string) => void;
  onFuzzyChange: (rowId: string, value: number) => void;
  onRemove: (rowId: string) => void;
  onAdd: () => void;
  onError: (message: string) => void;
}>;

function rowShowsFuzzy(
  row: CriterionRow,
  index: number,
  pairedRows: CriterionRow[] | undefined,
  isFuzzyMatchable: (ref: TableRef, column: string) => boolean,
): boolean {
  if (isFuzzyMatchable(row.ref, row.column)) return true;
  const paired = pairedRows?.[index];
  return paired ? isFuzzyMatchable(paired.ref, paired.column) : false;
}

type DisplayColumnBoxProps = Readonly<{
  title: string;
  boxId: string;
  rows: DisplayRow[];
  businessNameFor: (ref: TableRef, column: string) => string | null;
  onTableChange: (rowId: string, ref: CascadeValue) => void;
  onColumnChange: (rowId: string, column: string) => void;
  onRemove: (rowId: string) => void;
  onAdd: () => void;
  onError: (message: string) => void;
}>;

function DisplayColumnBox({
  title,
  boxId,
  rows,
  businessNameFor,
  onTableChange,
  onColumnChange,
  onRemove,
  onAdd,
  onError,
}: DisplayColumnBoxProps) {
  return (
    <div style={{ marginTop: "0.75rem" }}>
      <h3 className="srse-text-muted" style={{ fontSize: "0.85rem", margin: "0 0 0.35rem" }}>
        {title}
      </h3>
      {rows.map((row, index) => (
        <div
          key={row.id}
          style={{
            display: "flex",
            gap: "0.6rem",
            alignItems: "flex-end",
            flexWrap: "wrap",
            marginBottom: "0.5rem",
            paddingBottom: "0.5rem",
            borderBottom: index === rows.length - 1 ? "none" : "1px solid var(--srse-border)",
          }}
        >
          <div style={{ flex: "1 1 100%" }}>
            <LakehouseCascade
              value={row.ref}
              onChange={(ref) => onTableChange(row.id, ref)}
              fetchers={REGISTRY_FETCHERS}
              idPrefix={`${boxId}-${row.id}`}
              onError={onError}
            />
          </div>
          <div style={{ flex: "1 1 150px" }}>
            <label htmlFor={`${boxId}-column-${row.id}`} className="srse-text-muted" style={fieldLabelStyle}>
              Column
            </label>
            <select
              id={`${boxId}-column-${row.id}`}
              className="srse-select"
              style={{ width: "100%" }}
              value={row.column}
              onChange={(e) => onColumnChange(row.id, e.target.value)}
              disabled={!isCascadeComplete(row.ref)}
            >
              <option value="">— select —</option>
              {row.columns.map((c) => (
                <option key={c.name} value={c.name}>
                  {businessNameFor(row.ref, c.name) ?? c.name}
                </option>
              ))}
            </select>
          </div>
          <button
            type="button"
            className="srse-btn srse-btn-ghost srse-btn-sm"
            onClick={() => onRemove(row.id)}
            title="Remove this row"
          >
            ✕
          </button>
        </div>
      ))}
      <button type="button" className="srse-btn srse-btn-ghost srse-btn-sm" onClick={onAdd}>
        + Add column to show
      </button>
    </div>
  );
}

function CriterionBox({
  title,
  boxId,
  rows,
  showFuzzy,
  pairedRows,
  isFuzzyMatchable,
  businessNameFor,
  compareAsFor,
  onTableChange,
  onColumnChange,
  onFuzzyChange,
  onRemove,
  onAdd,
  onError,
  displaySection,
}: CriterionBoxProps & { displaySection?: React.ReactNode }) {
  return (
    <section className="srse-card" style={{ flex: "1 1 380px" }}>
      <h2 className="srse-card-title">{title}</h2>
      <p className="srse-text-muted" style={{ fontSize: "0.82rem", marginTop: 0 }}>
        Catalog › Schema › Table › Column — registered lakehouse tables only
      </p>

      {rows.map((row, index) => (
        <div
          key={row.id}
          style={{
            display: "flex",
            gap: "0.6rem",
            alignItems: "flex-end",
            flexWrap: "wrap",
            marginBottom: "0.6rem",
            paddingBottom: "0.6rem",
            borderBottom: index === rows.length - 1 ? "none" : "1px solid var(--srse-border)",
          }}
        >
          <div style={{ flex: "1 1 100%" }}>
            <LakehouseCascade
              value={row.ref}
              onChange={(ref) => onTableChange(row.id, ref)}
              fetchers={REGISTRY_FETCHERS}
              idPrefix={`${boxId}-${row.id}`}
              onError={onError}
            />
          </div>
          <div style={{ flex: "1 1 150px" }}>
            <label htmlFor={`${boxId}-column-${row.id}`} className="srse-text-muted" style={fieldLabelStyle}>
              Column
            </label>
            <select
              id={`${boxId}-column-${row.id}`}
              className="srse-select"
              style={{ width: "100%" }}
              value={row.column}
              onChange={(e) => onColumnChange(row.id, e.target.value)}
              disabled={!isCascadeComplete(row.ref)}
            >
              <option value="">— select —</option>
              {row.columns.map((c) => (
                <option key={c.name} value={c.name}>
                  {businessNameFor(row.ref, c.name) ?? c.name}
                </option>
              ))}
            </select>
            {dataTypeOf(row) && (
              <div className="srse-text-muted" style={{ fontSize: "0.7rem", marginTop: "0.2rem" }}>
                {dataTypeOf(row)}
              </div>
            )}
            {(() => {
              const hint = mixedTypeHint(row, pairedRows?.[index], compareAsFor);
              return hint ? (
                <div
                  style={{ fontSize: "0.7rem", marginTop: "0.15rem", color: "var(--srse-warning)" }}
                  title="SRSE casts the pair so the match can run at all. Set 'Compare as' on the Admin page to force the other reading."
                >
                  {hint}
                </div>
              ) : null;
            })()}
          </div>
          {showFuzzy && rowShowsFuzzy(row, index, pairedRows, isFuzzyMatchable) && (
            <div style={{ flex: "0 1 100px" }}>
              <label htmlFor={`${boxId}-fuzzy-${row.id}`} className="srse-text-muted" style={fieldLabelStyle}>
                Fuzzy %
              </label>
              <input
                id={`${boxId}-fuzzy-${row.id}`}
                type="number"
                className="srse-input"
                style={{ width: "100%" }}
                min={0}
                max={100}
                value={row.fuzzyThresholdPercent}
                onChange={(e) => onFuzzyChange(row.id, Number(e.target.value))}
              />
            </div>
          )}
          {rows.length > 1 && (
            <button
              type="button"
              className="srse-btn srse-btn-ghost srse-btn-sm"
              onClick={() => onRemove(row.id)}
              title="Remove this row"
            >
              ✕
            </button>
          )}
        </div>
      ))}

      <button type="button" className="srse-btn srse-btn-ghost srse-btn-sm" onClick={onAdd}>
        + Add more
      </button>
      {displaySection}
    </section>
  );
}

export default function AnalysisPage() {
  const [loadError, setLoadError] = useState<string | null>(null);
  const [columnMetadata, setColumnMetadata] = useState<Map<string, ColumnMetadata>>(new Map());

  const [highlightDuplicates, setHighlightDuplicates] = useState(false);

  const [sourceRows, setSourceRows] = useState<CriterionRow[]>([createEmptyRow()]);
  const [targetRows, setTargetRows] = useState<CriterionRow[]>([createEmptyRow()]);
  const [sourceDisplayRows, setSourceDisplayRows] = useState<DisplayRow[]>([]);
  const [targetDisplayRows, setTargetDisplayRows] = useState<DisplayRow[]>([]);

  const [multiMatchMode, setMultiMatchMode] = useState(false);
  const [hubSide, setHubSide] = useState<HubSide>("SOURCE");
  const [targetBlocks, setTargetBlocks] = useState<TargetBlock[]>([createTargetBlock("Target 1")]);
  const [maxTargetSets, setMaxTargetSets] = useState(5);
  const [targetSetFilter, setTargetSetFilter] = useState<string>("All");
  const [matchProgress, setMatchProgress] = useState<MatchProgressEvent[]>([]);

  const [dedupEnabled, setDedupEnabled] = useState(false);
  const dedupColumn = detectLastUpdatedColumn(
    (multiMatchMode ? sourceRows[0] : targetRows[0])?.columns ?? [],
  );

  const [ageFilterEnabled, setAgeFilterEnabled] = useState(false);
  const [minAge, setMinAge] = useState(0);
  const [maxAge, setMaxAge] = useState(100);
  const [ageUnit, setAgeUnit] = useState<AgeUnit>("YEARS");

  const [matchStatus, setMatchStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [matchError, setMatchError] = useState<string | null>(null);
  const [matchColumns, setMatchColumns] = useState<string[]>([]);
  const [matchRows, setMatchRows] = useState<Record<string, unknown>[]>([]);
  const [matchSql, setMatchSql] = useState("");
  // The backend deliberately no longer caps the match result (see
  // RecordMatchService's javadoc) — but a browser tab still cannot hold or
  // recompute over an unbounded row array without crashing (confirmed live:
  // an OOM tab crash on a match with far more matches than the grid/charts
  // could safely render). These are a display-side safety limit, not a
  // reintroduction of that backend guardrail: the true total is still
  // counted and shown even when not every row is rendered.
  const [matchTotalRows, setMatchTotalRows] = useState<number | null>(null);
  // Set the moment the result outgrows MAX_DISPLAYED_ROWS: the grid switches
  // to the CSV-only panel and the buffered rows are released.
  const [matchTooManyToDisplay, setMatchTooManyToDisplay] = useState(false);
  // True when we stopped reading before the stream finished naturally, so
  // matchTotalRows (if set at all) is a lower bound, not an exact count.
  const [matchCountIsPartial, setMatchCountIsPartial] = useState(false);
  // True for the whole streaming lifecycle (first byte to last), not just the
  // initial network round trip — drives the grid's "(loading more…)" caption
  // and disables CSV/column-visibility until the result is actually complete.
  const matchStreaming = matchStatus === "loading";

  // The request the displayed result actually came from. The CSV download must
  // use THIS, not a freshly built one: the officer may have edited the criteria
  // since running the match, and a file that quietly answers a different
  // question than the count on screen is worse than no file.
  const lastRunRequestRef = useRef<RecordMatchRequest | null>(null);
  const lastRunMultiRequestRef = useRef<MultiTargetRecordMatchRequest | null>(null);
  const pendingRowsRef = useRef<Record<string, unknown>[]>([]);
  const flushIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const rowsSeenRef = useRef(0);

  useEffect(() => {
    // The table list is no longer fetched here — each CriterionBox's cascade
    // loads its own levels from the registry on demand.
    listColumnMetadata()
      .then((entries) => setColumnMetadata(new Map(entries.map((e) => [metadataKey(e, e.column), e]))))
      .catch((err: unknown) => setLoadError(err instanceof Error ? err.message : String(err)));
    fetchAnalysisLimits()
      .then((limits) => setMaxTargetSets(limits.maxTargetSets))
      .catch(() => {
        /* keep default */
      });
  }, []);

  const reportError = useCallback((message: string) => setLoadError(message), []);

  // Admin-registered override takes precedence, on either side of the pair
  // it's used for — same fallback order as the backend (RecordMatchService),
  // so the UI's Fuzzy % control and the actual query never disagree.
  function isFuzzyMatchable(ref: TableRef, column: string): boolean {
    const entry = columnMetadata.get(metadataKey(ref, column));
    return entry ? entry.fuzzyMatchable : isNameColumn(column);
  }

  function businessNameFor(ref: TableRef, column: string): string | null {
    return columnMetadata.get(metadataKey(ref, column))?.businessName ?? null;
  }

  function compareAsFor(ref: TableRef, column: string): CompareAs {
    return columnMetadata.get(metadataKey(ref, column))?.compareAs ?? "AUTO";
  }

  async function handleTableChange(
    setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>,
    rowId: string,
    ref: CascadeValue,
  ) {
    // Clearing the column alongside the table matters more than it used to:
    // changing only the catalog can leave a column name that exists in both
    // layers, which would submit a valid-looking but wrong reference.
    setRows((rows) => updateRowById(rows, rowId, { ref, column: "", columns: [] }));
    if (!isCascadeComplete(ref)) return;
    try {
      const cols = await listAnalysisColumns(ref);
      setRows((rows) => updateRowById(rows, rowId, { columns: cols }));
    } catch (err: unknown) {
      setLoadError(err instanceof Error ? err.message : String(err));
    }
  }

  function handleColumnChange(
    setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>,
    rowId: string,
    column: string,
  ) {
    setRows((rows) => updateRowById(rows, rowId, { column }));
  }

  function handleFuzzyChange(
    setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>,
    rowId: string,
    fuzzyThresholdPercent: number,
  ) {
    setRows((rows) => updateRowById(rows, rowId, { fuzzyThresholdPercent }));
  }

  function handleRemoveRow(setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>, rowId: string) {
    setRows((rows) => removeRowById(rows, rowId));
  }

  function defaultSideRef(sideRows: CriterionRow[]): CascadeValue {
    const filled = sideRows.find(isRowFilled);
    return filled?.ref ?? sideRows[0]?.ref ?? EMPTY_CASCADE;
  }

  function isDisplayRowFilled(row: DisplayRow): boolean {
    return isCascadeComplete(row.ref) && Boolean(row.column);
  }

  async function handleDisplayTableChange(
    setRows: React.Dispatch<React.SetStateAction<DisplayRow[]>>,
    rowId: string,
    ref: CascadeValue,
  ) {
    setRows((rows) => rows.map((r) => (r.id === rowId ? { ...r, ref, column: "", columns: [] } : r)));
    if (!isCascadeComplete(ref)) return;
    try {
      const cols = await listAnalysisColumns(ref);
      setRows((rows) => rows.map((r) => (r.id === rowId ? { ...r, columns: cols } : r)));
    } catch (err: unknown) {
      setLoadError(err instanceof Error ? err.message : String(err));
    }
  }

  function buildRequest(withDedup: boolean): RecordMatchRequest | null {
    const filledSource = sourceRows.filter(isRowFilled);
    const filledTarget = targetRows.filter(isRowFilled);
    const n = Math.min(filledSource.length, filledTarget.length);
    if (n === 0) return null;
    const dedupRef = targetRows[0]?.ref;
    const filledSourceDisplay = sourceDisplayRows.filter(isDisplayRowFilled);
    const filledTargetDisplay = targetDisplayRows.filter(isDisplayRowFilled);
    const req: RecordMatchRequest = {
      sourceCriteria: filledSource.slice(0, n).map((r, i) => ({
        ...r.ref,
        column: r.column,
        fuzzyThresholdPercent:
          isFuzzyMatchable(r.ref, r.column) || isFuzzyMatchable(filledTarget[i].ref, filledTarget[i].column)
            ? r.fuzzyThresholdPercent
            : null,
      })),
      targetCriteria: filledTarget.slice(0, n).map((r) => ({
        ...r.ref,
        column: r.column,
        fuzzyThresholdPercent: null,
      })),
      highlightDuplicates,
      dedup:
        withDedup && dedupColumn && dedupRef && isCascadeComplete(dedupRef)
          ? { ...dedupRef, column: dedupColumn }
          : null,
      ageFilter: ageFilterEnabled ? { minAge, maxAge, unit: ageUnit } : null,
    };
    if (filledSourceDisplay.length > 0) {
      req.sourceDisplayColumns = filledSourceDisplay.map((r) => ({ ...r.ref, column: r.column }));
    }
    if (filledTargetDisplay.length > 0) {
      req.targetDisplayColumns = filledTargetDisplay.map((r) => ({ ...r.ref, column: r.column }));
    }
    return req;
  }

  function buildMultiRequest(withDedup: boolean): MultiTargetRecordMatchRequest | null {
    const filledHub = sourceRows.filter(isRowFilled);
    if (filledHub.length === 0) return null;

    const targets: MultiTargetRecordMatchRequest["targets"] = [];
    for (const block of targetBlocks) {
      const label = block.label.trim();
      if (!label) return null;
      const filledJoin = block.joinRows.filter(isRowFilled);
      const n = Math.min(filledHub.length, filledJoin.length);
      if (n === 0) continue;
      const tableRef = filledJoin[0].ref;
      if (!isCascadeComplete(tableRef)) return null;
      const filledDisplay = block.displayRows.filter(isDisplayRowFilled);
      targets.push({
        label,
        ...tableRef,
        joinCriteria: filledJoin.slice(0, n).map((r, i) => ({
          ...r.ref,
          column: r.column,
          fuzzyThresholdPercent:
            isFuzzyMatchable(filledHub[i].ref, filledHub[i].column) ||
            isFuzzyMatchable(r.ref, r.column)
              ? filledHub[i].fuzzyThresholdPercent
              : null,
        })),
        displayColumns:
          filledDisplay.length > 0
            ? filledDisplay.map((r) => ({ ...r.ref, column: r.column }))
            : undefined,
      });
    }
    if (targets.length === 0) return null;

    const hubRef = filledHub[0].ref;
    const filledHubDisplay = sourceDisplayRows.filter(isDisplayRowFilled);
    const req: MultiTargetRecordMatchRequest = {
      hubCriteria: filledHub.map((r) => ({
        ...r.ref,
        column: r.column,
        fuzzyThresholdPercent: isFuzzyMatchable(r.ref, r.column) ? r.fuzzyThresholdPercent : null,
      })),
      hubSide,
      targets,
      highlightDuplicates,
      dedup:
        withDedup && dedupColumn && isCascadeComplete(hubRef)
          ? { ...hubRef, column: dedupColumn }
          : null,
      ageFilter: ageFilterEnabled ? { minAge, maxAge, unit: ageUnit } : null,
    };
    if (filledHubDisplay.length > 0) {
      req.hubDisplayColumns = filledHubDisplay.map((r) => ({ ...r.ref, column: r.column }));
    }
    return req;
  }

  function buildColumnLabels(): Record<string, string> {
    const labels: Record<string, string> = {};
    const labelSide = (prefix: string, side: string, ref: TableRef, column: string) => {
      const bn = businessNameFor(ref, column);
      if (bn) labels[`${prefix}_${column}`] = `${side}: ${bn}`;
    };
    for (const r of sourceRows) {
      if (isRowFilled(r)) labelSide("source", "Source", r.ref, r.column);
    }
    for (const r of targetRows) {
      if (isRowFilled(r)) labelSide("target", "Target", r.ref, r.column);
    }
    for (const r of sourceDisplayRows) {
      if (isDisplayRowFilled(r)) labelSide("source", "Source", r.ref, r.column);
    }
    for (const r of targetDisplayRows) {
      if (isDisplayRowFilled(r)) labelSide("target", "Target", r.ref, r.column);
    }
    return labels;
  }

  function flushPendingRows() {
    if (pendingRowsRef.current.length > 0) {
      const batch = pendingRowsRef.current;
      pendingRowsRef.current = [];
      // A flush already in flight when the limit was crossed must not put the
      // dropped rows back.
      setMatchRows((prev) => (rowsSeenRef.current > MAX_DISPLAYED_ROWS ? [] : [...prev, ...batch]));
    }
  }

  function beginMatchStream() {
    setMatchStatus("loading");
    setMatchError(null);
    setMatchColumns([]);
    setMatchRows([]);
    setMatchSql("");
    setMatchTotalRows(null);
    setMatchCountIsPartial(false);
    setMatchTooManyToDisplay(false);
    pendingRowsRef.current = [];
    rowsSeenRef.current = 0;
    if (flushIntervalRef.current !== null) {
      clearInterval(flushIntervalRef.current);
    }
    flushIntervalRef.current = setInterval(flushPendingRows, 100);
  }

  function appendStreamRow(row: Record<string, unknown>, controller: AbortController) {
    rowsSeenRef.current += 1;
    if (rowsSeenRef.current <= MAX_DISPLAYED_ROWS) {
      pendingRowsRef.current.push(row);
    } else if (rowsSeenRef.current === MAX_DISPLAYED_ROWS + 1) {
      pendingRowsRef.current = [];
      setMatchTooManyToDisplay(true);
      setMatchRows([]);
    }
    if (rowsSeenRef.current >= MAX_ROWS_TO_PARSE) {
      setMatchCountIsPartial(true);
      controller.abort();
    }
  }

  function finishStreamFlush() {
    if (flushIntervalRef.current !== null) {
      clearInterval(flushIntervalRef.current);
      flushIntervalRef.current = null;
    }
    flushPendingRows();
  }

  function handleStreamAbort(err: unknown, controller: AbortController) {
    if (controller.signal.aborted) {
      setMatchTotalRows(rowsSeenRef.current);
      setMatchStatus("ok");
    } else {
      setMatchError(err instanceof Error ? err.message : String(err));
      setMatchStatus("error");
    }
  }

  /**
   * Streams the COMPLETE result to a file. Built from a fresh backend request,
   * not from `matchRows` — above MAX_DISPLAYED_ROWS the browser deliberately
   * never held the rows, and even below it the grid holds only what it was
   * given. The match query therefore runs again, which is why this is on an
   * explicit click.
   */
  async function downloadFullCsv() {
    if (multiMatchMode) {
      const multiReq = lastRunMultiRequestRef.current;
      if (!multiReq) {
        throw new Error("Run a match first.");
      }
      const blob = await downloadMultiTargetMatchCsv(multiReq);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `analysis-match-multi-${new Date().toISOString().slice(0, 19).replaceAll(/[:T]/g, "-")}.csv`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
      return;
    }
    const req = lastRunRequestRef.current;
    if (!req) {
      throw new Error("Run a match first.");
    }
    const blob = await downloadRecordMatchCsv(req);
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `analysis-match-${new Date().toISOString().slice(0, 19).replaceAll(/[:T]/g, "-")}.csv`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  }

  async function runMatch(withDedup: boolean) {
    if (multiMatchMode) {
      const multiReq = buildMultiRequest(withDedup);
      if (!multiReq) {
        setMatchError(
          "Pick hub match columns and at least one target set (label + paired match columns on each target).",
        );
        return;
      }
      lastRunMultiRequestRef.current = multiReq;
      lastRunRequestRef.current = null;
      setMatchProgress([]);
      beginMatchStream();
      const controller = new AbortController();
      try {
        await runMultiTargetMatchStream(
          multiReq,
          {
            onMeta: (meta) => {
              setMatchColumns(meta.columns);
              setMatchSql("");
            },
            onProgress: (event) => {
              setMatchProgress((prev) => {
                const next = prev.filter((p) => p.label !== event.label || event.phase === "started");
                return [...next, event];
              });
              // Each target announces its own SQL as it is planned — the meta
              // line is written before any of them exist.
              if (event.sql) {
                setMatchSql((prev) =>
                  prev ? `${prev}\n\n---\n\n${event.sql}` : (event.sql as string),
                );
              }
            },
            onRow: (row) => appendStreamRow(row, controller),
            onDone: (totalRows) => {
              setMatchTotalRows(totalRows);
              setMatchStatus("ok");
            },
            onError: (message) => {
              setMatchError(message);
              setMatchStatus("error");
            },
          },
          controller.signal,
        );
      } catch (err: unknown) {
        handleStreamAbort(err, controller);
      } finally {
        finishStreamFlush();
      }
      return;
    }

    const req = buildRequest(withDedup);
    if (!req) {
      setMatchError("Pick at least one Source table + column and one matching Target table + column.");
      return;
    }
    lastRunRequestRef.current = req;
    lastRunMultiRequestRef.current = null;
    beginMatchStream();

    const controller = new AbortController();
    try {
      await runRecordMatchStream(
        req,
        {
          onMeta: (meta) => {
            setMatchColumns(meta.columns);
            setMatchSql(meta.sql);
          },
          onRow: (row) => appendStreamRow(row, controller),
          onDone: (totalRows) => {
            setMatchTotalRows(totalRows);
            setMatchStatus("ok");
          },
          onError: (message) => {
            setMatchError(message);
            setMatchStatus("error");
          },
        },
        controller.signal,
      );
    } catch (err: unknown) {
      handleStreamAbort(err, controller);
    } finally {
      finishStreamFlush();
    }
  }

  const displayedMatchRows =
    multiMatchMode && targetSetFilter !== "All"
      ? matchRows.filter((row) => row.match_set_label === targetSetFilter)
      : matchRows;

  return (
    <main className="srse-page">
      <h1 className="srse-page-title">Analysis</h1>
      <p className="srse-page-description" style={{ maxWidth: "none", whiteSpace: "nowrap" }}>
        Reconcile records across lakehouse tables and columns — fuzzy-match on names, review duplicates, and
        export a clean result set. Nothing here is ever deleted from the lakehouse.
      </p>

      {loadError && <p className="srse-text-danger">{loadError}</p>}

      <label className="srse-checkbox-label" htmlFor="highlight-duplicates" style={{ marginBottom: "0.5rem", display: "inline-flex" }}>
        <input
          id="highlight-duplicates"
          type="checkbox"
          checked={highlightDuplicates}
          onChange={(e) => setHighlightDuplicates(e.target.checked)}
        />
        {" "}
        Highlight Duplicate Records
        {multiMatchMode && highlightDuplicates && (
          <span className="srse-text-muted" style={{ marginLeft: "0.5rem", fontSize: "0.78rem" }} title="Scores are per target set and are not comparable across sets.">
            (match score is per target set)
          </span>
        )}
      </label>

      <label className="srse-checkbox-label" htmlFor="multi-match-mode" style={{ marginBottom: "1rem", display: "inline-flex", marginLeft: "1.5rem" }}>
        <input
          id="multi-match-mode"
          type="checkbox"
          checked={multiMatchMode}
          onChange={(e) => setMultiMatchMode(e.target.checked)}
        />
        {" "}
        Match against multiple tables
      </label>

      {multiMatchMode && (
        <div style={{ marginBottom: "1rem" }}>
          <span className="srse-text-muted" style={{ fontSize: "0.82rem", marginRight: "0.75rem" }}>Hub is the</span>
          <label className="srse-checkbox-label" htmlFor="hub-side-source" style={{ display: "inline-flex", marginRight: "1rem" }}>
            <input
              id="hub-side-source"
              type="radio"
              name="hub-side"
              checked={hubSide === "SOURCE"}
              onChange={() => setHubSide("SOURCE")}
            />
            {" "}
            Source side
          </label>
          <label className="srse-checkbox-label" htmlFor="hub-side-target" style={{ display: "inline-flex" }}>
            <input
              id="hub-side-target"
              type="radio"
              name="hub-side"
              checked={hubSide === "TARGET"}
              onChange={() => setHubSide("TARGET")}
            />
            {" "}
            Target side
          </label>
        </div>
      )}

      <div style={{ display: "flex", gap: "1rem", flexWrap: "wrap", width: "100%" }}>
        <CriterionBox
          title={multiMatchMode ? "Hub table — match on" : "Select Source"}
          boxId="source"
          rows={sourceRows}
          showFuzzy
          pairedRows={multiMatchMode ? undefined : targetRows}
          isFuzzyMatchable={isFuzzyMatchable}
          businessNameFor={businessNameFor}
          compareAsFor={compareAsFor}
          onTableChange={(rowId, ref) => handleTableChange(setSourceRows, rowId, ref)}
          onColumnChange={(rowId, column) => handleColumnChange(setSourceRows, rowId, column)}
          onFuzzyChange={(rowId, value) => handleFuzzyChange(setSourceRows, rowId, value)}
          onRemove={(rowId) => handleRemoveRow(setSourceRows, rowId)}
          onAdd={() => setSourceRows((rs) => [...rs, createEmptyRow()])}
          onError={reportError}
          displaySection={
            <DisplayColumnBox
              title="Also show (not matched on)"
              boxId="source-display"
              rows={sourceDisplayRows}
              businessNameFor={businessNameFor}
              onTableChange={(rowId, ref) => handleDisplayTableChange(setSourceDisplayRows, rowId, ref)}
              onColumnChange={(rowId, column) =>
                setSourceDisplayRows((rows) => rows.map((r) => (r.id === rowId ? { ...r, column } : r)))
              }
              onRemove={(rowId) => setSourceDisplayRows((rows) => rows.filter((r) => r.id !== rowId))}
              onAdd={() => {
                const ref = defaultSideRef(sourceRows);
                const row = createEmptyDisplayRow(ref);
                setSourceDisplayRows((rows) => [...rows, row]);
                if (isCascadeComplete(ref)) {
                  listAnalysisColumns(ref)
                    .then((cols) =>
                      setSourceDisplayRows((rows) =>
                        rows.map((r) => (r.id === row.id ? { ...r, columns: cols } : r)),
                      ),
                    )
                    .catch((err: unknown) => setLoadError(err instanceof Error ? err.message : String(err)));
                }
              }}
              onError={reportError}
            />
          }
        />
        {!multiMatchMode && (
          <CriterionBox
            title="Select Target"
            boxId="target"
            rows={targetRows}
            showFuzzy={false}
            isFuzzyMatchable={isFuzzyMatchable}
            businessNameFor={businessNameFor}
            compareAsFor={compareAsFor}
            onTableChange={(rowId, ref) => handleTableChange(setTargetRows, rowId, ref)}
            onColumnChange={(rowId, column) => handleColumnChange(setTargetRows, rowId, column)}
            onFuzzyChange={(rowId, value) => handleFuzzyChange(setTargetRows, rowId, value)}
            onRemove={(rowId) => handleRemoveRow(setTargetRows, rowId)}
            onAdd={() => setTargetRows((rs) => [...rs, createEmptyRow()])}
            onError={reportError}
            displaySection={
              <DisplayColumnBox
                title="Also show (not matched on)"
                boxId="target-display"
                rows={targetDisplayRows}
                businessNameFor={businessNameFor}
                onTableChange={(rowId, ref) => handleDisplayTableChange(setTargetDisplayRows, rowId, ref)}
                onColumnChange={(rowId, column) =>
                  setTargetDisplayRows((rows) => rows.map((r) => (r.id === rowId ? { ...r, column } : r)))
                }
                onRemove={(rowId) => setTargetDisplayRows((rows) => rows.filter((r) => r.id !== rowId))}
                onAdd={() => {
                  const ref = defaultSideRef(targetRows);
                  const row = createEmptyDisplayRow(ref);
                  setTargetDisplayRows((rows) => [...rows, row]);
                  if (isCascadeComplete(ref)) {
                    listAnalysisColumns(ref)
                      .then((cols) =>
                        setTargetDisplayRows((rows) =>
                          rows.map((r) => (r.id === row.id ? { ...r, columns: cols } : r)),
                        ),
                      )
                      .catch((err: unknown) => setLoadError(err instanceof Error ? err.message : String(err)));
                  }
                }}
                onError={reportError}
              />
            }
          />
        )}
      </div>

      {multiMatchMode && (
        <div style={{ width: "100%", marginTop: "1rem" }}>
          {targetBlocks.map((block, blockIndex) => (
            <section key={block.id} className="srse-card" style={{ marginBottom: "1rem" }}>
              <div style={{ display: "flex", gap: "0.75rem", alignItems: "center", marginBottom: "0.5rem" }}>
                <label htmlFor={`target-label-${block.id}`} className="srse-text-muted" style={fieldLabelStyle}>
                  Target set label
                </label>
                <input
                  id={`target-label-${block.id}`}
                  className="srse-input"
                  style={{ flex: "1 1 200px" }}
                  value={block.label}
                  onChange={(e) =>
                    setTargetBlocks((blocks) =>
                      blocks.map((b) => (b.id === block.id ? { ...b, label: e.target.value } : b)),
                    )
                  }
                />
                {targetBlocks.length > 1 && (
                  <button
                    type="button"
                    className="srse-btn srse-btn-ghost srse-btn-sm"
                    onClick={() => setTargetBlocks((blocks) => blocks.filter((b) => b.id !== block.id))}
                  >
                    Remove set
                  </button>
                )}
              </div>
              <CriterionBox
                title={`Target ${blockIndex + 1} — match on (paired with hub rows)`}
                boxId={`target-block-${block.id}`}
                rows={block.joinRows}
                showFuzzy={false}
                pairedRows={sourceRows}
                isFuzzyMatchable={isFuzzyMatchable}
                businessNameFor={businessNameFor}
                compareAsFor={compareAsFor}
                onTableChange={(rowId, ref) => {
                  setTargetBlocks((blocks) =>
                    blocks.map((b) =>
                      b.id === block.id
                        ? {
                            ...b,
                            joinRows: updateRowById(b.joinRows, rowId, { ref, column: "", columns: [] }),
                          }
                        : b,
                    ),
                  );
                  if (!isCascadeComplete(ref)) return;
                  listAnalysisColumns(ref)
                    .then((cols) =>
                      setTargetBlocks((blocks) =>
                        blocks.map((b) =>
                          b.id === block.id
                            ? { ...b, joinRows: updateRowById(b.joinRows, rowId, { columns: cols }) }
                            : b,
                        ),
                      ),
                    )
                    .catch((err: unknown) => setLoadError(err instanceof Error ? err.message : String(err)));
                }}
                onColumnChange={(rowId, column) =>
                  setTargetBlocks((blocks) =>
                    blocks.map((b) =>
                      b.id === block.id
                        ? { ...b, joinRows: updateRowById(b.joinRows, rowId, { column }) }
                        : b,
                    ),
                  )
                }
                onFuzzyChange={() => {}}
                onRemove={(rowId) =>
                  setTargetBlocks((blocks) =>
                    blocks.map((b) =>
                      b.id === block.id ? { ...b, joinRows: removeRowById(b.joinRows, rowId) } : b,
                    ),
                  )
                }
                onAdd={() =>
                  setTargetBlocks((blocks) =>
                    blocks.map((b) =>
                      b.id === block.id ? { ...b, joinRows: [...b.joinRows, createEmptyRow()] } : b,
                    ),
                  )
                }
                onError={reportError}
                displaySection={
                  <DisplayColumnBox
                    title="Also show (not matched on)"
                    boxId={`target-block-display-${block.id}`}
                    rows={block.displayRows}
                    businessNameFor={businessNameFor}
                    onTableChange={(rowId, ref) =>
                      setTargetBlocks((blocks) =>
                        blocks.map((b) =>
                          b.id === block.id
                            ? {
                                ...b,
                                displayRows: b.displayRows.map((r) =>
                                  r.id === rowId ? { ...r, ref, column: "", columns: [] } : r,
                                ),
                              }
                            : b,
                        ),
                      )
                    }
                    onColumnChange={(rowId, column) =>
                      setTargetBlocks((blocks) =>
                        blocks.map((b) =>
                          b.id === block.id
                            ? {
                                ...b,
                                displayRows: b.displayRows.map((r) =>
                                  r.id === rowId ? { ...r, column } : r,
                                ),
                              }
                            : b,
                        ),
                      )
                    }
                    onRemove={(rowId) =>
                      setTargetBlocks((blocks) =>
                        blocks.map((b) =>
                          b.id === block.id
                            ? { ...b, displayRows: b.displayRows.filter((r) => r.id !== rowId) }
                            : b,
                        ),
                      )
                    }
                    onAdd={() => {
                      const ref = defaultSideRef(block.joinRows);
                      const row = createEmptyDisplayRow(ref);
                      setTargetBlocks((blocks) =>
                        blocks.map((b) =>
                          b.id === block.id ? { ...b, displayRows: [...b.displayRows, row] } : b,
                        ),
                      );
                      if (isCascadeComplete(ref)) {
                        listAnalysisColumns(ref)
                          .then((cols) =>
                            setTargetBlocks((blocks) =>
                              blocks.map((b) =>
                                b.id === block.id
                                  ? {
                                      ...b,
                                      displayRows: b.displayRows.map((r) =>
                                        r.id === row.id ? { ...r, columns: cols } : r,
                                      ),
                                    }
                                  : b,
                              ),
                            ),
                          )
                          .catch((err: unknown) =>
                            setLoadError(err instanceof Error ? err.message : String(err)),
                          );
                      }
                    }}
                    onError={reportError}
                  />
                }
              />
            </section>
          ))}
          <button
            type="button"
            className="srse-btn srse-btn-ghost srse-btn-sm"
            disabled={targetBlocks.length >= maxTargetSets}
            onClick={() =>
              setTargetBlocks((blocks) => [...blocks, createTargetBlock(`Target ${blocks.length + 1}`)])
            }
          >
            + Add target ({targetBlocks.length}/{maxTargetSets})
          </button>
          {dedupEnabled && (
            <p className="srse-text-muted" style={{ fontSize: "0.78rem", marginTop: "0.5rem" }}>
              Dedup uses a column on the hub table only (not on individual target tables).
            </p>
          )}
        </div>
      )}

      <p className="srse-text-muted" style={{ fontSize: "0.78rem", marginTop: "0.5rem" }}>
        Fuzzy % applies to columns marked fuzzy-matchable in Admin, or (if unmapped) when a column
        name contains &quot;name&quot;; other pairs match exactly. Match-on rows pair up in order and form
        the join. Rows under &quot;Also show (not matched on)&quot; are projected in the result only.
        {multiMatchMode &&
          " Multi-target runs one two-table join per target set; the NDJSON stream can include partial results if one set fails — CSV export is all-or-nothing."}
      </p>

      <section className="srse-card" style={{ width: "100%", marginTop: "1rem" }}>
        <h2 className="srse-card-title">Optional filters</h2>
        <div style={{ display: "flex", gap: "1.5rem", flexWrap: "wrap", marginTop: "0.75rem" }}>
          <div style={{ flex: "2 1 420px" }}>
            <label className="srse-checkbox-label" htmlFor="age-filter-enabled" style={{ display: "inline-flex" }}>
              <input
                id="age-filter-enabled"
                type="checkbox"
                checked={ageFilterEnabled}
                onChange={(e) => setAgeFilterEnabled(e.target.checked)}
              />
              {" "}
              Age range filter
            </label>
            {ageFilterEnabled && (
              <div style={{ display: "flex", gap: "0.75rem", flexWrap: "wrap", marginTop: "0.6rem", alignItems: "flex-end" }}>
                <div style={{ flex: "0 1 140px" }}>
                  <label htmlFor="min-age" className="srse-text-muted" style={fieldLabelStyle}>
                    Minimum Age
                  </label>
                  <input
                    id="min-age"
                    type="number"
                    className="srse-input"
                    style={{ width: "100%" }}
                    min={0}
                    value={minAge}
                    onChange={(e) => setMinAge(Number(e.target.value))}
                  />
                </div>
                <div style={{ flex: "0 1 140px" }}>
                  <label htmlFor="max-age" className="srse-text-muted" style={fieldLabelStyle}>
                    Maximum Age
                  </label>
                  <input
                    id="max-age"
                    type="number"
                    className="srse-input"
                    style={{ width: "100%" }}
                    min={0}
                    value={maxAge}
                    onChange={(e) => setMaxAge(Number(e.target.value))}
                  />
                </div>
                <div style={{ flex: "0 1 140px" }}>
                  <label htmlFor="age-unit" className="srse-text-muted" style={fieldLabelStyle}>
                    Unit
                  </label>
                  <select
                    id="age-unit"
                    className="srse-select"
                    style={{ width: "100%" }}
                    value={ageUnit}
                    onChange={(e) => setAgeUnit(e.target.value as AgeUnit)}
                  >
                    <option value="DAYS">Days</option>
                    <option value="MONTHS">Month</option>
                    <option value="YEARS">Year</option>
                  </select>
                </div>
              </div>
            )}
          </div>
        </div>
      </section>

      <div style={{ display: "flex", justifyContent: "flex-end", marginTop: "1rem" }}>
        <button
          type="button"
          className="srse-btn srse-btn-primary"
          onClick={() => runMatch(dedupEnabled)}
          disabled={matchStatus === "loading"}
        >
          {matchStatus === "loading" ? "Running…" : "Run Match"}
        </button>
      </div>

      {matchError && (
        <p className="srse-text-danger" style={{ textAlign: "right" }}>
          {matchError}
        </p>
      )}

      {multiMatchMode && matchProgress.length > 0 && (
        <ul className="srse-text-muted" style={{ fontSize: "0.82rem", marginTop: "0.75rem", listStyle: "none", padding: 0 }}>
          {matchProgress.map((p) => (
            <li key={`${p.label}-${p.phase}-${p.targetIndex}`} style={{ marginBottom: "0.25rem" }}>
              <strong>{p.label}</strong>:{" "}
              {p.phase === "started" && "running…"}
              {p.phase === "done" && `${p.rows ?? 0} rows`}
              {p.phase === "error" && (
                <span className="srse-text-danger">{p.message ?? "failed"}</span>
              )}
              {p.phase === "skipped" && (
                <span>skipped ({p.reason ?? "time budget"})</span>
              )}
            </li>
          ))}
        </ul>
      )}

      {matchColumns.length > 0 && (
        <div style={{ marginTop: "1.5rem" }}>
          {multiMatchMode && (
            <div style={{ marginBottom: "0.75rem" }}>
              <label htmlFor="target-set-filter" className="srse-text-muted" style={{ marginRight: "0.5rem" }}>
                Target set
              </label>
              <select
                id="target-set-filter"
                className="srse-select"
                value={targetSetFilter}
                onChange={(e) => setTargetSetFilter(e.target.value)}
              >
                <option value="All">All</option>
                {targetBlocks.map((b) => (
                  <option key={b.id} value={b.label.trim() || b.id}>
                    {b.label.trim() || "Unnamed"}
                  </option>
                ))}
              </select>
            </div>
          )}
          <AnalysisResultsGrid
            columns={matchColumns}
            rows={displayedMatchRows}
            sql={matchSql}
            streaming={matchStreaming}
            totalRows={matchTotalRows}
            totalRowsIsPartial={matchCountIsPartial}
            tooManyToDisplay={matchTooManyToDisplay}
            displayLimit={MAX_DISPLAYED_ROWS}
            onDownloadFullCsv={downloadFullCsv}
            highlightDuplicates={highlightDuplicates}
            dedupAvailable={!!dedupColumn}
            dedupEnabled={dedupEnabled}
            columnLabels={buildColumnLabels()}
            onDedupToggle={(enabled) => {
              setDedupEnabled(enabled);
              runMatch(enabled);
            }}
          />
        </div>
      )}
    </main>
  );
}
