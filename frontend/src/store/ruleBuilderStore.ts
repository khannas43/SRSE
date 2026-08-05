// Rule-builder state (Zustand). Holds the ruleset the officer is composing,
// including threshold overrides. Kept deliberately minimal in the skeleton;
// AND/OR grouping and validation are the first UI build task (design doc §8.4).

import { create } from "zustand";
import type { PredicateParam } from "@/lib/decisionApi";

interface RuleBuilderState {
  schemeId: string | null;
  rulesetVersion: string;              // "draft" until saved
  predicates: PredicateParam[];
  setScheme: (schemeId: string) => void;
  addPredicate: (p: PredicateParam) => void;
  updatePredicate: (index: number, p: Partial<PredicateParam>) => void;
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
