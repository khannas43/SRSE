"use client";

import { Suspense, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "next/navigation";
import {
  cohortSample,
  createScheme,
  fetchDecisionLimits,
  fetchSchemeTemplate,
  listFields,
  listSchemes,
  previewRuleset,
  saveScenario,
  type CohortResponse,
  type FieldCatalogEntry,
  type PredicateNode,
  type PreviewResponse,
  type Scheme,
} from "@/lib/decisionApi";
import { useRuleBuilder, type NodePath } from "@/store/ruleBuilderStore";
import { RuleGroupEditor, countPredicates } from "@/components/RuleGroupEditor";
import { ResultsPanel } from "@/components/ResultsPanel";
import { MultiSelectDropdown } from "@/components/MultiSelectDropdown";

type PendingByField = Record<
  string,
  {
    min: number;
    max: number;
    selected: string[];
    fuzzyName: string;
    fuzzyThreshold: number;
    exemptRation: string[];
    exemptCaste: string[];
  }
>;

/** Categorical fields rendered as a dropdown (select one/many/all) instead of a checkbox list. */
const DROPDOWN_FIELD_KEYS = new Set(["district", "community", "relationship_to_hof", "class_passed"]);
const DROPDOWN_ALL_LABEL: Record<string, string> = {
  district: "All Districts",
  community: "All Castes",
  relationship_to_hof: "All Relationships",
  class_passed: "All Classes",
};

/** Mutually-exclusive categorical fields rendered as radio buttons (single value, EQ) instead of a checkbox list (IN). */
const RADIO_FIELD_KEYS = new Set(["tsp_classification"]);

const BASE_SAMPLE_SIZES = [25, 50, 100] as const;

function sampleSizeOptions(defaultSize: number, cohortCap: number): number[] {
  const sizes = new Set<number>([...BASE_SAMPLE_SIZES, defaultSize]);
  return [...sizes].filter((n) => n <= cohortCap).sort((a, b) => a - b);
}

const INCOME_BY_FY_GROUP = "Income by Financial Year";

type FyRow = { id: string; fy: string; min: number; max: number };

/**
 * Multiple independent FY picker + range rows, shared across the whole
 * "Income by Financial Year" group instead of one row per field — officer
 * sets FY1's range, adds it, clicks "+ Add more" for FY2's own row, and so
 * on, same "Add more" pattern as the Analysis tab's Source/Target rows.
 * Each row's "+ Add" is independent — adding one predicate doesn't clear or
 * remove its row, so a range can be tweaked and re-added.
 */
function FyIncomeComposite({
  fields,
  onAdd,
}: Readonly<{
  fields: FieldCatalogEntry[];
  onAdd: (fyFieldKey: string, min: number, max: number) => void;
}>) {
  const sorted = useMemo(() => [...fields].sort((a, b) => b.fieldKey.localeCompare(a.fieldKey)), [fields]);

  function emptyRow(): FyRow {
    return { id: crypto.randomUUID(), fy: sorted[0]?.fieldKey ?? "", min: 0, max: 100 };
  }

  const [rows, setRows] = useState<FyRow[]>([emptyRow()]);

  function updateRow(id: string, patch: Partial<FyRow>) {
    setRows((rs) => rs.map((r) => (r.id === id ? { ...r, ...patch } : r)));
  }

  function removeRow(id: string) {
    setRows((rs) => (rs.length <= 1 ? rs : rs.filter((r) => r.id !== id)));
  }

  return (
    <div style={{ width: "100%" }}>
      {rows.map((row) => (
        <div
          key={row.id}
          style={{
            display: "flex",
            alignItems: "center",
            flexWrap: "wrap",
            gap: "0.75rem",
            width: "100%",
            marginBottom: "0.6rem",
          }}
        >
          <select
            value={row.fy}
            onChange={(e) => updateRow(row.id, { fy: e.target.value })}
            className="srse-select"
            style={{ flex: "0 1 160px" }}
          >
            {sorted.map((f) => (
              <option key={f.fieldKey} value={f.fieldKey}>
                {f.displayLabel}
              </option>
            ))}
          </select>
          <input
            type="number"
            className="srse-input"
            style={{ flex: "1 1 120px", minWidth: 90, maxWidth: 220 }}
            value={row.min}
            onChange={(e) => updateRow(row.id, { min: Number(e.target.value) })}
          />
          <span className="srse-text-muted">to</span>
          <input
            type="number"
            className="srse-input"
            style={{ flex: "1 1 120px", minWidth: 90, maxWidth: 220 }}
            value={row.max}
            onChange={(e) => updateRow(row.id, { max: Number(e.target.value) })}
          />
          <button
            type="button"
            className="srse-btn"
            style={{ marginLeft: "auto" }}
            onClick={() => onAdd(row.fy, row.min, row.max)}
          >
            + Add FY income range
          </button>
          {rows.length > 1 && (
            <button
              type="button"
              className="srse-btn srse-btn-ghost srse-btn-sm"
              onClick={() => removeRow(row.id)}
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
    </div>
  );
}

function groupFields(fields: FieldCatalogEntry[]): Map<string, FieldCatalogEntry[]> {
  const groups = new Map<string, FieldCatalogEntry[]>();
  for (const field of fields) {
    const key = field.groupName || "Other";
    groups.set(key, [...(groups.get(key) ?? []), field]);
  }
  return groups;
}

type IncomeExemptionControlsProps = Readonly<{
  fields: FieldCatalogEntry[];
  exemptRation: string[];
  exemptCaste: string[];
  onExemptRationChange: (next: string[]) => void;
  onExemptCasteChange: (next: string[]) => void;
  onAdd: () => void;
}>;

function IncomeExemptionControls({
  fields,
  exemptRation,
  exemptCaste,
  onExemptRationChange,
  onExemptCasteChange,
  onAdd,
}: IncomeExemptionControlsProps) {
  const rationField = fields.find((f) => f.fieldKey === "ration_card_category");
  const casteField = fields.find((f) => f.fieldKey === "community");

  return (
    <div style={{ display: "flex", alignItems: "center", flexWrap: "wrap", gap: "0.75rem", marginTop: "0.6rem" }}>
      {rationField && (
        <div style={{ flex: "0 1 220px" }}>
          <div className="srse-text-muted" style={{ marginBottom: "0.3rem" }}>
            Ration card
          </div>
          <MultiSelectDropdown
            options={rationField.allowedValues}
            selected={exemptRation}
            onChange={onExemptRationChange}
            allLabel="None exempted"
            width="100%"
          />
        </div>
      )}
      {casteField && (
        <div style={{ flex: "0 1 220px" }}>
          <div className="srse-text-muted" style={{ marginBottom: "0.3rem" }}>
            Caste
          </div>
          <MultiSelectDropdown
            options={casteField.allowedValues}
            selected={exemptCaste}
            onChange={onExemptCasteChange}
            allLabel="None exempted"
            width="100%"
          />
        </div>
      )}
      <button
        type="button"
        className="srse-btn srse-btn-primary"
        style={{ marginLeft: "auto" }}
        onClick={onAdd}
        title="Adds (income in range) OR (category is one of the exempted values)"
      >
        + Add income with exemption
      </button>
    </div>
  );
}

function FieldPalette({
  fields,
  targetPath,
}: Readonly<{
  fields: FieldCatalogEntry[];
  targetPath: NodePath;
}>) {
  const addPredicate = useRuleBuilder((s) => s.addPredicate);
  const addGroup = useRuleBuilder((s) => s.addGroup);
  const [pending, setPending] = useState<PendingByField>({});
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const [showExemption, setShowExemption] = useState<Record<string, boolean>>({});

  function pendingFor(fieldKey: string) {
    return (
      pending[fieldKey] ?? {
        min: 0,
        max: 100,
        selected: [] as string[],
        fuzzyName: "",
        fuzzyThreshold: 80,
        exemptRation: [] as string[],
        exemptCaste: [] as string[],
      }
    );
  }

  function addRange(field: FieldCatalogEntry) {
    const { min, max } = pendingFor(field.fieldKey);
    const predicate: PredicateNode = {
      type: "PREDICATE",
      fieldKey: field.fieldKey,
      operator: "BETWEEN",
      value: [min, max],
    };
    addPredicate(targetPath, predicate);
  }

  function addSelection(field: FieldCatalogEntry) {
    const { selected } = pendingFor(field.fieldKey);
    if (selected.length === 0) return;
    const predicate: PredicateNode = {
      type: "PREDICATE",
      fieldKey: field.fieldKey,
      operator: "IN",
      value: selected,
    };
    addPredicate(targetPath, predicate);
    setPending((p) => ({ ...p, [field.fieldKey]: { ...pendingFor(field.fieldKey), selected: [] } }));
  }

  function toggleCheckboxSelection(fieldKey: string, opt: string) {
    setPending((p) => {
      const current = pendingFor(fieldKey);
      const next = current.selected.includes(opt)
        ? current.selected.filter((v) => v !== opt)
        : [...current.selected, opt];
      return { ...p, [fieldKey]: { ...current, selected: next } };
    });
  }

  function addBoolean(field: FieldCatalogEntry, value: boolean) {
    const predicate: PredicateNode = {
      type: "PREDICATE",
      fieldKey: field.fieldKey,
      operator: value ? "IS_TRUE" : "IS_FALSE",
      value: null,
    };
    addPredicate(targetPath, predicate);
  }

  function addRadio(field: FieldCatalogEntry, value: string) {
    const predicate: PredicateNode = {
      type: "PREDICATE",
      fieldKey: field.fieldKey,
      operator: "EQ",
      value,
    };
    addPredicate(targetPath, predicate);
  }

  function addFuzzyMatch(field: FieldCatalogEntry) {
    const { fuzzyName, fuzzyThreshold } = pendingFor(field.fieldKey);
    if (!fuzzyName.trim()) return;
    const predicate: PredicateNode = {
      type: "PREDICATE",
      fieldKey: field.fieldKey,
      operator: "FUZZY_MATCH",
      value: [fuzzyName.trim(), fuzzyThreshold],
    };
    addPredicate(targetPath, predicate);
    setPending((p) => ({ ...p, [field.fieldKey]: { ...pendingFor(field.fieldKey), fuzzyName: "" } }));
  }

  function addFyIncomeRange(fyFieldKey: string, min: number, max: number) {
    if (!fyFieldKey) return;
    const predicate: PredicateNode = {
      type: "PREDICATE",
      fieldKey: fyFieldKey,
      operator: "BETWEEN",
      value: [min, max],
    };
    addPredicate(targetPath, predicate);
  }

  function addIncomeWithExemption(field: FieldCatalogEntry) {
    const { min, max, exemptRation, exemptCaste } = pendingFor(field.fieldKey);
    const incomeRange: PredicateNode = {
      type: "PREDICATE",
      fieldKey: field.fieldKey,
      operator: "BETWEEN",
      value: [min, max],
    };
    const exemptions: PredicateNode[] = [];
    if (exemptRation.length > 0) {
      exemptions.push({ type: "PREDICATE", fieldKey: "ration_card_category", operator: "IN", value: exemptRation });
    }
    if (exemptCaste.length > 0) {
      exemptions.push({ type: "PREDICATE", fieldKey: "community", operator: "IN", value: exemptCaste });
    }
    if (exemptions.length === 0) {
      // No exemption picked — same as a plain range, no OR wrapper needed.
      addPredicate(targetPath, incomeRange);
      return;
    }
    addGroup(targetPath, "OR", [incomeRange, ...exemptions]);
    setPending((p) => ({
      ...p,
      [field.fieldKey]: { ...pendingFor(field.fieldKey), exemptRation: [], exemptCaste: [] },
    }));
    setShowExemption((s) => ({ ...s, [field.fieldKey]: false }));
  }

  const grouped = useMemo(() => groupFields(fields), [fields]);

  return (
    <>
      {Array.from(grouped.entries()).map(([groupName, groupFieldsList]) => {
        const isCollapsed = collapsed[groupName] ?? true;
        return (
          <section key={groupName} className="srse-card" style={{ width: "100%" }}>
            <button
              type="button"
              onClick={() => setCollapsed((c) => ({ ...c, [groupName]: !isCollapsed }))}
              style={{
                display: "flex",
                alignItems: "center",
                justifyContent: "space-between",
                width: "100%",
                background: "none",
                border: "none",
                padding: 0,
                margin: 0,
                cursor: "pointer",
                marginBottom: isCollapsed ? 0 : "0.5rem",
              }}
            >
              <span style={{ display: "flex", alignItems: "center", gap: "0.6rem" }}>
                <span className="srse-card-title" style={{ margin: 0 }}>
                  {groupName}
                </span>
                <span className="srse-tag">{groupFieldsList.length}</span>
              </span>
              <span className="srse-text-muted" style={{ fontSize: "0.78rem" }}>
                {isCollapsed ? "▸ Expand" : "▾ Collapse"}
              </span>
            </button>

            {!isCollapsed && groupName === INCOME_BY_FY_GROUP && (
              <FyIncomeComposite
                fields={groupFieldsList}
                onAdd={addFyIncomeRange}
              />
            )}

            {!isCollapsed &&
              groupName !== INCOME_BY_FY_GROUP &&
              groupFieldsList.map((field, i) => (
                <div
                  key={field.fieldKey}
                  style={{
                    width: "100%",
                    padding: "0.65rem 0",
                    borderTop: i === 0 ? "none" : "1px solid var(--srse-border)",
                  }}
                >
                  <div style={{ fontSize: "0.88rem", fontWeight: 500, marginBottom: "0.5rem" }}>
                    {field.displayLabel}
                    {field.tier === "TIER_3" && (
                      <span className="srse-tag" style={{ marginLeft: "0.5rem" }}>
                        derived
                      </span>
                    )}
                  </div>

                  {field.dataType === "NUMBER" && (
                    <div style={{ display: "flex", gap: "0.5rem", alignItems: "center", flexWrap: "wrap", width: "100%" }}>
                      <input
                        type="number"
                        className="srse-input"
                        style={{ flex: "1 1 120px", minWidth: 90, maxWidth: 220 }}
                        value={pendingFor(field.fieldKey).min}
                        onChange={(e) =>
                          setPending((p) => ({
                            ...p,
                            [field.fieldKey]: { ...pendingFor(field.fieldKey), min: Number(e.target.value) },
                          }))
                        }
                      />
                      <span className="srse-text-muted">to</span>
                      <input
                        type="number"
                        className="srse-input"
                        style={{ flex: "1 1 120px", minWidth: 90, maxWidth: 220 }}
                        value={pendingFor(field.fieldKey).max}
                        onChange={(e) =>
                          setPending((p) => ({
                            ...p,
                            [field.fieldKey]: { ...pendingFor(field.fieldKey), max: Number(e.target.value) },
                          }))
                        }
                      />
                      <button
                        type="button"
                        className="srse-btn"
                        style={{ marginLeft: "auto" }}
                        onClick={() => addRange(field)}
                      >
                        + Add range
                      </button>
                    </div>
                  )}

                  {field.dataType === "NUMBER" && field.fieldKey === "annual_income_total" && (
                    <div style={{ width: "100%", marginTop: "0.6rem" }}>
                      <button
                        type="button"
                        className="srse-btn srse-btn-ghost srse-btn-sm"
                        onClick={() => setShowExemption((s) => ({ ...s, [field.fieldKey]: !s[field.fieldKey] }))}
                      >
                        {showExemption[field.fieldKey] ? "▾" : "▸"} Exempted categories (skip income check)
                      </button>

                      {showExemption[field.fieldKey] && (
                        <IncomeExemptionControls
                          fields={fields}
                          exemptRation={pendingFor(field.fieldKey).exemptRation}
                          exemptCaste={pendingFor(field.fieldKey).exemptCaste}
                          onExemptRationChange={(next) =>
                            setPending((p) => ({
                              ...p,
                              [field.fieldKey]: { ...pendingFor(field.fieldKey), exemptRation: next },
                            }))
                          }
                          onExemptCasteChange={(next) =>
                            setPending((p) => ({
                              ...p,
                              [field.fieldKey]: { ...pendingFor(field.fieldKey), exemptCaste: next },
                            }))
                          }
                          onAdd={() => addIncomeWithExemption(field)}
                        />
                      )}
                    </div>
                  )}

                  {field.dataType === "BOOLEAN" && (
                    <div style={{ display: "flex", gap: "0.5rem" }}>
                      <button type="button" className="srse-btn" onClick={() => addBoolean(field, true)}>
                        Yes
                      </button>
                      <button type="button" className="srse-btn" onClick={() => addBoolean(field, false)}>
                        No
                      </button>
                    </div>
                  )}

                  {field.dataType === "STRING" && field.fuzzyMatchable && (
                    <div style={{ display: "flex", alignItems: "center", flexWrap: "wrap", gap: "0.75rem", width: "100%" }}>
                      <input
                        type="text"
                        placeholder={`e.g. ${field.displayLabel}`}
                        className="srse-input"
                        style={{ flex: "1 1 180px", minWidth: 140, maxWidth: 260 }}
                        value={pendingFor(field.fieldKey).fuzzyName}
                        onChange={(e) =>
                          setPending((p) => ({
                            ...p,
                            [field.fieldKey]: { ...pendingFor(field.fieldKey), fuzzyName: e.target.value },
                          }))
                        }
                      />
                      <label
                        style={{ display: "flex", alignItems: "center", gap: "0.5rem", fontSize: "0.85rem", flex: "1 1 220px" }}
                      >
                        <span className="srse-text-muted">Match ≥</span>
                        <input
                          type="range"
                          min={0}
                          max={100}
                          value={pendingFor(field.fieldKey).fuzzyThreshold}
                          onChange={(e) =>
                            setPending((p) => ({
                              ...p,
                              [field.fieldKey]: { ...pendingFor(field.fieldKey), fuzzyThreshold: Number(e.target.value) },
                            }))
                          }
                          style={{ flex: "1 1 100px" }}
                        />
                        <span style={{ fontWeight: 500, minWidth: 36 }}>
                          {pendingFor(field.fieldKey).fuzzyThreshold}%
                        </span>
                      </label>
                      <button
                        type="button"
                        className="srse-btn"
                        style={{ marginLeft: "auto" }}
                        onClick={() => addFuzzyMatch(field)}
                      >
                        + Add fuzzy match
                      </button>
                    </div>
                  )}

                  {field.dataType === "STRING" && !field.fuzzyMatchable && DROPDOWN_FIELD_KEYS.has(field.fieldKey) && (
                    <div style={{ display: "flex", alignItems: "center", flexWrap: "wrap", gap: "0.75rem", width: "100%" }}>
                      <div style={{ flex: "0 1 300px", maxWidth: 360 }}>
                        <MultiSelectDropdown
                          options={field.allowedValues}
                          selected={pendingFor(field.fieldKey).selected}
                          onChange={(next) =>
                            setPending((p) => ({
                              ...p,
                              [field.fieldKey]: { ...pendingFor(field.fieldKey), selected: next },
                            }))
                          }
                          allLabel={DROPDOWN_ALL_LABEL[field.fieldKey] ?? `All ${field.displayLabel}`}
                          width="100%"
                        />
                      </div>
                      <button
                        type="button"
                        className="srse-btn"
                        style={{ marginLeft: "auto" }}
                        onClick={() => addSelection(field)}
                        title={
                          pendingFor(field.fieldKey).selected.length === 0
                            ? `${DROPDOWN_ALL_LABEL[field.fieldKey] ?? "All"} selected — no filter will be added`
                            : undefined
                        }
                      >
                        + Add selection
                      </button>
                    </div>
                  )}

                  {field.dataType === "STRING" && !field.fuzzyMatchable && RADIO_FIELD_KEYS.has(field.fieldKey) && (
                    <div style={{ display: "flex", alignItems: "center", flexWrap: "wrap", gap: "1.25rem" }}>
                      {field.allowedValues.map((opt) => (
                        <label
                          key={opt}
                          style={{ display: "flex", alignItems: "center", gap: "0.4rem", fontSize: "0.85rem", cursor: "pointer" }}
                        >
                          <input
                            type="radio"
                            name={`radio-${field.fieldKey}`}
                            onChange={() => addRadio(field, opt)}
                          />
                          {opt}
                        </label>
                      ))}
                    </div>
                  )}

                  {field.dataType === "STRING" &&
                    !field.fuzzyMatchable &&
                    !DROPDOWN_FIELD_KEYS.has(field.fieldKey) &&
                    !RADIO_FIELD_KEYS.has(field.fieldKey) && (
                    <div style={{ width: "100%" }}>
                      <div style={{ display: "flex", flexWrap: "wrap", gap: "0.4rem 1.25rem", marginBottom: "0.6rem" }}>
                        {field.allowedValues.map((opt) => {
                          const selected = pendingFor(field.fieldKey).selected;
                          return (
                            <label key={opt} className="srse-checkbox-label">
                              <input
                                type="checkbox"
                                checked={selected.includes(opt)}
                                onChange={() => toggleCheckboxSelection(field.fieldKey, opt)}
                              />
                              {" "}
                              {opt}
                            </label>
                          );
                        })}
                      </div>
                      <div style={{ display: "flex", justifyContent: "flex-end", width: "100%" }}>
                        <button type="button" className="srse-btn" onClick={() => addSelection(field)}>
                          + Add selection
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              ))}
          </section>
        );
      })}
    </>
  );
}

function RulesPageInner() {
  const searchParams = useSearchParams();
  const preselectScheme = searchParams.get("scheme");

  const { name, schemeIds, root, setName, setSchemeIds, reset, loadRuleset } = useRuleBuilder();
  const [templateSchemeName, setTemplateSchemeName] = useState<string | null>(null);
  const [targetPath, setTargetPath] = useState<NodePath>([]);
  const [ruleCollapsed, setRuleCollapsed] = useState(true);

  const [fields, setFields] = useState<FieldCatalogEntry[]>([]);
  const [schemes, setSchemes] = useState<Scheme[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [showNewScheme, setShowNewScheme] = useState(false);
  const [newSchemeCode, setNewSchemeCode] = useState("");
  const [newSchemeName, setNewSchemeName] = useState("");

  const [previewStatus, setPreviewStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [previewData, setPreviewData] = useState<PreviewResponse | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [sampleSize, setSampleSize] = useState(50);
  const [sampleChoices, setSampleChoices] = useState<number[]>([25, 50, 100]);
  const [cohortSampleData, setCohortSampleData] = useState<CohortResponse | null>(null);
  const [cohortSampleError, setCohortSampleError] = useState<string | null>(null);

  const [saveStatus, setSaveStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [saveError, setSaveError] = useState<string | null>(null);
  const [savedScenarioId, setSavedScenarioId] = useState<number | null>(null);

  useEffect(() => {
    listFields()
      .then(setFields)
      .catch((err: unknown) => setLoadError(err instanceof Error ? err.message : String(err)));
    listSchemes()
      .then(setSchemes)
      .catch((err: unknown) => setLoadError(err instanceof Error ? err.message : String(err)));
    fetchDecisionLimits()
      .then((limits) => {
        setSampleSize(limits.previewSampleSize);
        setSampleChoices(sampleSizeOptions(limits.previewSampleSize, limits.cohortCap));
      })
      .catch(() => {
        setSampleChoices(sampleSizeOptions(50, 1000));
      });
  }, []);

  useEffect(() => {
    if (!preselectScheme || schemes.length === 0) return;
    const match = schemes.find((s) => s.code === preselectScheme);
    if (match && !schemeIds.includes(match.id)) {
      setSchemeIds([...schemeIds, match.id]);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [preselectScheme, schemes]);

  async function loadTemplateForScheme(schemeId: number, schemeName: string) {
    const hasEdits = countPredicates(root) > 0 || name.trim().length > 0;
    if (hasEdits) {
      const ok = globalThis.confirm(
        "Replace the current rules with this scheme's official criteria? Unsaved edits will be lost.",
      );
      if (!ok) return;
    }
    try {
      const spec = await fetchSchemeTemplate(schemeId);
      if (!spec?.root || spec.root.type !== "GROUP") {
        setTemplateSchemeName(null);
        return;
      }
      loadRuleset(spec.root, `${schemeName} — from official criteria`);
      setTemplateSchemeName(schemeName);
      setRuleCollapsed(false);
    } catch (err: unknown) {
      setLoadError(err instanceof Error ? err.message : String(err));
    }
  }

  async function onPreview() {
    setPreviewStatus("loading");
    setPreviewError(null);
    setCohortSampleData(null);
    setCohortSampleError(null);

    const previewReq = { ruleset: { root }, includeBreakdown: true };
    const cohortReq = { ruleset: { root }, limit: sampleSize };

    const [previewResult, cohortResult] = await Promise.allSettled([
      previewRuleset(previewReq),
      cohortSample(cohortReq),
    ]);

    if (previewResult.status === "fulfilled") {
      setPreviewData(previewResult.value);
      setPreviewStatus("ok");
    } else {
      const err = previewResult.reason;
      setPreviewError(err instanceof Error ? err.message : String(err));
      setPreviewStatus("error");
      setPreviewData(null);
    }

    if (cohortResult.status === "fulfilled") {
      setCohortSampleData(cohortResult.value);
      setCohortSampleError(null);
    } else {
      const err = cohortResult.reason;
      setCohortSampleError(err instanceof Error ? err.message : String(err));
      setCohortSampleData(null);
    }
  }

  async function onSave() {
    setSaveStatus("loading");
    setSaveError(null);
    try {
      const result = await saveScenario({
        name: name || `Ruleset — ${new Date().toISOString()}`,
        schemeIds,
        ruleset: { root },
        includeBreakdown: true,
      });
      setSavedScenarioId(result.scenarioId);
      setPreviewData({ totalCount: result.totalCount, breakdown: result.breakdown });
      setCohortSampleData(null);
      setCohortSampleError(null);
      setSaveStatus("ok");
    } catch (err: unknown) {
      setSaveError(err instanceof Error ? err.message : String(err));
      setSaveStatus("error");
    }
  }

  async function onCreateScheme() {
    if (!newSchemeCode.trim() || !newSchemeName.trim()) return;
    const created = await createScheme({
      code: newSchemeCode.trim(),
      name: newSchemeName.trim(),
      description: "",
    });
    setSchemes((prev) => [...prev, created]);
    setSchemeIds([...schemeIds, created.id]);
    setNewSchemeCode("");
    setNewSchemeName("");
    setShowNewScheme(false);
  }

  const predicateCount = countPredicates(root);

  return (
    <main className="srse-page">
      <h1 className="srse-page-title">Scheme Eligibility Rule Builder</h1>
      <p className="srse-page-description" style={{ maxWidth: "none", whiteSpace: "nowrap" }}>
        Compose grouped parameters into nested rules, preview the beneficiary count/breakdown live against the
        lakehouse, then save the combination and tag it to one or more schemes.
      </p>

      {loadError && <p className="srse-text-danger">Failed to load catalogue: {loadError}</p>}

      <div style={{ display: "flex", gap: "1.5rem", flexWrap: "wrap", alignItems: "flex-start", marginBottom: "0" }}>
        <section className="srse-card" style={{ flex: "1 1 320px", maxWidth: 400, marginBottom: 0 }}>
          <h2 className="srse-card-title">Scheme</h2>
          <MultiSelectDropdown
            options={schemes.map((scheme) => ({ value: String(scheme.id), label: `${scheme.name} (${scheme.code})` }))}
            selected={schemeIds.map(String)}
            onChange={(next) => {
              const nums = next.map(Number);
              const added = nums.find((id) => !schemeIds.includes(id));
              setSchemeIds(nums);
              if (added != null) {
                const scheme = schemes.find((s) => s.id === added);
                if (scheme) {
                  void loadTemplateForScheme(added, scheme.name);
                }
              }
              if (nums.length === 0) {
                setTemplateSchemeName(null);
              }
            }}
            allLabel="No scheme selected"
            width="100%"
          />

          <div style={{ marginTop: "0.85rem" }}>
            {showNewScheme ? (
              <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap", alignItems: "center" }}>
                <input
                  placeholder="CODE"
                  value={newSchemeCode}
                  onChange={(e) => setNewSchemeCode(e.target.value.toUpperCase())}
                  className="srse-input"
                  style={{ flex: "1 1 120px" }}
                />
                <input
                  placeholder="Scheme name"
                  value={newSchemeName}
                  onChange={(e) => setNewSchemeName(e.target.value)}
                  className="srse-input"
                  style={{ flex: "1 1 160px" }}
                />
                <div style={{ display: "flex", gap: "0.5rem" }}>
                  <button type="button" className="srse-btn srse-btn-primary" onClick={onCreateScheme}>
                    Create
                  </button>
                  <button type="button" className="srse-btn srse-btn-ghost" onClick={() => setShowNewScheme(false)}>
                    Cancel
                  </button>
                </div>
              </div>
            ) : (
              <button type="button" className="srse-btn" onClick={() => setShowNewScheme(true)}>
                + New scheme
              </button>
            )}
          </div>
        </section>

        <section className="srse-card" style={{ flex: "2 1 480px", marginBottom: 0 }}>
          <button
            type="button"
            onClick={() => setRuleCollapsed((c) => !c)}
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              width: "100%",
              background: "none",
              border: "none",
              padding: 0,
              margin: 0,
              cursor: "pointer",
              marginBottom: ruleCollapsed ? 0 : "0.5rem",
            }}
          >
            <span className="srse-card-title" style={{ margin: 0 }}>
              Rule ({predicateCount} condition{predicateCount === 1 ? "" : "s"})
            </span>
            <span className="srse-text-muted" style={{ fontSize: "0.78rem" }}>
              {ruleCollapsed ? "▸ Expand" : "▾ Collapse"}
            </span>
          </button>

          {!ruleCollapsed && (
            <>
              <RuleGroupEditor node={root} path={[]} fields={fields} targetPath={targetPath} onSetTarget={setTargetPath} />

              {templateSchemeName && (
                <div
                  className="srse-text-muted"
                  style={{
                    marginTop: "0.75rem",
                    padding: "0.65rem 0.75rem",
                    border: "1px solid var(--srse-border)",
                    borderRadius: 6,
                    lineHeight: 1.5,
                    fontSize: "0.85rem",
                  }}
                >
                  Loaded from <strong style={{ color: "var(--srse-text)" }}>{templateSchemeName}</strong>
                  &apos;s official criteria. Your changes will be saved as a <strong>new scenario</strong>, not as the
                  scheme&apos;s official criteria.
                </div>
              )}

              <div style={{ marginTop: "1rem", paddingTop: "1rem", borderTop: "1px solid var(--srse-border)" }}>
                <label htmlFor="ruleset-name" style={{ display: "block", marginBottom: "0.75rem" }}>
                  <span style={{ display: "block", fontSize: "0.85rem", marginBottom: "0.35rem", color: "var(--srse-text-muted)" }}>
                    Name
                  </span>
                  <input
                    id="ruleset-name"
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    placeholder="e.g. Senior citizen — no vehicle, low income"
                    className="srse-input"
                    style={{ width: "100%" }}
                  />
                </label>
                <div style={{ display: "flex", gap: "0.6rem", flexWrap: "wrap", alignItems: "center" }}>
                  <label htmlFor="preview-sample-size" className="srse-text-muted" style={{ fontSize: "0.85rem" }}>
                    Sample size
                    <select
                      id="preview-sample-size"
                      className="srse-select"
                      style={{ marginLeft: "0.4rem" }}
                      value={sampleSize}
                      disabled={previewStatus === "loading"}
                      onChange={(e) => setSampleSize(Number(e.target.value))}
                    >
                      {sampleChoices.map((n) => (
                        <option key={n} value={n}>
                          {n}
                        </option>
                      ))}
                    </select>
                  </label>
                  <button type="button" className="srse-btn" disabled={previewStatus === "loading"} onClick={onPreview}>
                    {previewStatus === "loading" ? "Previewing…" : "Preview"}
                  </button>
                  <button
                    type="button"
                    className="srse-btn srse-btn-primary"
                    disabled={saveStatus === "loading" || schemeIds.length === 0}
                    onClick={onSave}
                    title={schemeIds.length === 0 ? "Select at least one scheme first" : undefined}
                  >
                    {saveStatus === "loading" ? "Saving…" : "Save as new scenario"}
                  </button>
                  <button type="button" className="srse-btn srse-btn-ghost" onClick={reset}>
                    Reset
                  </button>
                </div>
                {previewError && <p className="srse-text-danger">{previewError}</p>}
                {saveError && <p className="srse-text-danger">{saveError}</p>}
                {saveStatus === "ok" && savedScenarioId && (
                  <p className="srse-text-success">Saved as scenario #{savedScenarioId}.</p>
                )}
              </div>
            </>
          )}
        </section>
      </div>

      {previewData && (
        <div className="srse-card" style={{ marginTop: "1.5rem" }}>
          <ResultsPanel
            totalCount={previewData.totalCount}
            breakdown={previewData.breakdown}
            caption="Eligible beneficiaries"
            cohortSample={cohortSampleData}
            cohortSampleError={cohortSampleError}
          />
        </div>
      )}

      <h2 className="srse-subheading" style={{ marginTop: "1.5rem" }}>
        Parameters
      </h2>
      <FieldPalette fields={fields} targetPath={targetPath} />
    </main>
  );
}

export default function RulesPage() {
  return (
    <Suspense fallback={<main className="srse-page">Loading…</main>}>
      <RulesPageInner />
    </Suspense>
  );
}
