"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  downloadMultiTargetMatchCsv,
  downloadRecordMatchCsv,
  fetchAnalysisLimits,
  fetchMatchSql,
  listAnalysisCatalogs,
  listAnalysisLayers,
  listAnalysisColumns,
  listAnalysisSchemas,
  listAnalysisTables,
  listColumnMetadata,
  qualifiedTableName,
  runMultiTargetMatchStream,
  runRecordMatchStream,
  suggestJoinKeys,
  type AgeUnit,
  type JoinKeySuggestion,
  type ColumnMetadata,
  type GroupMode,
  type MatchCriterion,
  type CompareAs,
  type HubSide,
  type JoinType,
  type MatchProgressEvent,
  type MultiTargetRecordMatchRequest,
  type RecordMatchRequest,
  type RegisteredColumn,
  type TableRef,
} from "@/lib/analysisApi";
import { AnalysisResultsGrid } from "@/components/AnalysisResultsGrid";
import { MultiTargetJoinCanvas, createInitialJoinCanvas } from "@/components/MultiTargetJoinCanvas";
import {
  buildMatchGroup,
  isCriterionRowFilled,
  isDisplayRowFilled,
  isNameColumn,
  pairIsFuzzy,
  rowColumns,
  rowFolds,
} from "@/lib/analysisCriterionModel";
import {
  joinCanvasFromForm,
  buildMultiTargetRequestFromCanvas,
  canvasTargetNodes,
} from "@/lib/joinCanvasModel";
import { buildMultiTargetRecordMatchRequest } from "@/lib/multiTargetMatchBuild";
import { initTargetProgress, mergeTargetProgress, phaseLabel } from "@/lib/multiTargetProgress";
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
const JOIN_TYPE_OPTIONS: { value: JoinType; label: string; hint: string }[] = [
  { value: "INNER", label: "Only matching records", hint: "INNER" },
  { value: "LEFT", label: "All source records", hint: "LEFT" },
  { value: "RIGHT", label: "All target records", hint: "RIGHT" },
  { value: "FULL", label: "All records from both", hint: "FULL" },
];

const REGISTRY_FETCHERS: CascadeFetchers = {
  listLayers: listAnalysisLayers,
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
  // Columns folded in alongside `column` — a target's first_name + last_name
  // against the hub's one full_name. Empty for every row until the officer
  // clicks "+ add column", which is what keeps the request shape unchanged for
  // everyone who does not need this.
  extraColumns: string[];
  // How this row's side folds. Only read from the SOURCE row of a pair, which
  // already owns the pair's fuzzy threshold.
  mode: GroupMode;
  separator: string;
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
    extraColumns: [],
    mode: "COMBINE",
    separator: " ",
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
  joinType: JoinType;
};

function createTargetBlock(defaultLabel: string): TargetBlock {
  return {
    id: crypto.randomUUID(),
    label: defaultLabel,
    joinRows: [createEmptyRow()],
    displayRows: [],
    joinType: "INNER",
  };
}

function metadataKey(ref: TableRef, column: string): string {
  return `${qualifiedTableName(ref)}.${column}`;
}

function isRowFilled(row: CriterionRow): boolean {
  return isCriterionRowFilled(row);
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
  registeredFuzzyFor: (ref: TableRef, column: string) => boolean | null;
  businessNameFor: (ref: TableRef, column: string) => string | null;
  compareAsFor: (ref: TableRef, column: string) => CompareAs;
  onTableChange: (rowId: string, ref: CascadeValue) => void;
  onColumnChange: (rowId: string, column: string) => void;
  onFuzzyChange: (rowId: string, value: number) => void;
  onExtraColumnsChange: (rowId: string, extraColumns: string[]) => void;
  onModeChange: (rowId: string, mode: GroupMode) => void;
  onSeparatorChange: (rowId: string, separator: string) => void;
  onRemove: (rowId: string) => void;
  onAdd: () => void;
  onError: (message: string) => void;
}>;

function rowShowsFuzzy(
  row: CriterionRow,
  index: number,
  pairedRows: CriterionRow[] | undefined,
  registeredFuzzyFor: (ref: TableRef, column: string) => boolean | null,
): boolean {
  return pairIsFuzzy(row, pairedRows?.[index], registeredFuzzyFor);
}

/**
 * The fold controls for one pair. Shown only once a side actually holds more
 * than one column, so the box looks exactly as it did for anyone matching one
 * column against one column.
 */
function GroupControls({
  row,
  boxId,
  anyOfCount,
  onModeChange,
  onSeparatorChange,
}: Readonly<{
  row: CriterionRow;
  boxId: string;
  anyOfCount: number;
  onModeChange: (rowId: string, mode: GroupMode) => void;
  onSeparatorChange: (rowId: string, separator: string) => void;
}>) {
  return (
    <div style={{ flex: "1 1 100%", display: "flex", gap: "0.6rem", alignItems: "flex-end", flexWrap: "wrap" }}>
      <div style={{ flex: "0 1 180px" }}>
        <label htmlFor={`${boxId}-mode-${row.id}`} className="srse-text-muted" style={fieldLabelStyle}>
          Compare as
        </label>
        <select
          id={`${boxId}-mode-${row.id}`}
          className="srse-select"
          style={{ width: "100%" }}
          value={row.mode}
          onChange={(e) => onModeChange(row.id, e.target.value as GroupMode)}
        >
          <option value="COMBINE">Combined into one value</option>
          <option value="ANY_OF">Any one of them</option>
        </select>
      </div>
      {row.mode === "COMBINE" && (
        <div style={{ flex: "0 1 110px" }}>
          <label htmlFor={`${boxId}-sep-${row.id}`} className="srse-text-muted" style={fieldLabelStyle}>
            Joined by
          </label>
          <input
            id={`${boxId}-sep-${row.id}`}
            type="text"
            className="srse-input"
            style={{ width: "100%" }}
            value={row.separator}
            onChange={(e) => onSeparatorChange(row.id, e.target.value)}
          />
        </div>
      )}
      <p className="srse-text-muted" style={{ fontSize: "0.7rem", margin: 0, flex: "1 1 220px" }}>
        {row.mode === "COMBINE"
          ? "Joined in the order listed — order matters for fuzzy scoring. Empty values are skipped."
          : "Matches if the other side equals any one of these columns; the result says which one."}
      </p>
      {row.mode === "ANY_OF" && anyOfCount > 1 && (
        <p style={{ fontSize: "0.7rem", margin: 0, flex: "1 1 100%", color: "var(--srse-warning)" }}>
          More than one &quot;any one of&quot; group on the same side multiplies that side&apos;s rows
          before the match runs, and can be slow on large tables.
        </p>
      )}
    </div>
  );
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
  registeredFuzzyFor,
  businessNameFor,
  compareAsFor,
  onTableChange,
  onColumnChange,
  onFuzzyChange,
  onExtraColumnsChange,
  onModeChange,
  onSeparatorChange,
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
          {showFuzzy && rowShowsFuzzy(row, index, pairedRows, registeredFuzzyFor) && (
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

          {/* Folded columns: one full_name here against first_name + last_name there. */}
          {row.extraColumns.map((extra, extraIndex) => (
            <div key={`${row.id}-extra-${extraIndex}`} style={{ flex: "1 1 100%", display: "flex", gap: "0.4rem", alignItems: "flex-end" }}>
              <div style={{ flex: "1 1 150px" }}>
                <label
                  htmlFor={`${boxId}-extra-${row.id}-${extraIndex}`}
                  className="srse-text-muted"
                  style={fieldLabelStyle}
                >
                  …and column {extraIndex + 2}
                </label>
                <select
                  id={`${boxId}-extra-${row.id}-${extraIndex}`}
                  className="srse-select"
                  style={{ width: "100%" }}
                  value={extra}
                  onChange={(e) =>
                    onExtraColumnsChange(
                      row.id,
                      row.extraColumns.map((c, k) => (k === extraIndex ? e.target.value : c)),
                    )
                  }
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
                onClick={() =>
                  onExtraColumnsChange(row.id, row.extraColumns.filter((_, k) => k !== extraIndex))
                }
                title="Remove this column from the group"
              >
                ✕
              </button>
            </div>
          ))}

          {isCascadeComplete(row.ref) && Boolean(row.column) && (
            <button
              type="button"
              className="srse-btn srse-btn-ghost srse-btn-sm"
              style={{ flex: "0 0 auto" }}
              onClick={() => onExtraColumnsChange(row.id, [...row.extraColumns, ""])}
              title="Match this against more than one column on this side"
            >
              + add column
            </button>
          )}

          {showFuzzy && (rowFolds(row) || (pairedRows?.[index] ? rowFolds(pairedRows[index]) : false)) && (
            <GroupControls
              row={row}
              boxId={boxId}
              anyOfCount={rows.filter((r, i) =>
                r.mode === "ANY_OF"
                && (rowFolds(r) || (pairedRows?.[i] ? rowFolds(pairedRows[i]) : false))).length}
              onModeChange={onModeChange}
              onSeparatorChange={onSeparatorChange}
            />
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
  const [multiUiMode, setMultiUiMode] = useState<"form" | "canvas">("form");
  const [joinCanvas, setJoinCanvas] = useState(createInitialJoinCanvas);
  const [targetRunStatus, setTargetRunStatus] = useState<ReturnType<typeof initTargetProgress>>([]);

  const [joinType, setJoinType] = useState<JoinType>("INNER");
  const [keySuggestions, setKeySuggestions] = useState<JoinKeySuggestion[]>([]);
  const [keySuggestLoading, setKeySuggestLoading] = useState(false);
  const [keySuggestError, setKeySuggestError] = useState<string | null>(null);
  const [sqlPreview, setSqlPreview] = useState<string | null>(null);
  const [sqlPreviewError, setSqlPreviewError] = useState<string | null>(null);
  const [sqlPreviewLoading, setSqlPreviewLoading] = useState(false);

  const [dedupEnabled, setDedupEnabled] = useState(false);
  const multiDedupBlockedByJoin =
    multiMatchMode && targetBlocks.some((b) => b.joinType === "RIGHT" || b.joinType === "FULL");
  const dedupBlockedByJoin =
    multiDedupBlockedByJoin || (!multiMatchMode && (joinType === "RIGHT" || joinType === "FULL"));

  useEffect(() => {
    if (dedupBlockedByJoin) {
      setDedupEnabled(false);
    }
  }, [dedupBlockedByJoin]);

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

  /** The Admin setting for one column, or null when it has none — see pairIsFuzzy. */
  function registeredFuzzyFor(ref: TableRef, column: string): boolean | null {
    return columnMetadata.get(metadataKey(ref, column))?.fuzzyMatchable ?? null;
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

  const twoTableRefsReady =
    !multiMatchMode
    && isCascadeComplete(sourceRows[0]?.ref ?? EMPTY_CASCADE)
    && isCascadeComplete(targetRows[0]?.ref ?? EMPTY_CASCADE);

  async function loadKeySuggestions(probe: boolean) {
    const src = sourceRows[0].ref;
    const tgt = targetRows[0].ref;
    if (!isCascadeComplete(src) || !isCascadeComplete(tgt)) return;
    setKeySuggestLoading(true);
    setKeySuggestError(null);
    try {
      const list = await suggestJoinKeys({
        sourceCatalog: src.catalog,
        sourceSchema: src.schema,
        sourceTable: src.table,
        targetCatalog: tgt.catalog,
        targetSchema: tgt.schema,
        targetTable: tgt.table,
        probe,
      });
      setKeySuggestions(list);
    } catch (err: unknown) {
      setKeySuggestError(err instanceof Error ? err.message : String(err));
      setKeySuggestions([]);
    } finally {
      setKeySuggestLoading(false);
    }
  }

  async function applyKeySuggestion(s: JoinKeySuggestion) {
    const srcRef = sourceRows[0].ref;
    const tgtRef = targetRows[0].ref;
    const srcRowId = sourceRows[0].id;
    const tgtRowId = targetRows[0].id;
    try {
      const [srcCols, tgtCols] = await Promise.all([
        listAnalysisColumns(srcRef),
        listAnalysisColumns(tgtRef),
      ]);
      setSourceRows((rows) =>
        updateRowById(rows, srcRowId, { columns: srcCols, column: s.sourceColumn }),
      );
      setTargetRows((rows) =>
        updateRowById(rows, tgtRowId, { columns: tgtCols, column: s.targetColumn }),
      );
    } catch (err: unknown) {
      setLoadError(err instanceof Error ? err.message : String(err));
    }
  }

  function handleFuzzyChange(
    setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>,
    rowId: string,
    fuzzyThresholdPercent: number,
  ) {
    setRows((rows) => updateRowById(rows, rowId, { fuzzyThresholdPercent }));
  }

  function handleExtraColumnsChange(
    setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>,
    rowId: string,
    extraColumns: string[],
  ) {
    setRows((rows) => updateRowById(rows, rowId, { extraColumns }));
  }

  function handleModeChange(
    setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>,
    rowId: string,
    mode: GroupMode,
  ) {
    setRows((rows) => updateRowById(rows, rowId, { mode }));
  }

  function handleSeparatorChange(
    setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>,
    rowId: string,
    separator: string,
  ) {
    setRows((rows) => updateRowById(rows, rowId, { separator }));
  }

  function handleRemoveRow(setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>, rowId: string) {
    setRows((rows) => removeRowById(rows, rowId));
  }

  function defaultSideRef(sideRows: CriterionRow[]): CascadeValue {
    const filled = sideRows.find(isRowFilled);
    return filled?.ref ?? sideRows[0]?.ref ?? EMPTY_CASCADE;
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
    const pairs = filledSource.slice(0, n).map((r, i) => ({ source: r, target: filledTarget[i] }));
    // Groups are sent only when a row actually folds more than one column.
    // Otherwise the payload is byte-for-byte what it has always been, and so is
    // the SQL the backend builds from it.
    const usesGroups = pairs.some(({ source, target }) => rowFolds(source) || rowFolds(target));
    const req: RecordMatchRequest = {
      sourceCriteria: pairs.map(({ source, target }) => ({
        ...source.ref,
        column: source.column,
        fuzzyThresholdPercent: pairIsFuzzy(source, target, registeredFuzzyFor)
          ? source.fuzzyThresholdPercent
          : null,
      })),
      targetCriteria: pairs.map(({ target }) => ({
        ...target.ref,
        column: target.column,
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
    if (usesGroups) {
      req.joinGroups = pairs.map(({ source, target }) =>
        buildMatchGroup(source, target, pairIsFuzzy(source, target, registeredFuzzyFor)),
      );
    }
    if (joinType !== "INNER") {
      req.joinType = joinType;
    }
    return req;
  }

  function multiBuildExtras(withDedup: boolean) {
    const hubRef = sourceRows.find(isRowFilled)?.ref;
    return {
      highlightDuplicates,
      dedup:
        withDedup && dedupColumn && hubRef && isCascadeComplete(hubRef)
          ? { ...hubRef, column: dedupColumn }
          : null,
      ageFilter: ageFilterEnabled ? { minAge, maxAge, unit: ageUnit } : null,
      registeredFuzzyFor,
      isFuzzyMatchable,
      maxTargetSets,
    };
  }

  function buildMultiRequest(withDedup: boolean): MultiTargetRecordMatchRequest | null {
    return buildMultiTargetRecordMatchRequest({
      hubRows: sourceRows,
      hubDisplayRows: sourceDisplayRows,
      hubSide,
      targets: targetBlocks.map((b) => ({
        label: b.label,
        joinRows: b.joinRows,
        displayRows: b.displayRows,
        joinType: b.joinType,
      })),
      ...multiBuildExtras(withDedup),
    });
  }

  function buildMultiRequestFromCanvas(withDedup: boolean): MultiTargetRecordMatchRequest | null {
    return buildMultiTargetRequestFromCanvas(joinCanvas, multiBuildExtras(withDedup), maxTargetSets);
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
      const multiReq =
        multiUiMode === "canvas" ? buildMultiRequestFromCanvas(withDedup) : buildMultiRequest(withDedup);
      if (!multiReq) {
        setMatchError(
          multiUiMode === "canvas"
            ? "Complete the join canvas: one hub, target labels, and aligned join edges."
            : "Pick hub match columns and at least one target set (label + paired match columns on each target).",
        );
        return;
      }
      lastRunMultiRequestRef.current = multiReq;
      lastRunRequestRef.current = null;
      setMatchProgress([]);
      const labels = multiReq.targets.map((t) => t.label);
      setTargetRunStatus(initTargetProgress(labels));
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
              setMatchProgress((prev) => [...prev, event]);
              setTargetRunStatus((prev) => mergeTargetProgress(prev, event, labels));
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

  const multiTargetLabels =
    multiMatchMode && multiUiMode === "canvas"
      ? canvasTargetNodes(joinCanvas).map((n) => n.label.trim()).filter(Boolean)
      : targetBlocks.map((b) => b.label.trim()).filter(Boolean);

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
        <div style={{ marginBottom: "1rem", display: "flex", flexWrap: "wrap", gap: "1rem", alignItems: "center" }}>
          <span className="srse-text-muted" style={{ fontSize: "0.82rem" }}>Configure with</span>
          <label className="srse-checkbox-label" htmlFor="multi-ui-form">
            <input
              id="multi-ui-form"
              type="radio"
              name="multi-ui-mode"
              checked={multiUiMode === "form"}
              onChange={() => setMultiUiMode("form")}
            />
            {" "}
            Form
          </label>
          <label className="srse-checkbox-label" htmlFor="multi-ui-canvas">
            <input
              id="multi-ui-canvas"
              type="radio"
              name="multi-ui-mode"
              checked={multiUiMode === "canvas"}
              onChange={() => {
                setMultiUiMode("canvas");
                setJoinCanvas(
                  joinCanvasFromForm(
                    hubSide,
                    sourceRows,
                    sourceDisplayRows,
                    targetBlocks,
                    defaultSideRef(sourceRows),
                  ),
                );
              }}
            />
            {" "}
            Join canvas
          </label>
        </div>
      )}

      {multiMatchMode && multiUiMode === "form" && (
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

      {!multiMatchMode && twoTableRefsReady && (
        <section className="srse-card" style={{ marginBottom: "1rem", width: "100%" }}>
          <h2 className="srse-card-title" style={{ marginTop: 0 }}>
            Join key suggestions
          </h2>
          <p className="srse-text-muted" style={{ marginTop: 0, lineHeight: 1.5 }}>
            Hints only — pick a row to fill the first criterion pair. Nothing is applied until you choose.
          </p>
          <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap", marginBottom: "0.75rem" }}>
            <button
              type="button"
              className="srse-btn srse-btn-secondary srse-btn-sm"
              disabled={keySuggestLoading}
              onClick={() => loadKeySuggestions(false)}
            >
              Suggest keys
            </button>
            <button
              type="button"
              className="srse-btn srse-btn-ghost srse-btn-sm"
              disabled={keySuggestLoading}
              title="Samples the source (10% Bernoulli) and scans the target in full per pair — bounded and may take several seconds"
              onClick={() => loadKeySuggestions(true)}
            >
              Check overlap (sampled)
            </button>
          </div>
          {keySuggestError && <p className="srse-text-danger">{keySuggestError}</p>}
          {keySuggestLoading && <p className="srse-text-muted">Loading suggestions…</p>}
          {!keySuggestLoading && keySuggestions.length > 0 && (
            <ul style={{ listStyle: "none", padding: 0, margin: 0 }}>
              {keySuggestions.map((s) => (
                <li key={`${s.sourceColumn}-${s.targetColumn}`} style={{ marginBottom: "0.4rem" }}>
                  <button
                    type="button"
                    className="srse-btn srse-btn-ghost srse-btn-sm"
                    style={{ textAlign: "left", width: "100%" }}
                    onClick={() => applyKeySuggestion(s)}
                  >
                    <strong>{s.sourceColumn}</strong> ({s.sourceType}) ↔{" "}
                    <strong>{s.targetColumn}</strong> ({s.targetType}) — {s.reason}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

      {multiMatchMode && multiUiMode === "canvas" && (
        <MultiTargetJoinCanvas
          fetchers={REGISTRY_FETCHERS}
          maxTargetSets={maxTargetSets}
          hubSide={hubSide}
          onHubSideChange={setHubSide}
          canvas={joinCanvas}
          onCanvasChange={setJoinCanvas}
          buildExtras={multiBuildExtras(false)}
          targetRunStatus={targetRunStatus}
          onReportError={reportError}
          registeredFuzzyFor={registeredFuzzyFor}
          businessNameFor={businessNameFor}
        />
      )}

      <div style={{ display: "flex", gap: "1rem", flexWrap: "wrap", width: "100%" }}>
        {!(multiMatchMode && multiUiMode === "canvas") && (
        <>
        <CriterionBox
          title={multiMatchMode ? "Hub table — match on" : "Select Source"}
          boxId="source"
          rows={sourceRows}
          showFuzzy
          pairedRows={multiMatchMode ? undefined : targetRows}
          registeredFuzzyFor={registeredFuzzyFor}
          businessNameFor={businessNameFor}
          compareAsFor={compareAsFor}
          onTableChange={(rowId, ref) => handleTableChange(setSourceRows, rowId, ref)}
          onColumnChange={(rowId, column) => handleColumnChange(setSourceRows, rowId, column)}
          onFuzzyChange={(rowId, value) => handleFuzzyChange(setSourceRows, rowId, value)}
          onExtraColumnsChange={(rowId, cols) => handleExtraColumnsChange(setSourceRows, rowId, cols)}
          onModeChange={(rowId, mode) => handleModeChange(setSourceRows, rowId, mode)}
          onSeparatorChange={(rowId, sep) => handleSeparatorChange(setSourceRows, rowId, sep)}
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
            registeredFuzzyFor={registeredFuzzyFor}
            businessNameFor={businessNameFor}
            compareAsFor={compareAsFor}
            onTableChange={(rowId, ref) => handleTableChange(setTargetRows, rowId, ref)}
            onColumnChange={(rowId, column) => handleColumnChange(setTargetRows, rowId, column)}
            onFuzzyChange={(rowId, value) => handleFuzzyChange(setTargetRows, rowId, value)}
            onExtraColumnsChange={(rowId, cols) => handleExtraColumnsChange(setTargetRows, rowId, cols)}
            onModeChange={(rowId, mode) => handleModeChange(setTargetRows, rowId, mode)}
            onSeparatorChange={(rowId, sep) => handleSeparatorChange(setTargetRows, rowId, sep)}
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
        </>
        )}
      </div>

      {multiMatchMode && multiUiMode === "form" && (
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
              <div style={{ marginBottom: "0.65rem" }}>
                <label htmlFor={`target-join-${block.id}`} className="srse-text-muted" style={fieldLabelStyle}>
                  Join type (hub is always the source side in SQL)
                </label>
                <select
                  id={`target-join-${block.id}`}
                  className="srse-select"
                  style={{ maxWidth: 320 }}
                  value={block.joinType}
                  onChange={(e) =>
                    setTargetBlocks((blocks) =>
                      blocks.map((b) =>
                        b.id === block.id ? { ...b, joinType: e.target.value as JoinType } : b,
                      ),
                    )
                  }
                >
                  {JOIN_TYPE_OPTIONS.map((opt) => (
                    <option key={opt.value} value={opt.value} title={opt.hint}>
                      {opt.label}
                    </option>
                  ))}
                </select>
                {block.joinType === "LEFT" && (
                  <p className="srse-text-muted" style={{ fontSize: "0.75rem", margin: "0.35rem 0 0" }}>
                    LEFT keeps every hub row — target columns are empty when this target has no match (useful for
                    &quot;which hub rows miss in {block.label.trim() || "this target"}?&quot;).
                  </p>
                )}
                {block.joinType === "FULL" && ageFilterEnabled && (
                  <p className="srse-text-danger" style={{ fontSize: "0.75rem", margin: "0.35rem 0 0" }}>
                    Age filter cannot run with FULL on this target — pick another join type or turn the age filter off.
                  </p>
                )}
              </div>
              <CriterionBox
                title={`Target ${blockIndex + 1} — match on (paired with hub rows)`}
                boxId={`target-block-${block.id}`}
                rows={block.joinRows}
                showFuzzy={false}
                pairedRows={sourceRows}
                registeredFuzzyFor={registeredFuzzyFor}
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
                onExtraColumnsChange={(rowId, cols) =>
                  setTargetBlocks((blocks) =>
                    blocks.map((b) =>
                      b.id === block.id
                        ? { ...b, joinRows: updateRowById(b.joinRows, rowId, { extraColumns: cols }) }
                        : b,
                    ),
                  )
                }
                onModeChange={() => {}}
                onSeparatorChange={() => {}}
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

      {!multiMatchMode && (
        <section className="srse-card" style={{ marginTop: "1rem" }}>
          <h2 className="srse-card-title">Join type</h2>
          <p className="srse-text-muted" style={{ marginTop: 0, lineHeight: 1.5 }}>
            Pick the same registered table on source and target for a <strong>self-join</strong> — no
            separate mode is needed.{" "}
            {joinType === "FULL" && (
              <span className="srse-text-danger">
                FULL joins can be very large: the match is uncapped server-side, so two big tables
                will usually hit the 10,000-row display limit and the CSV download path.
              </span>
            )}
          </p>
          <label htmlFor="join-type" className="srse-text-muted" style={{ fontSize: "0.85rem" }}>
            Records to include
            <select
              id="join-type"
              className="srse-select"
              style={{ marginLeft: "0.5rem", minWidth: 280 }}
              value={joinType}
              onChange={(e) => setJoinType(e.target.value as JoinType)}
            >
              {JOIN_TYPE_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label} ({opt.hint})
                </option>
              ))}
            </select>
          </label>
        </section>
      )}

      <div
        style={{
          display: "flex",
          justifyContent: "flex-end",
          gap: "0.6rem",
          flexWrap: "wrap",
          marginTop: "1rem",
          alignItems: "center",
        }}
      >
        {!multiMatchMode && (
          <button
            type="button"
            className="srse-btn srse-btn-ghost"
            disabled={sqlPreviewLoading || matchStatus === "loading"}
            onClick={async () => {
              const req = buildRequest(false);
              if (!req) {
                setSqlPreviewError("Fill in at least one source/target match pair first.");
                return;
              }
              setSqlPreviewLoading(true);
              setSqlPreviewError(null);
              try {
                setSqlPreview(await fetchMatchSql(req));
              } catch (err: unknown) {
                setSqlPreview(null);
                setSqlPreviewError(err instanceof Error ? err.message : String(err));
              } finally {
                setSqlPreviewLoading(false);
              }
            }}
          >
            {sqlPreviewLoading ? "Planning…" : "Preview SQL"}
          </button>
        )}
        <button
          type="button"
          className="srse-btn srse-btn-primary"
          onClick={() => runMatch(dedupEnabled)}
          disabled={matchStatus === "loading"}
        >
          {matchStatus === "loading" ? "Running…" : "Run Match"}
        </button>
      </div>

      {sqlPreviewError && (
        <p className="srse-text-danger" style={{ textAlign: "right", marginTop: "0.5rem" }}>
          {sqlPreviewError}
        </p>
      )}
      {sqlPreview && (
        <pre
          className="srse-card"
          style={{
            marginTop: "0.75rem",
            overflowX: "auto",
            fontSize: "0.75rem",
            whiteSpace: "pre-wrap",
            wordBreak: "break-word",
          }}
        >
          {sqlPreview}
        </pre>
      )}

      {matchError && (
        <p className="srse-text-danger" style={{ textAlign: "right" }}>
          {matchError}
        </p>
      )}

      {multiMatchMode && targetRunStatus.length > 0 && (
        <ul className="srse-text-muted" style={{ fontSize: "0.82rem", marginTop: "0.75rem", listStyle: "none", padding: 0 }}>
          {targetRunStatus.map((p) => (
            <li key={`${p.label}-${p.phase}-${p.targetIndex}`} style={{ marginBottom: "0.25rem" }}>
              <strong>{p.label}</strong>:{" "}
              {p.phase === "error" ? (
                <span className="srse-text-danger">{phaseLabel(p)}</span>
              ) : p.phase === "skipped" ? (
                <span title="Shared multi-target time budget — skipped is not the same as zero matches">{phaseLabel(p)}</span>
              ) : (
                phaseLabel(p)
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
                {multiTargetLabels.map((label) => (
                  <option key={label} value={label}>
                    {label}
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
            fullCsvDownloadNote={
              multiMatchMode
                ? "Multi-target CSV export is all-or-nothing: if any target set fails, the whole download aborts (unlike the stream, which can return partial rows)."
                : undefined
            }
            highlightDuplicates={highlightDuplicates}
            dedupAvailable={!!dedupColumn}
            dedupEnabled={dedupEnabled}
            dedupDisabledReason={
              dedupBlockedByJoin
                ? multiMatchMode
                  ? "Dedup is not available when any target uses RIGHT or FULL — hub partition keys are NULL on unmatched rows."
                  : "Dedup is not available with RIGHT or FULL joins — unmatched rows share NULL partition keys and would collapse to one row."
                : undefined
            }
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
