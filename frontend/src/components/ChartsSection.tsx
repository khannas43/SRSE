"use client";

import { useMemo, useState } from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { VennDiagram } from "@/components/VennDiagram";

export type ChartDimension = { key: string; label: string };

const PIE_COLORS = [
  "#4f46e5", "#059669", "#d97706", "#dc2626", "#0891b2",
  "#7c3aed", "#db2777", "#65a30d", "#0284c7", "#ea580c",
];

// Orange / green / navy, matching the reference 3-circle Venn layout.
const VENN_COLORS = ["#ea580c", "#65a30d", "#0c4a6e"];

const MAX_INTERSECTION_DIMS = 5;
// Separator for composite group-by keys — unlikely to appear in real data values.
const KEY_SEP = "␟";

// Analysis match results are no longer capped at 1000 rows, so a field can
// have thousands of distinct values. Recharts renders one SVG bar/label (or
// pie slice) per category with no virtualization — beyond a few dozen that
// crashes the tab (confirmed live: an OOM "Error code: 5" renderer crash),
// not just looks bad. Cap what actually gets rendered, always by count so
// the most meaningful categories survive the cut, and say so when truncated.
const MAX_CHART_CATEGORIES = 50;
// A plain HTML table row is far cheaper than an SVG bar, so this can be looser.
const MAX_TABLE_ROWS = 200;
// Matrix mode multiplies two axes into rowVals.length * colVals.length cells —
// cap each axis so the product stays bounded even if both dimensions are
// high-cardinality (e.g. two id-like columns).
const MAX_MATRIX_AXIS_VALUES = 40;
// Native <select> handles a few hundred options fine — this is just a sanity
// ceiling for pathological cases, not a rendering-cost concern like the above.
const MAX_SELECT_OPTIONS = 500;

type ChartType = "bar" | "pie" | "intersection";
type IntersectionDisplay = "table" | "venn";

/**
 * Generic Bar/Pie/Intersection chart section, shared by the Rule Engine's
 * breakdown table (ResultsPanel) and the Analysis tab's match grid
 * (AnalysisResultsGrid) — both aggregate over already-fetched rows,
 * client-side, no new API calls. `getCount` lets each caller decide what
 * "one" means: Rule Engine rows are pre-aggregated (sum row.count), Analysis
 * rows are one match per row (count rows, i.e. always 1).
 */
export function ChartsSection<T>({
  rows,
  dimensions,
  getValue,
  getCount,
  title = "Charts",
}: {
  rows: T[];
  dimensions: ChartDimension[];
  getValue: (row: T, key: string) => string;
  getCount: (row: T) => number;
  title?: string;
}) {
  const [chartType, setChartType] = useState<ChartType>("bar");
  const [singleDim, setSingleDim] = useState(dimensions[0]?.key ?? "");
  const [intersectionDims, setIntersectionDims] = useState<string[]>([]);
  // Defaults to the actual chart, not the raw table — "Intersection Chart"
  // should show a chart as soon as 2-3 fields are picked, not require an
  // extra click to discover the Venn option is even there.
  const [intersectionDisplay, setIntersectionDisplay] = useState<IntersectionDisplay>("venn");
  // For the Venn view only: which specific value defines "inside" each circle.
  const [vennValues, setVennValues] = useState<Record<string, string>>({});

  const singleAggFull = useMemo(() => {
    if (!singleDim) return [];
    const totals = new Map<string, number>();
    for (const row of rows) {
      const v = getValue(row, singleDim) || "(blank)";
      totals.set(v, (totals.get(v) ?? 0) + getCount(row));
    }
    return Array.from(totals.entries())
      .map(([value, count]) => ({ value, count }))
      .sort((a, b) => b.count - a.count);
  }, [rows, singleDim, getValue, getCount]);
  const singleAggTruncated = singleAggFull.length > MAX_CHART_CATEGORIES;
  const singleAgg = singleAggTruncated ? singleAggFull.slice(0, MAX_CHART_CATEGORIES) : singleAggFull;

  const intersection = useMemo(() => {
    if (intersectionDims.length < 2) return null;
    const totals = new Map<string, number>();
    for (const row of rows) {
      const key = intersectionDims.map((d) => getValue(row, d) || "(blank)").join(KEY_SEP);
      totals.set(key, (totals.get(key) ?? 0) + getCount(row));
    }
    if (intersectionDims.length === 2) {
      const rowValsFull = Array.from(
        new Set(rows.map((r) => getValue(r, intersectionDims[0]) || "(blank)")),
      ).sort();
      const colValsFull = Array.from(
        new Set(rows.map((r) => getValue(r, intersectionDims[1]) || "(blank)")),
      ).sort();
      const rowVals = rowValsFull.slice(0, MAX_MATRIX_AXIS_VALUES);
      const colVals = colValsFull.slice(0, MAX_MATRIX_AXIS_VALUES);
      return {
        kind: "matrix" as const,
        rowVals,
        colVals,
        totals,
        truncated: rowValsFull.length > MAX_MATRIX_AXIS_VALUES || colValsFull.length > MAX_MATRIX_AXIS_VALUES,
        rowValsFullCount: rowValsFull.length,
        colValsFullCount: colValsFull.length,
      };
    }
    const combosFull = Array.from(totals.entries())
      .map(([key, count]) => ({ parts: key.split(KEY_SEP), count }))
      .sort((a, b) => b.count - a.count);
    const truncated = combosFull.length > MAX_TABLE_ROWS;
    return {
      kind: "flat" as const,
      combos: truncated ? combosFull.slice(0, MAX_TABLE_ROWS) : combosFull,
      truncated,
      fullCount: combosFull.length,
    };
  }, [rows, intersectionDims, getValue, getCount]);

  // Venn view only applies to 2 or 3 dims — beyond that there's no clean
  // circle layout, so the display toggle is hidden and it falls back to table.
  const vennEligibleDims = intersectionDims.length === 2 || intersectionDims.length === 3;

  const distinctValuesFor = useMemo(
    () => (key: string) =>
      Array.from(new Set(rows.map((r) => getValue(r, key) || "(blank)"))).sort().slice(0, MAX_SELECT_OPTIONS),
    [rows, getValue],
  );

  const vennCounts = useMemo(() => {
    if (!vennEligibleDims) return null;
    const totals: Record<string, number> = {};
    for (const row of rows) {
      const bits = intersectionDims
        .map((d) => (getValue(row, d) || "(blank)") === (vennValues[d] ?? "") ? "1" : "0")
        .join("");
      if (!/1/.test(bits)) continue; // "in none of the circles" isn't a Venn region
      totals[bits] = (totals[bits] ?? 0) + getCount(row);
    }
    return totals;
  }, [rows, intersectionDims, vennValues, vennEligibleDims, getValue, getCount]);

  function toggleIntersectionDim(key: string) {
    setIntersectionDims((cur) => {
      if (cur.includes(key)) {
        setVennValues((v) => {
          const next = { ...v };
          delete next[key];
          return next;
        });
        return cur.filter((k) => k !== key);
      }
      if (cur.length >= MAX_INTERSECTION_DIMS) return cur;
      const values = distinctValuesFor(key);
      if (values.length > 0) {
        setVennValues((v) => ({ ...v, [key]: values[0] }));
      }
      return [...cur, key];
    });
  }

  function dimLabel(key: string): string {
    return dimensions.find((d) => d.key === key)?.label ?? key;
  }

  if (rows.length === 0) return null;

  return (
    <section className="srse-card" style={{ width: "100%" }}>
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          flexWrap: "wrap",
          gap: "0.75rem",
          marginBottom: "0.75rem",
        }}
      >
        <h2 className="srse-card-title" style={{ margin: 0 }}>
          {title}
        </h2>
        <div style={{ display: "flex", gap: "0.4rem" }}>
          {(["bar", "pie", "intersection"] as const).map((t) => (
            <button
              key={t}
              type="button"
              className={
                chartType === t
                  ? "srse-btn srse-btn-primary srse-btn-sm"
                  : "srse-btn srse-btn-ghost srse-btn-sm"
              }
              onClick={() => setChartType(t)}
            >
              {t === "bar" ? "Bar Chart" : t === "pie" ? "Pie Chart" : "Intersection Chart"}
            </button>
          ))}
        </div>
      </div>

      {(chartType === "bar" || chartType === "pie") && (
        <div>
          <label
            style={{ display: "flex", alignItems: "center", gap: "0.5rem", fontSize: "0.85rem", marginBottom: "0.75rem" }}
          >
            <span className="srse-text-muted">Field</span>
            <select className="srse-select" value={singleDim} onChange={(e) => setSingleDim(e.target.value)}>
              {dimensions.map((d) => (
                <option key={d.key} value={d.key}>
                  {d.label}
                </option>
              ))}
            </select>
          </label>

          {singleAggTruncated && (
            <p className="srse-text-muted" style={{ fontSize: "0.78rem", marginTop: "-0.35rem", marginBottom: "0.6rem" }}>
              Showing the top {MAX_CHART_CATEGORIES} of {singleAggFull.length} distinct values by count — pick a
              lower-cardinality field, or use the data grid / CSV export, to see the rest.
            </p>
          )}

          {singleAgg.length === 0 ? (
            <p className="srse-text-muted">No data to chart.</p>
          ) : chartType === "bar" ? (
            <div style={{ width: "100%", height: 320 }}>
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={singleAgg} margin={{ top: 8, right: 16, left: 0, bottom: 48 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="var(--srse-border)" />
                  <XAxis
                    dataKey="value"
                    angle={-30}
                    textAnchor="end"
                    interval={0}
                    height={60}
                    tick={{ fontSize: 12, fill: "var(--srse-text-muted)" }}
                  />
                  <YAxis allowDecimals={false} tick={{ fontSize: 12, fill: "var(--srse-text-muted)" }} />
                  <Tooltip />
                  <Bar dataKey="count" fill="var(--srse-primary)" radius={[4, 4, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          ) : (
            <div style={{ width: "100%", height: 360 }}>
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie data={singleAgg} dataKey="count" nameKey="value" cx="50%" cy="50%" outerRadius={110} label>
                    {singleAgg.map((_, i) => (
                      <Cell key={i} fill={PIE_COLORS[i % PIE_COLORS.length]} />
                    ))}
                  </Pie>
                  <Tooltip />
                  <Legend />
                </PieChart>
              </ResponsiveContainer>
            </div>
          )}
        </div>
      )}

      {chartType === "intersection" && (
        <div>
          <div style={{ display: "flex", flexWrap: "wrap", gap: "0.4rem 1rem", marginBottom: "0.85rem" }}>
            {dimensions.map((d) => (
              <label key={d.key} className="srse-checkbox-label">
                <input
                  type="checkbox"
                  checked={intersectionDims.includes(d.key)}
                  onChange={() => toggleIntersectionDim(d.key)}
                />
                {d.label}
              </label>
            ))}
          </div>

          {vennEligibleDims && (
            <div style={{ display: "flex", alignItems: "center", gap: "0.4rem", marginBottom: "0.85rem" }}>
              {(["table", "venn"] as const).map((d) => (
                <button
                  key={d}
                  type="button"
                  className={
                    intersectionDisplay === d
                      ? "srse-btn srse-btn-primary srse-btn-sm"
                      : "srse-btn srse-btn-ghost srse-btn-sm"
                  }
                  onClick={() => setIntersectionDisplay(d)}
                >
                  {d === "table" ? "Table" : "Venn Diagram"}
                </button>
              ))}
            </div>
          )}

          {vennEligibleDims && intersectionDisplay === "venn" && (
            <div style={{ display: "flex", flexWrap: "wrap", gap: "0.75rem", marginBottom: "1rem" }}>
              {intersectionDims.map((d) => (
                <label
                  key={d}
                  style={{ display: "flex", alignItems: "center", gap: "0.4rem", fontSize: "0.85rem" }}
                >
                  <span className="srse-text-muted">{dimLabel(d)} =</span>
                  <select
                    className="srse-select"
                    value={vennValues[d] ?? ""}
                    onChange={(e) => setVennValues((v) => ({ ...v, [d]: e.target.value }))}
                  >
                    {distinctValuesFor(d).map((v) => (
                      <option key={v} value={v}>
                        {v}
                      </option>
                    ))}
                  </select>
                </label>
              ))}
            </div>
          )}

          {intersectionDims.length < 2 ? (
            <p className="srse-text-muted">Pick at least 2 fields to see their intersection.</p>
          ) : vennEligibleDims && intersectionDisplay === "venn" && vennCounts ? (
            <VennDiagram
              sets={intersectionDims.map((d, i) => ({
                label: `${dimLabel(d)} = ${vennValues[d] ?? ""}`,
                color: VENN_COLORS[i % VENN_COLORS.length],
              }))}
              counts={vennCounts}
            />
          ) : intersection?.kind === "matrix" ? (
            <div>
              {intersection.truncated && (
                <p className="srse-text-muted" style={{ fontSize: "0.78rem", marginBottom: "0.6rem" }}>
                  Showing the top {intersection.rowVals.length} of {intersection.rowValsFullCount} row values and
                  top {intersection.colVals.length} of {intersection.colValsFullCount} column values by name —
                  pick lower-cardinality fields to see the rest.
                </p>
              )}
              <div style={{ overflowX: "auto" }}>
                <table className="srse-table">
                  <thead>
                    <tr>
                      <th>
                        {dimLabel(intersectionDims[0])} vs {dimLabel(intersectionDims[1])}
                      </th>
                      {intersection.colVals.map((c) => (
                        <th key={c}>{c}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {intersection.rowVals.map((r) => (
                      <tr key={r}>
                        <td style={{ fontWeight: 500 }}>{r}</td>
                        {intersection.colVals.map((c) => (
                          <td key={c}>{intersection.totals.get(`${r}${KEY_SEP}${c}`) ?? 0}</td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          ) : intersection?.kind === "flat" ? (
            <div>
              {intersection.truncated && (
                <p className="srse-text-muted" style={{ fontSize: "0.78rem", marginBottom: "0.6rem" }}>
                  Showing the top {MAX_TABLE_ROWS} of {intersection.fullCount} combinations by count — pick fewer
                  or lower-cardinality fields, or use the data grid / CSV export, to see the rest.
                </p>
              )}
              <div style={{ overflowX: "auto" }}>
                <table className="srse-table">
                  <thead>
                    <tr>
                      {intersectionDims.map((k) => (
                        <th key={k}>{dimLabel(k)}</th>
                      ))}
                      <th>Count</th>
                    </tr>
                  </thead>
                  <tbody>
                    {intersection.combos.map((combo, i) => (
                      <tr key={i}>
                        {combo.parts.map((p, j) => (
                          <td key={j}>{p}</td>
                        ))}
                        <td>{combo.count}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          ) : null}
        </div>
      )}
    </section>
  );
}
