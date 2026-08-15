// Rule-builder state (Zustand). Holds the full nested Ast.Node tree the
// officer is composing — root is always a GroupNode so predicates and
// sub-rule groups can always be added directly under it. Tree mutations are
// addressed by `path`: an array of child indices walked from the root
// (an empty path means "the root group itself").

import { create } from "zustand";
import type { GroupNode, Node, PredicateNode } from "@/lib/decisionApi";

export type NodePath = number[];

function emptyGroup(op: "AND" | "OR" = "AND"): GroupNode {
  return { type: "GROUP", op, children: [] };
}

/** Rebuild the children array of the group found at `path`, top-down, immutably. */
function updateGroupChildren(
  node: Node,
  path: NodePath,
  mutate: (children: Node[]) => Node[],
): Node {
  if (node.type !== "GROUP") {
    throw new Error("Path does not point to a group node");
  }
  if (path.length === 0) {
    return { ...node, children: mutate(node.children) };
  }
  const [index, ...rest] = path;
  return {
    ...node,
    children: node.children.map((child, i) =>
      i === index ? updateGroupChildren(child, rest, mutate) : child,
    ),
  };
}

/** Replace the node found at `path` in place, immutably. */
function replaceAtPath(node: Node, path: NodePath, updater: (n: Node) => Node): Node {
  if (path.length === 0) {
    return updater(node);
  }
  if (node.type !== "GROUP") {
    throw new Error("Path does not point to a group node");
  }
  const [index, ...rest] = path;
  return {
    ...node,
    children: node.children.map((child, i) =>
      i === index ? replaceAtPath(child, rest, updater) : child,
    ),
  };
}

function parentAndIndex(path: NodePath): { parentPath: NodePath; index: number } {
  if (path.length === 0) {
    throw new Error("Root node has no parent");
  }
  return { parentPath: path.slice(0, -1), index: path.at(-1)! };
}

interface RuleBuilderState {
  name: string;
  schemeIds: number[];
  root: GroupNode;
  setName: (name: string) => void;
  setSchemeIds: (schemeIds: number[]) => void;
  toggleSchemeId: (schemeId: number) => void;
  addPredicate: (path: NodePath, predicate: PredicateNode) => void;
  addSubGroup: (path: NodePath, op?: "AND" | "OR") => void;
  addGroup: (path: NodePath, op: "AND" | "OR", children: Node[]) => void;
  updateNode: (path: NodePath, patch: Partial<PredicateNode>) => void;
  setGroupOp: (path: NodePath, op: "AND" | "OR") => void;
  removeNode: (path: NodePath) => void;
  moveNode: (path: NodePath, direction: "up" | "down") => void;
  reset: () => void;
}

export const useRuleBuilder = create<RuleBuilderState>((set) => ({
  name: "",
  schemeIds: [],
  root: emptyGroup(),

  setName: (name) => set({ name }),
  setSchemeIds: (schemeIds) => set({ schemeIds }),
  toggleSchemeId: (schemeId) =>
    set((s) => ({
      schemeIds: s.schemeIds.includes(schemeId)
        ? s.schemeIds.filter((id) => id !== schemeId)
        : [...s.schemeIds, schemeId],
    })),

  addPredicate: (path, predicate) =>
    set((s) => ({
      root: updateGroupChildren(s.root, path, (children) => [...children, predicate]) as GroupNode,
    })),

  addSubGroup: (path, op = "AND") =>
    set((s) => ({
      root: updateGroupChildren(s.root, path, (children) => [
        ...children,
        emptyGroup(op),
      ]) as GroupNode,
    })),

  // Appends a fully-formed group (not an empty one) as a single child — used
  // by composite UI controls (e.g. income + exemption) that build a whole
  // OR/AND sub-tree in one action, so the caller never needs to know the new
  // sub-group's path to then add children into it separately.
  addGroup: (path, op, children) =>
    set((s) => ({
      root: updateGroupChildren(s.root, path, (kids) => [
        ...kids,
        { type: "GROUP", op, children } as GroupNode,
      ]) as GroupNode,
    })),

  updateNode: (path, patch) =>
    set((s) => ({
      root: replaceAtPath(s.root, path, (node) =>
        node.type === "PREDICATE" ? { ...node, ...patch } : node,
      ) as GroupNode,
    })),

  setGroupOp: (path, op) =>
    set((s) => ({
      root: replaceAtPath(s.root, path, (node) =>
        node.type === "GROUP" ? { ...node, op } : node,
      ) as GroupNode,
    })),

  removeNode: (path) =>
    set((s) => {
      const { parentPath, index } = parentAndIndex(path);
      return {
        root: updateGroupChildren(s.root, parentPath, (children) =>
          children.filter((_, i) => i !== index),
        ) as GroupNode,
      };
    }),

  moveNode: (path, direction) =>
    set((s) => {
      const { parentPath, index } = parentAndIndex(path);
      const swapWith = direction === "up" ? index - 1 : index + 1;
      return {
        root: updateGroupChildren(s.root, parentPath, (children) => {
          if (swapWith < 0 || swapWith >= children.length) return children;
          const next = [...children];
          [next[index], next[swapWith]] = [next[swapWith], next[index]];
          return next;
        }) as GroupNode,
      };
    }),

  reset: () => set({ name: "", schemeIds: [], root: emptyGroup() }),
}));
