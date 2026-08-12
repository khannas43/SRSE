"use client";

import { useEffect, useState } from "react";
import {
  listAnalysisColumns,
  listAnalysisTables,
  runRecordMatch,
  type AgeUnit,
  type ColumnInfo,
  type RecordMatchRequest,
  type RecordMatchResponse,
} from "@/lib/analysisApi";
import { AnalysisResultsGrid } from "@/components/AnalysisResultsGrid";

const fieldLabelStyle = { display: "block", marginBottom: "0.3rem", fontSize: "0.82rem" } as const;

type CriterionRow = {
  table: string;
  column: string;
  columns: ColumnInfo[];
  fuzzyThresholdPercent: number;
};

function emptyRow(): CriterionRow {
  return { table: "", column: "", columns: [], fuzzyThresholdPercent: 80 };
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

export default function AnalysisPage() {
  const [tables, setTables] = useState<string[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [highlightDuplicates, setHighlightDuplicates] = useState(false);

  const [sourceRows, setSourceRows] = useState<CriterionRow[]>([emptyRow()]);
  const [targetRows, setTargetRows] = useState<CriterionRow[]>([emptyRow()]);

  const [dedupEnabled, setDedupEnabled] = useState(false);
  const dedupColumn = detectLastUpdatedColumn(targetRows[0]?.columns ?? []);

  const [ageFilterEnabled, setAgeFilterEnabled] = useState(false);
  const [minAge, setMinAge] = useState(0);
  const [maxAge, setMaxAge] = useState(100);
  const [ageUnit, setAgeUnit] = useState<AgeUnit>("YEARS");

  const [matchStatus, setMatchStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [matchError, setMatchError] = useState<string | null>(null);
  const [matchResult, setMatchResult] = useState<RecordMatchResponse | null>(null);

  useEffect(() => {
    listAnalysisTables()
      .then(setTables)
      .catch((err: unknown) => setLoadError(err instanceof Error ? err.message : String(err)));
  }, []);

  async function setRowTable(
    setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>,
    index: number,
    table: string,
  ) {
    setRows((rows) => rows.map((r, i) => (i === index ? { ...r, table, column: "", columns: [] } : r)));
    if (!table) return;
    try {
      const cols = await listAnalysisColumns(table);
      setRows((rows) => rows.map((r, i) => (i === index ? { ...r, columns: cols } : r)));
    } catch (err: unknown) {
      setLoadError(err instanceof Error ? err.message : String(err));
    }
  }

  function setRowColumn(
    setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>,
    index: number,
    column: string,
  ) {
    setRows((rows) => rows.map((r, i) => (i === index ? { ...r, column } : r)));
  }

  function removeRow(setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>, index: number) {
    setRows((rows) => (rows.length <= 1 ? rows : rows.filter((_, i) => i !== index)));
  }

  function buildRequest(withDedup: boolean): RecordMatchRequest | null {
    const filledSource = sourceRows.filter((r) => r.table && r.column);
    const filledTarget = targetRows.filter((r) => r.table && r.column);
    const n = Math.min(filledSource.length, filledTarget.length);
    if (n === 0) return null;
    return {
      sourceCriteria: filledSource.slice(0, n).map((r) => ({
        table: r.table,
        column: r.column,
        fuzzyThresholdPercent: isNameColumn(r.column) ? r.fuzzyThresholdPercent : null,
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

  async function runMatch(withDedup: boolean) {
    const req = buildRequest(withDedup);
    if (!req) {
      setMatchError("Pick at least one Source table + column and one matching Target table + column.");
      return;
    }
    setMatchStatus("loading");
    setMatchError(null);
    try {
      const result = await runRecordMatch(req);
      setMatchResult(result);
      setMatchStatus("ok");
    } catch (err: unknown) {
      setMatchError(err instanceof Error ? err.message : String(err));
      setMatchStatus("error");
    }
  }

  function renderCriterionBox(
    title: string,
    rows: CriterionRow[],
    setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>,
    showFuzzy: boolean,
  ) {
    return (
      <section className="srse-card" style={{ flex: "1 1 380px" }}>
        <h2 className="srse-card-title">{title}</h2>
        <p className="srse-text-muted" style={{ fontSize: "0.82rem", marginTop: 0 }}>
          Based on schema of Lakehouse
        </p>

        {rows.map((row, index) => (
          <div
            key={index}
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
              <label className="srse-text-muted" style={fieldLabelStyle}>
                Table
              </label>
              <select
                className="srse-select"
                style={{ width: "100%" }}
                value={row.table}
                onChange={(e) => setRowTable(setRows, index, e.target.value)}
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
              <label className="srse-text-muted" style={fieldLabelStyle}>
                Column
              </label>
              <select
                className="srse-select"
                style={{ width: "100%" }}
                value={row.column}
                onChange={(e) => setRowColumn(setRows, index, e.target.value)}
                disabled={!row.table}
              >
                <option value="">— select —</option>
                {row.columns.map((c) => (
                  <option key={c.name} value={c.name}>
                    {c.name}
                  </option>
                ))}
              </select>
            </div>
            {showFuzzy && isNameColumn(row.column) && (
              <div style={{ flex: "0 1 100px" }}>
                <label className="srse-text-muted" style={fieldLabelStyle}>
                  Fuzzy %
                </label>
                <input
                  type="number"
                  className="srse-input"
                  style={{ width: "100%" }}
                  min={0}
                  max={100}
                  value={row.fuzzyThresholdPercent}
                  onChange={(e) =>
                    setRows((rs) =>
                      rs.map((r, i) => (i === index ? { ...r, fuzzyThresholdPercent: Number(e.target.value) } : r)),
                    )
                  }
                />
              </div>
            )}
            {rows.length > 1 && (
              <button
                type="button"
                className="srse-btn srse-btn-ghost srse-btn-sm"
                onClick={() => removeRow(setRows, index)}
                title="Remove this row"
              >
                ✕
              </button>
            )}
          </div>
        ))}

        <button
          type="button"
          className="srse-btn srse-btn-ghost srse-btn-sm"
          onClick={() => setRows((rs) => [...rs, emptyRow()])}
        >
          + Add more
        </button>
      </section>
    );
  }

  return (
    <main className="srse-page">
      <h1 className="srse-page-title">Analysis</h1>
      <p className="srse-page-description" style={{ maxWidth: "none", whiteSpace: "nowrap" }}>
        Reconcile records across lakehouse tables and columns — fuzzy-match on names, review duplicates, and
        export a clean result set. Nothing here is ever deleted from the lakehouse.
      </p>

      {loadError && <p className="srse-text-danger">{loadError}</p>}

      <label className="srse-checkbox-label" style={{ marginBottom: "1rem", display: "inline-flex" }}>
        <input
          type="checkbox"
          checked={highlightDuplicates}
          onChange={(e) => setHighlightDuplicates(e.target.checked)}
        />
        Highlight Duplicate Records
      </label>

      <div style={{ display: "flex", gap: "1rem", flexWrap: "wrap", width: "100%" }}>
        {renderCriterionBox("Select Source", sourceRows, setSourceRows, true)}
        {renderCriterionBox("Select Target", targetRows, setTargetRows, false)}
      </div>

      <p className="srse-text-muted" style={{ fontSize: "0.78rem", marginTop: "0.5rem" }}>
        Fuzzy % applies automatically when a column name contains &quot;name&quot;; other pairs match exactly.
        Source and Target rows pair up in order — add a matching row on both sides.
      </p>

      <section className="srse-card" style={{ width: "100%", marginTop: "1rem" }}>
        <h2 className="srse-card-title">Optional filters</h2>
        <div style={{ display: "flex", gap: "1.5rem", flexWrap: "wrap", marginTop: "0.75rem" }}>
          <div style={{ flex: "2 1 420px" }}>
            <label className="srse-checkbox-label" style={{ display: "inline-flex" }}>
              <input
                type="checkbox"
                checked={ageFilterEnabled}
                onChange={(e) => setAgeFilterEnabled(e.target.checked)}
              />
              Age range filter
            </label>
            {ageFilterEnabled && (
              <div style={{ display: "flex", gap: "0.75rem", flexWrap: "wrap", marginTop: "0.6rem", alignItems: "flex-end" }}>
                <div style={{ flex: "0 1 140px" }}>
                  <label className="srse-text-muted" style={fieldLabelStyle}>
                    Minimum Age
                  </label>
                  <input
                    type="number"
                    className="srse-input"
                    style={{ width: "100%" }}
                    min={0}
                    value={minAge}
                    onChange={(e) => setMinAge(Number(e.target.value))}
                  />
                </div>
                <div style={{ flex: "0 1 140px" }}>
                  <label className="srse-text-muted" style={fieldLabelStyle}>
                    Maximum Age
                  </label>
                  <input
                    type="number"
                    className="srse-input"
                    style={{ width: "100%" }}
                    min={0}
                    value={maxAge}
                    onChange={(e) => setMaxAge(Number(e.target.value))}
                  />
                </div>
                <div style={{ flex: "0 1 140px" }}>
                  <label className="srse-text-muted" style={fieldLabelStyle}>
                    Unit
                  </label>
                  <select
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

      {matchResult && (
        <div style={{ marginTop: "1.5rem" }}>
          <AnalysisResultsGrid
            columns={matchResult.columns}
            rows={matchResult.rows}
            sql={matchResult.sql}
            capped={matchResult.capped}
            highlightDuplicates={highlightDuplicates}
            dedupAvailable={!!dedupColumn}
            dedupEnabled={dedupEnabled}
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
