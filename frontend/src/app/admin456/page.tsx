"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  browseCatalogs,
  browseColumns,
  browseSchemas,
  browseTables,
  createField,
  deleteField,
  getConnections,
  listFields,
  listMappings,
  listRegistrations,
  registerTable,
  unregisterTable,
  updateAnalyticalConnection,
  updateField,
  updateOperationalConnection,
  updateTableRegistration,
  upsertMapping,
  type ConnectionPlaneInfo,
  type ConnectionsInfo,
  type DataMode,
  type FieldCatalogEntry,
  type FieldDataType,
  type FieldTier,
  type LakehouseColumnInfo,
  type MappingRow,
  type TableRegistration,
} from "@/lib/decisionApi";
import {
  deleteColumnMetadata,
  listColumnMetadata,
  upsertColumnMetadata,
  type ColumnMetadata,
  type CompareAs,
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

/**
 * True while a mapping is still the shipped CHANGE_ME placeholder, i.e. nobody
 * has bound this field to a real column for this environment.
 *
 * Matched as a whole identifier, case-insensitively, mirroring
 * FieldColumnMapping.isPlaceholder on the backend — a real column such as
 * `change_me_flag` must not be flagged. Keep the two in step: the backend now
 * REFUSES to resolve a placeholder, so anything marked here is a field that
 * will fail a simulation until it is set.
 */
function isPlaceholderMapping(expression: string | null | undefined): boolean {
  return !!expression && /\bCHANGE_ME\b/i.test(expression);
}

/** Registering an already-registered table re-tags its layer rather than duplicating it. */
function registerButtonLabel(saving: boolean, alreadyRegistered: boolean): string {
  if (saving) return "Registering…";
  return alreadyRegistered ? "Update layer" : "+ Register table";
}

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

/**
 * Two-click delete: the first click arms it, the second commits.
 *
 * Deliberately not `window.confirm` — a native modal blocks the whole page
 * (and any automated smoke-test driving this screen), and arming inline keeps
 * the row the admin is about to remove visible while they decide.
 */
function ConfirmDeleteButton({
  idleLabel,
  confirmLabel,
  title,
  onConfirm,
  onError,
}: Readonly<{
  idleLabel: string;
  confirmLabel: string;
  title?: string;
  onConfirm: () => Promise<void>;
  onError: (message: string) => void;
}>) {
  const [armed, setArmed] = useState(false);
  const [busy, setBusy] = useState(false);

  async function run() {
    setBusy(true);
    try {
      await onConfirm();
      setArmed(false);
    } catch (err: unknown) {
      onError(errorMessage(err));
      setArmed(false);
    } finally {
      setBusy(false);
    }
  }

  if (!armed) {
    return (
      <button
        type="button"
        className="srse-btn srse-btn-ghost srse-btn-sm"
        title={title}
        onClick={() => setArmed(true)}
      >
        {idleLabel}
      </button>
    );
  }

  return (
    <span style={{ display: "inline-flex", gap: "0.35rem", alignItems: "center" }}>
      <button type="button" className="srse-btn srse-btn-danger srse-btn-sm" disabled={busy} onClick={run}>
        {busy ? "Working…" : confirmLabel}
      </button>
      <button
        type="button"
        className="srse-btn srse-btn-ghost srse-btn-sm"
        disabled={busy}
        onClick={() => setArmed(false)}
      >
        Cancel
      </button>
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
            The Presto URL is now <strong style={{ color: "var(--srse-text)" }}>catalog-agnostic</strong>:{" "}
            <code>jdbc:presto://host:8080</code> is enough. Any trailing <code>/catalog/schema</code> is
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

/**
 * One catalogue field, editable in place.
 *
 * The field KEY is fixed once created: mappings, saved rulesets and the
 * officer-facing palette all address a field by that key, so renaming it here
 * would strand them. Everything an officer actually sees — label, tier, type,
 * group, allowed values, fuzzy eligibility — is editable.
 */
function FieldCatalogRowEditor({
  field,
  onChanged,
  onError,
}: Readonly<{
  field: FieldCatalogEntry;
  onChanged: () => void;
  onError: (message: string) => void;
}>) {
  const [editing, setEditing] = useState(false);
  const [displayLabel, setDisplayLabel] = useState(field.displayLabel);
  const [tier, setTier] = useState<FieldTier>(field.tier);
  const [dataType, setDataType] = useState<FieldDataType>(field.dataType);
  const [groupName, setGroupName] = useState(field.groupName ?? "");
  const [allowedValues, setAllowedValues] = useState((field.allowedValues ?? []).join(", "));
  const [fuzzyMatchable, setFuzzyMatchable] = useState(field.fuzzyMatchable);
  const [saving, setSaving] = useState(false);

  // Re-seed from the row rather than keeping half-typed edits around, so
  // Cancel really is a cancel.
  function startEditing() {
    setDisplayLabel(field.displayLabel);
    setTier(field.tier);
    setDataType(field.dataType);
    setGroupName(field.groupName ?? "");
    setAllowedValues((field.allowedValues ?? []).join(", "));
    setFuzzyMatchable(field.fuzzyMatchable);
    setEditing(true);
  }

  async function onSave() {
    setSaving(true);
    try {
      await updateField(field.fieldKey, {
        fieldKey: field.fieldKey,
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
      setEditing(false);
      onChanged();
    } catch (err: unknown) {
      onError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  if (!editing) {
    return (
      <tr>
        <td className="srse-text-muted" style={{ fontSize: "0.8rem", fontFamily: "monospace" }}>
          {field.fieldKey}
        </td>
        <td>{field.displayLabel}</td>
        <td className="srse-text-muted" style={{ fontSize: "0.78rem" }}>
          {field.tier} · {field.dataType}
        </td>
        <td className="srse-text-muted" style={{ fontSize: "0.78rem" }}>
          {field.groupName || "—"}
        </td>
        <td className="srse-text-muted" style={{ fontSize: "0.78rem" }}>
          {field.allowedValues.length > 0 ? field.allowedValues.join(", ") : "—"}
        </td>
        <td className="srse-text-muted" style={{ fontSize: "0.78rem" }}>
          {field.fuzzyMatchable ? "Yes" : "—"}
        </td>
        <td>
          <div style={{ display: "flex", gap: "0.4rem", alignItems: "center", flexWrap: "wrap" }}>
            <button type="button" className="srse-btn srse-btn-ghost srse-btn-sm" onClick={startEditing}>
              Edit
            </button>
            <ConfirmDeleteButton
              idleLabel="Delete"
              confirmLabel="Yes, delete"
              title="Stops offering this field in the rule builder and drops it from the mapping table below. Saved scenarios that already use it still resolve, and re-adding the same key brings it back."
              onConfirm={() => deleteField(field.fieldKey).then(onChanged)}
              onError={onError}
            />
          </div>
        </td>
      </tr>
    );
  }

  return (
    <tr>
      <td className="srse-text-muted" style={{ fontSize: "0.8rem", fontFamily: "monospace" }}>
        {field.fieldKey}
        <div className="srse-text-muted" style={{ fontSize: "0.7rem" }}>
          key is fixed
        </div>
      </td>
      <td>
        <input
          aria-label={`Display label for ${field.fieldKey}`}
          value={displayLabel}
          onChange={(e) => setDisplayLabel(e.target.value)}
          className="srse-input"
          style={{ width: 170 }}
        />
      </td>
      <td>
        <div style={{ display: "flex", gap: "0.35rem", flexWrap: "wrap" }}>
          <select
            aria-label={`Tier for ${field.fieldKey}`}
            value={tier}
            onChange={(e) => setTier(e.target.value as FieldTier)}
            className="srse-select"
          >
            {TIER_OPTIONS.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
          <select
            aria-label={`Data type for ${field.fieldKey}`}
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
        </div>
      </td>
      <td>
        <input
          aria-label={`Group for ${field.fieldKey}`}
          value={groupName}
          onChange={(e) => setGroupName(e.target.value)}
          className="srse-input"
          style={{ width: 130 }}
        />
      </td>
      <td>
        <input
          aria-label={`Allowed values for ${field.fieldKey}`}
          value={allowedValues}
          placeholder="comma-separated"
          onChange={(e) => setAllowedValues(e.target.value)}
          className="srse-input"
          style={{ width: 200 }}
        />
      </td>
      <td>
        <label className="srse-checkbox-label" htmlFor={`field-fuzzy-${field.fieldKey}`}>
          <input
            id={`field-fuzzy-${field.fieldKey}`}
            type="checkbox"
            checked={fuzzyMatchable}
            onChange={(e) => setFuzzyMatchable(e.target.checked)}
          />
          {" "}
          Fuzzy
        </label>
      </td>
      <td>
        <div style={{ display: "flex", gap: "0.4rem", alignItems: "center" }}>
          <button
            type="button"
            className="srse-btn srse-btn-sm"
            disabled={saving || !displayLabel.trim()}
            onClick={onSave}
          >
            {saving ? "Saving…" : "Save"}
          </button>
          <button
            type="button"
            className="srse-btn srse-btn-ghost srse-btn-sm"
            disabled={saving}
            onClick={() => setEditing(false)}
          >
            Cancel
          </button>
        </div>
      </td>
    </tr>
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
  const unconfigured = isPlaceholderMapping(row.physicalExpression);

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
        {unconfigured && (
          <div
            className="srse-text-danger"
            style={{ fontSize: "0.7rem", marginTop: "0.2rem", fontWeight: 600 }}
            title="Still the shipped CHANGE_ME placeholder — simulations using this field will fail until it is bound to a real column."
          >
            ⚠ not configured
          </div>
        )}
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
  const [fields, setFields] = useState<FieldCatalogEntry[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);

  useEffect(() => {
    // Clear before fetching. Without this the mode switch re-renders with the
    // NEW dataMode but the OLD rows still in state; those rows mount under the
    // new key and seed MappingRowEditor's mount-only useState from the previous
    // mode's expressions. The correct rows then arrive under the SAME key, so
    // React reuses the components and the inputs never re-seed — leaving Live
    // showing synthetic values. Keying by mode alone does not fix that; the
    // stale intermediate render has to not happen at all.
    setRows([]);
    listMappings(dataMode)
      .then(setRows)
      .catch((err: unknown) => setError(errorMessage(err)));
  }, [dataMode, refreshKey]);

  // The catalogue is mode-independent, but it shares refreshKey: deleting a
  // field has to drop it from the mapping table above in the same beat, since
  // the backend stops listing mappings for a deactivated field.
  useEffect(() => {
    listFields()
      .then(setFields)
      .catch((err: unknown) => setError(errorMessage(err)));
  }, [refreshKey]);

  const refresh = () => setRefreshKey((k) => k + 1);
  const unconfiguredCount = rows.filter((r) => isPlaceholderMapping(r.physicalExpression)).length;

  // Every field of one environment must resolve against the SAME flat table —
  // the flat-catalogue contract — because the query's FROM clause is derived
  // from a single binding. When they disagree, the WHERE names columns of a
  // table the FROM never mentioned and Presto answers "User defined type is not
  // supported", an error that mentions neither the table nor the mapping. Catch
  // it here, where the fix is one row away, instead of at simulation time.
  const mappedTables = Array.from(
    new Set(
      rows
        .filter((r) => !isPlaceholderMapping(r.physicalExpression))
        .map((r) => r.tableName)
        .filter((t): t is string => !!t),
    ),
  );

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

      <p className="srse-text-success" style={{ marginTop: 0 }}>
        Edits take effect immediately (cache is evicted on save) — no restart needed.{" "}
        {dataMode === "SYNTHETIC"
          ? "You are editing the bindings the local synthetic stack resolves against."
          : "You are editing the bindings the deployed Golden Layer resolves against."}
      </p>

      {/*
        A deployment that never replaced the shipped CHANGE_ME placeholders used
        to fail at query time with "table change_me does not exist", naming an
        object that appears nowhere on this page. Surface the real state here so
        the work left to do is visible before anyone runs a simulation.
      */}
      {dataMode === "LIVE" && unconfiguredCount > 0 && (
        <p className="srse-text-danger" style={{ marginTop: 0, fontWeight: 600 }}>
          ⚠ {unconfiguredCount} of {rows.length} live fields are still unconfigured (
          <code>CHANGE_ME</code> placeholders). Simulations using them will fail with a
          &quot;not configured&quot; error until each is bound to a real column below.
        </p>
      )}

      {mappedTables.length > 1 && (
        <p className="srse-text-danger" style={{ marginTop: 0, fontWeight: 600 }}>
          ⚠ These fields do not all point at the same table:{" "}
          <code>{mappedTables.join("</code>, <code>")}</code>. Every field of one environment must
          resolve against the same flat table — the query&apos;s <code>FROM</code> comes from one
          binding, so a mismatch makes Presto fail with{" "}
          <em>&quot;User defined type is not supported&quot;</em>, which names neither the table nor
          the field. Qualify them all the same way (a bare <code>table.column</code> and a fully
          qualified <code>catalog.schema.table.column</code> count as different tables).
        </p>
      )}

      <p className="srse-text-muted" style={{ marginTop: 0, lineHeight: 1.5 }}>
        Live expressions must be <strong>fully qualified</strong> —{" "}
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
              // Key includes dataMode deliberately. Field keys are IDENTICAL
              // across Synthetic and Live, so keying on fieldKey alone let React
              // reuse each row component across a mode switch — and because
              // MappingRowEditor seeds its input from useState (mount-only), the
              // inputs kept showing the PREVIOUS mode's expressions. Switching to
              // Live therefore displayed synthetic values, and pressing Save
              // would have written a synthetic expression as the Live mapping.
              // Including the mode forces a remount so the inputs re-seed.
              <MappingRowEditor
                key={`${dataMode}-${row.fieldKey}`}
                row={row}
                dataMode={dataMode}
                onSaved={refresh}
              />
            ))}
          </tbody>
        </table>
      </div>

      <h3 className="srse-subheading" style={{ fontSize: "0.95rem" }}>
        Field catalogue
      </h3>
      <p className="srse-text-muted" style={{ marginTop: 0, lineHeight: 1.5 }}>
        What officers see in the rule builder&apos;s parameter palette. Editing a field changes its
        label, tier, type, group, allowed values and fuzzy eligibility everywhere at once; the field
        key itself is fixed, because mappings and saved rulesets address the field by it. Deleting
        withdraws the field from the palette and from the mapping table above — saved scenarios that
        already use it keep resolving.
      </p>

      {fields.length > 0 && (
        <div style={{ overflowX: "auto", marginBottom: "1rem" }}>
          <table className="srse-table">
            <thead>
              <tr>
                <th>Key</th>
                <th>Label</th>
                <th>Tier · Type</th>
                <th>Group</th>
                <th>Allowed values</th>
                <th>Fuzzy</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {fields.map((field) => (
                <FieldCatalogRowEditor
                  key={field.fieldKey}
                  field={field}
                  onChanged={refresh}
                  onError={setError}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}

      <h3 className="srse-subheading" style={{ fontSize: "0.95rem" }}>
        Register a new field
      </h3>
      <AddFieldForm onCreated={refresh} />
    </section>
  );
}

const COMPARE_AS_OPTIONS: { value: CompareAs; label: string; title: string }[] = [
  {
    value: "AUTO",
    label: "Auto",
    title:
      "Default. Compared as numbers when one side is numeric and the other is text, so 0123 still matches 123. Two columns of the same type are compared as they always were.",
  },
  {
    value: "NUMBER",
    label: "Number",
    title:
      "Always compare numerically — the text side goes through TRY_CAST. Text that is not a number simply does not match.",
  },
  {
    value: "TEXT",
    label: "Text",
    title:
      "Always compare as text — use when the text form is the truth, e.g. a code with meaningful leading zeros.",
  },
];

function CompareAsSelect({
  id,
  value,
  onChange,
}: Readonly<{ id: string; value: CompareAs; onChange: (value: CompareAs) => void }>) {
  return (
    <select
      id={id}
      aria-label="Compare as"
      className="srse-select"
      value={value}
      onChange={(e) => onChange(e.target.value as CompareAs)}
      title={COMPARE_AS_OPTIONS.find((o) => o.value === value)?.title}
    >
      {COMPARE_AS_OPTIONS.map((o) => (
        <option key={o.value} value={o.value} title={o.title}>
          {o.label}
        </option>
      ))}
    </select>
  );
}

function ColumnMetadataRowEditor({
  row,
  orphaned,
  onChanged,
}: Readonly<{
  row: ColumnMetadata;
  /** True once the table this row curates is no longer registered. */
  orphaned: boolean;
  onChanged: () => void;
}>) {
  const [businessName, setBusinessName] = useState(row.businessName ?? "");
  const [fuzzyMatchable, setFuzzyMatchable] = useState(row.fuzzyMatchable);
  const [visible, setVisible] = useState(row.visible);
  const [compareAs, setCompareAs] = useState<CompareAs>(row.compareAs ?? "AUTO");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const dirty =
    businessName !== (row.businessName ?? "") ||
    fuzzyMatchable !== row.fuzzyMatchable ||
    visible !== row.visible ||
    compareAs !== (row.compareAs ?? "AUTO");

  // Unique per fully-qualified column — the same table.column can exist in
  // both the Silver and Gold catalog, so the bare pair is not a unique DOM id.
  const rowId = `${row.catalog}-${row.schema}-${row.table}-${row.column}`;

  async function onSave() {
    setSaving(true);
    setError(null);
    try {
      await upsertColumnMetadata(
        { catalog: row.catalog, schema: row.schema, table: row.table },
        row.column,
        businessName.trim() || null,
        fuzzyMatchable,
        visible,
        compareAs,
      );
      onChanged();
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
        {/*
          Settings outlive an unregister on purpose — re-registering the table
          restores the admin's intent, hidden columns included, rather than
          silently re-exposing them. That leaves rows pointing at tables nobody
          can reach any more, so say so and offer the delete that clears them.
        */}
        {orphaned && (
          <div
            className="srse-text-danger"
            style={{ fontSize: "0.7rem", marginTop: "0.2rem", fontWeight: 600 }}
            title="This table is not registered, so the setting has no effect. Register the table again to reapply it, or delete the row."
          >
            ⚠ table not registered
          </div>
        )}
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
        <CompareAsSelect id={`col-meta-compare-${rowId}`} value={compareAs} onChange={setCompareAs} />
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
        <div style={{ display: "flex", alignItems: "center", gap: "0.5rem", flexWrap: "wrap" }}>
          <button type="button" className="srse-btn srse-btn-sm" disabled={!dirty || saving} onClick={onSave}>
            {saving ? "Saving…" : "Save"}
          </button>
          <ConfirmDeleteButton
            idleLabel="Delete"
            confirmLabel="Yes, delete"
            title="Removes this override. The column stays available to officers with an auto-derived label and default fuzzy matching."
            onConfirm={() =>
              deleteColumnMetadata(
                { catalog: row.catalog, schema: row.schema, table: row.table },
                row.column,
              ).then(onChanged)
            }
            onError={setError}
          />
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
  const [compareAs, setCompareAs] = useState<CompareAs>("AUTO");
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
        compareAs,
      );
      setBusinessName("");
      setFuzzyMatchable(false);
      setVisible(true);
      setCompareAs("AUTO");
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
      <label className="srse-text-muted" htmlFor="register-col-compare" style={{ fontSize: "0.72rem" }}>
        Compare as
      </label>
      <CompareAsSelect id="register-col-compare" value={compareAs} onChange={setCompareAs} />
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
  rows,
  error,
  onChanged,
}: Readonly<{
  registrations: TableRegistration[];
  rows: ColumnMetadata[];
  error: string | null;
  onChanged: () => void;
}>) {
  const registeredTables = useMemo(
    () => new Set(registrations.map((r) => r.qualifiedName)),
    [registrations],
  );

  return (
    <section className="srse-card">
      <h2 className="srse-card-title">Analysis tab: column business names, fuzzy matching &amp; visibility</h2>
      <p className="srse-page-description" style={{ maxWidth: "none", marginTop: 0 }}>
        Registering a table above exposes <strong>all</strong> of its columns to officers. Use this table
        to give individual columns a business name, mark them fuzzy-matchable, or hide them. Columns with
        no entry here stay visible and fall back to an auto-derived label and a name-substring guess for
        fuzzy matching — which is exactly what <strong>Delete</strong> reverts a column to.
        <br />
        <strong>Compare as</strong> only matters when a match puts this column against one of a{" "}
        <em>different</em> type — an account number stored <code>varchar</code> in one table and{" "}
        <code>bigint</code> in another. SRSE casts the pair rather than letting the query fail;{" "}
        <em>Auto</em> compares such a pair as <strong>numbers</strong> (so <code>0123</code> still
        matches <code>123</code>), and setting <em>Text</em> on either side forces the text reading
        instead. Two columns of the same type are unaffected.
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
                <th>Compare as</th>
                <th>Visible</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <ColumnMetadataRowEditor
                  key={`${row.catalog}.${row.schema}.${row.table}.${row.column}`}
                  row={row}
                  orphaned={!registeredTables.has(`${row.catalog}.${row.schema}.${row.table}`)}
                  onChanged={onChanged}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}

      <h3 className="srse-subheading" style={{ fontSize: "0.95rem" }}>
        Register column metadata
      </h3>
      <RegisterColumnMetadataForm registrations={registrations} onCreated={onChanged} />
    </section>
  );
}

/**
 * One registered table, editable in place.
 *
 * Only the layer tag is editable. The catalog/schema/table triple IS the
 * address every mapping, column-metadata row and saved ruleset refers to, so
 * "editing" a registration into a different table would silently orphan all of
 * them — retargeting is Delete + register, which is why both buttons are here.
 */
function RegistrationRow({
  registration,
  curatedColumnCount,
  onChanged,
  onError,
}: Readonly<{
  registration: TableRegistration;
  curatedColumnCount: number;
  onChanged: () => void;
  onError: (message: string) => void;
}>) {
  const [editing, setEditing] = useState(false);
  const [layer, setLayer] = useState(registration.layer ?? "");
  const [saving, setSaving] = useState(false);

  function startEditing() {
    setLayer(registration.layer ?? "");
    setEditing(true);
  }

  async function onSave() {
    setSaving(true);
    try {
      await updateTableRegistration(registration.id, layer.trim() || null);
      setEditing(false);
      onChanged();
    } catch (err: unknown) {
      onError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <tr>
      <td style={{ fontFamily: "monospace", fontSize: "0.82rem" }}>{registration.catalog}</td>
      <td style={{ fontFamily: "monospace", fontSize: "0.82rem" }}>{registration.schema}</td>
      <td style={{ fontFamily: "monospace", fontSize: "0.82rem" }}>{registration.table}</td>
      <td>
        {editing ? (
          <input
            aria-label={`Layer for ${registration.qualifiedName}`}
            list="srse-layer-suggestions"
            placeholder="SILVER / GOLD"
            value={layer}
            onChange={(e) => setLayer(e.target.value)}
            className="srse-input"
            style={{ width: 130 }}
          />
        ) : (
          <>
            {registration.layer ? (
              <span className="srse-badge">{registration.layer}</span>
            ) : (
              <span className="srse-text-muted">—</span>
            )}
          </>
        )}
      </td>
      <td>
        <div style={{ display: "flex", gap: "0.4rem", alignItems: "center", flexWrap: "wrap" }}>
          {editing ? (
            <>
              <button type="button" className="srse-btn srse-btn-sm" disabled={saving} onClick={onSave}>
                {saving ? "Saving…" : "Save"}
              </button>
              <button
                type="button"
                className="srse-btn srse-btn-ghost srse-btn-sm"
                disabled={saving}
                onClick={() => setEditing(false)}
              >
                Cancel
              </button>
            </>
          ) : (
            <>
              <button
                type="button"
                className="srse-btn srse-btn-ghost srse-btn-sm"
                onClick={startEditing}
                title="Re-tag this table's layer. The catalog/schema/table address itself cannot be edited — delete and register the other table instead."
              >
                Edit
              </button>
              <ConfirmDeleteButton
                idleLabel="Delete"
                confirmLabel="Yes, unregister"
                title="Officers stop seeing this table. Nothing in the lakehouse is touched, and its column settings are kept in case you register it again."
                onConfirm={() => unregisterTable(registration.id).then(onChanged)}
                onError={onError}
              />
              {curatedColumnCount > 0 && (
                <span className="srse-text-muted" style={{ fontSize: "0.72rem" }}>
                  {curatedColumnCount} column setting{curatedColumnCount === 1 ? "" : "s"}
                </span>
              )}
            </>
          )}
        </div>
      </td>
    </tr>
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
  columnMetadata,
  loading,
  error,
  onChanged,
}: Readonly<{
  registrations: TableRegistration[];
  columnMetadata: ColumnMetadata[];
  loading: boolean;
  error: string | null;
  onChanged: () => void;
}>) {
  const [cascade, setCascade] = useState<CascadeValue>(EMPTY_CASCADE);
  const [layer, setLayer] = useState("");
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const onCascadeError = useCallback((message: string) => setFormError(message), []);

  // How many column settings each table carries, so unregistering says what
  // curation it is putting out of reach (the rows are kept, not deleted).
  const curatedByTable = useMemo(() => {
    const counts = new Map<string, number>();
    for (const m of columnMetadata) {
      const key = `${m.catalog}.${m.schema}.${m.table}`;
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    return counts;
  }, [columnMetadata]);

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

  return (
    <section className="srse-card">
      <h2 className="srse-card-title">Lakehouse registry — Catalog › Schema › Table</h2>
      <p className="srse-page-description" style={{ maxWidth: "none", marginTop: 0 }}>
        Browse the live lakehouse and register the tables SRSE may use. Officers only ever see registered
        tables. Registering exposes all of the table&apos;s columns — hide individual ones below. Tag each
        table with its layer (e.g. <code>SILVER</code> / <code>GOLD</code>) so the same table name in two
        layers stays distinguishable.{" "}
        <strong>Edit</strong> re-tags that layer; <strong>Delete</strong> withdraws the table from officers
        without touching anything in the lakehouse, and keeps its column settings in case you register it
        again.
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
          {registerButtonLabel(saving, alreadyRegistered)}
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
                <RegistrationRow
                  key={r.id}
                  registration={r}
                  curatedColumnCount={curatedByTable.get(r.qualifiedName) ?? 0}
                  onChanged={onChanged}
                  onError={setFormError}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

export default function AdminPage() {
  // Registrations and column settings are loaded once here and passed down:
  // several panels need the same two lists, and they have to refresh TOGETHER.
  // Unregistering a table, for instance, changes no column-settings row but
  // does turn every one of them into an orphan — a panel refreshing only its
  // own list would keep showing the stale verdict.
  const [registrations, setRegistrations] = useState<TableRegistration[]>([]);
  const [columnMetadata, setColumnMetadata] = useState<ColumnMetadata[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [metadataError, setMetadataError] = useState<string | null>(null);
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

  useEffect(() => {
    listColumnMetadata()
      .then((m) => {
        setColumnMetadata(m);
        setMetadataError(null);
      })
      .catch((err: unknown) => setMetadataError(errorMessage(err)));
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
        columnMetadata={columnMetadata}
        loading={loading}
        error={error}
        onChanged={refresh}
      />
      <MappingsPanel registrations={registrations} />
      <ColumnMetadataPanel
        registrations={registrations}
        rows={columnMetadata}
        error={metadataError}
        onChanged={refresh}
      />
    </main>
  );
}
