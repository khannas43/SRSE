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
import { MultiSelectDropdown } from "@/components/MultiSelectDropdown";
import { ChartsSection } from "@/components/ChartsSection";

type Row = Record<string, unknown>;

const PAGE_SIZE_OPTIONS = [10, 20, 50, 100];

function prettify(columnId: string): string {
  return columnId
    .replace(/^source_/, "Source: ")
    .replace(/^target_/, "Target: ")
    .replaceAll("_", " ")
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

function csvEscape(value: unknown): string {
  const s = String(value);
  return /[",\n]/.test(s) ? `"${s.replaceAll('"', '""')}"` : s;
}

function downloadCsv(rows: Row[], visibleIds: string[], labelFor: (id: string) => string) {
  const lines = [
    visibleIds.map((id) => csvEscape(labelFor(id))).join(","),
    ...rows.map((row) => visibleIds.map((id) => csvEscape(row[id])).join(",")),
  ];
  const blob = new Blob([lines.join("\n")], { type: "text/csv;charset=utf-8;" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `analysis-match-${new Date().toISOString().slice(0, 19).replaceAll(/[:T]/g, "-")}.csv`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

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

type AnalysisResultsGridProps = Readonly<{
  columns: string[];
  rows: Row[];
  sql: string;
  streaming?: boolean;
  totalRows?: number | null;
  totalRowsIsPartial?: boolean;
  highlightDuplicates: boolean;
  dedupAvailable: boolean;
  dedupEnabled: boolean;
  onDedupToggle: (enabled: boolean) => void;
  columnLabels?: Record<string, string>;
}>;

function sortIndicator(sorted: false | "asc" | "desc"): string {
  if (sorted === "asc") return "▲";
  if (sorted === "desc") return "▼";
  return "⇅";
}

function MatchRowCountCaption({
  rowsLength,
  streaming,
  totalRows,
  totalRowsIsPartial,
}: Readonly<{
  rowsLength: number;
  streaming: boolean;
  totalRows: number | null | undefined;
  totalRowsIsPartial: boolean | undefined;
}>) {
  if (streaming || totalRows == null || totalRows <= rowsLength) {
    return (
      <>
        {rowsLength} row{rowsLength === 1 ? "" : "s"}
        {streaming ? " (loading more…)" : ""}
      </>
    );
  }
  return (
    <>
      Showing {rowsLength} of {totalRows}
      {totalRowsIsPartial ? "+" : ""} matching rows — refine your Source/Target criteria (add a
      matching column, narrow a fuzzy threshold, or add an age filter) to bring this into view.
    </>
  );
}

export function AnalysisResultsGrid({
  columns,
  rows,
  sql,
  streaming,
  totalRows,
  totalRowsIsPartial,
  highlightDuplicates,
  dedupAvailable,
  dedupEnabled,
  onDedupToggle,
  columnLabels,
}: AnalysisResultsGridProps) {
  const labelFor = (id: string) => columnLabels?.[id] ?? prettify(id);
  const [sorting, setSorting] = useState<SortingState>([]);
  const [columnFilters, setColumnFilters] = useState<ColumnFiltersState>([]);
  const [visibleColumnIds, setVisibleColumnIds] = useState<string[]>([]);
  const [showSql, setShowSql] = useState(true);
  const showAllColumns = visibleColumnIds.length === 0;
  const effectiveVisibleIds = useMemo(
    () => (showAllColumns ? columns : columns.filter((c) => visibleColumnIds.includes(c))),
    [showAllColumns, visibleColumnIds, columns],
  );

  const columnHelper = useMemo(() => createColumnHelper<Row>(), []);
  const tableColumns = useMemo(
    () =>
      columns.map((id) =>
        columnHelper.accessor((row) => row[id], {
          id,
          header: labelFor(id),
          filterFn: (row, columnId, filterValue) => {
            if (!filterValue) return true;
            return String(row.getValue(columnId))
              .toLowerCase()
              .includes(String(filterValue).toLowerCase());
          },
        }),
      ),
    [columns, columnHelper, columnLabels],
  );

  const table = useReactTable({
    data: rows,
    columns: tableColumns,
    state: {
      sorting,
      columnFilters,
      columnVisibility: Object.fromEntries(columns.map((c) => [c, effectiveVisibleIds.includes(c)])),
    },
    initialState: { pagination: { pageSize: 20 } },
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
        <div>
          <h2 className="srse-card-title" style={{ margin: 0 }}>
            Match results
          </h2>
          <span className="srse-text-muted" style={{ fontSize: "0.85rem" }}>
            <MatchRowCountCaption
              rowsLength={rows.length}
              streaming={!!streaming}
              totalRows={totalRows}
              totalRowsIsPartial={totalRowsIsPartial}
            />
          </span>
        </div>
        {rows.length > 0 && (
          <div style={{ display: "flex", alignItems: "center", gap: "0.6rem", flexWrap: "wrap" }}>
            {dedupAvailable && (
              <label className="srse-checkbox-label" htmlFor="hide-duplicate-records" title="Hides older duplicate rows, keeping the latest by last-updated date">
                <input
                  id="hide-duplicate-records"
                  type="checkbox"
                  checked={dedupEnabled}
                  onChange={(e) => onDedupToggle(e.target.checked)}
                />
                {" "}
                Hide duplicate records
              </label>
            )}
            <MultiSelectDropdown
              options={columns.map((c) => ({ value: c, label: labelFor(c) }))}
              selected={visibleColumnIds}
              onChange={setVisibleColumnIds}
              allLabel="All columns"
              width={200}
              disabled={streaming}
            />
            <button
              type="button"
              className="srse-btn srse-btn-sm"
              onClick={() => downloadCsv(filteredSortedRows, [...effectiveVisibleIds], labelFor)}
              disabled={streaming}
              title={streaming ? "Wait for the match to finish loading before exporting" : undefined}
            >
              ⬇ Download CSV
            </button>
          </div>
        )}
      </div>

      {rows.length === 0 ? (
        <p className="srse-text-muted">No matching rows. Run a match to see results.</p>
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
                          id={`filter-${header.id}`}
                          value={(header.column.getFilterValue() as string) ?? ""}
                          onChange={(e) => header.column.setFilterValue(e.target.value)}
                          placeholder="filter…"
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
                  <tr
                    key={row.id}
                    style={highlightDuplicates ? { background: "var(--srse-warning-bg, #fff7e6)" } : undefined}
                  >
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
              {filteredSortedRows.length !== rows.length ? ` (of ${rows.length})` : ""}
            </span>
            <div style={{ display: "flex", alignItems: "center", gap: "0.6rem" }}>
              <label htmlFor="rows-per-page" style={{ display: "flex", alignItems: "center", gap: "0.4rem", fontSize: "0.85rem" }}>
                <span>Rows per page</span>
                <select
                  id="rows-per-page"
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

      <div style={{ marginTop: "1rem" }}>
        <button
          type="button"
          className="srse-btn srse-btn-ghost srse-btn-sm"
          onClick={() => setShowSql((s) => !s)}
        >
          {showSql ? "▾" : "▸"} Show generated SQL
        </button>
        {showSql && (
          <pre
            style={{
              marginTop: "0.5rem",
              padding: "0.75rem",
              background: "var(--srse-surface-muted, #f5f5f5)",
              border: "1px solid var(--srse-border)",
              borderRadius: "var(--srse-radius-sm)",
              fontSize: "0.8rem",
              overflowX: "auto",
              whiteSpace: "pre-wrap",
              wordBreak: "break-word",
            }}
          >
            {sql}
          </pre>
        )}
      </div>
    </section>

    {rows.length > 0 && (
      <div style={{ marginTop: "1.5rem" }}>
        <ChartsSection
          rows={rows}
          dimensions={columns.map((c) => ({ key: c, label: labelFor(c) }))}
          getValue={(row, key) => String(row[key])}
          getCount={() => 1}
        />
      </div>
    )}
    </>
  );
}
