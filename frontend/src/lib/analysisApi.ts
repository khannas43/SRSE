// Typed client for the Analysis tab's cross-table fuzzy record-match seam
// (/api/analysis/**). Deliberately separate from decisionApi.ts's Rule
// Engine calls — this tab picks tables/columns ad hoc rather than from the
// pre-registered field catalogue (see CLAUDE.md's flat-catalogue rule and the
// analysis backend package's javadoc for why).
//
// Every table/column reference here is FULLY QUALIFIED —
// catalog › schema › table › column. SRSE maps several catalogs and schemas
// at once (the lakehouse's Silver and Gold layers), so a bare table name is
// no longer an address: the same table name legitimately exists in both
// layers. Officers pick through a four-level cascade that offers only what an
// admin registered on the Admin page.

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

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
        cachedToken = null;
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

export type ColumnInfo = { name: string; dataType: string };

// One rung of the officer-facing cascade. `layer` is the admin's SILVER/GOLD
// tag (or null) — shown as a badge so an officer can tell the two layers'
// same-named tables apart at a glance.
export type RegisteredTable = { name: string; layer: string | null };

// A column of a registered table: live from the lakehouse, decorated with the
// admin's business name / fuzzy flag. Columns the admin hid are already
// filtered out server-side, so anything returned here is selectable.
export type RegisteredColumn = {
  name: string;
  dataType: string;
  businessName: string | null;
  fuzzyMatchable: boolean;
};

// A fully-qualified table address. Used as the identity of a picked table
// everywhere in the Analysis tab — comparisons must be on all three parts,
// never on `table` alone.
export type TableRef = { catalog: string; schema: string; table: string };

export function qualifiedTableName(ref: TableRef): string {
  return `${ref.catalog}.${ref.schema}.${ref.table}`;
}

export function displayTableName(ref: TableRef): string {
  return `${ref.catalog} › ${ref.schema} › ${ref.table}`;
}

// fuzzyThresholdPercent is set on Source-side criteria only (null on Target
// entries) and applies to that (source, target) pair when either column
// name contains "name" — see RecordMatchService's javadoc.
export type MatchCriterion = TableRef & {
  column: string;
  fuzzyThresholdPercent: number | null;
};

export type DedupSpec = TableRef & { column: string };

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

async function analysisGet<T>(path: string): Promise<T> {
  const res = await authorizedFetch(`${API_BASE}${path}`, { credentials: "include" });
  if (!res.ok) {
    throw new Error(`Analysis service error ${res.status}: ${await res.text()}`);
  }
  return res.json() as Promise<T>;
}

const e = encodeURIComponent;

// ---- officer-facing cascade: Catalog → Schema → Table → Column ----
// Every level is answered from the admin's registry, NOT the live cluster —
// an officer is only ever offered what an admin registered. (The one live
// read is the column list of an already-registered table, server-side, so a
// column added upstream appears without re-registration.)

export function listAnalysisCatalogs(): Promise<string[]> {
  return analysisGet<string[]>(`/api/analysis/lakehouse/catalogs`);
}

export function listAnalysisSchemas(catalog: string): Promise<string[]> {
  return analysisGet<string[]>(`/api/analysis/lakehouse/catalogs/${e(catalog)}/schemas`);
}

export function listAnalysisTables(catalog: string, schema: string): Promise<RegisteredTable[]> {
  return analysisGet<RegisteredTable[]>(
    `/api/analysis/lakehouse/catalogs/${e(catalog)}/schemas/${e(schema)}/tables`,
  );
}

export function listAnalysisColumns(ref: TableRef): Promise<RegisteredColumn[]> {
  return analysisGet<RegisteredColumn[]>(
    `/api/analysis/lakehouse/catalogs/${e(ref.catalog)}/schemas/${e(ref.schema)}` +
      `/tables/${e(ref.table)}/columns`,
  );
}

export type RecordMatchStreamHandlers = {
  onMeta: (meta: { columns: string[]; sql: string }) => void;
  onRow: (row: Record<string, unknown>) => void;
  onDone: (totalRows: number) => void;
  onError: (message: string) => void;
};

// The match result is no longer a single buffered JSON object — the backend
// streams newline-delimited JSON (one "meta" line, then one "row" line per
// match, then a "done" or "error" line) so the officer sees the first rows
// almost immediately instead of waiting for the whole (now uncapped) result.
// See RecordMatchService's javadoc on the backend for why.
export async function runRecordMatchStream(
  req: RecordMatchRequest,
  handlers: RecordMatchStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const res = await authorizedFetch(`${API_BASE}/api/analysis/match`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(req),
    signal,
  });
  if (!res.ok) {
    throw new Error(`Analysis service error ${res.status}: ${await res.text()}`);
  }
  if (!res.body) {
    throw new Error("Analysis service error: streaming response has no body");
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  function handleLine(line: string) {
    if (!line.trim()) {
      return;
    }
    const event = JSON.parse(line) as
      | { type: "meta"; columns: string[]; sql: string }
      | { type: "row"; data: Record<string, unknown> }
      | { type: "done"; totalRows: number }
      | { type: "error"; message: string };
    switch (event.type) {
      case "meta":
        handlers.onMeta({ columns: event.columns, sql: event.sql });
        break;
      case "row":
        handlers.onRow(event.data);
        break;
      case "done":
        handlers.onDone(event.totalRows);
        break;
      case "error":
        handlers.onError(event.message);
        break;
    }
  }

  for (;;) {
    const { value, done } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    let newlineIndex: number;
    while ((newlineIndex = buffer.indexOf("\n")) >= 0) {
      handleLine(buffer.slice(0, newlineIndex));
      buffer = buffer.slice(newlineIndex + 1);
    }
  }
  if (buffer.trim()) {
    handleLine(buffer);
  }
}

// Admin-managed business name / fuzzy-matchable / visibility override per
// physical column, keyed by the FULL catalog.schema.table.column address —
// see AnalysisColumnMetadata's javadoc on the backend for why this is
// separate from the Rule Engine's field catalogue, and why table+column alone
// was not a unique key once Silver and Gold layers were both mapped.
// Unregistered columns of a registered table are visible and fall back to an
// auto-derived label and a name-substring guess for fuzzy eligibility.
export type ColumnMetadata = TableRef & {
  column: string;
  businessName: string | null;
  fuzzyMatchable: boolean;
  visible: boolean;
};

export async function listColumnMetadata(): Promise<ColumnMetadata[]> {
  const res = await authorizedFetch(`${API_BASE}/api/analysis/column-metadata`, { credentials: "include" });
  if (!res.ok) {
    throw new Error(`Analysis service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}

export async function upsertColumnMetadata(
  ref: TableRef,
  column: string,
  businessName: string | null,
  fuzzyMatchable: boolean,
  visible: boolean = true,
): Promise<ColumnMetadata> {
  const res = await authorizedFetch(`${API_BASE}/api/analysis/column-metadata`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ ...ref, column, businessName, fuzzyMatchable, visible }),
  });
  if (!res.ok) {
    throw new Error(`Analysis service error ${res.status}: ${await res.text()}`);
  }
  return res.json();
}
