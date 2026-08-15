// Typed client for the SRSE decision-service seam (design doc §8.1 / CLAUDE.md #6).
// Full-ruleset preview/save model — caller sends the complete PredicateSpec on every call.
// Shaped like an ODM decision-service call so CP4BA/ODM can later fulfil it without
// changing this caller.

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

// /api/decision/**, /api/schemes/** and /api/metadata/** now require a
// STATE_OFFICER-scoped bearer token (backend SecurityConfig). RajSewadwar SSO
// isn't wired up yet, so this fetches a mock token once per page load and
// caches it — see MockJwtIssuer on the backend.
let cachedToken: Promise<string> | null = null;

async function getAuthToken(): Promise<string> {
  cachedToken ??= fetch(`${API_BASE}/api/auth/mock-login`, { method: "POST" })
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
  | "NOT_NULL"
  | "FUZZY_MATCH";

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

export type FieldTier = "TIER_1" | "TIER_2" | "TIER_3";
export type FieldDataType = "NUMBER" | "STRING" | "BOOLEAN" | "DATE";

export type FieldCatalogEntry = {
  id: number;
  fieldKey: string;
  displayLabel: string;
  tier: FieldTier;
  dataType: FieldDataType;
  groupName: string;
  allowedValues: string[];
  fuzzyMatchable: boolean;
};

export type FieldCatalogRequest = {
  fieldKey: string;
  displayLabel: string;
  tier: FieldTier;
  dataType: FieldDataType;
  groupName: string;
  allowedValues: string[];
  fuzzyMatchable: boolean;
};

export type DataMode = "SYNTHETIC" | "LIVE";

export type ConnectionPlaneInfo = {
  jdbcUrl: string;
  username: string;
  driverClassName: string;
  status: string;
};

export type ConnectionsInfo = {
  dataMode: string;
  operational: ConnectionPlaneInfo;
  analytical: ConnectionPlaneInfo;
};

export type MappingRow = {
  fieldKey: string;
  displayLabel: string;
  physicalExpression: string | null;
};

export type UpdateConnectionRequest = {
  jdbcUrl: string;
  username: string;
  password: string;
  driverClassName: string;
};

export type UpdateConnectionResponse = {
  plane: ConnectionPlaneInfo | null;
  restartRequired: boolean;
};

export type Scheme = {
  id: number;
  code: string;
  name: string;
  description: string | null;
};

export type PreviewRequest = {
  ruleset: PredicateSpec;
  includeBreakdown: boolean;
};

export type PreviewResponse = {
  totalCount: number;
  breakdown: BreakdownRow[];
};

export type SaveScenarioRequest = {
  name: string;
  schemeIds: number[];
  ruleset: PredicateSpec;
  includeBreakdown: boolean;
};

export type SaveScenarioResponse = {
  scenarioId: number;
  totalCount: number;
  breakdown: BreakdownRow[];
};

export type ScenarioSummary = {
  id: number;
  name: string;
  schemeIds: number[];
  totalCount: number | null;
  createdAt: string;
};

export type ScenarioDetail = {
  id: number;
  name: string;
  schemeIds: number[];
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

export async function previewRuleset(req: PreviewRequest): Promise<PreviewResponse> {
  const res = await authorizedFetch(`${API_BASE}/api/decision/preview`, {
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

export async function saveScenario(req: SaveScenarioRequest): Promise<SaveScenarioResponse> {
  const res = await authorizedFetch(`${API_BASE}/api/decision/scenarios`, {
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

export async function listScenarios(schemeId: number): Promise<ScenarioSummary[]> {
  const res = await authorizedFetch(
    `${API_BASE}/api/decision/scenarios?schemeId=${encodeURIComponent(String(schemeId))}`,
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

export async function listSchemes(): Promise<Scheme[]> {
  const res = await authorizedFetch(`${API_BASE}/api/schemes`, { credentials: "include" });
  if (!res.ok) {
    throw new Error(`Scheme service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

export async function createScheme(req: {
  code: string;
  name: string;
  description: string;
}): Promise<Scheme> {
  const res = await authorizedFetch(`${API_BASE}/api/schemes`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    throw new Error(`Scheme service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

export async function listFields(): Promise<FieldCatalogEntry[]> {
  const res = await authorizedFetch(`${API_BASE}/api/metadata/fields`, { credentials: "include" });
  if (!res.ok) {
    throw new Error(`Metadata service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

export async function createField(req: FieldCatalogRequest): Promise<FieldCatalogEntry> {
  const res = await authorizedFetch(`${API_BASE}/api/metadata/fields`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    throw new Error(`Metadata service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

export async function updateField(
  fieldKey: string,
  req: FieldCatalogRequest,
): Promise<FieldCatalogEntry> {
  const res = await authorizedFetch(`${API_BASE}/api/metadata/fields/${encodeURIComponent(fieldKey)}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    throw new Error(`Metadata service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

export async function getConnections(): Promise<ConnectionsInfo> {
  const res = await authorizedFetch(`${API_BASE}/api/admin/connections`, { credentials: "include" });
  if (!res.ok) {
    throw new Error(`Admin service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

async function updateConnection(
  plane: "analytical" | "operational",
  req: UpdateConnectionRequest,
): Promise<UpdateConnectionResponse> {
  const res = await authorizedFetch(`${API_BASE}/api/admin/connections/${plane}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    throw new Error(`Connection update error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

export function updateAnalyticalConnection(req: UpdateConnectionRequest): Promise<UpdateConnectionResponse> {
  return updateConnection("analytical", req);
}

export function updateOperationalConnection(req: UpdateConnectionRequest): Promise<UpdateConnectionResponse> {
  return updateConnection("operational", req);
}

export async function listMappings(dataMode: DataMode): Promise<MappingRow[]> {
  const res = await authorizedFetch(
    `${API_BASE}/api/metadata/mappings?dataMode=${encodeURIComponent(dataMode)}`,
    { credentials: "include" },
  );
  if (!res.ok) {
    throw new Error(`Metadata service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

export async function upsertMapping(
  fieldKey: string,
  dataMode: DataMode,
  physicalExpression: string,
): Promise<MappingRow> {
  const res = await authorizedFetch(
    `${API_BASE}/api/metadata/mappings/${encodeURIComponent(fieldKey)}?dataMode=${encodeURIComponent(dataMode)}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify({ physicalExpression }),
    },
  );
  if (!res.ok) {
    throw new Error(`Metadata service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}
