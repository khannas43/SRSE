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
