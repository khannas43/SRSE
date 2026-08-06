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
  type PredicateSpec,
} from "@/lib/decisionApi";

function buildEkalNaariRuleset(ageMin: number, incomeCeiling: number): PredicateSpec {
  return {
    root: {
      type: "GROUP",
      op: "AND",
      children: [
        {
          type: "PREDICATE",
          fieldKey: "marital_status",
          operator: "EQ",
          value: "DIVORCED",
        },
        {
          type: "PREDICATE",
          fieldKey: "gender",
          operator: "EQ",
          value: "FEMALE",
        },
        {
          type: "PREDICATE",
          fieldKey: "age_years",
          operator: "GTE",
          value: ageMin,
        },
        {
          type: "PREDICATE",
          fieldKey: "is_domicile_holder",
          operator: "IS_TRUE",
          value: null,
        },
        {
          type: "GROUP",
          op: "OR",
          children: [
            {
              type: "PREDICATE",
              fieldKey: "annual_income_total",
              operator: "LT",
              value: incomeCeiling,
            },
            {
              type: "PREDICATE",
              fieldKey: "ration_card_category",
              operator: "IN",
              value: ["BPL", "ANTYODAYA"],
            },
            {
              type: "PREDICATE",
              fieldKey: "community",
              operator: "IN",
              value: ["SAHARIYA", "KATHODI", "KHAIRWA"],
            },
          ],
        },
      ],
    },
  };
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
  width: 140,
};

const labelStyle: CSSProperties = {
  display: "flex",
  flexDirection: "column",
  gap: "0.35rem",
  fontSize: "0.9rem",
};

export default function EkalNaariPage() {
  const [ageMin, setAgeMin] = useState(18);
  const [incomeCeiling, setIncomeCeiling] = useState(48000);
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

  async function onSimulate() {
    setStatus("loading");
    setError(null);
    setData(null);

    const ruleset = buildEkalNaariRuleset(ageMin, incomeCeiling);
    const name = `Ekal Naari — age>=${ageMin}, income<${incomeCeiling}`;

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

      <p style={{ color: "#444", maxWidth: 640 }}>
        Adjust age and income thresholds, then simulate against the beneficiary
        cohort. Rules mirror the official Ekal Naari worked example (marital
        status, gender, domicile, income with BPL/Antyodaya and tribal
        community exemptions).
      </p>

      <div
        style={{
          display: "flex",
          flexWrap: "wrap",
          gap: "1.25rem",
          alignItems: "flex-end",
          marginBottom: "1.5rem",
        }}
      >
        <label style={labelStyle}>
          Minimum age (years)
          <input
            type="number"
            value={ageMin}
            min={0}
            onChange={(e) => setAgeMin(Number(e.target.value))}
            style={inputStyle}
          />
        </label>

        <label style={labelStyle}>
          Income ceiling (₹)
          <input
            type="number"
            value={incomeCeiling}
            min={0}
            step={1000}
            onChange={(e) => setIncomeCeiling(Number(e.target.value))}
            style={inputStyle}
          />
        </label>

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
