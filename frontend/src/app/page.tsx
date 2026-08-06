"use client";

import { useEffect, useState } from "react";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";
const HEALTH_URL = `${API_BASE}/api/health/planes`;

type PlanesHealth = {
  operational: string;
  analytical: string;
};

export default function HomePage() {
  const [status, setStatus] = useState<"loading" | "ok" | "error">("loading");
  const [data, setData] = useState<PlanesHealth | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    fetch(HEALTH_URL)
      .then(async (res) => {
        if (!res.ok) {
          throw new Error(`HTTP ${res.status} ${res.statusText}`);
        }
        return res.json() as Promise<PlanesHealth>;
      })
      .then((json) => {
        if (cancelled) return;
        setData(json);
        setStatus("ok");
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : String(err));
        setStatus("error");
      });

    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <main style={{ padding: "2rem", maxWidth: 640 }}>
      <h1 style={{ marginTop: 0 }}>SRSE — Both-Planes Health Check</h1>

      {status === "loading" && <p>Loading…</p>}

      {status === "ok" && data && (
        <pre
          style={{
            background: "#f5f5f5",
            padding: "1rem",
            borderRadius: 4,
            overflow: "auto",
          }}
        >
          {JSON.stringify(data, null, 2)}
        </pre>
      )}

      {status === "error" && (
        <p style={{ color: "#b00020" }}>Fetch error: {error}</p>
      )}
    </main>
  );
}
