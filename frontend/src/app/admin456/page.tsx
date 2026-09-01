"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  browseCatalogs,
  browseColumns,
  browseSchemas,
  browseTables,
  createField,
  getConnections,
  listMappings,
  listRegistrations,
  registerTable,
  unregisterTable,
  updateAnalyticalConnection,
  updateOperationalConnection,
  upsertMapping,
  type ConnectionPlaneInfo,
  type ConnectionsInfo,
  type DataMode,
  type FieldDataType,
  type FieldTier,
  type LakehouseColumnInfo,
  type MappingRow,
  type TableRegistration,
} from "@/lib/decisionApi";
import {
  listColumnMetadata,
  upsertColumnMetadata,
  type ColumnMetadata,
} from "@/lib/analysisApi";
import LakehouseCascade, {
  EMPTY_CASCADE,
  isCascadeComplete,
  type CascadeFetchers,
  type CascadeValue,
} from "@/components/LakehouseCascade";

/**
 * Admin cascades browse the LIVE lakehouse — everything the current Presto
 * connection can physically reach — which is what makes discovery possible
 * before anything is registered. (The Analysis tab passes registry-backed
 * fetchers to the same component instead; see LakehouseCascade's javadoc.)
 */
const BROWSE_FETCHERS: CascadeFetchers = {
  listCatalogs: browseCatalogs,
  listSchemas: browseSchemas,
  listTables: (catalog, schema) =>
    browseTables(catalog, schema).then((names) => names.map((name) => ({ name }))),
};

function qualified(v: CascadeValue, column?: string): string {
  const base = `${v.catalog}.${v.schema}.${v.table}`;
  return column ? `${base}.${column}` : base;
}

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

function StatusBadge({ status }: Readonly<{ status: string }>) {
  const up = status === "up";
  return (
    <span className={up ? "srse-badge srse-badge-success" : "srse-badge srse-badge-danger"}>
      <span className="srse-badge-dot" />
      {status}
    </span>
  );
}

type PlaneKey = "operational" | "analytical";

const PLANE_LABEL: Record<PlaneKey, string> = {
  operational: "Operational (DB2 / JPA)",
  analytical: "Analytical (Presto / JDBC)",
};

function ConnectionCard({
  planeKey,
  plane,
  onLiveUpdate,
}: Readonly<{
  planeKey: PlaneKey;
  plane: ConnectionPlaneInfo;
  onLiveUpdate: (plane: ConnectionPlaneInfo) => void;
}>) {
  const [editing, setEditing] = useState(false);
  const [jdbcUrl, setJdbcUrl] = useState(plane.jdbcUrl);
  const [username, setUsername] = useState(plane.username);
  const [password, setPassword] = useState("");
  const [driverClassName, setDriverClassName] = useState(plane.driverClassName);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  function startEditing() {
    setJdbcUrl(plane.jdbcUrl);
    setUsername(plane.username);
    setPassword("");
    setDriverClassName(plane.driverClassName);
    setError(null);
    setMessage(null);
    setEditing(true);
  }

  async function onSave() {
    setSaving(true);
    setError(null);
    setMessage(null);
    try {
      const update = planeKey === "analytical" ? updateAnalyticalConnection : updateOperationalConnection;
      const result = await update({ jdbcUrl, username, password, driverClassName });
      if (result.restartRequired) {
        setMessage("Saved — restart the backend for this to take effect.");
      } else if (result.plane) {
        setMessage("Saved and applied immediately — no restart needed.");
        onLiveUpdate(result.plane);
      }
      setEditing(false);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div
      style={{
        flex: "1 1 320px",
        border: "1px solid var(--srse-border)",
        borderRadius: "var(--srse-radius-sm)",
        padding: "1rem 1.15rem",
        background: "var(--srse-surface)",
      }}
    >
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "0.6rem" }}>
        <div style={{ fontWeight: 600, fontSize: "0.92rem" }}>{PLANE_LABEL[planeKey]}</div>
        <button
          type="button"
          className="srse-btn srse-btn-ghost srse-btn-sm"
          onClick={() => (editing ? setEditing(false) : startEditing())}
        >
          {editing ? "Cancel" : "Edit"}
        </button>
      </div>

      {!editing && (
        <>
          <div style={{ fontSize: "0.83rem", fontFamily: "monospace", marginBottom: "0.35rem", color: "var(--srse-text)" }}>
            {plane.jdbcUrl}
          </div>
          <div className="srse-text-muted" style={{ marginBottom: "0.6rem" }}>
            user: {plane.username} · driver: {plane.driverClassName}
          </div>
          <StatusBadge status={plane.status} />
        </>
      )}

      {editing && (
        <div style={{ display: "flex", flexDirection: "column", gap: "0.5rem" }}>
          <input
            placeholder="JDBC URL"
            value={jdbcUrl}
            onChange={(e) => setJdbcUrl(e.target.value)}
            className="srse-input"
            style={{ fontFamily: "monospace" }}
          />
          <input
            placeholder="Username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            className="srse-input"
          />
          <input
            type="password"
            placeholder="Password (write-only — always re-enter)"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="srse-input"
          />
          <input
            placeholder="Driver class name"
            value={driverClassName}
            onChange={(e) => setDriverClassName(e.target.value)}
            className="srse-input"
          />
          <button type="button" className="srse-btn srse-btn-primary" disabled={saving} onClick={onSave}>
            {saving ? "Testing & saving…" : "Test & Save"}
          </button>
        </div>
      )}

      {message && (
        <p className="srse-text-success" style={{ marginBottom: 0, marginTop: "0.6rem" }}>
          {message}
        </p>
      )}
      {error && (
        <p className="srse-text-danger" style={{ marginBottom: 0, marginTop: "0.6rem" }}>
          {error}
        </p>
      )}
    </div>
  );
}

function ConnectionsPanel() {
  const [connections, setConnections] = useState<ConnectionsInfo | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getConnections()
      .then(setConnections)
      .catch((err: unknown) => setError(err instanceof Error ? err.message : String(err)));
  }, []);

  return (
    <section className="srse-card">
      <h2 className="srse-card-title">Connections</h2>
      {error && <p className="srse-text-danger">{error}</p>}
      {!connections && !error && <p className="srse-text-muted">Loading…</p>}
      {connections && (
        <>
          <p className="srse-text-muted" style={{ marginTop: 0, marginBottom: "1rem", lineHeight: 1.5 }}>
            DATA_MODE: <strong style={{ color: "var(--srse-text)" }}>{connections.dataMode}</strong> — still set
            via environment config, not editable here.{" "}
            <strong style={{ color: "var(--srse-text)" }}>Analytical</strong> edits below apply immediately, no
            restart. <strong style={{ color: "var(--srse-text)" }}>Operational</strong> edits are tested and saved
            but only take effect after a manual backend restart.
            <br />
            The Presto URL is now <strong style={{ color: "var(--srse-text)" }}>catalog-agnostic</strong>:
            <code> jdbc:presto://host:8080</code> is enough. Any trailing <code>/catalog/schema</code> is
            only a default — SRSE addresses every table by its full{" "}
            <code>catalog.schema.table</code>, so one connection reaches all registered catalogs
            (including both the Silver and Gold layers).
          </p>
          <div style={{ display: "flex", gap: "1rem", flexWrap: "wrap" }}>
            <ConnectionCard
              planeKey="operational"
              plane={connections.operational}
              onLiveUpdate={(plane) => setConnections((c) => (c ? { ...c, operational: plane } : c))}
            />
            <ConnectionCard
              planeKey="analytical"
              plane={connections.analytical}
              onLiveUpdate={(plane) => setConnections((c) => (c ? { ...c, analytical: plane } : c))}
            />
          </div>
        </>
      )}
    </section>
  );
}

const TIER_OPTIONS: FieldTier[] = ["TIER_1", "TIER_2", "TIER_3"];
const DATA_TYPE_OPTIONS: FieldDataType[] = ["NUMBER", "STRING", "BOOLEAN", "DATE"];

function AddFieldForm({ onCreated }: Readonly<{ onCreated: () => void }>) {
  const [fieldKey, setFieldKey] = useState("");
  const [displayLabel, setDisplayLabel] = useState("");
  const [tier, setTier] = useState<FieldTier>("TIER_1");
  const [dataType, setDataType] = useState<FieldDataType>("NUMBER");
  const [groupName, setGroupName] = useState("");
  const [allowedValues, setAllowedValues] = useState("");
  const [fuzzyMatchable, setFuzzyMatchable] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function onSubmit() {
    if (!fieldKey.trim() || !displayLabel.trim()) return;
    setSaving(true);
    setError(null);
    try {
      await createField({
        fieldKey: fieldKey.trim(),
        displayLabel: displayLabel.trim(),
        tier,
        dataType,
        groupName: groupName.trim(),
        allowedValues: allowedValues
          .split(",")
          .map((v) => v.trim())
          .filter(Boolean),
        fuzzyMatchable,
      });
      setFieldKey("");
      setDisplayLabel("");
      setGroupName("");
      setAllowedValues("");
      setFuzzyMatchable(false);
      onCreated();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap", alignItems: "center" }}>
      <input
        placeholder="field_key"
        value={fieldKey}
        onChange={(e) => setFieldKey(e.target.value)}
        className="srse-input"
        style={{ width: 160 }}
      />
      <input
        placeholder="Display label"
        value={displayLabel}
        onChange={(e) => setDisplayLabel(e.target.value)}
        className="srse-input"
        style={{ width: 180 }}
      />
      <select value={tier} onChange={(e) => setTier(e.target.value as FieldTier)} className="srse-select">
        {TIER_OPTIONS.map((t) => (
          <option key={t} value={t}>
            {t}
          </option>
        ))}
      </select>
      <select
        value={dataType}
        onChange={(e) => setDataType(e.target.value as FieldDataType)}
        className="srse-select"
      >
        {DATA_TYPE_OPTIONS.map((t) => (
          <option key={t} value={t}>
            {t}
          </option>
        ))}
      </select>
      <input
        placeholder="Group (e.g. Assets)"
        value={groupName}
        onChange={(e) => setGroupName(e.target.value)}
        className="srse-input"
        style={{ width: 160 }}
      />
      <input
        placeholder="Allowed values (comma-separated, STRING only)"
        value={allowedValues}
        onChange={(e) => setAllowedValues(e.target.value)}
        className="srse-input"
        style={{ width: 260 }}
      />
        <label className="srse-checkbox-label" htmlFor="add-field-fuzzy">
        <input id="add-field-fuzzy" type="checkbox" checked={fuzzyMatchable} onChange={(e) => setFuzzyMatchable(e.target.checked)} />
        {" "}
        Fuzzy matchable
      </label>
      <button type="button" className="srse-btn srse-btn-primary" disabled={saving} onClick={onSubmit}>
        {saving ? "Adding…" : "+ Add field"}
      </button>
      {error && (
        <p className="srse-text-danger" style={{ width: "100%", margin: 0 }}>
          {error}
        </p>
      )}
    </div>
  );
}

// Presto date_diff('year', dob, current_date) — the exact Tier-2 pattern
// CLAUDE.md's own worked example uses for age. Built here so an admin only
// ever has to know the DOB column name for THEIR environment, never Presto
// syntax — the DOB column genuinely differs per environment (that's the
// whole reason this exists instead of just typing the expression by hand).
function buildDobAgeExpression(dobColumn: string): string {
  return `date_diff('year', ${dobColumn}, current_date)`;
}

const DOB_AGE_PREFIX = "date_diff('year', ";
const DOB_AGE_SUFFIX = ", current_date)";

function parseDobAgeExpression(expression: string): string | null {
  const trimmed = expression.trim();
  if (!trimmed.startsWith(DOB_AGE_PREFIX) || !trimmed.endsWith(DOB_AGE_SUFFIX)) {
    return null;
  }
  return trimmed.slice(DOB_AGE_PREFIX.length, trimmed.length - DOB_AGE_SUFFIX.length).trim();
}

/**
 * Builds a fully-qualified physical expression from the cascade, so an admin
 * never has to type `catalog.schema.table.column` by hand and get a segment
 * wrong. The free-text field stays the source of truth — Tier-2 expressions
 * (see buildDobAgeExpression) are not a single column reference and can only
 * be written out.
 */
function ColumnPickerForMapping({
  idPrefix,
  onPick,
  onError,
}: Readonly<{
  idPrefix: string;
  onPick: (qualifiedColumn: string) => void;
  onError: (message: string) => void;
}>) {
  const [cascade, setCascade] = useState<CascadeValue>(EMPTY_CASCADE);
  const [columns, setColumns] = useState<LakehouseColumnInfo[]>([]);

  useEffect(() => {
    if (!isCascadeComplete(cascade)) {
      setColumns([]);
      return;
    }
    browseColumns(cascade.catalog, cascade.schema, cascade.table)
      .then(setColumns)
      .catch((err: unknown) => onError(errorMessage(err)));
  }, [cascade, onError]);

  return (
    <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap", alignItems: "flex-end", marginTop: "0.4rem" }}>
      <LakehouseCascade
        value={cascade}
        onChange={setCascade}
        fetchers={BROWSE_FETCHERS}
        idPrefix={idPrefix}
        compact
        onError={onError}
      />
      <select
        className="srse-select"
        value=""
        disabled={!isCascadeComplete(cascade)}
        onChange={(e) => {
          if (e.target.value) {
            onPick(qualified(cascade, e.target.value));
          }
        }}
      >
        <option value="">— pick column —</option>
        {columns.map((c) => (
          <option key={c.name} value={c.name}>
            {c.name} ({c.dataType})
          </option>
        ))}
      </select>
    </div>
  );
}

function MappingRowEditor({
  row,
  dataMode,
  onSaved,
}: Readonly<{
  row: MappingRow;
  dataMode: DataMode;
  onSaved: (physicalExpression: string) => void;
}>) {
  const isAgeField = row.fieldKey === "age_years";
  const existingDobColumn = isAgeField ? parseDobAgeExpression(row.physicalExpression ?? "") : null;

  const [value, setValue] = useState(row.physicalExpression ?? "");
  const [dobMode, setDobMode] = useState(existingDobColumn !== null);
  const [dobColumn, setDobColumn] = useState(existingDobColumn ?? "");
  const [picking, setPicking] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const effectiveValue = dobMode ? buildDobAgeExpression(dobColumn) : value;
  const dirty = effectiveValue !== (row.physicalExpression ?? "");

  const onPickError = useCallback((message: string) => setError(message), []);

  // The picked column feeds whichever field is active: the DOB column when
  // computing age from DOB, the expression itself otherwise.
  function applyPick(qualifiedColumn: string) {
    if (dobMode) {
      setDobColumn(qualifiedColumn);
    } else {
      setValue(qualifiedColumn);
    }
    setPicking(false);
  }

  async function onSave() {
    setSaving(true);
    setError(null);
    try {
      await upsertMapping(row.fieldKey, dataMode, effectiveValue);
      onSaved(effectiveValue);
    } catch (err: unknown) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <tr>
      <td>{row.displayLabel}</td>
      <td className="srse-text-muted" style={{ fontSize: "0.8rem" }}>
        {row.fieldKey}
      </td>
      <td>
        {isAgeField && (
          <label
            className="srse-checkbox-label"
            htmlFor={`dob-mode-${row.fieldKey}`}
            style={{ display: "flex", marginBottom: "0.4rem", fontSize: "0.78rem" }}
            title="Computes age on the fly as of today, instead of storing a fixed number — no need to know Presto syntax, just the DOB column for this environment"
          >
            <input
              id={`dob-mode-${row.fieldKey}`}
              type="checkbox"
              checked={dobMode}
              onChange={(e) => setDobMode(e.target.checked)}
            />
            {" "}
            Compute from Date of Birth
          </label>
        )}
        {isAgeField && dobMode ? (
          <input
            value={dobColumn}
            placeholder="e.g. iceberg_gold.golden_layer.tbl_beneficiary.date_of_birth"
            onChange={(e) => setDobColumn(e.target.value)}
            className="srse-input"
            style={{ width: 340, fontFamily: "monospace" }}
          />
        ) : (
          <input
            value={value}
            placeholder="e.g. iceberg_gold.golden_layer.tbl_beneficiary.age_years"
            onChange={(e) => setValue(e.target.value)}
            className="srse-input"
            style={{ width: 340, fontFamily: "monospace" }}
          />
        )}

        <button
          type="button"
          className="srse-btn srse-btn-ghost srse-btn-sm"
          style={{ marginTop: "0.35rem" }}
          onClick={() => setPicking((v) => !v)}
        >
          {picking ? "Close picker" : "Pick from lakehouse…"}
        </button>
        {picking && (
          <ColumnPickerForMapping
            idPrefix={`map-pick-${row.fieldKey}`}
            onPick={applyPick}
            onError={onPickError}
          />
        )}

        {isAgeField && dobMode && (
          <>
            <div className="srse-text-muted" style={{ fontSize: "0.75rem", marginTop: "0.3rem", fontFamily: "monospace" }}>
              → {buildDobAgeExpression(dobColumn || "…")}
            </div>
            <div className="srse-text-muted" style={{ fontSize: "0.72rem", marginTop: "0.2rem" }}>
              Column must be DATE/TIMESTAMP-typed in the lakehouse.
            </div>
          </>
        )}
      </td>
      <td>
        <div style={{ display: "flex", alignItems: "center", gap: "0.6rem" }}>
          <button
            type="button"
            className="srse-btn srse-btn-sm"
            disabled={!dirty || saving || (dobMode && !dobColumn.trim())}
            onClick={onSave}
          >
            {saving ? "Saving…" : "Save"}
          </button>
          {error && <span className="srse-text-danger">{error}</span>}
        </div>
      </td>
    </tr>
  );
}

function MappingsPanel({
  registrations,
}: Readonly<{ registrations: TableRegistration[] }>) {
  const [dataMode, setDataMode] = useState<DataMode>("SYNTHETIC");
  const [rows, setRows] = useState<MappingRow[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);

  useEffect(() => {
    listMappings(dataMode)
      .then(setRows)
      .catch((err: unknown) => setError(errorMessage(err)));
  }, [dataMode, refreshKey]);

  const refresh = () => setRefreshKey((k) => k + 1);

  return (
    <section className="srse-card">
      <h2 className="srse-card-title">Field → catalog/schema/table/column mappings</h2>
      <div style={{ display: "flex", gap: "1.25rem", alignItems: "center", marginBottom: "0.85rem" }}>
        <label className="srse-checkbox-label" htmlFor="mapping-data-mode-synthetic">
          <input id="mapping-data-mode-synthetic" type="radio" checked={dataMode === "SYNTHETIC"} onChange={() => setDataMode("SYNTHETIC")} />
          {" "}
          Synthetic
        </label>
        <label className="srse-checkbox-label" htmlFor="mapping-data-mode-live">
          <input id="mapping-data-mode-live" type="radio" checked={dataMode === "LIVE"} onChange={() => setDataMode("LIVE")} />
          {" "}
          Live
        </label>
      </div>

      {dataMode === "SYNTHETIC" ? (
        <p className="srse-text-muted" style={{ marginTop: 0 }}>
          Synthetic-mode mappings are informational only — local dev resolution uses the built-in
          bindings in <code>StubFieldResolver</code>, not this table.
        </p>
      ) : (
        <p className="srse-text-success" style={{ marginTop: 0 }}>
          Live-mode edits take effect immediately (cache is evicted on save) — no restart needed.
        </p>
      )}

      <p className="srse-text-muted" style={{ marginTop: 0, lineHeight: 1.5 }}>
        Live expressions must be <strong>fully qualified</strong> —
        <code>catalog.schema.table.column</code>. The connection no longer pins a single catalog and
        schema, so a bare <code>table.column</code> only resolves if the JDBC URL still carries a
        default. Use <em>Pick from lakehouse…</em> to build one from the live schema rather than typing it.
        {registrations.length > 0 && (
          <>
            {" "}Registered tables: {registrations.map((r) => r.qualifiedName).join(", ")}.
          </>
        )}
      </p>

      {error && <p className="srse-text-danger">{error}</p>}

      <div style={{ overflowX: "auto", marginBottom: "1.25rem" }}>
        <table className="srse-table">
          <thead>
            <tr>
              <th>Field</th>
              <th>Key</th>
              <th>Physical expression</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <MappingRowEditor key={row.fieldKey} row={row} dataMode={dataMode} onSaved={refresh} />
            ))}
          </tbody>
        </table>
      </div>

      <h3 className="srse-subheading" style={{ fontSize: "0.95rem" }}>
        Register a new field
      </h3>
      <AddFieldForm onCreated={refresh} />
    </section>
  );
}

function ColumnMetadataRowEditor({
  row,
  onSaved,
}: Readonly<{
  row: ColumnMetadata;
  onSaved: (updated: ColumnMetadata) => void;
}>) {
  const [businessName, setBusinessName] = useState(row.businessName ?? "");
  const [fuzzyMatchable, setFuzzyMatchable] = useState(row.fuzzyMatchable);
  const [visible, setVisible] = useState(row.visible);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const dirty =
    businessName !== (row.businessName ?? "") ||
    fuzzyMatchable !== row.fuzzyMatchable ||
    visible !== row.visible;

  // Unique per fully-qualified column — the same table.column can exist in
  // both the Silver and Gold catalog, so the bare pair is not a unique DOM id.
  const rowId = `${row.catalog}-${row.schema}-${row.table}-${row.column}`;

  async function onSave() {
    setSaving(true);
    setError(null);
    try {
      const updated = await upsertColumnMetadata(
        { catalog: row.catalog, schema: row.schema, table: row.table },
        row.column,
        businessName.trim() || null,
        fuzzyMatchable,
        visible,
      );
      onSaved(updated);
    } catch (err: unknown) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <tr>
      <td className="srse-text-muted" style={{ fontSize: "0.78rem", fontFamily: "monospace" }}>
        {row.catalog} › {row.schema} › {row.table}
      </td>
      <td className="srse-text-muted" style={{ fontSize: "0.8rem", fontFamily: "monospace" }}>
        {row.column}
      </td>
      <td>
        <input
          value={businessName}
          placeholder="e.g. Account Number"
          onChange={(e) => setBusinessName(e.target.value)}
          className="srse-input"
          style={{ width: 220 }}
        />
      </td>
      <td>
        <label className="srse-checkbox-label" htmlFor={`col-meta-fuzzy-${rowId}`} title="Offer approximate (Levenshtein) matching for this column in the Analysis tab">
          <input
            id={`col-meta-fuzzy-${rowId}`}
            type="checkbox"
            checked={fuzzyMatchable}
            onChange={(e) => setFuzzyMatchable(e.target.checked)}
          />
          {" "}
          Fuzzy
        </label>
      </td>
      <td>
        <label className="srse-checkbox-label" htmlFor={`col-meta-visible-${rowId}`} title="Uncheck to hide this column from officers in the Analysis tab">
          <input
            id={`col-meta-visible-${rowId}`}
            type="checkbox"
            checked={visible}
            onChange={(e) => setVisible(e.target.checked)}
          />
          {" "}
          Visible
        </label>
      </td>
      <td>
        <div style={{ display: "flex", alignItems: "center", gap: "0.6rem" }}>
          <button type="button" className="srse-btn srse-btn-sm" disabled={!dirty || saving} onClick={onSave}>
            {saving ? "Saving…" : "Save"}
          </button>
          {error && <span className="srse-text-danger">{error}</span>}
        </div>
      </td>
    </tr>
  );
}

function RegisterColumnMetadataForm({
  registrations,
  onCreated,
}: Readonly<{ registrations: TableRegistration[]; onCreated: () => void }>) {
  // Scoped to REGISTERED tables, not the whole lakehouse: curating metadata
  // for an unregistered table would be invisible to officers, and the backend
  // rejects it anyway (registration is the first of its two gates).
  const [registrationId, setRegistrationId] = useState("");
  const [columns, setColumns] = useState<LakehouseColumnInfo[]>([]);
  const [column, setColumn] = useState("");
  const [businessName, setBusinessName] = useState("");
  const [fuzzyMatchable, setFuzzyMatchable] = useState(false);
  const [visible, setVisible] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const selected = registrations.find((r) => String(r.id) === registrationId) ?? null;

  useEffect(() => {
    setColumn("");
    if (!selected) {
      setColumns([]);
      return;
    }
    browseColumns(selected.catalog, selected.schema, selected.table)
      .then(setColumns)
      .catch((err: unknown) => setError(errorMessage(err)));
    // Re-fetch keyed on the qualified name, not the object identity, so a
    // list refresh that returns an equal-but-new object doesn't re-query.
  }, [selected?.qualifiedName]); // eslint-disable-line react-hooks/exhaustive-deps

  async function onSubmit() {
    if (!selected || !column) return;
    setSaving(true);
    setError(null);
    try {
      await upsertColumnMetadata(
        { catalog: selected.catalog, schema: selected.schema, table: selected.table },
        column,
        businessName.trim() || null,
        fuzzyMatchable,
        visible,
      );
      setBusinessName("");
      setFuzzyMatchable(false);
      setVisible(true);
      onCreated();
    } catch (err: unknown) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  if (registrations.length === 0) {
    return (
      <p className="srse-text-muted" style={{ margin: 0 }}>
        Register a table above first — column metadata can only be attached to a registered table.
      </p>
    );
  }

  return (
    <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap", alignItems: "center" }}>
      <select
        value={registrationId}
        onChange={(e) => setRegistrationId(e.target.value)}
        className="srse-select"
        style={{ minWidth: 320 }}
      >
        <option value="">— select registered table —</option>
        {registrations.map((r) => (
          <option key={r.id} value={String(r.id)}>
            {r.catalog} › {r.schema} › {r.table}
            {r.layer ? ` (${r.layer})` : ""}
          </option>
        ))}
      </select>
      <select
        value={column}
        onChange={(e) => setColumn(e.target.value)}
        className="srse-select"
        disabled={!selected}
      >
        <option value="">— select column —</option>
        {columns.map((c) => (
          <option key={c.name} value={c.name}>
            {c.name}
          </option>
        ))}
      </select>
      <input
        placeholder="Business name (e.g. Account Number)"
        value={businessName}
        onChange={(e) => setBusinessName(e.target.value)}
        className="srse-input"
        style={{ width: 220 }}
      />
      <label className="srse-checkbox-label" htmlFor="register-col-fuzzy" title="Offer approximate (Levenshtein) matching for this column in the Analysis tab">
        <input id="register-col-fuzzy" type="checkbox" checked={fuzzyMatchable} onChange={(e) => setFuzzyMatchable(e.target.checked)} />
        {" "}
        Fuzzy matchable
      </label>
      <label className="srse-checkbox-label" htmlFor="register-col-visible" title="Uncheck to hide this column from officers — registering a table exposes all of its columns by default">
        <input id="register-col-visible" type="checkbox" checked={visible} onChange={(e) => setVisible(e.target.checked)} />
        {" "}
        Visible to officers
      </label>
      <button type="button" className="srse-btn srse-btn-primary" disabled={saving || !selected || !column} onClick={onSubmit}>
        {saving ? "Adding…" : "+ Add"}
      </button>
      {error && (
        <p className="srse-text-danger" style={{ width: "100%", margin: 0 }}>
          {error}
        </p>
      )}
    </div>
  );
}

function ColumnMetadataPanel({
  registrations,
}: Readonly<{ registrations: TableRegistration[] }>) {
  const [rows, setRows] = useState<ColumnMetadata[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);

  useEffect(() => {
    listColumnMetadata()
      .then(setRows)
      .catch((err: unknown) => setError(errorMessage(err)));
  }, [refreshKey]);

  const refresh = () => setRefreshKey((k) => k + 1);

  return (
    <section className="srse-card">
      <h2 className="srse-card-title">Analysis tab: column business names, fuzzy matching &amp; visibility</h2>
      <p className="srse-page-description" style={{ maxWidth: "none", marginTop: 0 }}>
        Registering a table above exposes <strong>all</strong> of its columns to officers. Use this table
        to give individual columns a business name, mark them fuzzy-matchable, or hide them. Columns with
        no entry here stay visible and fall back to an auto-derived label and a name-substring guess for
        fuzzy matching.
      </p>

      {error && <p className="srse-text-danger">{error}</p>}

      {rows.length > 0 && (
        <div style={{ overflowX: "auto", marginBottom: "1.25rem" }}>
          <table className="srse-table">
            <thead>
              <tr>
                <th>Catalog › Schema › Table</th>
                <th>Column</th>
                <th>Business name</th>
                <th>Fuzzy matchable</th>
                <th>Visible</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <ColumnMetadataRowEditor
                  key={`${row.catalog}.${row.schema}.${row.table}.${row.column}`}
                  row={row}
                  onSaved={refresh}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}

      <h3 className="srse-subheading" style={{ fontSize: "0.95rem" }}>
        Register column metadata
      </h3>
      <RegisterColumnMetadataForm registrations={registrations} onCreated={refresh} />
    </section>
  );
}

/**
 * Catalog → Schema → Table registration. This is the seam between everything
 * the Presto connection can physically reach and what officers are actually
 * offered: the cascade browses the live lakehouse, and registering pins the
 * chosen table into DB2.
 *
 * Columns are deliberately NOT part of a registration — they are re-read live
 * on every use, so a column added upstream appears without re-registration
 * and a dropped one disappears instead of lingering as a broken reference.
 */
function LakehouseRegistryPanel({
  registrations,
  loading,
  error,
  onChanged,
}: Readonly<{
  registrations: TableRegistration[];
  loading: boolean;
  error: string | null;
  onChanged: () => void;
}>) {
  const [cascade, setCascade] = useState<CascadeValue>(EMPTY_CASCADE);
  const [layer, setLayer] = useState("");
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const onCascadeError = useCallback((message: string) => setFormError(message), []);

  const alreadyRegistered = useMemo(
    () =>
      isCascadeComplete(cascade) &&
      registrations.some((r) => r.qualifiedName === qualified(cascade)),
    [cascade, registrations],
  );

  async function onRegister() {
    if (!isCascadeComplete(cascade)) return;
    setSaving(true);
    setFormError(null);
    try {
      await registerTable({ ...cascade, layer: layer.trim() || null });
      setCascade(EMPTY_CASCADE);
      setLayer("");
      onChanged();
    } catch (err: unknown) {
      setFormError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  async function onUnregister(id: number) {
    setFormError(null);
    try {
      await unregisterTable(id);
      onChanged();
    } catch (err: unknown) {
      setFormError(errorMessage(err));
    }
  }

  return (
    <section className="srse-card">
      <h2 className="srse-card-title">Lakehouse registry — Catalog › Schema › Table</h2>
      <p className="srse-page-description" style={{ maxWidth: "none", marginTop: 0 }}>
        Browse the live lakehouse and register the tables SRSE may use. Officers only ever see registered
        tables. Registering exposes all of the table&apos;s columns — hide individual ones below. Tag each
        table with its layer (e.g. <code>SILVER</code> / <code>GOLD</code>) so the same table name in two
        layers stays distinguishable.
      </p>

      {error && <p className="srse-text-danger">{error}</p>}
      {formError && <p className="srse-text-danger">{formError}</p>}

      <div style={{ display: "flex", gap: "0.75rem", flexWrap: "wrap", alignItems: "flex-end", marginBottom: "1.1rem" }}>
        <LakehouseCascade
          value={cascade}
          onChange={setCascade}
          fetchers={BROWSE_FETCHERS}
          idPrefix="register-table"
          onError={onCascadeError}
        />
        <div>
          <label htmlFor="register-layer" className="srse-text-muted" style={{ fontSize: "0.72rem", display: "block" }}>
            Layer (optional)
          </label>
          <input
            id="register-layer"
            list="srse-layer-suggestions"
            placeholder="SILVER / GOLD"
            value={layer}
            onChange={(e) => setLayer(e.target.value)}
            className="srse-input"
            style={{ width: 140 }}
          />
          <datalist id="srse-layer-suggestions">
            <option value="SILVER" />
            <option value="GOLD" />
          </datalist>
        </div>
        <button
          type="button"
          className="srse-btn srse-btn-primary"
          disabled={saving || !isCascadeComplete(cascade)}
          onClick={onRegister}
        >
          {saving ? "Registering…" : alreadyRegistered ? "Update layer" : "+ Register table"}
        </button>
      </div>

      {isCascadeComplete(cascade) && (
        <p className="srse-text-muted" style={{ fontFamily: "monospace", fontSize: "0.8rem", marginTop: 0 }}>
          → {qualified(cascade)}
          {alreadyRegistered ? " (already registered — this will re-tag its layer)" : ""}
        </p>
      )}

      {loading && <p className="srse-text-muted">Loading registrations…</p>}

      {!loading && registrations.length === 0 && (
        <p className="srse-text-muted">
          Nothing registered yet — officers will see no tables in the Analysis tab until you register one.
        </p>
      )}

      {registrations.length > 0 && (
        <div style={{ overflowX: "auto" }}>
          <table className="srse-table">
            <thead>
              <tr>
                <th>Catalog</th>
                <th>Schema</th>
                <th>Table</th>
                <th>Layer</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {registrations.map((r) => (
                <tr key={r.id}>
                  <td style={{ fontFamily: "monospace", fontSize: "0.82rem" }}>{r.catalog}</td>
                  <td style={{ fontFamily: "monospace", fontSize: "0.82rem" }}>{r.schema}</td>
                  <td style={{ fontFamily: "monospace", fontSize: "0.82rem" }}>{r.table}</td>
                  <td>
                    {r.layer ? <span className="srse-badge">{r.layer}</span> : <span className="srse-text-muted">—</span>}
                  </td>
                  <td>
                    <button
                      type="button"
                      className="srse-btn srse-btn-ghost srse-btn-sm"
                      onClick={() => onUnregister(r.id)}
                      title="Officers stop seeing this table. Nothing in the lakehouse is touched."
                    >
                      Unregister
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

export default function AdminPage() {
  // Registrations are loaded once here and passed down, because three panels
  // need the same list and it must refresh together when one of them changes it.
  const [registrations, setRegistrations] = useState<TableRegistration[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);

  useEffect(() => {
    setLoading(true);
    listRegistrations()
      .then((r) => {
        setRegistrations(r);
        setError(null);
      })
      .catch((err: unknown) => setError(errorMessage(err)))
      .finally(() => setLoading(false));
  }, [refreshKey]);

  const refresh = useCallback(() => setRefreshKey((k) => k + 1), []);

  return (
    <main className="srse-page">
      <h1 className="srse-page-title">Admin — Lakehouse Connections</h1>
      <p className="srse-page-description" style={{ maxWidth: "none" }}>
        Configure the Presto/DB2 connections, register the lakehouse tables SRSE may use, and manage which
        physical column each abstract field resolves to, per environment.
      </p>

      <ConnectionsPanel />
      <LakehouseRegistryPanel
        registrations={registrations}
        loading={loading}
        error={error}
        onChanged={refresh}
      />
      <MappingsPanel registrations={registrations} />
      <ColumnMetadataPanel registrations={registrations} />
    </main>
  );
}
