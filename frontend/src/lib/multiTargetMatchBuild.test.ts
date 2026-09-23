import { describe, expect, it } from "vitest";
import type { TableRef } from "@/lib/analysisApi";
import type { CriterionRowModel } from "@/lib/analysisCriterionModel";
import {
  joinCanvasFromForm,
  joinCanvasToFormModels,
  reorderJoinCanvasSlots,
} from "@/lib/joinCanvasModel";
import {
  buildMultiTargetRecordMatchRequest,
  stableMultiTargetRequestJson,
} from "@/lib/multiTargetMatchBuild";

const TABLE_A: TableRef = { catalog: "iceberg_gold", schema: "golden_layer", table: "hub_tbl" };
const TABLE_B: TableRef = { catalog: "iceberg_silver", schema: "silver_layer", table: "bank_txn" };
const TABLE_C: TableRef = { catalog: "iceberg_silver", schema: "silver_layer", table: "ration_card" };

function ref(t: TableRef) {
  return { ...t };
}

function hubRow(
  column: string,
  extra: string[] = [],
  mode: CriterionRowModel["mode"] = "COMBINE",
): CriterionRowModel {
  return {
    ref: ref(TABLE_A),
    column,
    extraColumns: extra,
    fuzzyThresholdPercent: 85,
    mode,
    separator: " ",
  };
}

function targetRow(
  t: TableRef,
  column: string,
  extra: string[] = [],
  mode: CriterionRowModel["mode"] = "COMBINE",
): CriterionRowModel {
  return {
    ref: ref(t),
    column,
    extraColumns: extra,
    fuzzyThresholdPercent: 80,
    mode,
    separator: " ",
  };
}

const noopFuzzy = () => false;

describe("multi-target request serialization", () => {
  it("canvas and form produce byte-identical JSON for the same configuration", () => {
    const hubRows = [hubRow("full_name"), hubRow("aadhaar")];
    const targetBlocks = [
      {
        id: "t1",
        label: "Bank",
        joinRows: [targetRow(TABLE_B, "account_holder"), targetRow(TABLE_B, "uid")],
        displayRows: [],
      },
      {
        id: "t2",
        label: "Ration",
        joinRows: [targetRow(TABLE_C, "member_name"), targetRow(TABLE_C, "aadhaar_no")],
        displayRows: [],
      },
    ];
    const extras = {
      highlightDuplicates: true,
      dedup: null,
      ageFilter: null,
      registeredFuzzyFor: noopFuzzy,
      isFuzzyMatchable: noopFuzzy,
    };
    const fromForm = buildMultiTargetRecordMatchRequest({
      hubRows,
      hubDisplayRows: [],
      hubSide: "SOURCE",
      targets: targetBlocks.map(({ label, joinRows, displayRows }) => ({ label, joinRows, displayRows })),
      ...extras,
    });
    const canvas = joinCanvasFromForm("SOURCE", hubRows, [], targetBlocks, ref(TABLE_A));
    const { hubRows: cHub, hubDisplayRows, targets } = joinCanvasToFormModels(canvas);
    const fromCanvas = buildMultiTargetRecordMatchRequest({
      hubRows: cHub,
      hubDisplayRows,
      hubSide: canvas.hubSide,
      targets,
      ...extras,
    });
    expect(fromForm).not.toBeNull();
    expect(fromCanvas).not.toBeNull();
    expect(stableMultiTargetRequestJson(fromForm!)).toBe(stableMultiTargetRequestJson(fromCanvas!));
  });

  it("canvas and form match for COMBINE hub and ANY_OF target folding", () => {
    const hubRows = [hubRow("first_name", ["last_name"], "COMBINE")];
    const targetBlocks = [
      {
        id: "t1",
        label: "Bank",
        joinRows: [targetRow(TABLE_B, "cand_a", ["cand_b"], "ANY_OF")],
        displayRows: [],
      },
    ];
    const extras = {
      highlightDuplicates: false,
      dedup: null,
      ageFilter: null,
      registeredFuzzyFor: noopFuzzy,
      isFuzzyMatchable: noopFuzzy,
    };
    const fromForm = buildMultiTargetRecordMatchRequest({
      hubRows,
      hubDisplayRows: [],
      hubSide: "SOURCE",
      targets: targetBlocks.map(({ label, joinRows, displayRows }) => ({ label, joinRows, displayRows })),
      ...extras,
    });
    const canvas = joinCanvasFromForm("SOURCE", hubRows, [], targetBlocks, ref(TABLE_A));
    const { hubRows: cHub, hubDisplayRows, targets } = joinCanvasToFormModels(canvas);
    const fromCanvas = buildMultiTargetRecordMatchRequest({
      hubRows: cHub,
      hubDisplayRows,
      hubSide: canvas.hubSide,
      targets,
      ...extras,
    });
    expect(stableMultiTargetRequestJson(fromForm!)).toBe(stableMultiTargetRequestJson(fromCanvas!));
    expect(fromForm!.targets[0].joinGroups?.length).toBeGreaterThan(0);
  });

  it("emits each target's own comparison groups against its own table", () => {
    const hubRows = [hubRow("m_id")];
    const targetBlocks = [
      {
        id: "t1",
        label: "Bank",
        joinRows: [targetRow(TABLE_B, "m_id")],
        displayRows: [],
        comparisonPairs: [
          { id: "c1", sourceColumn: "district", targetColumn: "district", fuzzyThresholdPercent: 80 },
        ],
      },
      {
        id: "t2",
        label: "Ration",
        joinRows: [targetRow(TABLE_C, "m_id")],
        displayRows: [],
        comparisonPairs: [
          { id: "c2", sourceColumn: "pan", targetColumn: "pan_no", fuzzyThresholdPercent: 80 },
        ],
      },
    ];
    const req = buildMultiTargetRecordMatchRequest({
      hubRows,
      hubDisplayRows: [],
      hubSide: "SOURCE",
      targets: targetBlocks.map(({ label, joinRows, displayRows, comparisonPairs }) => ({
        label,
        joinRows,
        displayRows,
        comparisonPairs,
      })),
      highlightDuplicates: false,
      dedup: null,
      ageFilter: null,
      registeredFuzzyFor: noopFuzzy,
      isFuzzyMatchable: noopFuzzy,
    })!;
    expect(req.targets[0].comparisonGroups?.[0].target[0].table).toBe(TABLE_B.table);
    expect(req.targets[0].comparisonGroups?.[0].source[0].table).toBe(TABLE_A.table);
    expect(req.targets[1].comparisonGroups?.[0].target[0].column).toBe("pan_no");
    // the hub is the source side of every target's comparison
    expect(req.targets[1].comparisonGroups?.[0].source[0].table).toBe(TABLE_A.table);
  });

  it("sets mismatchOnly only when a target actually compares something", () => {
    const base = {
      hubRows: [hubRow("m_id")],
      hubDisplayRows: [],
      hubSide: "SOURCE" as const,
      highlightDuplicates: false,
      dedup: null,
      ageFilter: null,
      registeredFuzzyFor: noopFuzzy,
      isFuzzyMatchable: noopFuzzy,
      mismatchOnly: true,
    };
    const noCompare = buildMultiTargetRecordMatchRequest({
      ...base,
      targets: [{ label: "Bank", joinRows: [targetRow(TABLE_B, "m_id")], displayRows: [] }],
    })!;
    expect(noCompare.mismatchOnly).toBeUndefined();

    const withCompare = buildMultiTargetRecordMatchRequest({
      ...base,
      targets: [
        {
          label: "Bank",
          joinRows: [targetRow(TABLE_B, "m_id")],
          displayRows: [],
          comparisonPairs: [
            { id: "c1", sourceColumn: "district", targetColumn: "district", fuzzyThresholdPercent: 80 },
          ],
        },
      ],
    })!;
    expect(withCompare.mismatchOnly).toBe(true);
  });

  it("canvas round-trip preserves per-target comparisons byte-identically", () => {
    const hubRows = [hubRow("m_id")];
    const targetBlocks = [
      {
        id: "t1",
        label: "Bank",
        joinRows: [targetRow(TABLE_B, "m_id")],
        displayRows: [],
        comparisonPairs: [
          { id: "c1", sourceColumn: "full_name", targetColumn: "full_name", fuzzyThresholdPercent: 85 },
        ],
      },
    ];
    const extras = {
      highlightDuplicates: false,
      dedup: null,
      ageFilter: null,
      registeredFuzzyFor: noopFuzzy,
      isFuzzyMatchable: noopFuzzy,
    };
    const fromForm = buildMultiTargetRecordMatchRequest({
      hubRows,
      hubDisplayRows: [],
      hubSide: "SOURCE",
      targets: targetBlocks.map(({ label, joinRows, displayRows, comparisonPairs }) => ({
        label,
        joinRows,
        displayRows,
        comparisonPairs,
      })),
      ...extras,
    })!;
    const canvas = joinCanvasFromForm("SOURCE", hubRows, [], targetBlocks, ref(TABLE_A));
    const { hubRows: cHub, hubDisplayRows, targets } = joinCanvasToFormModels(canvas);
    const fromCanvas = buildMultiTargetRecordMatchRequest({
      hubRows: cHub,
      hubDisplayRows,
      hubSide: canvas.hubSide,
      targets,
      ...extras,
    })!;
    // a fuzzy name comparison must survive the trip with its threshold
    expect(fromCanvas.targets[0].comparisonGroups?.[0].fuzzyThresholdPercent).toBe(85);
    expect(stableMultiTargetRequestJson(fromForm)).toBe(stableMultiTargetRequestJson(fromCanvas));
  });

  it("reordering canvas slots reorders hubCriteria and every target joinCriteria together", () => {
    const hubRows = [hubRow("c1"), hubRow("c2"), hubRow("c3")];
    const targetBlocks = [
      {
        id: "t1",
        label: "One",
        joinRows: [targetRow(TABLE_B, "x1"), targetRow(TABLE_B, "x2"), targetRow(TABLE_B, "x3")],
        displayRows: [],
      },
    ];
    const canvas = joinCanvasFromForm("SOURCE", hubRows, [], targetBlocks, ref(TABLE_A));
    const reordered = reorderJoinCanvasSlots(canvas, 0, 2);
    const { hubRows: newHub, targets } = joinCanvasToFormModels(reordered);
    expect(newHub.map((r) => r.column)).toEqual(["c2", "c3", "c1"]);
    expect(targets[0].joinRows.map((r) => r.column)).toEqual(["x2", "x3", "x1"]);
    const req = buildMultiTargetRecordMatchRequest({
      hubRows: newHub,
      hubDisplayRows: [],
      hubSide: "SOURCE",
      targets,
      highlightDuplicates: false,
      dedup: null,
      ageFilter: null,
      registeredFuzzyFor: noopFuzzy,
      isFuzzyMatchable: noopFuzzy,
    });
    expect(req!.hubCriteria.map((c) => c.column)).toEqual(["c2", "c3", "c1"]);
    expect(req!.targets[0].joinCriteria.map((c) => c.column)).toEqual(["x2", "x3", "x1"]);
  });
});
