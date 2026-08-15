"use client";

import { useMemo, useState, type CSSProperties } from "react";
import {
  createColumnHelper,
  flexRender,
  getCoreRowModel,
  getFilteredRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  useReactTable,
  type ColumnFiltersState,
  type SortingState,
} from "@tanstack/react-table";
import type { BreakdownRow } from "@/lib/decisionApi";
import { MultiSelectDropdown } from "@/components/MultiSelectDropdown";
import { ChartsSection, type ChartDimension } from "@/components/ChartsSection";

const CHART_DIMENSIONS: ChartDimension[] = [
  { key: "district", label: "District" },
  { key: "gender", label: "Gender" },
  { key: "ageBand", label: "Age band" },
];

function breakdownDimensionValue(row: BreakdownRow, key: string): string {
  if (key === "district") return row.district;
  if (key === "gender") return row.gender;
  if (key === "ageBand") return row.ageBand;
  return "";
}

/** Single source of truth for id/label, reused for columns, the visibility
 * dropdown, and CSV headers so they can never drift apart. */
const COLUMN_META: { id: keyof BreakdownRow; label: string }[] = [
  { id: "district", label: "District" },
  { id: "gender", label: "Gender" },
  { id: "ageBand", label: "Age band" },
  { id: "count", label: "Count" },
];

const columnHelper = createColumnHelper<BreakdownRow>();

const columns = [
  columnHelper.accessor("district", { header: "District" }),
  columnHelper.accessor("gender", { header: "Gender" }),
  columnHelper.accessor("ageBand", { header: "Age band" }),
  columnHelper.accessor("count", {
    header: "Count",
    filterFn: (row, columnId, filterValue) => {
      if (filterValue === "" || filterValue === undefined) return true;
      const min = Number(filterValue);
      return Number.isNaN(min) ? true : (row.getValue(columnId) as number) >= min;
    },
  }),
];

const PAGE_SIZE_OPTIONS = [10, 20, 50, 100];

function csvEscape(value: unknown): string {
  const s = String(value);
  return /[",\n]/.test(s) ? `"${s.replaceAll('"', '""')}"` : s;
}

function downloadCsv(rows: BreakdownRow[], visibleIds: Set<string>) {
  const cols = COLUMN_META.filter((c) => visibleIds.has(c.id));
  const lines = [
    cols.map((c) => csvEscape(c.label)).join(","),
    ...rows.map((row) => cols.map((c) => csvEscape(row[c.id])).join(",")),
  ];
  const blob = new Blob([lines.join("\n")], { type: "text/csv;charset=utf-8;" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `breakdown-${new Date().toISOString().slice(0, 19).replaceAll(/[:T]/g, "-")}.csv`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

const captionStyle: CSSProperties = { margin: 0, color: "var(--srse-text-muted)", fontSize: "0.9rem" };
const totalStyle: CSSProperties = {
  margin: "0.25rem 0 0",
  fontSize: "2.75rem",
  fontWeight: 700,
  letterSpacing: "-0.02em",
  lineHeight: 1.1,
  color: "var(--srse-primary)",
};
const sectionHeadingStyle: CSSProperties = { fontSize: "1rem", fontWeight: 600, marginBottom: "0.75rem" };
const filterInputStyle: CSSProperties = {
  width: "100%",
  padding: "0.25rem 0.4rem",
  fontSize: "0.78rem",
  fontWeight: 400,
  border: "1px solid var(--srse-border-strong)",
  borderRadius: "var(--srse-radius-sm)",
  background: "var(--srse-surface)",
  marginTop: "0.35rem",
};

function sortIndicator(sorted: false | "asc" | "desc"): string {
  if (sorted === "asc") return "▲";
  if (sorted === "desc") return "▼";
  return "⇅";
}

export function ResultsPanel({
  totalCount,
  breakdown,
  caption,
}: Readonly<{
  totalCount: number;
  breakdown: BreakdownRow[];
  caption?: string;
}>) {
  const [sorting, setSorting] = useState<SortingState>([]);
  const [columnFilters, setColumnFilters] = useState<ColumnFiltersState>([]);
  // Empty selection means "all columns visible" — same convention as the
  // District/Scheme dropdowns elsewhere in the app.
  const [visibleColumnIds, setVisibleColumnIds] = useState<string[]>([]);
  const showAllColumns = visibleColumnIds.length === 0;
  const effectiveVisibleIds = useMemo(
    () => new Set(showAllColumns ? COLUMN_META.map((c) => c.id) : visibleColumnIds),
    [showAllColumns, visibleColumnIds],
  );

  const table = useReactTable({
    data: breakdown,
    columns,
    state: {
      sorting,
      columnFilters,
      columnVisibility: Object.fromEntries(COLUMN_META.map((c) => [c.id, effectiveVisibleIds.has(c.id)])),
    },
    initialState: { pagination: { pageSize: 10 } },
    onSortingChange: setSorting,
    onColumnFiltersChange: setColumnFilters,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
  });

  const filteredSortedRows = table.getSortedRowModel().rows.map((r) => r.original);
  const pageCount = table.getPageCount();
  const { pageIndex, pageSize } = table.getState().pagination;

  return (
    <>
      <section style={{ marginBottom: "2rem" }}>
        {caption && <p style={captionStyle}>{caption}</p>}
        <p style={totalStyle}>{totalCount.toLocaleString("en-IN")}</p>
      </section>

      <section style={{ marginBottom: "2rem" }}>
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
          <h2 style={{ ...sectionHeadingStyle, marginBottom: 0 }}>Breakdown by district / gender / age band</h2>
          {breakdown.length > 0 && (
            <div style={{ display: "flex", alignItems: "center", gap: "0.6rem" }}>
              <MultiSelectDropdown
                options={COLUMN_META.map((c) => ({ value: c.id, label: c.label }))}
                selected={visibleColumnIds}
                onChange={setVisibleColumnIds}
                allLabel="All columns"
                width={180}
              />
              <button
                type="button"
                className="srse-btn srse-btn-sm"
                onClick={() => downloadCsv(filteredSortedRows, effectiveVisibleIds)}
              >
                ⬇ Download CSV
              </button>
            </div>
          )}
        </div>

        {breakdown.length === 0 ? (
          <p className="srse-text-muted">No breakdown rows returned.</p>
        ) : (
          <>
            <div style={{ overflowX: "auto" }}>
              <table className="srse-table">
                <thead>
                  {table.getHeaderGroups().map((hg) => (
                    <tr key={hg.id}>
                      {hg.headers.map((header) => (
                        <th key={header.id} style={{ verticalAlign: "top" }}>
                          <button
                            type="button"
                            onClick={header.column.getToggleSortingHandler()}
                            style={{
                              cursor: "pointer",
                              userSelect: "none",
                              display: "inline-flex",
                              gap: "0.3rem",
                              background: "none",
                              border: "none",
                              padding: 0,
                              font: "inherit",
                              color: "inherit",
                            }}
                          >
                            {flexRender(header.column.columnDef.header, header.getContext())}
                            <span style={{ color: "var(--srse-text-faint)" }}>
                              {sortIndicator(header.column.getIsSorted())}
                            </span>
                          </button>
                          <input
                            id={`breakdown-filter-${header.id}`}
                            value={(header.column.getFilterValue() as string) ?? ""}
                            onChange={(e) => header.column.setFilterValue(e.target.value)}
                            placeholder={header.column.id === "count" ? "min ≥" : "filter…"}
                            style={filterInputStyle}
                            onClick={(e) => e.stopPropagation()}
                            aria-label={`Filter ${String(header.column.columnDef.header)}`}
                          />
                        </th>
                      ))}
                    </tr>
                  ))}
                </thead>
                <tbody>
                  {table.getRowModel().rows.map((row) => (
                    <tr key={row.id}>
                      {row.getVisibleCells().map((cell) => (
                        <td key={cell.id}>{flexRender(cell.column.columnDef.cell, cell.getContext())}</td>
                      ))}
                    </tr>
                  ))}
                  {table.getRowModel().rows.length === 0 && (
                    <tr>
                      <td colSpan={table.getVisibleFlatColumns().length} className="srse-text-muted">
                        No rows match the current filters.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>

            <div
              style={{
                display: "flex",
                alignItems: "center",
                justifyContent: "space-between",
                flexWrap: "wrap",
                gap: "0.75rem",
                marginTop: "0.85rem",
              }}
            >
              <span className="srse-text-muted">
                {filteredSortedRows.length} row{filteredSortedRows.length === 1 ? "" : "s"}
                {filteredSortedRows.length !== breakdown.length ? ` (of ${breakdown.length})` : ""}
              </span>
              <div style={{ display: "flex", alignItems: "center", gap: "0.6rem" }}>
                <label htmlFor="breakdown-rows-per-page" style={{ display: "flex", alignItems: "center", gap: "0.4rem", fontSize: "0.85rem" }}>
                  <span>Rows per page</span>
                  <select
                    id="breakdown-rows-per-page"
                    value={pageSize}
                    onChange={(e) => table.setPageSize(Number(e.target.value))}
                    className="srse-select"
                  >
                    {PAGE_SIZE_OPTIONS.map((size) => (
                      <option key={size} value={size}>
                        {size}
                      </option>
                    ))}
                  </select>
                </label>
                <button
                  type="button"
                  className="srse-btn srse-btn-ghost srse-btn-sm"
                  onClick={() => table.setPageIndex(0)}
                  disabled={!table.getCanPreviousPage()}
                >
                  «
                </button>
                <button
                  type="button"
                  className="srse-btn srse-btn-ghost srse-btn-sm"
                  onClick={() => table.previousPage()}
                  disabled={!table.getCanPreviousPage()}
                >
                  ‹ Prev
                </button>
                <span className="srse-text-muted">
                  Page {pageCount === 0 ? 0 : pageIndex + 1} of {pageCount}
                </span>
                <button
                  type="button"
                  className="srse-btn srse-btn-ghost srse-btn-sm"
                  onClick={() => table.nextPage()}
                  disabled={!table.getCanNextPage()}
                >
                  Next ›
                </button>
                <button
                  type="button"
                  className="srse-btn srse-btn-ghost srse-btn-sm"
                  onClick={() => table.setPageIndex(pageCount - 1)}
                  disabled={!table.getCanNextPage()}
                >
                  »
                </button>
              </div>
            </div>
          </>
        )}
      </section>

      <ChartsSection
        rows={breakdown}
        dimensions={CHART_DIMENSIONS}
        getValue={breakdownDimensionValue}
        getCount={(row) => row.count}
      />
    </>
  );
}
