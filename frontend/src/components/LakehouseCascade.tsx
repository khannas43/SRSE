"use client";

import { useCallback, useEffect, useState } from "react";

/**
 * The Catalog → Schema → Table cascade, shared by every place an admin or
 * officer picks a lakehouse table.
 *
 * Each level is fetched only once the level above it is chosen, and choosing
 * a level clears everything below it — so a stale table name from a previous
 * catalog can never survive into a submitted request. That matters more than
 * usual here: with the lakehouse's Silver and Gold layers both mapped, the
 * same table name exists under more than one catalog, so a leftover value
 * would silently point at the wrong layer rather than failing loudly.
 *
 * Deliberately source-agnostic — the caller supplies the three fetchers. The
 * Admin page passes the live-browse endpoints (everything the Presto
 * connection can reach); the Analysis tab passes the registry endpoints (only
 * what an admin registered). Same component, two different reaches.
 */

export type CascadeValue = {
  catalog: string;
  schema: string;
  table: string;
};

export const EMPTY_CASCADE: CascadeValue = { catalog: "", schema: "", table: "" };

export function isCascadeComplete(v: CascadeValue): boolean {
  return Boolean(v.catalog && v.schema && v.table);
}

/** A table option; `layer` (SILVER/GOLD) is rendered as a suffix when present. */
export type TableOption = { name: string; layer?: string | null };

export type CascadeFetchers = {
  listCatalogs: () => Promise<string[]>;
  listSchemas: (catalog: string) => Promise<string[]>;
  listTables: (catalog: string, schema: string) => Promise<TableOption[]>;
};

type Props = Readonly<{
  value: CascadeValue;
  onChange: (value: CascadeValue) => void;
  fetchers: CascadeFetchers;
  /** Rendered above the row; omit for a bare inline cascade. */
  label?: string;
  idPrefix: string;
  disabled?: boolean;
  /** Compact drops the per-select captions — for dense repeated rows. */
  compact?: boolean;
  onError?: (message: string) => void;
}>;

const selectStyle = { minWidth: 150 } as const;

export default function LakehouseCascade({
  value,
  onChange,
  fetchers,
  label,
  idPrefix,
  disabled = false,
  compact = false,
  onError,
}: Props) {
  const [catalogs, setCatalogs] = useState<string[]>([]);
  const [schemas, setSchemas] = useState<string[]>([]);
  const [tables, setTables] = useState<TableOption[]>([]);
  const [loading, setLoading] = useState(false);

  const report = useCallback(
    (err: unknown) => onError?.(err instanceof Error ? err.message : String(err)),
    [onError],
  );

  const { listCatalogs, listSchemas, listTables } = fetchers;

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    listCatalogs()
      .then((c) => {
        if (!cancelled) setCatalogs(c);
      })
      .catch(report)
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [listCatalogs, report]);

  useEffect(() => {
    let cancelled = false;
    if (!value.catalog) {
      setSchemas([]);
      return undefined;
    }
    listSchemas(value.catalog)
      .then((s) => {
        if (!cancelled) setSchemas(s);
      })
      .catch(report);
    return () => {
      cancelled = true;
    };
  }, [value.catalog, listSchemas, report]);

  useEffect(() => {
    let cancelled = false;
    if (!value.catalog || !value.schema) {
      setTables([]);
      return undefined;
    }
    listTables(value.catalog, value.schema)
      .then((t) => {
        if (!cancelled) setTables(t);
      })
      .catch(report);
    return () => {
      cancelled = true;
    };
  }, [value.catalog, value.schema, listTables, report]);

  // Each setter clears every level BELOW it — see the component javadoc.
  function pickCatalog(catalog: string) {
    onChange({ catalog, schema: "", table: "" });
  }

  function pickSchema(schema: string) {
    onChange({ ...value, schema, table: "" });
  }

  function pickTable(table: string) {
    onChange({ ...value, table });
  }

  function caption(text: string, htmlFor: string) {
    if (compact) return null;
    return (
      <label htmlFor={htmlFor} className="srse-text-muted" style={{ fontSize: "0.72rem", display: "block" }}>
        {text}
      </label>
    );
  }

  return (
    <div>
      {label && (
        <div className="srse-text-muted" style={{ fontSize: "0.78rem", marginBottom: "0.3rem" }}>
          {label}
        </div>
      )}
      <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap", alignItems: "flex-end" }}>
        <div>
          {caption("Catalog", `${idPrefix}-catalog`)}
          <select
            id={`${idPrefix}-catalog`}
            className="srse-select"
            style={selectStyle}
            value={value.catalog}
            disabled={disabled}
            onChange={(e) => pickCatalog(e.target.value)}
          >
            <option value="">{loading ? "— loading… —" : "— catalog —"}</option>
            {catalogs.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </div>

        <div>
          {caption("Schema", `${idPrefix}-schema`)}
          <select
            id={`${idPrefix}-schema`}
            className="srse-select"
            style={selectStyle}
            value={value.schema}
            disabled={disabled || !value.catalog}
            onChange={(e) => pickSchema(e.target.value)}
          >
            <option value="">— schema —</option>
            {schemas.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>

        <div>
          {caption("Table", `${idPrefix}-table`)}
          <select
            id={`${idPrefix}-table`}
            className="srse-select"
            style={selectStyle}
            value={value.table}
            disabled={disabled || !value.schema}
            onChange={(e) => pickTable(e.target.value)}
          >
            <option value="">— table —</option>
            {tables.map((t) => (
              <option key={t.name} value={t.name}>
                {t.layer ? `${t.name} (${t.layer})` : t.name}
              </option>
            ))}
          </select>
        </div>
      </div>
    </div>
  );
}
