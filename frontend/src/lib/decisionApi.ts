// Typed client for the SRSE decision-service seam (design doc §8.1).
// This mirrors the backend REST contract exactly. The contract is shaped like
// an ODM decision-service call so it can later be fulfilled by CP4BA/ODM with
// no change to this caller.

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

export type Operator =
  | "EQ" | "NE" | "LT" | "LTE" | "GT" | "GTE"
  | "IN" | "NOT_IN" | "BETWEEN"
  | "IS_TRUE" | "IS_FALSE" | "IS_NULL" | "NOT_NULL";

export interface PredicateParam {
  fieldKey: string;
  operator: Operator;
  value: unknown;
}

export interface EvaluateRequest {
  schemeId: string;
  rulesetVersion: string;           // or "draft"
  parameters: PredicateParam[];      // officer overrides
  options: {
    includeBreakdown: boolean;
    includeCohort: boolean;
    cohortLimit: number;
  };
}

export interface BreakdownRow {
  district: string;
  gender: string;
  ageBand: string;
  n: number;
}

export interface EvaluateResponse {
  totalCount: number;
  breakdown: BreakdownRow[];
  cohortSample?: Record<string, unknown>[];
  dataFreshness: { lastRefreshedAt: string };
}

export async function evaluate(req: EvaluateRequest): Promise<EvaluateResponse> {
  const res = await fetch(`${API_BASE}/api/decision/evaluate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include", // cookie-based JWT
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    throw new Error(`Decision service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}
