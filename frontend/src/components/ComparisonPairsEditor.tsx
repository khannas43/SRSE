"use client";

import type { RegisteredColumn, TableRef } from "@/lib/analysisApi";
import {
  comparisonPairIsFuzzy,
  createComparisonPairRow,
  type ComparisonPairRow,
} from "@/lib/analysisCriterionModel";

/** Caps the number of comparison groups, mirroring the server-side limit. */
export const MAX_COMPARISON_GROUPS = 8;

const fieldLabelStyle = { display: "block", marginBottom: "0.3rem", fontSize: "0.82rem" } as const;

/**
 * The post-join comparison rows, shared by the two-table section and each
 * multi-target block. One editor, two call sites — a second copy would drift
 * from this one the first time the fuzzy rule or the caps changed.
 */
export function ComparisonPairsEditor({
  pairs,
  onChange,
  sourceRef,
  targetRef,
  sourceColumns,
  targetColumns,
  registeredFuzzyFor,
  defaultThreshold,
}: Readonly<{
  pairs: ComparisonPairRow[];
  onChange: (next: ComparisonPairRow[]) => void;
  sourceRef: TableRef | undefined;
  targetRef: TableRef | undefined;
  sourceColumns: RegisteredColumn[];
  targetColumns: RegisteredColumn[];
  registeredFuzzyFor: (ref: TableRef, column: string) => boolean | null;
  defaultThreshold: number;
}>) {
  return (
    <>
          <ul style={{ listStyle: "none", padding: 0, margin: 0 }}>
            {pairs.map((pair) => {
              const showFuzzyCompare = comparisonPairIsFuzzy(sourceRef, targetRef, pair, registeredFuzzyFor);
              return (
                <li
                  key={pair.id}
                  style={{
                    display: "flex",
                    gap: "0.5rem",
                    flexWrap: "wrap",
                    alignItems: "flex-end",
                    marginBottom: "0.5rem",
                  }}
                >
                  <div style={{ flex: "1 1 160px" }}>
                    <span className="srse-text-muted" style={fieldLabelStyle}>
                      Source column
                    </span>
                    <select
                      className="srse-select"
                      style={{ width: "100%" }}
                      value={pair.sourceColumn}
                      onChange={(e) =>
                        onChange(
                          pairs.map((r) =>
                            r.id === pair.id ? { ...r, sourceColumn: e.target.value } : r,
                          ),
                        )
                      }
                    >
                      <option value="">—</option>
                      {sourceColumns.map((c) => (
                        <option key={c.name} value={c.name}>
                          {c.name}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div style={{ flex: "1 1 160px" }}>
                    <span className="srse-text-muted" style={fieldLabelStyle}>
                      Target column
                    </span>
                    <select
                      className="srse-select"
                      style={{ width: "100%" }}
                      value={pair.targetColumn}
                      onChange={(e) =>
                        onChange(
                          pairs.map((r) =>
                            r.id === pair.id ? { ...r, targetColumn: e.target.value } : r,
                          ),
                        )
                      }
                    >
                      <option value="">—</option>
                      {targetColumns.map((c) => (
                        <option key={c.name} value={c.name}>
                          {c.name}
                        </option>
                      ))}
                    </select>
                  </div>
                  {showFuzzyCompare && (
                    <div style={{ flex: "0 1 100px" }}>
                      <span className="srse-text-muted" style={fieldLabelStyle}>
                        Fuzzy match %
                      </span>
                      <input
                        type="number"
                        className="srse-input"
                        style={{ width: "100%" }}
                        min={0}
                        max={100}
                        value={pair.fuzzyThresholdPercent}
                        onChange={(e) =>
                          onChange(
                            pairs.map((r) =>
                              r.id === pair.id
                                ? { ...r, fuzzyThresholdPercent: Number(e.target.value) }
                                : r,
                            ),
                          )
                        }
                        title="Similarity threshold for this comparison (not an exact equality check)"
                      />
                    </div>
                  )}
                  <button
                    type="button"
                    className="srse-btn srse-btn-ghost srse-btn-sm"
                    onClick={() => onChange(pairs.filter((r) => r.id !== pair.id))}
                  >
                    Remove
                  </button>
                </li>
              );
            })}
          </ul>
          <button
            type="button"
            className="srse-btn srse-btn-ghost srse-btn-sm"
            disabled={pairs.length >= MAX_COMPARISON_GROUPS}
            onClick={() =>
              onChange([...pairs, createComparisonPairRow(defaultThreshold)])
            }
          >
            + Add comparison ({pairs.length}/{MAX_COMPARISON_GROUPS})
          </button>
    </>
  );
}
