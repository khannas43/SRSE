// Typed client for the SRSE decision-service seam (design doc §8.1 / CLAUDE.md #6).
// Full-ruleset evaluate model — caller sends the complete PredicateSpec on every call.
// Shaped like an ODM decision-service call so CP4BA/ODM can later fulfil it without
// changing this caller.

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

// /api/decision/** now requires a STATE_OFFICER-scoped bearer token (backend
// SecurityConfig). RajSewadwar SSO isn't wired up yet, so this fetches a mock
// token once per page load and caches it — see MockJwtIssuer on the backend.
let cachedToken: Promise<string> | null = null;

async function getAuthToken(): Promise<string> {
  if (!cachedToken) {
    cachedToken = fetch(`${API_BASE}/api/auth/mock-login`, { method: "POST" })
      .then(async (res) => {
        if (!res.ok) {
          throw new Error(`Mock login failed ${res.status}: ${await res.text()}`);
        }
        const body = (await res.json()) as { token: string };
        return body.token;
      })
      .catch((err) => {
        cachedToken = null; // let the next call retry instead of caching the failure
        throw err;
      });
  }
  return cachedToken;
}

async function authorizedFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const token = await getAuthToken();
  return fetch(input, {
    ...init,
    headers: { ...init.headers, Authorization: `Bearer ${token}` },
  });
}

export type Operator =
  | "EQ"
  | "NE"
  | "LT"
  | "LTE"
  | "GT"
  | "GTE"
  | "IN"
  | "NOT_IN"
  | "BETWEEN"
  | "IS_TRUE"
  | "IS_FALSE"
  | "IS_NULL"
  | "NOT_NULL";

export type PredicateNode = {
  type: "PREDICATE";
  fieldKey: string;
  operator: Operator;
  value: unknown;
};

export type GroupNode = {
  type: "GROUP";
  op: "AND" | "OR";
  children: Node[];
};

export type Node = GroupNode | PredicateNode;

export type PredicateSpec = { root: Node };

export type BreakdownRow = {
  district: string;
  gender: string;
  ageBand: string;
  count: number;
};

export type BreakdownDelta = {
  district: string;
  gender: string;
  ageBand: string;
  countA: number;
  countB: number;
  delta: number;
};

export type EvaluateRequest = {
  schemeId: string;
  name: string;
  ruleset: PredicateSpec;
  includeBreakdown: boolean;
};

export type EvaluateResponse = {
  scenarioId: number;
  totalCount: number;
  breakdown: BreakdownRow[];
};

export type ScenarioSummary = {
  id: number;
  name: string;
  schemeId: string;
  totalCount: number | null;
  createdAt: string;
};

export type ScenarioDetail = {
  id: number;
  name: string;
  schemeId: string;
  ruleset: PredicateSpec;
  totalCount: number | null;
  breakdown: BreakdownRow[];
  createdAt: string;
};

export type CompareResponse = {
  scenarioA: ScenarioSummary;
  scenarioB: ScenarioSummary;
  totalCountDelta: number;
  breakdownDeltas: BreakdownDelta[];
};

export async function evaluate(req: EvaluateRequest): Promise<EvaluateResponse> {
  const res = await authorizedFetch(`${API_BASE}/api/decision/evaluate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    throw new Error(`Decision service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

export async function listScenarios(schemeId: string): Promise<ScenarioSummary[]> {
  const res = await authorizedFetch(
    `${API_BASE}/api/decision/scenarios?schemeId=${encodeURIComponent(schemeId)}`,
    { credentials: "include" },
  );
  if (!res.ok) {
    throw new Error(`Decision service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

export async function getScenario(id: number): Promise<ScenarioDetail> {
  const res = await authorizedFetch(`${API_BASE}/api/decision/scenarios/${id}`, {
    credentials: "include",
  });
  if (!res.ok) {
    throw new Error(`Decision service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

export async function compare(a: number, b: number): Promise<CompareResponse> {
  const res = await authorizedFetch(
    `${API_BASE}/api/decision/compare?a=${encodeURIComponent(String(a))}&b=${encodeURIComponent(String(b))}`,
    { credentials: "include" },
  );
  if (!res.ok) {
    throw new Error(`Decision service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}
