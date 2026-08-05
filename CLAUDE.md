# SRSE — Scheme Rule Simulation Engine

> **Read this first, every session.** This file is the source of truth for locked
> architectural decisions. Do not re-litigate them. If a change to any decision
> below is proposed, flag it explicitly rather than silently drifting.

## What SRSE is

A standalone, containerised microservice that lets Rajasthan government departmental
officers compose eligibility rulesets for welfare schemes, adjust thresholds
(age, income, etc.), and simulate how many beneficiaries qualify — against live
data in the watsonx.data lakehouse. It replaces ad-hoc PL/SQL + ETL + Excel workflows.

Part of the SMART Project (Government of Rajasthan, DoIT&C / RISL; delivered by Deloitte).
SRSE is **independent** of the SMART web portal — its own repo, release rhythm, containers.

## Locked decisions (do not drift)

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | **Standalone microservice**, two containers, own release lifecycle | Decoupled from portal |
| 2 | **Backend: Java 17 / Spring Boot 3.x**, fat-JAR, embedded Tomcat (NOT WAR) | Isolated container → modern LTS; records/switch/text-blocks suit compiler |
| 3 | **Frontend: Next.js 16 / React 19 / TypeScript / HeroUI / Tailwind / Zustand** | Matches portal stack; TanStack Table + Recharts for results |
| 4 | **Two data planes** | see below |
| 5 | **Flat field catalogue** — officers only see single-table fields | Joins/relationships pre-materialised upstream |
| 6 | **DMN-shaped rules** behind a REST decision-service seam | Future swap to CP4BA/ODM without touching execution |
| 7 | **Config-switched data source** `DATA_MODE=synthetic|live` | Runs offline on laptop; connects to lakehouse when deployed |
| 8 | **Push-down execution** — count runs in Presto, never pull rows into app tier | Analytical scale |

## The two data planes (critical — never conflate)

- **Operational plane** → **DB2** via **IBM JCC 11.5.8** + **Spring Data JPA**.
  SRSE's own data: field catalogue, field→column mappings, rulesets (AST as JSON),
  scenario snapshots. Small, transactional, entity-shaped. ORM is right here.
- **Analytical plane** → **PrestoDB 0.297** over **Iceberg** via
  **`com.facebook.presto:presto-jdbc`** + **JdbcTemplate** (raw SQL).
  The beneficiary simulation query. Set-based. NO ORM — Hibernate has no Presto
  dialect, and you never want an ORM mediating an analytical set-query.

> ⚠️ **Driver flavour is PrestoDB, NOT Trino.** watsonx.data 2.3.1 = PrestoDB 0.297
> (Facebook/Presto lineage). Since the 2020 fork these are different drivers.
> Use `com.facebook.presto:presto-jdbc`, never `io.trino:trino-jdbc`.
> (Open item: confirm exact coordinates with Lovadeep against the live endpoint.)

## The flat-catalogue / derived-field contract (load-bearing)

The rule-to-SQL compiler must **NEVER** emit a JOIN or an on-the-fly cross-table
calculation. Three field tiers:

- **Tier 1** — direct column (e.g. `age → beneficiary.age_years`). UI-mappable.
- **Tier 2** — same-table expression (e.g. `date_diff('year', dob, current_date)`). UI-mappable.
- **Tier 3** — cross-table / relationship / temporal (e.g. `is_girl_child_of_hof`,
  `annual_income_3yr_avg`). **Pre-materialised upstream** by Spark ETL / REL-01 into
  a flat Golden Layer column, then exposed to SRSE as an ordinary Tier-1 field.

New cross-table need ⇒ one-time data-engineering step to add the flat column FIRST.

## Java 17 code-style expectations (backend)

- **Records** for AST nodes and immutable DTOs.
- **Sealed interfaces** for the node hierarchy.
- **Switch expressions** for the operator emitter.
- **Text blocks** for SQL templates.
- Lombok available but records supersede it for simple value types.
- Generate idiomatic Java 17 — **do NOT** emit Java 8 idioms.

## Injection safety (non-negotiable)

- Compiler emits **only parameterised SQL** (`?` placeholders + ordered param list).
- Officer values are **always bound parameters**, never string-concatenated.
- Field keys resolve to physical columns **only** via the mapping service (allow-list).
  No user input ever reaches SQL as an identifier.

## Build order (from the design doc §11.2)

1. Scaffold + docker-compose + two datasources + `DATA_MODE` switch (skeleton proves both planes connect).
2. **Rule AST + compiler** (behind the data-access interface, unit-tested). ← the hard core, do first
3. Execution service (JdbcTemplate) + guardrails.
4. Metadata / mapping service (JPA + Caffeine) + admin screens.
5. Scenario store + comparison.
6. Officer UI (rule builder, thresholds, results, compare).
7. Two-container packaging + synthetic seed; client-deploy profile.
8. (Later) IBM-led CP4BA enablement swaps the authoring surface via the seam.

## Guardrails on execution

- Cohort drill-down **hard-capped** (`SRSE_COHORT_CAP`, default 1000); never full populations.
- Query **timeout** enforced.
- Breakdown dimensions fixed: district, gender, age_band.
- Count query returns **aggregates only** — never row-level data.

## Reference

- Full spec: `docs/SRSE_Technical_Design_Document.docx`
- Worked example: Ekal Naari (divorced woman) pension — age ≥ 18, income < ₹48,000,
  domicile, with BPL/Antyodaya + Sahariya/Kathodi/Khairwa income exemption.
- Authentic thresholds from `consolidatedSerSchWiseCriteriaListForStage1And2.xlsx`.

## Open items (owners)

- PrestoDB vs Trino driver coordinates for watsonx.data 2.3.1 — **Lovadeep**
- Golden Layer physical table/column names — **Lovadeep / DBA**
- SRSE operational-store DB2 placement (schema vs instance) — **Lovadeep**
- Pre-materialised derived fields confirmation (REL-01, income 3-yr) — **Lovadeep**
- STATE_OFFICER RBAC role + RajSewadwar SSO payload — **Arvind**
- CP4BA version + IBM enablement scheduling — **Arvind / IBM**

## Environment cheat-sheet

| | Local (laptop) | Client Dev |
|---|---|---|
| DATA_MODE | synthetic | live |
| Presto | local container | on-prem PrestoDB 0.297 |
| Operational DB2 | local container | on-prem DB2 (SRSE schema) |
| Auth | mock JWT issuer | RajSewadwar SSO |
| Field mapping | synthetic columns | real Golden Layer columns |
