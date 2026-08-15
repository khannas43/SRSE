// Typed client for the Analysis tab's cross-table fuzzy record-match seam
// (/api/analysis/**). Deliberately separate from decisionApi.ts's Rule
// Engine calls — this tab reads the live lakehouse schema ad hoc rather
// than the pre-registered field catalogue (see CLAUDE.md's flat-catalogue
// rule and the analysis backend package's javadoc for why).

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

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
        cachedToken = null;
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

export type ColumnInfo = { name: string; dataType: string };

// fuzzyThresholdPercent is set on Source-side criteria only (null on Target
// entries) and applies to that (source, target) pair when either column
// name contains "name" — see RecordMatchService's javadoc.
export type MatchCriterion = { table: string; column: string; fuzzyThresholdPercent: number | null };

export type DedupSpec = { table: string; column: string };

export type AgeUnit = "DAYS" | "MONTHS" | "YEARS";

// No table/column here — this filter is resolved server-side against the
// field catalogue's registered "age_years" field (the same admin-mapped
// column the Rule Engine uses), applied to both Source and Target rows.
export type AgeFilterSpec = {
  minAge: number;
  maxAge: number;
  unit: AgeUnit;
};

export type RecordMatchRequest = {
  sourceCriteria: MatchCriterion[];
  targetCriteria: MatchCriterion[];
  highlightDuplicates: boolean;
  dedup: DedupSpec | null;
  ageFilter: AgeFilterSpec | null;
};

export type RecordMatchResponse = {
  columns: string[];
  rows: Record<string, unknown>[];
  sql: string;
  capped: boolean;
};

export async function listAnalysisTables(): Promise<string[]> {
  const res = await authorizedFetch(`${API_BASE}/api/analysis/tables`, { credentials: "include" });
  if (!res.ok) {
    throw new Error(`Analysis service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

export async function listAnalysisColumns(table: string): Promise<ColumnInfo[]> {
  const res = await authorizedFetch(
    `${API_BASE}/api/analysis/tables/${encodeURIComponent(table)}/columns`,
    { credentials: "include" },
  );
  if (!res.ok) {
    throw new Error(`Analysis service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

export async function runRecordMatch(req: RecordMatchRequest): Promise<RecordMatchResponse> {
  const res = await authorizedFetch(`${API_BASE}/api/analysis/match`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    throw new Error(`Analysis service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

// Admin-managed business name / fuzzy-matchable override per physical
// table.column — see AnalysisColumnMetadata's javadoc on the backend for why
// this is separate from the Rule Engine's field catalogue. Unregistered
// columns fall back to an auto-derived label and a name-substring guess.
export type ColumnMetadata = {
  table: string;
  column: string;
  businessName: string | null;
  fuzzyMatchable: boolean;
};

export async function listColumnMetadata(): Promise<ColumnMetadata[]> {
  const res = await authorizedFetch(`${API_BASE}/api/analysis/column-metadata`, { credentials: "include" });
  if (!res.ok) {
    throw new Error(`Analysis service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

export async function upsertColumnMetadata(
  table: string,
  column: string,
  businessName: string | null,
  fuzzyMatchable: boolean,
): Promise<ColumnMetadata> {
  const res = await authorizedFetch(`${API_BASE}/api/analysis/column-metadata`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ table, column, businessName, fuzzyMatchable }),
  });
  if (!res.ok) {
    throw new Error(`Analysis service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}
