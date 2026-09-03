# SRSE Configuration Guide

Step-by-step instructions to configure, build, and run the **Scheme Rule Simulation Engine (SRSE)** on a developer laptop (offline synthetic data) or in the client Dev environment (live watsonx.data lakehouse).

For architectural context and locked design decisions, see [`CLAUDE.md`](../CLAUDE.md). For build status, see [`implementation_plan.md`](implementation_plan.md).

---

## Table of contents

1. [What you are configuring](#1-what-you-are-configuring)
2. [Prerequisites](#2-prerequisites)
3. [Quick start — local Docker stack (recommended)](#3-quick-start--local-docker-stack-recommended)
4. [Verify the installation](#4-verify-the-installation)
5. [Configure environment variables](#5-configure-environment-variables)
6. [Build from source (without Docker)](#6-build-from-source-without-docker)
7. [Client Dev deployment (live lakehouse)](#7-client-dev-deployment-live-lakehouse)
8. [Post-deployment admin configuration](#8-post-deployment-admin-configuration)
9. [Optional: SonarQube code quality scans](#9-optional-sonarqube-code-quality-scans)
10. [Troubleshooting](#10-troubleshooting)
11. [Configuration reference](#11-configuration-reference)

---

## 1. What you are configuring

SRSE is a two-container application plus supporting infrastructure:

| Component | Technology | Purpose |
|-----------|------------|---------|
| **Frontend** | Next.js 16 / React 19 | Officer UI — rule builder, simulation results, analysis tab |
| **Backend** | Java 17 / Spring Boot 3.x | Rule compiler, execution engine, metadata store, REST API |
| **Operational plane** | DB2 + JPA | Field catalogue, column mappings, saved scenarios |
| **Analytical plane** | PrestoDB 0.297 + Iceberg | Beneficiary count / breakdown queries (push-down SQL) |

Two **data modes** switch behaviour without code changes:

| Mode | When to use | Data source |
|------|-------------|-------------|
| `synthetic` | Laptop / offline dev | Local Docker Presto + seeded beneficiary table |
| `live` | Client Dev / on-prem | Real watsonx.data Presto + Golden Layer |

---

## 2. Prerequisites

### Required (all setups)

| Tool | Version | Notes |
|------|---------|-------|
| **Git** | Any recent | Clone the repository |
| **Docker Desktop** | 4.x+ | Recommended path for local stack |
| **Docker Compose** | v2 (bundled with Docker Desktop) | `docker compose` command |

Allocate at least **8 GB RAM** to Docker. DB2 and Presto are memory-heavy; first boot can take several minutes.

### Required (native / IDE development only)

| Tool | Version | Notes |
|------|---------|-------|
| **JDK** | **17 only** | Backend enforces `[17,18)` via Maven Enforcer |
| **Maven** | 3.9+ | Backend build |
| **Node.js** | 20 LTS | Frontend build |
| **npm** | 10+ | Bundled with Node 20 |

On macOS, pin Java 17 explicitly:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

### Optional

| Tool | Purpose |
|------|---------|
| SonarQube (local) | Code quality scans — see [Section 9](#9-optional-sonarqube-code-quality-scans) |
| `gh` CLI | GitHub operations |

### Apple Silicon (M1/M2/M3) note

The DB2 community image has no native ARM build. Docker Compose sets `platform: linux/amd64` and runs DB2 under emulation. First boot is slower than on Intel; this is expected.

---

## 3. Quick start — local Docker stack (recommended)

### Step 1 — Clone the repository

```bash
git clone https://github.com/khannas43/SRSE.git
cd SRSE
```

### Step 2 — Create your environment file

```bash
cp .env.example .env
```

For a standard laptop setup, the defaults in `.env.example` are sufficient. You only need to edit `.env` if you change host ports or add SonarQube tokens (see [Section 5](#5-configure-environment-variables)).

> **Never commit `.env`.** It is listed in `.gitignore`. Passwords and tokens belong here or in your organisation's secret store — not in source control.

### Step 3 — Start the full stack

```bash
docker compose up --build
```

This starts:

| Service | Host URL | Internal role |
|---------|----------|---------------|
| Frontend | http://localhost:3000 | Officer UI |
| Backend | http://localhost:8080 | REST API + Swagger |
| Presto | http://localhost:8081 | Analytical queries |
| DB2 | `localhost:50000` | Operational store |
| MinIO console | http://localhost:9011 | Object storage (Iceberg) |
| Seed job | *(one-shot)* | Creates synthetic Iceberg `beneficiary` table (~200k rows) |

Add `-d` to run in the background:

```bash
docker compose up --build -d
```

### Step 4 — Wait for first boot

On first run, allow **5–10 minutes** for:

1. DB2 to initialise (`db2` container)
2. Presto + Hive metastore + MinIO to start
3. Seed job to populate synthetic data
4. Backend to connect to both data planes and seed the field catalogue

Watch progress:

```bash
docker compose logs -f srse-backend
```

### Step 5 — Open the application

| Page | URL | Purpose |
|------|-----|---------|
| Rule builder | http://localhost:3000/rules | Compose eligibility rules and run simulations |
| Analysis | http://localhost:3000/analysis | Cross-table record matching (admin feature) |
| Admin | http://localhost:3000/admin456 | Connection settings, field mappings *(unlinked — bookmark this URL)* |
| API docs | http://localhost:8080/swagger-ui.html | Swagger UI |
| Health check | http://localhost:8080/api/health/planes | Both-planes connectivity |

---

## 4. Verify the installation

### 4.1 Both-planes health check

```bash
curl -s http://localhost:8080/api/health/planes | python3 -m json.tool
```

Expected:

```json
{
  "operational": "up",
  "analytical": "up"
}
```

If either plane shows `"down"`, see [Troubleshooting](#10-troubleshooting).

### 4.2 Run a simulation (UI)

1. Open http://localhost:3000/rules
2. Select a scheme from the dropdown (or create one)
3. Add rule predicates from the field palette (e.g. Age ≥ 18)
4. Click **Preview** — you should see a beneficiary count and district breakdown

Local dev uses **mock authentication** (`SRSE_AUTH_MODE=mock`). The frontend obtains a short-lived mock JWT automatically; no login screen is shown.

### 4.3 Register a lakehouse table (required before the Analysis tab works)

The Analysis tab shows **only tables an admin has registered** — on a fresh
stack (or after wiping the DB2 volume) nothing is registered, so its
Catalog/Schema/Table dropdowns are empty. This is expected, not a fault.

1. Open http://localhost:3000/admin456
2. In **Lakehouse registry**, pick Catalog `iceberg` → Schema `srse` → Table `beneficiary`
3. Optionally tag the layer, then click **Register table**

Or via the API:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/mock-login | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl -s -X POST http://localhost:8080/api/admin/lakehouse/registrations \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"catalog":"iceberg","schema":"srse","table":"beneficiary","layer":null}'
```

The Rule Engine tab does **not** need this — in synthetic mode it resolves
through `StubFieldResolver` against the connection's default catalog/schema.

**Testing the multi-catalog paths locally.** The stack ships a second Iceberg
catalog, `iceberg_silver` (`docker/presto/catalog/iceberg_silver.properties`),
purely so the local environment can exercise what the client deployment relies
on: Silver and Gold layers in *different catalogs*. Without a second catalog
nothing locally can prove a cross-catalog join actually executes.

It shares the metastore and MinIO with `iceberg` — the point is two distinct
Presto **catalog handles**, not two storage backends — so on a fresh stack both
catalogs list the same schemas. To reconcile across them, register the same
table under each catalog and pick one as Source and the other as Target. For a
more realistic fixture, create a distinctly-named Silver table:

```sql
CREATE SCHEMA IF NOT EXISTS iceberg_silver.silver_txn;
CREATE TABLE iceberg_silver.silver_txn.tbl_txn_bankdtl AS
SELECT id AS bank_id, id AS m_id, CAST(id AS VARCHAR) AS account_no,
       father_name, district
FROM iceberg.srse.beneficiary WHERE id <= 20000;
```

### 4.4 Run backend tests (optional)

```bash
cd backend
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test
```

All tests should pass. If Mockito errors mention "Could not instrument class", your shell is using the wrong JDK — pin Java 17 as shown above.

---

## 5. Configure environment variables

Copy `.env.example` to `.env` and set values per environment.

### 5.1 Core settings (local laptop)

| Variable | Default (local) | Description |
|----------|-----------------|-------------|
| `DATA_MODE` | `synthetic` | `synthetic` = local lakehouse stand-in; `live` = on-prem Golden Layer |
| `SRSE_AUTH_MODE` | `mock` | `mock` = local JWT issuer; `rajsewadwar` = SSO (client Dev) |
| `NEXT_PUBLIC_API_BASE` | `http://localhost:8080` | Backend URL **as seen by the browser** |
| `SRSE_FRONTEND_ORIGINS` | `http://localhost:3000` | CORS allow-list — must match the frontend URL |
| `NEXT_PUBLIC_AUTH_MODE` | `mock` | Frontend auth mode (must align with backend) |

### 5.2 Operational plane (DB2)

| Variable | Local default | Description |
|----------|---------------|-------------|
| `SRSE_DB2_URL` | `jdbc:db2://db2:50000/SRSEDB` | JDBC URL (use `db2` hostname inside Docker; `localhost:50000` from host) |
| `SRSE_DB2_USER` | `db2inst1` | DB2 user |
| `SRSE_DB2_PASSWORD` | *(set in compose)* | DB2 password — `srse_local_pw` in `docker-compose.yml` |

### 5.3 Analytical plane (PrestoDB)

| Variable | Local default | Description |
|----------|---------------|-------------|
| `SRSE_PRESTO_URL` | `jdbc:presto://presto:8080/iceberg/srse` | Presto JDBC URL. The trailing `/iceberg/srse` is only a **default** catalog/schema — kept locally so the synthetic `beneficiary` table resolves unqualified. In client Dev, drop it and register catalogs explicitly (Section 8, Panel 2) |
| `SRSE_PRESTO_USER` | `srse` | Presto user |
| `SRSE_PRESTO_PASSWORD` | *(empty)* | Only if endpoint requires auth |
| `SRSE_PRESTO_SSL` | `false` | Set `true` for TLS endpoints (client Dev) |
| `SRSE_PRESTO_TRUSTSTORE_PATH` | — | Path to JKS truststore (when SSL enabled) |
| `SRSE_PRESTO_TRUSTSTORE_PASSWORD` | — | Truststore password |

> **Important:** SRSE uses the **PrestoDB** driver (`com.facebook.presto:presto-jdbc`), **not** Trino. watsonx.data 2.3.1 is PrestoDB 0.297 lineage.

### 5.4 Guardrails

| Variable | Default | Description |
|----------|---------|-------------|
| `SRSE_COHORT_CAP` | `1000` | Maximum rows returned by cohort drill-down |
| `SRSE_QUERY_TIMEOUT_SECONDS` | `30` | Presto query timeout |
| `SRSE_AGE_BAND_COLUMN` | `age_band` | Physical column for breakdown age-band dimension |

### 5.5 Frontend build-time vs runtime

`NEXT_PUBLIC_*` variables are **baked into the frontend JavaScript bundle at Docker build time**. Changing them requires a rebuild:

```bash
docker compose build srse-frontend
docker compose up -d srse-frontend
```

`SRSE_FRONTEND_ORIGINS` is read by the **backend** at runtime — a backend restart is enough (no frontend rebuild).

---

## 6. Build from source (without Docker)

Use this path if you want to run backend and frontend in your IDE while pointing at Docker infrastructure.

### Step 1 — Start infrastructure only

```bash
docker compose up db2 presto metastore minio seed -d
```

Wait until DB2 and Presto are healthy (see [Section 4.1](#41-both-planes-health-check)).

### Step 2 — Configure backend for localhost

Create or edit `.env` (or export variables) so JDBC URLs use **localhost** instead of Docker service names:

```bash
export DATA_MODE=synthetic
export SRSE_DB2_URL=jdbc:db2://localhost:50000/SRSEDB
export SRSE_DB2_USER=db2inst1
export SRSE_DB2_PASSWORD=srse_local_pw
export SRSE_PRESTO_URL=jdbc:presto://localhost:8081/iceberg/srse
export SRSE_PRESTO_USER=srse
export SRSE_AUTH_MODE=mock
export SRSE_FRONTEND_ORIGINS=http://localhost:3000
```

### Step 3 — Run the backend

```bash
cd backend
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn spring-boot:run
```

Backend listens on http://localhost:8080.

### Step 4 — Run the frontend

```bash
cd frontend
npm install
NEXT_PUBLIC_API_BASE=http://localhost:8080 NEXT_PUBLIC_AUTH_MODE=mock npm run dev
```

Frontend listens on http://localhost:3000 (Next.js dev server).

### Step 5 — Production-style frontend build (optional)

```bash
cd frontend
NEXT_PUBLIC_API_BASE=http://localhost:8080 npm run build
npm start
```

---

## 7. Client Dev deployment (live lakehouse)

Use this when connecting to on-prem watsonx.data Presto and DB2. The local lakehouse containers (Presto, MinIO, metastore, seed) are **not** started.

### Step 1 — Obtain connection details

The following must be confirmed by the infrastructure team before go-live (see `CLAUDE.md` open items):

| Item | Owner | What you need |
|------|-------|---------------|
| PrestoDB JDBC endpoint | Lovadeep | Host, port, SSL/truststore. **Catalog and schema are no longer part of the URL** — see below |
| Silver + Gold catalog/schema/table names | Lovadeep / DBA | Registered through the Admin page after deployment |
| Golden Layer table/column names | Lovadeep / DBA | Replace `CHANGE_ME` in field mappings |
| DB2 instance / schema for SRSE | Lovadeep | JDBC URL, credentials, schema creation |
| RajSewadwar SSO | Arvind | Client ID, secret, JWT validation |

### Step 2 — Create client Dev environment file

Merge values from both example files:

```bash
cp .env.example .env
cp .env.client-dev.example .env.client-dev
```

Edit `.env` with browser-facing URLs:

```bash
NEXT_PUBLIC_API_BASE=https://srse-api.your-domain.gov.in
SRSE_FRONTEND_ORIGINS=https://srse.your-domain.gov.in
```

Edit `.env.client-dev` with on-prem credentials:

```bash
CLIENT_DB2_URL=jdbc:db2://<host>:50000/<database>
CLIENT_DB2_USER=<user>
CLIENT_DB2_PASSWORD=<password>

# Catalog-agnostic: no trailing /catalog/schema. SRSE addresses every table
# by its full catalog.schema.table, so one connection reaches every
# registered catalog (Silver and Gold layers alike). A trailing
# /<catalog>/<schema> is still accepted but is only a default.
CLIENT_PRESTO_URL=jdbc:presto://<host>:8080
CLIENT_PRESTO_USER=<user>
CLIENT_PRESTO_PASSWORD=<password>
CLIENT_PRESTO_SSL=true
CLIENT_PRESTO_TRUSTSTORE_PATH=/path/to/truststore.jks
CLIENT_PRESTO_TRUSTSTORE_PASSWORD=<truststore-password>

CLIENT_AGE_BAND_COLUMN=<golden-layer-age-band-column>
```

Load both files before starting:

```bash
set -a
source .env
source .env.client-dev
set +a
```

### Step 3 — Prepare DB2 schema (one-time)

In client Dev, Hibernate `ddl-auto=update` is **disabled** (Spring profile `client-dev`). The DBA must create the operational tables before first boot:

- `field_catalog`
- `field_column_mapping`
- `scenario`
- `scenario_scheme_tag`
- `analysis_column_metadata`
- `registered_table`

Table shapes match the JPA entities under `backend/src/main/java/gov/rajasthan/smart/srse/`.

> **Upgrading a database that already has these tables** — client Dev *and* any local
> stack whose DB2 volume predates the change. Run
> [`docs/migrations/001-qualify-analysis-column-metadata.sql`](migrations/001-qualify-analysis-column-metadata.sql).
>
> Do not assume `ddl-auto: update` handles it — **it cannot, and it does not say so.**
> It adds nullable columns happily (which is how `visible` appears on its own) but DB2
> rejects `ADD COLUMN ... NOT NULL` on an existing table, so `catalog_name` and
> `schema_name` are skipped in silence. The backend then starts perfectly cleanly and
> every Analysis-tab query fails at runtime with `SQLCODE=-206` (undefined column).
> This was hit for real on a local stack, not theorised.
>
> Two details the script explains and that are easy to get wrong: the old unique
> constraint has a Hibernate-generated name that **differs per environment** (look it up
> in `SYSCAT.TABCONST`), and the identifier columns must be `VARCHAR(128)`, not 255 —
> a four-column unique key over `VARCHAR(255)` exceeds DB2's index key limit and is
> rejected with `SQL0613N`.

### Step 4 — Update Golden Layer field mappings

Edit `backend/src/main/resources/metadata/field-catalog-seed.yml` — replace every `CHANGE_ME.*` live expression with the **fully-qualified** Golden Layer column reference, `<catalog>.<schema>.<table>.<column>` (e.g. `iceberg_gold.golden_layer.tbl_beneficiary.age_years`).

A bare `table.column` only resolves if the JDBC URL still carries a default catalog/schema, which is not guaranteed in client Dev — so qualify these fully rather than relying on the connection.

Alternatively, configure mappings after deployment via the Admin UI (see [Section 8](#8-post-deployment-admin-configuration)).

### Step 5 — Build and start app containers only

```bash
docker compose -f docker-compose.yml -f docker-compose.client-dev.yml up --build srse-backend srse-frontend
```

This sets:

- `DATA_MODE=live`
- `SPRING_PROFILES_ACTIVE=client-dev`
- `SRSE_AUTH_MODE=rajsewadwar`
- JDBC URLs from `CLIENT_*` variables

### Step 6 — Verify live connectivity

```bash
curl -s https://srse-api.your-domain.gov.in/api/health/planes
```

Both planes must report `"up"`. Then open the Admin page and confirm field mappings resolve to real columns.

---

## 8. Post-deployment admin configuration

Open http://localhost:3000/admin456 (or your deployed equivalent). This route is intentionally unlinked from the main navigation.

The panels are ordered as a workflow — connect, then register, then map. Work through them in order on a fresh deployment.

### Panel 1 — Connections

View and edit JDBC connection settings for both planes. Analytical (Presto) edits apply immediately after a successful test-connect; operational (DB2) edits are saved but need a backend restart — the UI says which.

The Presto URL is **catalog-agnostic**: `jdbc:presto://host:8080` is complete. Any trailing `/catalog/schema` is only a default, because SRSE addresses every table by its full `catalog.schema.table` and one connection must reach every registered catalog.

Connection overrides are persisted to `connection-overrides.properties` (Docker volume `connection-overrides`) so admin edits survive container recreation.

### Panel 2 — Lakehouse registry (Catalog › Schema › Table)

This is the seam between what the connection can physically reach and what officers are offered. Nothing appears in the Analysis tab until a table is registered here.

1. Pick **Catalog** → **Schema** → **Table**. Each dropdown reads the live lakehouse (`SHOW CATALOGS`, then that catalog's `information_schema`), so it shows exactly what the current connection can see.
2. Optionally tag the **Layer** — `SILVER` or `GOLD`. This is a display tag that keeps same-named tables in two layers distinguishable; it is free text, so a third layer needs no code change.
3. **Register table.**

Registering exposes **all** of the table's columns to officers. Columns are never copied into DB2 — they are re-read from the lakehouse on every use, so a column added upstream appears without re-registration and a dropped one disappears instead of lingering as a broken reference.

Register both sides of any reconciliation you intend to run: a Silver↔Gold comparison is an ordinary match between two registered tables that happen to sit in different catalogs.

**Unregister** removes a table from the officer-facing lists. Nothing in the lakehouse is touched.

### Panel 3 — Field mappings (Rule Engine plane)

Map each officer-facing **field key** (e.g. `age_years`, `district`) to a physical Presto expression for the active `DATA_MODE`:

| Data mode | Mapping source |
|-----------|----------------|
| `SYNTHETIC` | Seeded from `field-catalog-seed.yml` → `beneficiary.*` columns |
| `LIVE` | Admin-configured → fully-qualified Golden Layer columns |

Live expressions must be fully qualified — `catalog.schema.table.column`. Use **Pick from lakehouse…** to build one from the live schema rather than typing it; the free-text field remains editable for Tier-2 expressions (e.g. computing age from a DOB column), which are not a single column reference.

Officers only ever see flat field keys in the rule builder. Joins and cross-table calculations must be pre-materialised upstream (Tier 3 fields).

### Panel 4 — Analysis column business names, fuzzy matching & visibility

Per-column overrides for the Analysis tab, scoped to a **registered** table:

- **Business name** — the officer-facing label. Unset columns fall back to the raw column name.
- **Fuzzy matchable** — offers approximate (Levenshtein) matching for that column. Unset columns fall back to a name-substring guess, matching the backend's own rule.
- **Visible** — uncheck to hide a column from officers. This is how you narrow a wide registered table; registration exposes everything by default.

Entries are keyed by the full `catalog.schema.table.column`, so the same column name in the Silver and Gold layers carries independent metadata.

### Field catalogue bootstrap

On every backend boot, `FieldCatalogSeedRunner` upserts entries from `field-catalog-seed.yml`. Existing catalogue rows are not overwritten — only missing fields are inserted.

---

## 9. Optional: SonarQube code quality scans

### One-time SonarQube setup

1. Run SonarQube locally (default http://localhost:9012).
2. Create project: **Project key** `SRSE`, **Display name** `SRSE`.
3. Generate token: **My Account → Security → Generate Token**.
4. Add to `.env`:

```bash
SONAR_HOST_URL=http://localhost:9012
SONAR_TOKEN=<your-token>
```

### Run a scan

```bash
./scripts/sonar-scan.sh
```

Dashboard: http://localhost:9012/dashboard?id=SRSE

### Optional pre-push hook

```bash
git config core.hooksPath .githooks
```

The pre-push hook runs the same scan and blocks push on failure.

---

## 10. Troubleshooting

### DB2 slow or backend shows `operational: down`

- DB2 community image first boot takes **5–10+ minutes**, longer on Apple Silicon.
- Check logs: `docker compose logs db2`
- Confirm password matches: `SRSE_DB2_PASSWORD=srse_local_pw` (local compose default).

### Presto OOM — "Internal Server Error" on Preview, or a seed that stops mid-run

The single most likely cause of a `500` from `/api/decision/preview` or an
Analysis match that dies partway. Presto is a **single-node local stand-in with a
4 GB heap** (`docker/presto/jvm.config`); a large enough query will exhaust it.
A wide fuzzy match across two large tables is more than enough — the join is
evaluated before any display cap applies, so "only 20,000 rows shown" says
nothing about how much Presto had to hold.

**Recognising it.** The confusing part is that `docker compose ps` keeps showing
Presto as **`Up`** while every query fails. `jvm.config` sets both
`HeapDumpOnOutOfMemoryError` and `ExitOnOutOfMemoryError`, so on OOM the JVM
first writes a multi-GB heap dump and only then exits — for those minutes the
container is running but answering nothing. Confirm with:

```bash
docker compose logs presto | grep -i outofmemory
docker compose exec -T presto curl -s --max-time 5 http://localhost:8080/v1/info
```

An empty response from the second command with a healthy-looking container is
the signature. `/api/health/planes` reports it as
`analytical: error: ... Error executing query`.

**Recovering.**

```bash
docker compose restart presto
docker compose up seed          # repopulate if the seed was interrupted
```

Presto now carries `restart: unless-stopped`, so once the JVM actually exits
Docker brings it back on its own — previously it stayed dead and every request
failed until someone restarted it by hand. The heap dump is kept (it survives a
restart in the container's writable layer, retrievable with `docker cp`); it is
the reason recovery is not instant.

**Avoiding it.** Give Docker Desktop more memory, raise `-Xmx` in
`docker/presto/jvm.config`, or constrain the query — add a second match column,
raise the fuzzy threshold, or apply the age filter. Note that SRSE deliberately
does not cap match output server-side (see `RecordMatchService`), so the query
timeout is the only backstop.

### Metastore exits on restart, taking Presto's Iceberg catalog with it

**Fixed** — recorded here because the symptom is confusing and may appear in
older stacks. The `apache/hive:3.1.3` entrypoint ran
`schematool -initSchema` unconditionally and exited 1 when the schema already
existed:

```
Error: FUNCTION 'NUCLEUS_ASCII' already exists. (state=X0Y68,code=30000)
Schema initialization failed!
```

Because the Derby metastore lives in the container's writable layer, this made
the stock behaviour correct exactly once: `docker compose up` worked, and every
later `start`/`restart` failed. Presto then reports every Iceberg query as a
missing schema or table, which looks like a Presto fault rather than a
metastore one.

`docker/hive/metastore-entrypoint.sh` now checks `schematool -info` and only
initialises when there is no schema, then delegates to the image's own
entrypoint. If you are on an older checkout, recreate the container to recover:

```bash
docker compose rm -sf metastore && docker compose up -d metastore
```

**Note the trade-off:** the metastore has no volume, so *recreating* the
container (as opposed to restarting it) discards all Iceberg table metadata.
Presto will then show empty schemas until `docker compose up seed` repopulates
them — and any table you created by hand, such as the `iceberg_silver` fixture
in [Section 4.3](#43-register-a-lakehouse-table-required-before-the-analysis-tab-works),
must be recreated too. Restarts are safe; recreates are not.

### `analytical: down` after stack is up

- Confirm Presto is listening: `curl http://localhost:8081/v1/info`
- Check seed completed: `docker compose logs seed`
- Verify JDBC URL uses port **8081** from the host (mapped from container 8080).

### Frontend cannot reach backend (CORS / network errors)

- `NEXT_PUBLIC_API_BASE` must be the URL the **browser** uses — not `http://srse-backend:8080`.
- `SRSE_FRONTEND_ORIGINS` must include the exact frontend origin (scheme + host + port).
- After changing `NEXT_PUBLIC_*`, rebuild the frontend container.

### Maven / Mockito test failures

```
Detected JDK version 25... is not in the allowed range [17,18)
```

Pin Java 17:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

### Port conflicts

| Port | Service | Remediation |
|------|---------|-------------|
| 3000 | Frontend | Stop other Node apps or change compose port mapping |
| 8080 | Backend | Stop other Spring apps |
| 8081 | Presto | Stop other Presto instances |
| 50000 | DB2 | Stop other DB2 containers |
| 9010/9011 | MinIO | Remapped to avoid clash with SonarQube on 9000 |

### `503` — "Field 'x' has no physical mapping for this environment yet"

The Golden Layer column names have not been bound yet: the LIVE mapping is still
the shipped `CHANGE_ME` placeholder. Expected on a fresh client deployment —
nothing is broken, the environment is simply not finished being configured.

Fix it in either place:

- **Admin page** (no redeploy) — `/admin456` → *Field → catalog/schema/table/column
  mappings* → switch to **Live**. Every unbound field is flagged
  **⚠ not configured**, with a count at the top of the panel. Use
  *Pick from lakehouse…* to build each fully-qualified reference.
- **`field-catalog-seed.yml`** — replace the `CHANGE_ME.*` entries. Note the seed
  only inserts rows that do not exist yet, so this affects new deployments; an
  environment that has already booted must be corrected through the Admin page.

**If you are on an older build**, the same misconfiguration surfaced very
differently and far less helpfully:

```
Decision service error 500: Query failed:
Table silver_data.jan_aadhaar_txn.change_me does not exist
```

The placeholder was emitted into SQL as a table name, and Presto resolved it
against the connection's default catalog/schema. The table it names has never
been configured anywhere — it is the literal placeholder — so it does not appear
in the Admin registry and searching for it leads nowhere. The resolver now
refuses to hand a placeholder to the compiler, so this cannot happen.

### RajSewadwar auth rejects requests

Client Dev sets `SRSE_AUTH_MODE=rajsewadwar`. Real SSO integration is pending (Arvind). Until then, use `mock` mode for integration testing.

---

## 11. Configuration reference

### File map

| File | Purpose |
|------|---------|
| `.env.example` | Local laptop template |
| `.env.client-dev.example` | On-prem connection template |
| `docker-compose.yml` | Full local stack |
| `docker-compose.client-dev.yml` | Override for live deployment |
| `backend/src/main/resources/application.yml` | Spring Boot defaults and profiles |
| `backend/src/main/resources/metadata/field-catalog-seed.yml` | Bootstrap field catalogue + mappings |
| `sonar-project.properties` | SonarQube project key and paths |
| `scripts/sonar-scan.sh` | Local SonarQube scan script |

### Spring profiles

| Profile | Used when | Key behaviour |
|---------|-----------|---------------|
| `local` | Docker laptop stack | `ddl-auto=update`, fast-fail Hikari (3 s) |
| `client-dev` | On-prem deployment | No auto DDL; real DB2 schema required |

Resolver selection (synthetic stub vs JPA-backed metadata) is keyed off **`DATA_MODE`**, not Spring profile.

### Default local URLs summary

```
Frontend:     http://localhost:3000
Backend API:  http://localhost:8080
Swagger:      http://localhost:8080/swagger-ui.html
Health:       http://localhost:8080/api/health/planes
Presto UI:    http://localhost:8081
MinIO console:http://localhost:9011  (minioadmin / minioadmin)
DB2:          localhost:50000  (db2inst1 / srse_local_pw)
```

### Support contacts (open items)

| Topic | Contact |
|-------|---------|
| Presto / Golden Layer / DB2 placement | Lovadeep |
| RajSewadwar SSO / RBAC | Arvind |
| CP4BA / ODM integration | Arvind / IBM |

---

*Last updated: August 2026 — aligned with commit `7a9c99f` (SonarQube automation + zero open issues).*
