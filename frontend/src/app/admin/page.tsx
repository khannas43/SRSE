"use client";

import { useEffect, useState } from "react";
import {
  createField,
  getConnections,
  listMappings,
  updateAnalyticalConnection,
  updateOperationalConnection,
  upsertMapping,
  type ConnectionPlaneInfo,
  type ConnectionsInfo,
  type DataMode,
  type FieldDataType,
  type FieldTier,
  type MappingRow,
} from "@/lib/decisionApi";

function StatusBadge({ status }: { status: string }) {
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
}: {
  planeKey: PlaneKey;
  plane: ConnectionPlaneInfo;
  onLiveUpdate: (plane: ConnectionPlaneInfo) => void;
}) {
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

function AddFieldForm({ onCreated }: { onCreated: () => void }) {
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
      <label className="srse-checkbox-label" title="Offer approximate (Levenshtein) matching for this field in the rule builder">
        <input type="checkbox" checked={fuzzyMatchable} onChange={(e) => setFuzzyMatchable(e.target.checked)} />
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

function MappingRowEditor({
  row,
  dataMode,
  onSaved,
}: {
  row: MappingRow;
  dataMode: DataMode;
  onSaved: (physicalExpression: string) => void;
}) {
  const [value, setValue] = useState(row.physicalExpression ?? "");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const dirty = value !== (row.physicalExpression ?? "");

  async function onSave() {
    setSaving(true);
    setError(null);
    try {
      await upsertMapping(row.fieldKey, dataMode, value);
      onSaved(value);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : String(err));
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
        <input
          value={value}
          placeholder="e.g. beneficiary.age_years"
          onChange={(e) => setValue(e.target.value)}
          className="srse-input"
          style={{ width: 280, fontFamily: "monospace" }}
        />
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

function MappingsPanel() {
  const [dataMode, setDataMode] = useState<DataMode>("SYNTHETIC");
  const [rows, setRows] = useState<MappingRow[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);

  useEffect(() => {
    listMappings(dataMode)
      .then(setRows)
      .catch((err: unknown) => setError(err instanceof Error ? err.message : String(err)));
  }, [dataMode, refreshKey]);

  const refresh = () => setRefreshKey((k) => k + 1);

  return (
    <section className="srse-card">
      <h2 className="srse-card-title">Field → table/column mappings</h2>
      <div style={{ display: "flex", gap: "1.25rem", alignItems: "center", marginBottom: "0.85rem" }}>
        <label className="srse-checkbox-label">
          <input type="radio" checked={dataMode === "SYNTHETIC"} onChange={() => setDataMode("SYNTHETIC")} />
          Synthetic
        </label>
        <label className="srse-checkbox-label">
          <input type="radio" checked={dataMode === "LIVE"} onChange={() => setDataMode("LIVE")} />
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

export default function AdminPage() {
  return (
    <main className="srse-page">
      <h1 className="srse-page-title">Admin — Lakehouse Connections</h1>
      <p className="srse-page-description" style={{ maxWidth: "none", whiteSpace: "nowrap" }}>
        View the active Presto/DB2 connections and manage which physical table/column each abstract field
        resolves to, per environment.
      </p>

      <ConnectionsPanel />
      <MappingsPanel />
    </main>
  );
}
