"use client";

import type { CSSProperties } from "react";
import type { FieldCatalogEntry, GroupNode, Node, Operator, PredicateNode } from "@/lib/decisionApi";
import { useRuleBuilder, type NodePath } from "@/store/ruleBuilderStore";

const OPERATOR_LABEL: Record<Operator, string> = {
  EQ: "=",
  NE: "≠",
  LT: "<",
  LTE: "≤",
  GT: ">",
  GTE: "≥",
  IN: "in",
  NOT_IN: "not in",
  BETWEEN: "between",
  IS_TRUE: "is",
  IS_FALSE: "is",
  IS_NULL: "is unset",
  NOT_NULL: "is set",
  FUZZY_MATCH: "≈",
};

function formatValue(p: PredicateNode): string {
  if (p.operator === "BETWEEN" && Array.isArray(p.value)) {
    return `${p.value[0]}–${p.value[1]}`;
  }
  if ((p.operator === "IN" || p.operator === "NOT_IN") && Array.isArray(p.value)) {
    return p.value.join(", ");
  }
  if (p.operator === "FUZZY_MATCH" && Array.isArray(p.value)) {
    const [name, thresholdPct] = p.value as [string, number];
    return `"${name}" (≥${thresholdPct}%)`;
  }
  if (p.operator === "IS_TRUE") return "Yes";
  if (p.operator === "IS_FALSE") return "No";
  if (p.operator === "IS_NULL" || p.operator === "NOT_NULL") return "";
  return String(p.value);
}

function formatPredicate(p: PredicateNode, fields: FieldCatalogEntry[]): string {
  const label = fields.find((f) => f.fieldKey === p.fieldKey)?.displayLabel ?? p.fieldKey;
  const value = formatValue(p);
  return value ? `${label} ${OPERATOR_LABEL[p.operator]} ${value}` : `${label} ${OPERATOR_LABEL[p.operator]}`;
}

const groupStyle = (depth: number): CSSProperties => ({
  border: "1px solid var(--srse-border)",
  borderRadius: "var(--srse-radius-sm)",
  padding: "0.85rem 1rem",
  marginLeft: depth * 20,
  marginBottom: "0.75rem",
  background: depth % 2 === 0 ? "var(--srse-surface)" : "var(--srse-bg)",
});

const chipStyle: CSSProperties = {
  display: "inline-flex",
  alignItems: "center",
  gap: "0.4rem",
  padding: "0.3rem 0.7rem",
  border: "1px solid var(--srse-border-strong)",
  borderRadius: 999,
  background: "var(--srse-surface)",
  fontSize: "0.83rem",
  marginRight: "0.5rem",
  marginBottom: "0.5rem",
};

export function RuleGroupEditor({
  node,
  path,
  fields,
  targetPath,
  onSetTarget,
  depth = 0,
}: Readonly<{
  node: GroupNode;
  path: NodePath;
  fields: FieldCatalogEntry[];
  targetPath: NodePath;
  onSetTarget: (path: NodePath) => void;
  depth?: number;
}>) {
  const setGroupOp = useRuleBuilder((s) => s.setGroupOp);
  const addSubGroup = useRuleBuilder((s) => s.addSubGroup);
  const removeNode = useRuleBuilder((s) => s.removeNode);
  const moveNode = useRuleBuilder((s) => s.moveNode);
  const isRoot = path.length === 0;
  const isTarget = path.join(",") === targetPath.join(",");

  return (
    <div
      style={{
        ...groupStyle(depth),
        boxShadow: isTarget ? "0 0 0 2px var(--srse-primary)" : "none",
      }}
    >
      <div style={{ display: "flex", alignItems: "center", gap: "0.6rem", marginBottom: "0.6rem", flexWrap: "wrap" }}>
        <strong style={{ fontSize: "0.78rem", color: "var(--srse-text-muted)", textTransform: "uppercase", letterSpacing: "0.03em" }}>
          {isRoot ? "Match" : "Sub-rule — match"}
        </strong>
        <select
          value={node.op}
          onChange={(e) => setGroupOp(path, e.target.value as "AND" | "OR")}
          className="srse-select srse-btn-sm"
        >
          <option value="AND">ALL of (AND)</option>
          <option value="OR">ANY of (OR)</option>
        </select>
        <button
          type="button"
          className={isTarget ? "srse-btn srse-btn-sm srse-btn-primary" : "srse-btn srse-btn-sm"}
          onClick={() => onSetTarget(path)}
        >
          {isTarget ? "✓ Adding params here" : "Add params here"}
        </button>
        <button type="button" className="srse-btn srse-btn-sm" onClick={() => addSubGroup(path, "AND")}>
          + Add sub-rule
        </button>
        {!isRoot && (
          <>
            <button type="button" className="srse-btn srse-btn-ghost srse-btn-sm" onClick={() => moveNode(path, "up")}>
              ↑
            </button>
            <button type="button" className="srse-btn srse-btn-ghost srse-btn-sm" onClick={() => moveNode(path, "down")}>
              ↓
            </button>
            <button type="button" className="srse-btn srse-btn-danger srse-btn-sm" onClick={() => removeNode(path)}>
              Remove sub-rule
            </button>
          </>
        )}
      </div>

      {node.children.length === 0 && (
        <p className="srse-text-muted" style={{ margin: "0.25rem 0" }}>
          No conditions yet — add parameters from the palette on the left.
        </p>
      )}

      <div>
        {node.children.map((child, index) => {
          const childPath = [...path, index];
          const childKey = childPath.join("-");
          return child.type === "PREDICATE" ? (
            <PredicateChip
              key={childKey}
              predicate={child}
              path={childPath}
              fields={fields}
              siblingCount={node.children.length}
              indexInParent={index}
            />
          ) : (
            <RuleGroupEditor
              key={childKey}
              node={child}
              path={childPath}
              fields={fields}
              targetPath={targetPath}
              onSetTarget={onSetTarget}
              depth={depth + 1}
            />
          );
        })}
      </div>
    </div>
  );
}

function PredicateChip({
  predicate,
  path,
  fields,
  siblingCount,
  indexInParent,
}: Readonly<{
  predicate: PredicateNode;
  path: NodePath;
  fields: FieldCatalogEntry[];
  siblingCount: number;
  indexInParent: number;
}>) {
  const removeNode = useRuleBuilder((s) => s.removeNode);
  const moveNode = useRuleBuilder((s) => s.moveNode);

  return (
    <span style={chipStyle}>
      {formatPredicate(predicate, fields)}
      {indexInParent > 0 && (
        <button type="button" className="srse-btn srse-btn-ghost srse-btn-sm" onClick={() => moveNode(path, "up")}>
          ↑
        </button>
      )}
      {indexInParent < siblingCount - 1 && (
        <button type="button" className="srse-btn srse-btn-ghost srse-btn-sm" onClick={() => moveNode(path, "down")}>
          ↓
        </button>
      )}
      <button type="button" className="srse-btn srse-btn-danger srse-btn-sm" onClick={() => removeNode(path)}>
        ×
      </button>
    </span>
  );
}

export function countPredicates(node: Node): number {
  if (node.type === "PREDICATE") return 1;
  return node.children.reduce((sum, child) => sum + countPredicates(child), 0);
}
