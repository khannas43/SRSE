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

function hubRow(column: string, extra: string[] = []): CriterionRowModel {
  return {
    ref: ref(TABLE_A),
    column,
    extraColumns: extra,
    fuzzyThresholdPercent: 85,
    mode: "COMBINE",
    separator: " ",
  };
}

function targetRow(t: TableRef, column: string): CriterionRowModel {
  return {
    ref: ref(t),
    column,
    extraColumns: [],
    fuzzyThresholdPercent: 80,
    mode: "COMBINE",
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
