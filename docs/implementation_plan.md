# SRSE Implementation Plan

> Living project plan — tracks what's built, what's pending, and who owns what's blocked.
> Locked architectural decisions live in `CLAUDE.md`; this file tracks status against
> the build order in the design doc (§11.2), not the decisions themselves.

## Status at a glance

| Stage | What | Status |
|---|---|---|
| 1–2 | Scaffold, two datasources, `DATA_MODE` switch, rule AST + compiler | ✅ Done |
| 3 | Execution service (JdbcTemplate push-down) + guardrails | ✅ Done |
| 4 | Metadata / mapping service (JPA + Caffeine) | ✅ Done |
| 5 | Scenario store + comparison | ✅ Done |
| 6 | REST decision-service seam + officer UI (Ekal Naari) | ✅ Done |
| 7 | Two-container packaging + synthetic seed + client-deploy profile | ✅ Done |
| — | Housekeeping + prep for open items (JDK pin, DATA_MODE resolver wiring, field-catalog seed, RBAC/SSO seam) | ✅ Done |
| 8 | CP4BA/ODM authoring-surface swap | ⏳ Not started — blocked on IBM |

---

## Completed

### Stage 1–2 — Scaffold (`4d91837`, `155afb9`)
Two-datasource skeleton (DB2/JPA operational, Presto/JdbcTemplate analytical), `DATA_MODE=synthetic|live` switch, rule AST (`Ast.java`, sealed node hierarchy) and `RuleCompiler` emitting parameterised SQL. Fixed to build on Java 17 proper (no Java 21 switch pattern-matching).

### Stage 3 — Execution service (`1c960bb`)
`ExecutionService` — push-down `count`/`breakdown`/cohort-sample against Presto via `JdbcTemplate`. Guardrails: aggregate-only results, fixed district/gender/age_band breakdown dimensions, hard-capped cohort drill-down (`SRSE_COHORT_CAP`), configurable query timeout.

### Stage 4 — Metadata / mapping service (`28836a8`)
`FieldCatalogEntry` (officer-facing, environment-agnostic) + `FieldColumnMapping` (per-`DataMode` physical binding), both JPA-backed. `MetadataFieldResolver` (Caffeine-cached) for non-synthetic data; `StubFieldResolver` for local dev.

### Stage 5 — Scenario store + comparison (`fabd406`)
`Ast.Node` made JSON-polymorphic so rulesets persist as JSON. `Scenario` entity (ruleset + results snapshot), `ScenarioService` (create/record/load/compare), pairwise breakdown-delta comparison.

### Stage 6 — Decision-service seam + Ekal Naari UI (`0eafdbc`, `69d9776`, `6e28d55`)
`DecisionController` (evaluate/list/get/compare) wired to compiler + execution + scenario services behind a full-ruleset-per-call REST contract (DMN-shaped, CP4BA-swappable later). Frontend: `/ekal-naari` page with TanStack Table breakdown + Recharts district chart. Later extended so all 11 resolvable fields (not just age/income) are independently adjustable — ranges via `BETWEEN`, categoricals via multi-select `IN`, booleans via a three-way Any/Yes/No toggle — verified against live Presto.

### Stage 7 — Packaging + seed + client-deploy profile (`8a20f73`, `ad45533`, `bde54c6`)
Two-container Docker packaging, real `seed.py` (batch INSERT of synthetic beneficiary rows via Presto), `docker-compose.client-dev.yml` override for on-prem Presto/DB2. Fixed along the way: `GuardrailProperties` constructor binding, `/error` permitted through security, DB2 `ddl-auto` silently never applied (fixed + verified against a real DB2 + Presto + 114k-row local stack).

### Housekeeping + prep for open items (`5ebf65a`, `68dfff7`)
- Committed missing frontend build artifacts (`package-lock.json`, `next-env.d.ts`, `public/.gitkeep`) and Docker/tsconfig fixes that had never been checked in.
- Removed a stray untracked duplicate of the whole project (`docs/srse/`) and duplicate local Claude settings files.
- `maven-enforcer-plugin` pins JDK 17 for backend builds — a wrong-JDK shell now fails fast with a clear message instead of cryptic Mockito errors on final classes like `JdbcTemplate`.
- `.gitignore` covers `logs/` and `*.tsbuildinfo`.
- **Resolver selection now keyed off `DATA_MODE`, not Spring profile** — fixes a real gap where `docker-compose.client-dev.yml` set `DATA_MODE=live` but stayed on the `local` Spring profile, so the hardcoded synthetic `StubFieldResolver` silently stayed active instead of the JPA-backed `MetadataFieldResolver`. Client-dev's profile renamed to `client-dev` so it also stops inheriting `local`'s `ddl-auto=update` against a real DB2 schema.
- **`FieldCatalogSeedRunner`** — `field_catalog`/`field_column_mapping` had no seed path in any environment (no migration tool, no admin API). Now upserted on boot from a checked-in YAML (`field-catalog-seed.yml`), with `LIVE`-mode physical expressions as `CHANGE_ME` placeholders ready for Lovadeep's real Golden Layer column names — a one-file edit once those land.
- **RBAC/SSO seam** — `AuthMode` enum, `MockJwtIssuer`/`MockJwtAuthenticationFilter` (STATE_OFFICER-scoped mock tokens for local dev), fail-closed `RajSewadwarAuthenticationFilter` stub pending Arvind's real SSO payload spec. `/api/decision/**` now actually gated behind `STATE_OFFICER` instead of `permitAll()`. Frontend `decisionApi.ts` fetches/attaches the mock token so the Ekal Naari UI keeps working end-to-end.
- Verified: 25/25 backend tests green under JDK 17, frontend `tsc --noEmit` and `next build` clean, new HTTP-level tests (`SecurityConfigTest`, `RajSewadwarAuthModeTest`) confirm the RBAC seam actually rejects/accepts requests as designed.

---

## Pending

### Not yet actioned
| Item | Notes |
|---|---|
| Git author identity | Commits are landing with an auto-inferred name/email from the machine hostname rather than a configured `user.name`/`user.email`. |
| Full docker-compose verification | Client-dev's `DATA_MODE`/profile wiring and the mock-login flow are verified at the unit/HTTP-slice level only — not yet run against a real booted DB2 + Presto stack (DB2 boot is slow under Apple Silicon emulation). |

### Blocked on external owners (CLAUDE.md open items)
| Item | Owner |
|---|---|
| PrestoDB vs Trino driver coordinates for watsonx.data 2.3.1 | Lovadeep |
| Golden Layer physical table/column names (to replace `CHANGE_ME` placeholders in `field-catalog-seed.yml`) | Lovadeep / DBA |
| SRSE operational-store DB2 placement (schema vs instance) | Lovadeep |
| Pre-materialised derived fields confirmation (REL-01, income 3-yr avg) | Lovadeep |
| Real RajSewadwar SSO payload shape (filter seam exists and fails closed; only the parsing is stubbed) | Arvind |
| CP4BA version + IBM enablement scheduling | Arvind / IBM |

No code changes are possible on the above until each owner delivers their input — the codebase is already structured (via `DataMode`/`AuthMode`-keyed seams) so each one is a small, contained change once it lands, not a redesign.

### Stage 8 — CP4BA/ODM authoring-surface swap
Not started. Explicitly deferred per the design doc until IBM-led enablement is scheduled. The REST decision-service seam (Stage 6) already exists specifically so this swap only replaces what's behind `DecisionController`, not the caller contract.
