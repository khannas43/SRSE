import type { CompareAs } from "@/lib/analysisApi";
import {
  authorizedFetch as scopedAuthorizedFetch,
  type AuthScope,
} from "@/lib/authToken";

// Typed client for the SRSE decision-service seam (design doc §8.1 / CLAUDE.md #6).
// Full-ruleset preview/save model — caller sends the complete PredicateSpec on every call.
// Shaped like an ODM decision-service call so CP4BA/ODM can later fulfil it without
// changing this caller.

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

async function authorizedFetch(
  input: string,
  init: RequestInit = {},
  scope: AuthScope = "officer",
): Promise<Response> {
  return scopedAuthorizedFetch(scope, input, init);
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
  environmentLabel: string;
  operational: ConnectionPlaneInfo;
  analytical: ConnectionPlaneInfo;
};

export type MappingRow = {
  fieldKey: string;
  displayLabel: string;
  physicalExpression: string | null;
  /**
   * The table this binding selects FROM, or null when the binding is an
   * expression. Derived server-side (FieldColumnMapping.tableOf) so the page
   * does not have to reimplement the rule the query builder uses.
   */
  tableName: string | null;
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
  templateScenarioId: number | null;
};

export type PreviewRequest = {
  ruleset: PredicateSpec;
  includeBreakdown: boolean;
};

export type PreviewResponse = {
  totalCount: number;
  breakdown: BreakdownRow[];
};

export type CohortRequest = {
  ruleset: PredicateSpec;
  limit: number;
};

export type CohortResponse = {
  rows: Record<string, unknown>[];
  appliedLimit: number;
  capped: boolean;
};

export type DecisionLimits = {
  previewSampleSize: number;
  cohortCap: number;
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

export function fetchDecisionLimits(): Promise<DecisionLimits> {
  return authorizedFetch(`${API_BASE}/api/decision/limits`, { credentials: "include" }).then(async (res) => {
    if (!res.ok) {
      throw new Error(`Decision service error ${res.status}: ${await res.text()}`);
    }
    return res.json() as Promise<DecisionLimits>;
  });
}

export async function cohortSample(req: CohortRequest): Promise<CohortResponse> {
  const res = await authorizedFetch(`${API_BASE}/api/decision/cohort`, {
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

export async function listScenarios(
  schemeId: number,
  scope: AuthScope = "officer",
): Promise<ScenarioSummary[]> {
  const res = await authorizedFetch(
    `${API_BASE}/api/decision/scenarios?schemeId=${encodeURIComponent(String(schemeId))}`,
    { credentials: "include" },
    scope,
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

export async function listSchemes(scope: AuthScope = "officer"): Promise<Scheme[]> {
  const res = await authorizedFetch(`${API_BASE}/api/schemes`, { credentials: "include" }, scope);
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

export async function fetchSchemeTemplate(
  schemeId: number,
): Promise<PredicateSpec | null> {
  const res = await authorizedFetch(`${API_BASE}/api/schemes/${schemeId}/template`, {
    credentials: "include",
  });
  if (res.status === 204) {
    return null;
  }
  if (!res.ok) {
    throw new Error(`Scheme template error ${res.status}: ${await res.text()}`);
  }
  const body = (await res.json()) as { ruleset: PredicateSpec };
  return body.ruleset;
}

export async function setSchemeTemplate(schemeId: number, scenarioId: number): Promise<void> {
  const res = await authorizedFetch(`${API_BASE}/api/schemes/${schemeId}/template`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ scenarioId }),
  }, "admin");
  if (!res.ok) {
    throw new Error(`Set scheme template error ${res.status}: ${await res.text()}`);
  }
}

export async function listFields(scope: AuthScope = "officer"): Promise<FieldCatalogEntry[]> {
  const res = await authorizedFetch(`${API_BASE}/api/metadata/fields`, { credentials: "include" }, scope);
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
  }, "admin");
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
  }, "admin");
  if (!res.ok) {
    throw new Error(`Metadata service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

/**
 * Deactivates a field. The row is kept and flagged inactive rather than
 * physically deleted, so saved scenarios that reference the field key still
 * resolve; it simply stops being offered in the rule builder and the mapping
 * table. Re-adding the same key through createField brings it back.
 */
export async function deleteField(fieldKey: string): Promise<void> {
  const res = await authorizedFetch(`${API_BASE}/api/metadata/fields/${encodeURIComponent(fieldKey)}`, {
    method: "DELETE",
    credentials: "include",
  }, "admin");
  if (!res.ok) {
    throw new Error(`Metadata service error ${res.status}: ${await res.text()}`);
  }
}

export async function getConnections(): Promise<ConnectionsInfo> {
  const res = await authorizedFetch(`${API_BASE}/api/admin/connections`, { credentials: "include" }, "admin");
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
  }, "admin");
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

export async function listMappings(dataMode: DataMode, scope: AuthScope = "officer"): Promise<MappingRow[]> {
  const res = await authorizedFetch(
    `${API_BASE}/api/metadata/mappings?dataMode=${encodeURIComponent(dataMode)}`,
    { credentials: "include" },
    scope,
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
    "admin",
  );
  if (!res.ok) {
    throw new Error(`Metadata service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

// ---------------------------------------------------------------------------
// Admin: lakehouse Catalog → Schema → Table → Column cascade
// ---------------------------------------------------------------------------
// Two distinct reaches, deliberately kept apart:
//
//  - browse* : the LIVE lakehouse — everything the current Presto connection
//    can physically see. Admin-only, used for discovery.
//  - registrations : the subset an admin has chosen to expose. This is what
//    officers get offered (via analysisApi's cascade); the Analysis tab never
//    calls the browse endpoints.
//
// This replaces the old model where a single catalog and schema were baked
// into the JDBC URL. The connection is now catalog-agnostic — any /catalog
// /schema still in the URL is only a default — because SRSE maps several
// catalogs at once, including the lakehouse's Silver and Gold layers.

export type LakehouseColumnInfo = { name: string; dataType: string };

/**
 * Unbinds a field for ONE environment. The field stays in the catalogue and the
 * other environment's binding is untouched, so this retires a binding without
 * retiring the field — after it, the row shows as not configured, exactly like
 * an untouched CHANGE_ME placeholder.
 */
export async function deleteMapping(fieldKey: string, dataMode: DataMode): Promise<void> {
  const res = await authorizedFetch(
    `${API_BASE}/api/metadata/mappings/${encodeURIComponent(fieldKey)}?dataMode=${encodeURIComponent(dataMode)}`,
    { method: "DELETE", credentials: "include" },
    "admin",
  );
  if (!res.ok) {
    throw new Error(`Metadata service error ${res.status}: ${await res.text()}`);
  }
}

export type TableRegistration = {
  id: number;
  catalog: string;
  schema: string;
  table: string;
  /** SILVER / GOLD / null — a display tag, not a level of the hierarchy. */
  layer: string | null;
  qualifiedName: string;
};

async function adminGet<T>(path: string): Promise<T> {
  const res = await authorizedFetch(`${API_BASE}${path}`, { credentials: "include" }, "admin");
  if (!res.ok) {
    throw new Error(`Admin service error ${res.status}: ${await res.text()}`);
  }
  return res.json() as Promise<T>;
}

const enc = encodeURIComponent;

export function browseCatalogs(): Promise<string[]> {
  return adminGet<string[]>(`/api/admin/lakehouse/browse/catalogs`);
}

export function browseSchemas(catalog: string): Promise<string[]> {
  return adminGet<string[]>(`/api/admin/lakehouse/browse/catalogs/${enc(catalog)}/schemas`);
}

export function browseTables(catalog: string, schema: string): Promise<string[]> {
  return adminGet<string[]>(
    `/api/admin/lakehouse/browse/catalogs/${enc(catalog)}/schemas/${enc(schema)}/tables`,
  );
}

export function browseColumns(
  catalog: string,
  schema: string,
  table: string,
): Promise<LakehouseColumnInfo[]> {
  return adminGet<LakehouseColumnInfo[]>(
    `/api/admin/lakehouse/browse/catalogs/${enc(catalog)}/schemas/${enc(schema)}` +
      `/tables/${enc(table)}/columns`,
  );
}

export function listRegistrations(): Promise<TableRegistration[]> {
  return adminGet<TableRegistration[]>(`/api/admin/lakehouse/registrations`);
}

export function listLakehouseLayers(): Promise<string[]> {
  return adminGet<string[]>(`/api/admin/lakehouse/layers`);
}

/**
 * Registers a table (or re-tags an already-registered one). Registering
 * exposes ALL of the table's live columns to officers — individual columns
 * are hidden afterwards via upsertColumnMetadata's `visible` flag.
 */
export async function registerTable(req: {
  catalog: string;
  schema: string;
  table: string;
  layer: string;
}): Promise<TableRegistration> {
  const res = await authorizedFetch(`${API_BASE}/api/admin/lakehouse/registrations`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(req),
  }, "admin");
  if (!res.ok) {
    throw new Error(`Admin service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

/**
 * Edits an existing registration's layer tag in place. Only the layer is
 * editable — the catalog/schema/table triple is the registration's identity
 * and everything downstream refers to a table by that address, so retargeting
 * is unregister + register (see LakehouseRegistryService.updateLayer).
 */
export async function updateTableRegistration(id: number, layer: string): Promise<TableRegistration> {
  const res = await authorizedFetch(`${API_BASE}/api/admin/lakehouse/registrations/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ layer }),
  }, "admin");
  if (!res.ok) {
    throw new Error(`Admin service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

export async function unregisterTable(id: number): Promise<void> {
  const res = await authorizedFetch(`${API_BASE}/api/admin/lakehouse/registrations/${id}`, {
    method: "DELETE",
    credentials: "include",
  }, "admin");
  if (!res.ok) {
    throw new Error(`Admin service error ${res.status}: ${await res.text()}`);
  }
}

// ---------------------------------------------------------------------------
// Admin: configuration backup / restore (JSON bundle)
// ---------------------------------------------------------------------------

export type AdminConfigConnectionPlane = {
  jdbcUrl: string;
  username: string;
  password: string;
  driverClassName: string;
};

export type AdminConfigBundle = {
  schemaVersion: string;
  exportedAt: string;
  dataMode: string;
  connections: {
    operational: AdminConfigConnectionPlane;
    analytical: AdminConfigConnectionPlane;
  } | null;
  fieldCatalog: FieldCatalogRequest[];
  fieldColumnMappings: {
    fieldKey: string;
    dataMode: DataMode;
    physicalExpression: string;
  }[];
  registeredTables: {
    catalog: string;
    schema: string;
    table: string;
    layer: string | null;
  }[];
  analysisColumnMetadata: {
    catalog: string;
    schema: string;
    table: string;
    column: string;
    businessName: string | null;
    fuzzyMatchable: boolean;
    visible: boolean;
    compareAs: CompareAs;
  }[];
  schemes: {
    code: string;
    name: string;
    description: string | null;
  }[];
};

export type AdminConfigImportResult = {
  fieldCatalogCount: number;
  fieldMappingCount: number;
  registeredTableCount: number;
  columnMetadataCount: number;
  schemeCount: number;
  operationalRestartRequired: boolean;
};

/** Downloads the full admin configuration as JSON (connections, mappings, registrations, etc.). */
export async function exportAdminConfig(): Promise<AdminConfigBundle> {
  const res = await authorizedFetch(`${API_BASE}/api/admin/config/export`, {
    credentials: "include",
  }, "admin");
  if (!res.ok) {
    throw new Error(`Admin config export failed ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

/** Restores admin configuration from a previously exported JSON bundle. */
export async function importAdminConfig(
  bundle: AdminConfigBundle,
  options?: { testConnections?: boolean },
): Promise<AdminConfigImportResult> {
  const testConnections = options?.testConnections ?? true;
  const res = await authorizedFetch(
    `${API_BASE}/api/admin/config/import?testConnections=${testConnections ? "true" : "false"}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify(bundle),
    },
    "admin",
  );
  if (!res.ok) {
    throw new Error(`Admin config import failed ${res.status}: ${await res.text()}`);
  }
  return res.json();
}
