"use client";

import { useMemo, useState, type CSSProperties } from "react";
import Link from "next/link";
import {
  createColumnHelper,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from "@tanstack/react-table";
import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import {
  evaluate,
  type BreakdownRow,
  type EvaluateResponse,
  type PredicateNode,
  type PredicateSpec,
} from "@/lib/decisionApi";

type TriState = "any" | "yes" | "no";

type FormState = {
  ageMin: number;
  ageMax: number;
  incomeMin: number;
  incomeMax: number;
  disabilityMin: number;
  disabilityMax: number;
  gender: string[];
  district: string[];
  maritalStatus: string[];
  rationCardCategory: string[];
  community: string[];
  isDomicileHolder: TriState;
  isEnrolledInSchool: TriState;
  isGirlChildOfHof: TriState;
};

const GENDER_OPTIONS = ["MALE", "FEMALE"] as const;
const DISTRICT_OPTIONS = [
  "Jaipur",
  "Jodhpur",
  "Udaipur",
  "Kota",
  "Ajmer",
  "Bikaner",
  "Alwar",
] as const;
const MARITAL_STATUS_OPTIONS = [
  "SINGLE",
  "MARRIED",
  "DIVORCED",
  "WIDOWED",
] as const;
const RATION_CARD_OPTIONS = ["NONE", "BPL", "ANTYODAYA"] as const;
const COMMUNITY_OPTIONS = [
  "GENERAL",
  "SAHARIYA",
  "KATHODI",
  "KHAIRWA",
] as const;

const DEFAULT_FORM: FormState = {
  ageMin: 18,
  ageMax: 100,
  incomeMin: 0,
  incomeMax: 48000,
  disabilityMin: 0,
  disabilityMax: 100,
  gender: ["FEMALE"],
  district: [],
  maritalStatus: ["DIVORCED"],
  rationCardCategory: ["BPL", "ANTYODAYA"],
  community: ["SAHARIYA", "KATHODI", "KHAIRWA"],
  isDomicileHolder: "yes",
  isEnrolledInSchool: "any",
  isGirlChildOfHof: "any",
};

function rangePredicate(
  fieldKey: string,
  min: number,
  max: number,
): PredicateNode {
  return {
    type: "PREDICATE",
    fieldKey,
    operator: "BETWEEN",
    value: [min, max],
  };
}

function inPredicate(
  fieldKey: string,
  values: string[],
): PredicateNode | null {
  if (values.length === 0) return null;
  return {
    type: "PREDICATE",
    fieldKey,
    operator: "IN",
    value: values,
  };
}

function booleanPredicate(
  fieldKey: string,
  state: TriState,
): PredicateNode | null {
  if (state === "any") return null;
  return {
    type: "PREDICATE",
    fieldKey,
    operator: state === "yes" ? "IS_TRUE" : "IS_FALSE",
    value: null,
  };
}

function buildRuleset(form: FormState): PredicateSpec {
  const children: PredicateNode[] = [
    rangePredicate("age_years", form.ageMin, form.ageMax),
    rangePredicate("annual_income_total", form.incomeMin, form.incomeMax),
    rangePredicate("disability_pct", form.disabilityMin, form.disabilityMax),
  ];

  const categorical: (PredicateNode | null)[] = [
    inPredicate("gender", form.gender),
    inPredicate("district", form.district),
    inPredicate("marital_status", form.maritalStatus),
    inPredicate("ration_card_category", form.rationCardCategory),
    inPredicate("community", form.community),
  ];
  for (const p of categorical) {
    if (p) children.push(p);
  }

  const booleans: (PredicateNode | null)[] = [
    booleanPredicate("is_domicile_holder", form.isDomicileHolder),
    booleanPredicate("is_enrolled_in_school", form.isEnrolledInSchool),
    booleanPredicate("is_girl_child_of_hof", form.isGirlChildOfHof),
  ];
  for (const p of booleans) {
    if (p) children.push(p);
  }

  return {
    root: {
      type: "GROUP",
      op: "AND",
      children,
    },
  };
}

function toggleValue(current: string[], value: string): string[] {
  return current.includes(value)
    ? current.filter((v) => v !== value)
    : [...current, value];
}

function aggregateByDistrict(
  rows: BreakdownRow[],
): { district: string; count: number }[] {
  const totals = new Map<string, number>();
  for (const row of rows) {
    totals.set(row.district, (totals.get(row.district) ?? 0) + row.count);
  }
  return Array.from(totals.entries())
    .map(([district, count]) => ({ district, count }))
    .sort((a, b) => a.district.localeCompare(b.district));
}

const columnHelper = createColumnHelper<BreakdownRow>();

const columns = [
  columnHelper.accessor("district", { header: "District" }),
  columnHelper.accessor("gender", { header: "Gender" }),
  columnHelper.accessor("ageBand", { header: "Age band" }),
  columnHelper.accessor("count", { header: "Count" }),
];

const inputStyle: CSSProperties = {
  padding: "0.4rem 0.6rem",
  border: "1px solid #ccc",
  borderRadius: 4,
  fontSize: "1rem",
  width: 120,
};

const labelStyle: CSSProperties = {
  display: "flex",
  flexDirection: "column",
  gap: "0.35rem",
  fontSize: "0.9rem",
};

const sectionStyle: CSSProperties = {
  marginBottom: "1.5rem",
  padding: "1rem 1.25rem",
  border: "1px solid #e0e0e0",
  borderRadius: 4,
  background: "#fafafa",
};

const sectionTitleStyle: CSSProperties = {
  margin: "0 0 0.75rem",
  fontSize: "1rem",
  fontWeight: 600,
};

const checkboxGroupStyle: CSSProperties = {
  display: "flex",
  flexWrap: "wrap",
  gap: "0.5rem 1rem",
  fontSize: "0.9rem",
};

const fieldBlockStyle: CSSProperties = {
  marginBottom: "1rem",
};

const fieldLabelStyle: CSSProperties = {
  display: "block",
  marginBottom: "0.4rem",
  fontSize: "0.9rem",
  fontWeight: 500,
};

export default function EkalNaariPage() {
  const [form, setForm] = useState<FormState>(DEFAULT_FORM);
  const [status, setStatus] = useState<"loading" | "ok" | "error" | null>(null);
  const [data, setData] = useState<EvaluateResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const chartData = useMemo(
    () => (data ? aggregateByDistrict(data.breakdown) : []),
    [data],
  );

  const table = useReactTable({
    data: data?.breakdown ?? [],
    columns,
    getCoreRowModel: getCoreRowModel(),
  });

  function setNumber(key: keyof FormState, raw: string) {
    setForm((prev) => ({ ...prev, [key]: Number(raw) }));
  }

  function setTriState(key: keyof FormState, value: TriState) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  function setChecked(key: keyof FormState, value: string) {
    setForm((prev) => ({
      ...prev,
      [key]: toggleValue(prev[key] as string[], value),
    }));
  }

  async function onSimulate() {
    setStatus("loading");
    setError(null);
    setData(null);

    const ruleset = buildRuleset(form);
    const name = `Ekal Naari — age ${form.ageMin}-${form.ageMax}, income ${form.incomeMin}-${form.incomeMax}`;

    try {
      const result = await evaluate({
        schemeId: "EKAL_NAARI",
        name,
        ruleset,
        includeBreakdown: true,
      });
      setData(result);
      setStatus("ok");
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : String(err));
      setStatus("error");
    }
  }

  return (
    <main style={{ padding: "2rem", maxWidth: 960 }}>
      <p style={{ marginTop: 0 }}>
        <Link href="/" style={{ color: "#0645ad" }}>
          ← SRSE health check
        </Link>
      </p>

      <h1 style={{ marginTop: "0.5rem" }}>
        Ekal Naari (Divorced Woman) Pension -- Eligibility Simulator
      </h1>

      <p style={{ color: "#444", maxWidth: 720 }}>
        Defaults reproduce the real Ekal Naari scheme, but every field below is
        independently adjustable for general cohort exploration — this is no
        longer a strict single-scheme simulator.
      </p>

      {/* Range fields */}
      <section style={sectionStyle}>
        <h2 style={sectionTitleStyle}>Ranges</h2>
        <div
          style={{
            display: "flex",
            flexWrap: "wrap",
            gap: "1.25rem",
            alignItems: "flex-end",
          }}
        >
          <label style={labelStyle}>
            Age: from
            <input
              type="number"
              value={form.ageMin}
              min={0}
              onChange={(e) => setNumber("ageMin", e.target.value)}
              style={inputStyle}
            />
          </label>
          <label style={labelStyle}>
            to
            <input
              type="number"
              value={form.ageMax}
              min={0}
              onChange={(e) => setNumber("ageMax", e.target.value)}
              style={inputStyle}
            />
          </label>

          <label style={labelStyle}>
            Income (₹): from
            <input
              type="number"
              value={form.incomeMin}
              min={0}
              step={1000}
              onChange={(e) => setNumber("incomeMin", e.target.value)}
              style={inputStyle}
            />
          </label>
          <label style={labelStyle}>
            to
            <input
              type="number"
              value={form.incomeMax}
              min={0}
              step={1000}
              onChange={(e) => setNumber("incomeMax", e.target.value)}
              style={inputStyle}
            />
          </label>

          <label style={labelStyle}>
            Disability %: from
            <input
              type="number"
              value={form.disabilityMin}
              min={0}
              max={100}
              onChange={(e) => setNumber("disabilityMin", e.target.value)}
              style={inputStyle}
            />
          </label>
          <label style={labelStyle}>
            to
            <input
              type="number"
              value={form.disabilityMax}
              min={0}
              max={100}
              onChange={(e) => setNumber("disabilityMax", e.target.value)}
              style={inputStyle}
            />
          </label>
        </div>
      </section>

      {/* Categorical multi-select */}
      <section style={sectionStyle}>
        <h2 style={sectionTitleStyle}>Categories</h2>
        <div style={fieldBlockStyle}>
          <span style={fieldLabelStyle}>Gender</span>
          <div style={checkboxGroupStyle}>
            {GENDER_OPTIONS.map((opt) => (
              <label key={opt} style={{ display: "flex", gap: "0.35rem", alignItems: "center" }}>
                <input
                  type="checkbox"
                  checked={form.gender.includes(opt)}
                  onChange={() => setChecked("gender", opt)}
                />
                {opt}
              </label>
            ))}
          </div>
        </div>

        <div style={fieldBlockStyle}>
          <span style={fieldLabelStyle}>District</span>
          <div style={checkboxGroupStyle}>
            {DISTRICT_OPTIONS.map((opt) => (
              <label key={opt} style={{ display: "flex", gap: "0.35rem", alignItems: "center" }}>
                <input
                  type="checkbox"
                  checked={form.district.includes(opt)}
                  onChange={() => setChecked("district", opt)}
                />
                {opt}
              </label>
            ))}
          </div>
        </div>

        <div style={fieldBlockStyle}>
          <span style={fieldLabelStyle}>Marital status</span>
          <div style={checkboxGroupStyle}>
            {MARITAL_STATUS_OPTIONS.map((opt) => (
              <label key={opt} style={{ display: "flex", gap: "0.35rem", alignItems: "center" }}>
                <input
                  type="checkbox"
                  checked={form.maritalStatus.includes(opt)}
                  onChange={() => setChecked("maritalStatus", opt)}
                />
                {opt}
              </label>
            ))}
          </div>
        </div>

        <div style={fieldBlockStyle}>
          <span style={fieldLabelStyle}>Ration card category</span>
          <div style={checkboxGroupStyle}>
            {RATION_CARD_OPTIONS.map((opt) => (
              <label key={opt} style={{ display: "flex", gap: "0.35rem", alignItems: "center" }}>
                <input
                  type="checkbox"
                  checked={form.rationCardCategory.includes(opt)}
                  onChange={() => setChecked("rationCardCategory", opt)}
                />
                {opt}
              </label>
            ))}
          </div>
        </div>

        <div style={{ ...fieldBlockStyle, marginBottom: 0 }}>
          <span style={fieldLabelStyle}>Community</span>
          <div style={checkboxGroupStyle}>
            {COMMUNITY_OPTIONS.map((opt) => (
              <label key={opt} style={{ display: "flex", gap: "0.35rem", alignItems: "center" }}>
                <input
                  type="checkbox"
                  checked={form.community.includes(opt)}
                  onChange={() => setChecked("community", opt)}
                />
                {opt}
              </label>
            ))}
          </div>
        </div>
      </section>

      {/* Boolean tri-state */}
      <section style={sectionStyle}>
        <h2 style={sectionTitleStyle}>Flags</h2>
        <div
          style={{
            display: "flex",
            flexWrap: "wrap",
            gap: "1.5rem",
          }}
        >
          {(
            [
              ["isDomicileHolder", "Domicile holder"],
              ["isEnrolledInSchool", "Enrolled in school"],
              ["isGirlChildOfHof", "Girl child of HoF"],
            ] as const
          ).map(([key, label]) => (
            <label key={key} style={labelStyle}>
              {label}
              <select
                value={form[key]}
                onChange={(e) => setTriState(key, e.target.value as TriState)}
                style={{ ...inputStyle, width: 160 }}
              >
                <option value="any">Any</option>
                <option value="yes">Yes</option>
                <option value="no">No</option>
              </select>
            </label>
          ))}
        </div>
      </section>

      <div style={{ marginBottom: "1.5rem" }}>
        <button
          type="button"
          onClick={onSimulate}
          disabled={status === "loading"}
          style={{
            padding: "0.5rem 1.25rem",
            fontSize: "1rem",
            cursor: status === "loading" ? "wait" : "pointer",
            border: "1px solid #333",
            borderRadius: 4,
            background: "#f5f5f5",
          }}
        >
          {status === "loading" ? "Simulating…" : "Simulate"}
        </button>
      </div>

      {status === "loading" && <p>Loading…</p>}

      {status === "error" && (
        <p style={{ color: "#b00020" }}>Fetch error: {error}</p>
      )}

      {status === "ok" && data && (
        <>
          <section style={{ marginBottom: "2rem" }}>
            <p style={{ margin: 0, color: "#666", fontSize: "0.9rem" }}>
              Eligible beneficiaries (scenario #{data.scenarioId})
            </p>
            <p
              style={{
                margin: "0.25rem 0 0",
                fontSize: "3rem",
                fontWeight: 600,
                lineHeight: 1.1,
              }}
            >
              {data.totalCount.toLocaleString("en-IN")}
            </p>
          </section>

          <section style={{ marginBottom: "2rem" }}>
            <h2 style={{ fontSize: "1.15rem", marginBottom: "0.75rem" }}>
              Breakdown by district / gender / age band
            </h2>
            {data.breakdown.length === 0 ? (
              <p style={{ color: "#666" }}>No breakdown rows returned.</p>
            ) : (
              <div style={{ overflowX: "auto" }}>
                <table
                  style={{
                    width: "100%",
                    borderCollapse: "collapse",
                    fontSize: "0.95rem",
                  }}
                >
                  <thead>
                    {table.getHeaderGroups().map((hg) => (
                      <tr key={hg.id}>
                        {hg.headers.map((header) => (
                          <th
                            key={header.id}
                            style={{
                              textAlign: "left",
                              padding: "0.5rem 0.75rem",
                              borderBottom: "2px solid #ccc",
                              background: "#f5f5f5",
                            }}
                          >
                            {flexRender(
                              header.column.columnDef.header,
                              header.getContext(),
                            )}
                          </th>
                        ))}
                      </tr>
                    ))}
                  </thead>
                  <tbody>
                    {table.getRowModel().rows.map((row) => (
                      <tr key={row.id}>
                        {row.getVisibleCells().map((cell) => (
                          <td
                            key={cell.id}
                            style={{
                              padding: "0.45rem 0.75rem",
                              borderBottom: "1px solid #e0e0e0",
                            }}
                          >
                            {flexRender(
                              cell.column.columnDef.cell,
                              cell.getContext(),
                            )}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section>
            <h2 style={{ fontSize: "1.15rem", marginBottom: "0.75rem" }}>
              Total count per district
            </h2>
            {chartData.length === 0 ? (
              <p style={{ color: "#666" }}>No district totals to chart.</p>
            ) : (
              <div style={{ width: "100%", height: 320 }}>
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={chartData} margin={{ top: 8, right: 16, left: 0, bottom: 48 }}>
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis
                      dataKey="district"
                      angle={-30}
                      textAnchor="end"
                      interval={0}
                      height={60}
                    />
                    <YAxis allowDecimals={false} />
                    <Tooltip />
                    <Bar dataKey="count" fill="#555" name="Count" />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            )}
          </section>
        </>
      )}
    </main>
  );
}
