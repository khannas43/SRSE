// Rule-builder state (Zustand). Holds the ruleset the officer is composing.
// Kept deliberately minimal in the skeleton; the Ekal Naari page builds its
// PredicateSpec inline. AND/OR grouping UI is a later polish task.

import { create } from "zustand";
import type { PredicateNode } from "@/lib/decisionApi";

interface RuleBuilderState {
  schemeId: string | null;
  rulesetVersion: string; // "draft" until saved
  predicates: PredicateNode[];
  setScheme: (schemeId: string) => void;
  addPredicate: (p: PredicateNode) => void;
  updatePredicate: (index: number, p: Partial<PredicateNode>) => void;
  removePredicate: (index: number) => void;
  reset: () => void;
}

export const useRuleBuilder = create<RuleBuilderState>((set) => ({
  schemeId: null,
  rulesetVersion: "draft",
  predicates: [],
  setScheme: (schemeId) => set({ schemeId, predicates: [], rulesetVersion: "draft" }),
  addPredicate: (p) => set((s) => ({ predicates: [...s.predicates, p] })),
  updatePredicate: (index, p) =>
    set((s) => ({
      predicates: s.predicates.map((old, i) => (i === index ? { ...old, ...p } : old)),
    })),
  removePredicate: (index) =>
    set((s) => ({ predicates: s.predicates.filter((_, i) => i !== index) })),
  reset: () => set({ schemeId: null, rulesetVersion: "draft", predicates: [] }),
}));
