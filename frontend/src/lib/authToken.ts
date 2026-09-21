const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

/** Mock-login scope: officer screens vs admin456 (admin token also carries STATE_OFFICER). */
export type AuthScope = "officer" | "admin";

const tokenCache = new Map<AuthScope, Promise<string>>();

export class SrseAdminAccessDeniedError extends Error {
  constructor() {
    super("This account is not an SRSE administrator.");
    this.name = "SrseAdminAccessDeniedError";
  }
}

export function getAuthToken(scope: AuthScope = "officer"): Promise<string> {
  let cached = tokenCache.get(scope);
  if (!cached) {
    const loginUrl =
      scope === "admin"
        ? `${API_BASE}/api/auth/mock-login?role=admin`
        : `${API_BASE}/api/auth/mock-login`;
    cached = fetch(loginUrl, { method: "POST" })
      .then(async (res) => {
        if (!res.ok) {
          throw new Error(`Mock login failed ${res.status}: ${await res.text()}`);
        }
        const body = (await res.json()) as { token: string };
        return body.token;
      })
      .catch((err) => {
        tokenCache.delete(scope);
        throw err;
      });
    tokenCache.set(scope, cached);
  }
  return cached;
}

export async function authorizedFetch(
  scope: AuthScope,
  input: string,
  init: RequestInit = {},
): Promise<Response> {
  const token = await getAuthToken(scope);
  const res = await fetch(input, {
    ...init,
    headers: { ...init.headers, Authorization: `Bearer ${token}` },
  });
  if (scope === "admin" && res.status === 403) {
    throw new SrseAdminAccessDeniedError();
  }
  return res;
}
