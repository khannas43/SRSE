# SRSE — Scheme Rule Simulation Engine

Standalone microservice letting Rajasthan departmental officers compose welfare-scheme
eligibility rulesets, adjust thresholds, and simulate beneficiary counts against the
watsonx.data lakehouse. Part of the SMART Project (DoIT&C / RISL; Deloitte).

> **Read `CLAUDE.md` before any development session.** It holds the locked
> architectural decisions that every Cursor / Claude CLI session must respect.

## Structure

```
srse/
├── CLAUDE.md              # locked decisions — read every session
├── docker-compose.yml     # local stack: 2 app containers + Presto/Iceberg/MinIO/DB2 + seed
├── .env.example           # copy to .env; never commit secrets
├── backend/               # Java 17 / Spring Boot 3.x fat-JAR microservice
│   ├── pom.xml            # DB2 JCC + PrestoDB JDBC + Caffeine + JJWT + springdoc
│   └── src/main/java/gov/rajasthan/smart/srse/
│       ├── config/        # TWO datasources: operational (DB2/JPA) + analytical (Presto/JDBC)
│       ├── compiler/      # ← the hard core: AST (sealed/records) + RuleCompiler
│       ├── execution/     # (build) push-down execution via JdbcTemplate
│       ├── decision/      # (build) REST decision-service seam
│       ├── metadata/      # (build) field catalogue + field→column mapping
│       ├── scenario/      # (build) scenario store + comparison
│       ├── security/      # (build) RajSewadwar SSO + JJWT
│       └── web/           # HealthController (proves both planes connect)
├── frontend/              # Next.js 16 / React 19 / TS / HeroUI / Tailwind / Zustand
│   └── src/{app,store,lib}
└── docker/                # Presto catalog config + synthetic-data seed job
```

## Quickstart (local, offline)

```bash
cp .env.example .env          # adjust if needed
docker compose up             # brings up the full local stack
```

- Frontend → http://localhost:3000
- Backend  → http://localhost:8080  (Swagger at /swagger-ui.html)
- **Both-planes health check** → `GET http://localhost:8080/api/health/planes`
  Expect `{ "operational": "up", "analytical": "up" }` once DB2 + Presto are ready.
  (DB2 community image is slow to first-boot — give it a few minutes.)

## Two data planes (do not conflate)

| Plane | Engine | Driver | Access | Holds |
|-------|--------|--------|--------|-------|
| Operational | DB2 | IBM JCC 11.5.8 | Spring Data JPA | catalogue, mappings, rulesets, scenarios |
| Analytical | PrestoDB 0.297 / Iceberg | `com.facebook.presto:presto-jdbc` | JdbcTemplate (raw SQL) | beneficiary simulation query |

⚠️ **PrestoDB, not Trino.** watsonx.data 2.3.1 = PrestoDB lineage. Confirm exact
driver coordinates with Lovadeep before the first live connection.

## Build order

See `CLAUDE.md` → "Build order". In short:
1. Skeleton + two datasources + health check (this scaffold). ✔
2. **Rule AST + compiler** — the hard core, do first. (Scaffolded; unit-tested against Ekal Naari.)
3. Execution + guardrails ✔ → 4. Metadata/mapping → 5. Scenario store → 6. Officer UI → 7. Packaging.

## Running backend tests locally

If multiple JDKs are installed and `JAVA_HOME` is unset, `mvn` may pick a JDK
newer than 17 (e.g. via Homebrew) for its own JVM. Mockito's inline mock maker
(used by `ExecutionServiceTest`) fails to instrument classes there — so
`JAVA_HOME` must be pinned explicitly:

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test
```

`mvn compile` and `mvn spring-boot:run` are unaffected; only test runs hit this.

## The compiler is already testable

`backend/src/test/.../RuleCompilerTest.java` compiles the real **Ekal Naari**
(divorced woman) ruleset — age ≥ 18, income < ₹48,000, domicile, with the
BPL/Antyodaya + Sahariya/Kathodi/Khairwa income exemption — and asserts the
emitted parameterised Presto SQL and bound-parameter order. Run:

```bash
cd backend && mvn test
```

## Config-switched data source

`DATA_MODE=synthetic` (laptop) | `live` (client Dev). Same codebase; only config
and the field→column mapping differ. No lakehouse connection needed to build or
test locally.

## Code quality (SonarQube)

Local SonarQube scans the full monorepo (Java backend + TypeScript frontend) as a
single project.

### One-time setup

1. Ensure SonarQube is running (default: http://localhost:9012).
2. Create a project manually: **Project key** `SRSE`, **Display name** `SRSE`
   at http://localhost:9012/projects/create
   (Project key is case-sensitive — must match `sonar-project.properties`.)
3. Generate a token: **My Account → Security → Generate Token**
4. Add to `.env` (copy from `.env.example`):

```bash
export SONAR_HOST_URL=http://localhost:9012
export SONAR_TOKEN=<your-token>
```

### Run a scan

```bash
./scripts/sonar-scan.sh
```

This runs backend tests with JaCoCo coverage, then uploads results via
`sonarsource/sonar-scanner-cli` (Docker). Dashboard:
http://localhost:9012/dashboard?id=SRSE

### Optional: scan before every push

```bash
git config core.hooksPath .githooks
```

The pre-push hook calls the same script and blocks the push if the scan fails.
Start with manual scans first — each run takes 1–3 minutes.
