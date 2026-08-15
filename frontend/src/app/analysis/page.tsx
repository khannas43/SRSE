"use client";

import { useEffect, useRef, useState } from "react";
import {
  listAnalysisColumns,
  listAnalysisTables,
  listColumnMetadata,
  runRecordMatchStream,
  type AgeUnit,
  type ColumnInfo,
  type ColumnMetadata,
  type RecordMatchRequest,
} from "@/lib/analysisApi";
import { AnalysisResultsGrid } from "@/components/AnalysisResultsGrid";

const fieldLabelStyle = { display: "block", marginBottom: "0.3rem", fontSize: "0.82rem" } as const;

type CriterionRow = {
  id: string;
  table: string;
  column: string;
  columns: ColumnInfo[];
  fuzzyThresholdPercent: number;
};

function createEmptyRow(): CriterionRow {
  return { id: crypto.randomUUID(), table: "", column: "", columns: [], fuzzyThresholdPercent: 80 };
}

function metadataKey(table: string, column: string): string {
  return `${table}.${column}`;
}

function isNameColumn(column: string): boolean {
  return column.toLowerCase().includes("name");
}

// Same auto-detect-by-name-substring pattern as fuzzy-on-"*name*" — no manual
// column picker, checked in priority order against the Target table's columns.
const LAST_UPDATED_HINTS = ["updated", "refresh", "modified", "date"];

function detectLastUpdatedColumn(columns: ColumnInfo[]): string | null {
  for (const hint of LAST_UPDATED_HINTS) {
    const match = columns.find((c) => c.name.toLowerCase().includes(hint));
    if (match) return match.name;
  }
  return null;
}

const MAX_DISPLAYED_ROWS = 20000;
const MAX_ROWS_TO_PARSE = 200000;

function updateRowById(rows: CriterionRow[], id: string, patch: Partial<CriterionRow>): CriterionRow[] {
  return rows.map((r) => (r.id === id ? { ...r, ...patch } : r));
}

function removeRowById(rows: CriterionRow[], id: string): CriterionRow[] {
  return rows.length <= 1 ? rows : rows.filter((r) => r.id !== id);
}

type CriterionBoxProps = Readonly<{
  title: string;
  boxId: string;
  rows: CriterionRow[];
  tables: string[];
  showFuzzy: boolean;
  pairedRows?: CriterionRow[];
  isFuzzyMatchable: (table: string, column: string) => boolean;
  businessNameFor: (table: string, column: string) => string | null;
  onTableChange: (rowId: string, table: string) => void;
  onColumnChange: (rowId: string, column: string) => void;
  onFuzzyChange: (rowId: string, value: number) => void;
  onRemove: (rowId: string) => void;
  onAdd: () => void;
}>;

function rowShowsFuzzy(
  row: CriterionRow,
  index: number,
  pairedRows: CriterionRow[] | undefined,
  isFuzzyMatchable: (table: string, column: string) => boolean,
): boolean {
  if (isFuzzyMatchable(row.table, row.column)) return true;
  const paired = pairedRows?.[index];
  return paired ? isFuzzyMatchable(paired.table, paired.column) : false;
}

function CriterionBox({
  title,
  boxId,
  rows,
  tables,
  showFuzzy,
  pairedRows,
  isFuzzyMatchable,
  businessNameFor,
  onTableChange,
  onColumnChange,
  onFuzzyChange,
  onRemove,
  onAdd,
}: CriterionBoxProps) {
  return (
    <section className="srse-card" style={{ flex: "1 1 380px" }}>
      <h2 className="srse-card-title">{title}</h2>
      <p className="srse-text-muted" style={{ fontSize: "0.82rem", marginTop: 0 }}>
        Based on schema of Lakehouse
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
          <div style={{ flex: "1 1 150px" }}>
            <label htmlFor={`${boxId}-table-${row.id}`} className="srse-text-muted" style={fieldLabelStyle}>
              Table
            </label>
            <select
              id={`${boxId}-table-${row.id}`}
              className="srse-select"
              style={{ width: "100%" }}
              value={row.table}
              onChange={(e) => onTableChange(row.id, e.target.value)}
            >
              <option value="">— select —</option>
              {tables.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
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
              disabled={!row.table}
            >
              <option value="">— select —</option>
              {row.columns.map((c) => (
                <option key={c.name} value={c.name}>
                  {businessNameFor(row.table, c.name) ?? c.name}
                </option>
              ))}
            </select>
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
    </section>
  );
}

export default function AnalysisPage() {
  const [tables, setTables] = useState<string[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [columnMetadata, setColumnMetadata] = useState<Map<string, ColumnMetadata>>(new Map());

  const [highlightDuplicates, setHighlightDuplicates] = useState(false);

  const [sourceRows, setSourceRows] = useState<CriterionRow[]>([createEmptyRow()]);
  const [targetRows, setTargetRows] = useState<CriterionRow[]>([createEmptyRow()]);

  const [dedupEnabled, setDedupEnabled] = useState(false);
  const dedupColumn = detectLastUpdatedColumn(targetRows[0]?.columns ?? []);

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
  // True when we stopped reading before the stream finished naturally, so
  // matchTotalRows (if set at all) is a lower bound, not an exact count.
  const [matchCountIsPartial, setMatchCountIsPartial] = useState(false);
  // True for the whole streaming lifecycle (first byte to last), not just the
  // initial network round trip — drives the grid's "(loading more…)" caption
  // and disables CSV/column-visibility until the result is actually complete.
  const matchStreaming = matchStatus === "loading";

  const pendingRowsRef = useRef<Record<string, unknown>[]>([]);
  const flushIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const rowsSeenRef = useRef(0);

  useEffect(() => {
    listAnalysisTables()
      .then(setTables)
      .catch((err: unknown) => setLoadError(err instanceof Error ? err.message : String(err)));
    listColumnMetadata()
      .then((entries) => setColumnMetadata(new Map(entries.map((e) => [metadataKey(e.table, e.column), e]))))
      .catch((err: unknown) => setLoadError(err instanceof Error ? err.message : String(err)));
  }, []);

  // Admin-registered override takes precedence, on either side of the pair
  // it's used for — same fallback order as the backend (RecordMatchService),
  // so the UI's Fuzzy % control and the actual query never disagree.
  function isFuzzyMatchable(table: string, column: string): boolean {
    const entry = columnMetadata.get(metadataKey(table, column));
    return entry ? entry.fuzzyMatchable : isNameColumn(column);
  }

  function businessNameFor(table: string, column: string): string | null {
    return columnMetadata.get(metadataKey(table, column))?.businessName ?? null;
  }

  async function handleTableChange(
    setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>,
    rowId: string,
    table: string,
  ) {
    setRows((rows) => updateRowById(rows, rowId, { table, column: "", columns: [] }));
    if (!table) return;
    try {
      const cols = await listAnalysisColumns(table);
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

  function buildRequest(withDedup: boolean): RecordMatchRequest | null {
    const filledSource = sourceRows.filter((r) => r.table && r.column);
    const filledTarget = targetRows.filter((r) => r.table && r.column);
    const n = Math.min(filledSource.length, filledTarget.length);
    if (n === 0) return null;
    return {
      sourceCriteria: filledSource.slice(0, n).map((r, i) => ({
        table: r.table,
        column: r.column,
        fuzzyThresholdPercent:
          isFuzzyMatchable(r.table, r.column) || isFuzzyMatchable(filledTarget[i].table, filledTarget[i].column)
            ? r.fuzzyThresholdPercent
            : null,
      })),
      targetCriteria: filledTarget.slice(0, n).map((r) => ({
        table: r.table,
        column: r.column,
        fuzzyThresholdPercent: null,
      })),
      highlightDuplicates,
      dedup: withDedup && dedupColumn && targetRows[0]?.table
        ? { table: targetRows[0].table, column: dedupColumn }
        : null,
      ageFilter: ageFilterEnabled ? { minAge, maxAge, unit: ageUnit } : null,
    };
  }

  function buildColumnLabels(): Record<string, string> {
    const labels: Record<string, string> = {};
    for (const r of sourceRows) {
      const bn = r.table && r.column ? businessNameFor(r.table, r.column) : null;
      if (bn) labels[`source_${r.column}`] = `Source: ${bn}`;
    }
    for (const r of targetRows) {
      const bn = r.table && r.column ? businessNameFor(r.table, r.column) : null;
      if (bn) labels[`target_${r.column}`] = `Target: ${bn}`;
    }
    return labels;
  }

  function flushPendingRows() {
    if (pendingRowsRef.current.length > 0) {
      const batch = pendingRowsRef.current;
      pendingRowsRef.current = [];
      setMatchRows((prev) => [...prev, ...batch]);
    }
  }

  async function runMatch(withDedup: boolean) {
    const req = buildRequest(withDedup);
    if (!req) {
      setMatchError("Pick at least one Source table + column and one matching Target table + column.");
      return;
    }
    setMatchStatus("loading");
    setMatchError(null);
    setMatchColumns([]);
    setMatchRows([]);
    setMatchSql("");
    setMatchTotalRows(null);
    setMatchCountIsPartial(false);
    pendingRowsRef.current = [];
    rowsSeenRef.current = 0;
    if (flushIntervalRef.current !== null) {
      clearInterval(flushIntervalRef.current);
    }
    flushIntervalRef.current = setInterval(flushPendingRows, 100);

    const controller = new AbortController();
    try {
      await runRecordMatchStream(
        req,
        {
          onMeta: (meta) => {
            setMatchColumns(meta.columns);
            setMatchSql(meta.sql);
          },
          onRow: (row) => {
            rowsSeenRef.current += 1;
            if (rowsSeenRef.current <= MAX_DISPLAYED_ROWS) {
              pendingRowsRef.current.push(row);
            }
            if (rowsSeenRef.current >= MAX_ROWS_TO_PARSE) {
              setMatchCountIsPartial(true);
              controller.abort();
            }
          },
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
      if (controller.signal.aborted) {
        // Intentional stop at MAX_ROWS_TO_PARSE, not a real failure — the
        // true total is unknown past this point, so report a lower bound.
        setMatchTotalRows(rowsSeenRef.current);
        setMatchStatus("ok");
      } else {
        setMatchError(err instanceof Error ? err.message : String(err));
        setMatchStatus("error");
      }
    } finally {
      if (flushIntervalRef.current !== null) {
        clearInterval(flushIntervalRef.current);
        flushIntervalRef.current = null;
      }
      flushPendingRows();
    }
  }

  return (
    <main className="srse-page">
      <h1 className="srse-page-title">Analysis</h1>
      <p className="srse-page-description" style={{ maxWidth: "none", whiteSpace: "nowrap" }}>
        Reconcile records across lakehouse tables and columns — fuzzy-match on names, review duplicates, and
        export a clean result set. Nothing here is ever deleted from the lakehouse.
      </p>

      {loadError && <p className="srse-text-danger">{loadError}</p>}

      <label className="srse-checkbox-label" htmlFor="highlight-duplicates" style={{ marginBottom: "1rem", display: "inline-flex" }}>
        <input
          id="highlight-duplicates"
          type="checkbox"
          checked={highlightDuplicates}
          onChange={(e) => setHighlightDuplicates(e.target.checked)}
        />
        {" "}
        Highlight Duplicate Records
      </label>

      <div style={{ display: "flex", gap: "1rem", flexWrap: "wrap", width: "100%" }}>
        <CriterionBox
          title="Select Source"
          boxId="source"
          rows={sourceRows}
          tables={tables}
          showFuzzy
          pairedRows={targetRows}
          isFuzzyMatchable={isFuzzyMatchable}
          businessNameFor={businessNameFor}
          onTableChange={(rowId, table) => handleTableChange(setSourceRows, rowId, table)}
          onColumnChange={(rowId, column) => handleColumnChange(setSourceRows, rowId, column)}
          onFuzzyChange={(rowId, value) => handleFuzzyChange(setSourceRows, rowId, value)}
          onRemove={(rowId) => handleRemoveRow(setSourceRows, rowId)}
          onAdd={() => setSourceRows((rs) => [...rs, createEmptyRow()])}
        />
        <CriterionBox
          title="Select Target"
          boxId="target"
          rows={targetRows}
          tables={tables}
          showFuzzy={false}
          isFuzzyMatchable={isFuzzyMatchable}
          businessNameFor={businessNameFor}
          onTableChange={(rowId, table) => handleTableChange(setTargetRows, rowId, table)}
          onColumnChange={(rowId, column) => handleColumnChange(setTargetRows, rowId, column)}
          onFuzzyChange={(rowId, value) => handleFuzzyChange(setTargetRows, rowId, value)}
          onRemove={(rowId) => handleRemoveRow(setTargetRows, rowId)}
          onAdd={() => setTargetRows((rs) => [...rs, createEmptyRow()])}
        />
      </div>

      <p className="srse-text-muted" style={{ fontSize: "0.78rem", marginTop: "0.5rem" }}>
        Fuzzy % applies to columns marked fuzzy-matchable in Admin, or (if unmapped) when a column
        name contains &quot;name&quot;; other pairs match exactly. Source and Target rows pair up in
        order — add a matching row on both sides.
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

      {matchColumns.length > 0 && (
        <div style={{ marginTop: "1.5rem" }}>
          <AnalysisResultsGrid
            columns={matchColumns}
            rows={matchRows}
            sql={matchSql}
            streaming={matchStreaming}
            totalRows={matchTotalRows}
            totalRowsIsPartial={matchCountIsPartial}
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
