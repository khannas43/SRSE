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

## Lakehouse addressing: Catalog → Schema → Table → Column (load-bearing)

SRSE addresses lakehouse objects by their **full four-part path**. A bare table
name is NOT an address: the deployment maps at least two layers (**Silver** and
**Gold**) whose catalog, schema and table names all differ, and the same table
name legitimately exists in more than one of them.

- **The Presto connection is catalog-agnostic.** `jdbc:presto://host:8080` is a
  complete URL. Any trailing `/catalog/schema` is only a *default* — never
  relied on. (It used to be the single implicit catalog+schema for the whole
  app; that model could not express multiple catalogs.)
- **Everything is qualified.** `field_column_mapping.physical_expression` in
  LIVE mode, `analysis_column_metadata`'s key, and every identifier the
  Analysis tab's match SQL emits are `catalog.schema.table[.column]`.
- **Two reaches, never conflated** (`gov.rajasthan.smart.srse.lakehouse`):
 - `LakehouseBrowseService` — the LIVE cluster, everything the connection can
 reach. **Admin-only** (`SRSE_ADMIN`), for discovery — enforced in
 `SecurityConfig`, not only in docs.
  - `LakehouseRegistryService` — the admin-registered subset, persisted in DB2
    (`registered_table`). **Everything officer-facing reads this.**
- **Registration is per TABLE; columns are never copied into DB2.** Registering
  exposes all of a table's live columns, re-read on every call — so a column
  added upstream appears without re-registration, and a dropped one disappears
  instead of lingering as a reference that compiles into a broken query.
  Individual columns are hidden (and given business names / fuzzy flags) via
  `analysis_column_metadata`.
- **`layer` (BRONZE/SILVER/GOLD/…) is a display TAG, not a hierarchy level.**
  Registration requires a layer tag for new rows; config import may still
  restore null-layer rows. Legacy null-layer registrations stay reachable in
  the officer cascade under the wire sentinel `UNTAGGED` (filter only — never
  a stored tag name). The Analysis cascade leads with Layer as a **registry
  filter**; the submitted address stays `catalog.schema.table`. Layer never
  enters a request payload, a validation gate, or emitted SQL. A Silver↔Gold
  reconciliation is an ordinary two-table match whose sides carry different
  catalog/schema values; nothing in the query path special-cases layer.

### Injection safety at this seam (extends the non-negotiables below)

Catalog and schema names must be **interpolated**, not bound — Presto has no
placeholder for a catalog qualifier, so `?.information_schema` is not valid SQL.
Two mechanisms cover that, and **both** must stay:

1. **Allow-list (primary).** Each level is validated against the level above
   before use: catalog against `SHOW CATALOGS`, schema against that catalog's
   `information_schema.schemata`, and so on. Only names the lakehouse itself
   reported reach SQL text.
2. **Identifier grammar (defence in depth).** `LakehouseIdentifiers` rejects
   anything that is not a bare Presto identifier — including on the first,
   not-yet-validated use, since the validation query must itself interpolate
   the catalog to run at all.

Officer-supplied identifiers additionally pass **two gates**
(`LakehouseRegistryService.validateColumn`): the table must be *registered*, and
the column must exist *live* and not be hidden. Neither alone is sufficient —
the registry can name a since-dropped table, and live introspection alone would
let an officer reach any table on the cluster.

### Cross-type comparison (Analysis match + Rule Engine)

Two tables rarely agree on a type. The same account number is `varchar` in the
transaction table and `bigint` in the Golden Layer; an income is `varchar` where
the catalogue calls it a NUMBER. **Presto does not coerce across type families**,
so those comparisons did not return zero rows — they failed the whole query
(`'=' cannot be applied to varchar, bigint`; `lower(bigint)` →
`Unexpected parameters`).

- `compiler/SqlTypeFamily` + `compiler/TypeCoercion` are the single place that
  decides a cast. **Within** a family nothing is emitted — Presto already
  coerces integer↔bigint and varchar(20)↔varchar(50), and a cast there would
  only change results. An **UNKNOWN** type (row/array/map/varbinary) is left
  exactly as it is rather than guessed at.
- **Number vs text compares as NUMBERS by default** (`TRY_CAST` on the text
  side). A value stored as a number has already lost its leading zeros, so
  `'0123'` and `123` are the same identifier recorded twice; comparing as text
  would systematically miss exactly the rows being looked for. `TRY_CAST`, never
  `CAST`: one unparseable row must not fail the match.
- **Admin override per column** — `analysis_column_metadata.compare_as`
  (`AUTO`/`NUMBER`/`TEXT`), set on the Admin page. An explicit setting on
  *either* side wins; if the two sides conflict, TEXT wins (it can represent
  every value, so it can lose matches but never nulls a side away).
- **Fuzzy pairs always compare as text** — Levenshtein is a string measure — so
  a non-text side is cast for the blocking key and the similarity alike.
- **Rule Engine**: `MetadataFieldResolver.resolveColumn` casts the column to
  meet the field's declared data type, since the officer's value is already
  bound over JDBC and cannot move. Only a plain four-part binding is coerced (a
  Tier-2 expression is already typed); introspection failure falls back to the
  uncast binding rather than taking a preview down. Callers that take the
  binding APART rather than comparing it use `resolveRawColumn` — a cast around
  it would strip to nonsense.
- Boolean and temporal fields are never coerced: `TRY_CAST` would turn the
  `'Y'`/`'N'` flags this data is full of into silent NULLs, and a date's text
  format is anybody's guess. Better to fail loudly than answer wrongly.

## Java 17 code-style expectations (backend)

- **Records** for AST nodes and immutable DTOs.
- **Sealed interfaces** for the node hierarchy.
- **Switch expressions** for the operator emitter.
- **Text blocks** for SQL templates.
- Lombok available but records supersede it for simple value types.
- Generate idiomatic Java 17 — **do NOT** emit Java 8 idioms.

## Scheme official criteria vs officer scenarios (Package 7 — fork, never overwrite)

Each scheme may nominate one saved **scenario** as its official template
(`scheme.template_scenario_id`). **Officer workflow:** loading that template
into the Rules builder is a starting point only — **Save always creates a new
scenario**; it never overwrites the template or mutates an existing scenario's
ruleset. **Admin workflow:** only **`SRSE_ADMIN`** may change which scenario is
the template (`PUT /api/schemes/{id}/template`).

This is enforced by construction today, not only by UI copy:

- Scenario rulesets are **write-once** at create time.
- **`DecisionController` exposes no PUT/PATCH/DELETE** under
  `/api/decision/scenarios/**` — only GET (list/detail) and POST (create).
- **`ScenarioService` has no ruleset mutator.** A future "edit scenario"
  convenience route would re-open the overwrite path this decision forbids;
  `DecisionScenarioRoutesGuardTest` exists to catch that.

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
  `POST /api/decision/cohort` is the only endpoint in SRSE that returns row-level
  beneficiary data; the cap is applied in `ExecutionService` and cannot be raised
  by a caller argument. The response states the limit it used and whether the
  sample was truncated, so 1000 rows of a 40-lakh cohort cannot be misread as the
  cohort itself. The Rules tab preview calls `/cohort` alongside `/preview` for a
  representative sample; `/preview` stays aggregates-only.
- Rules preview sample uses **`SELECT *`** (full beneficiary row). Deliberate:
  SRSE is shown to selected departmental users, so the whole row on screen is
  acceptable; do not “fix” this by projecting a subset without an explicit product
  decision.
- `SRSE_PREVIEW_SAMPLE_SIZE` (default 50) is the **default sample size** the Rules
  UI preselects — not a second cap. `SRSE_COHORT_CAP` remains the only hard ceiling.
- Query **timeout** enforced.
- Breakdown dimensions fixed: district, gender, age_band.
- Count query returns **aggregates only** — never row-level data.
- **Join-key suggestions** (`POST /api/analysis/suggest-keys`): metadata-only by
  default; optional overlap probe samples the **source** only (`TABLESAMPLE
  BERNOULLI`) and scans the **target in full** per pair (semi-join), capped by
  `SRSE_ANALYSIS_MAX_PROBED_PAIRS` and query timeout. Source key-likeness uses
  one full-table `approx_distinct/count` on the source (not sampled — sampling
  inflates mid-cardinality ratios). That floors attributes (e.g. district)
  below join keys (e.g. id).
- **Post-join column comparison** (`comparisonGroups` on `RecordMatchRequest` /
  `TargetMatchSpec`): {@link ComparisonGroup} is separate from {@link MatchGroup}
  so pairs cannot reach the ON emitter. Values are projected in SELECT only
  (`cmp_<n>_source`, `cmp_<n>_target`, `cmp_<n>_match`, optional
  `cmp_<n>_score_pct` for fuzzy); exact compares use
  `NOT (a IS DISTINCT FROM b)` (both-null = match). On **outer** joins (not
  INNER), also emits `match_status` (`MATCHED` / `NO_TARGET` / `NO_SOURCE`) from
  join-key NULLability; no-counterpart rows get **NULL** verdicts (not false).
  `mismatchOnly` filters with `match_status <> 'MATCHED' OR NOT (all verdicts)` so
  unmatched rows are not dropped by three-valued logic. **`match_status` is omitted
  for INNER** (including INNER + comparisons) so no-comparison requests stay
  byte-identical. Aggregate rates:
  `POST /api/analysis/match/comparison-summary` — per-column rates over **matched**
  rows only; `noCounterpartRows` reported separately. Comparisons do not affect
  fan-out estimate, dedup, match score, or age filter. Cap: 8 groups, same per-side
  column cap as join groups.
- The Analysis match is deliberately **uncapped server-side** (an earlier top-500
  pre-sample made matches unfindable at crore scale). Large results are handled
  where they actually hurt — the browser: past **10,000 rows** the grid, its
  filters and the charts are not rendered and the buffered rows are dropped, and
  the result is offered as a CSV download instead. That download
  (`POST /api/analysis/match.csv`) re-runs the match and streams straight from
  Presto to the file, so it is the COMPLETE result — never a re-serialisation of
  what the screen was holding. Reading the NDJSON stream still stops at 200,000
  rows, after which the on-screen count is a lower bound (`200000+`) but the CSV
  remains complete.
- **Multi-target Analysis** (`POST /api/analysis/match-multi`) is **N × two-table
  JOIN**, never an N-way join — one hub table, one target table per sub-match.
  Each `TargetMatchSpec` carries its own `joinType` (null → INNER); sub-matches
  delegate to `RecordMatchService` so outer-join predicate routing
  is not duplicated. Hub is always {@code src}: **LEFT** preserves hub rows (target
  columns NULL when that target has no match); **RIGHT** preserves that target's
  rows. **Dedup** (hub-only) is rejected per target on RIGHT/FULL for the same
  reason as two-table mode. **Target↔target and arbitrary N-way joins remain out
  of scope** — the hub model is what gives per-target failure, per-target budget,
  and hash joins at crore scale; a 3-character fuzzy blocking key already produced
  ~145M candidate rows on an 86k × 20k name match locally. Reopen N-way only with
  table statistics Presto can use, the cardinality pre-check below, and
  officer-pinned join order. Partial failure is **per target** in the NDJSON stream;
  **`match-multi.csv` is all-or-nothing** (one failed target aborts the download).
  Each target's SQL rides its own `started` progress event, never the `meta` line.
- **Match fan-out guard:** before executing, `RecordMatchService` estimates
  equi-join output as {@code sourceRows × targetRows / ∏ max(sourceDistinct,
  targetDistinct)} per group. Inputs come from **Presto's catalog statistics**
  (`SHOW STATS`) when `ANALYZE` has been run on the table, and are computed live
  (`count(*)` + `approx_distinct`) when it has not — the fallback is silent and
  automatic, so a deployment that never runs ANALYZE still works, just with two
  extra aggregates per match on the officer's path. A **fuzzy** group always
  computes: it joins on the blocking-key expression, and no statistic describes
  `substr(lower(col), 1, n)`. Measured locally against ground truth: stats-fed
  estimates landed within 0.9% on a unique key (19,813 vs 20,000) and 0.00003%
  on a low-cardinality column (571,457,142 vs 571,457,293). Above
  `SRSE_ANALYSIS_MAX_ESTIMATED_ROWS` (default 50,000,000) the request is refused
  with the estimate in the message — not a silent query timeout. Statistics go
  stale as data lands; that is acceptable for a refusal threshold but wants a
  scheduled `ANALYZE`, which is a full scan.
  `SRSE_ANALYSIS_BLOCKING_PREFIX_LEN` (default 3) controls fuzzy blocking;
  longer prefixes block harder and cost recall on typos in the first N characters;
  shorter ones explode the candidate set.

- **Two-table join types** (`joinType` on `RecordMatchRequest`, default INNER).
  Officers pick INNER / LEFT / RIGHT / FULL on the Analysis tab; omitted/null
  deserialises as INNER so stored requests stay valid. **INNER SQL must stay
  byte-identical** to pre–join-type behaviour (bare `JOIN`, fuzzy similarity in
  WHERE). For outer joins, **any predicate that references a nullable side belongs
  in ON, not WHERE** — especially fuzzy Levenshtein, which used to live in WHERE
  and silently converted LEFT back to INNER by filtering away unmatched rows.
  **Age filter:** INNER unchanged (both sides when the column exists); LEFT applies
  only on the preserved source side, RIGHT only on the preserved target side; FULL
  rejects the age filter. **Dedup** is rejected with RIGHT/FULL — partitioning on
  `source_*` columns collapses unmatched rows (NULL keys) into one partition.
  **Do not re-add `LEFT JOIN UNNEST` for ANY_OF (Presto rejects it).** PrestoDB 0.297
  fails at analysis with “UNNEST on other than the right side of CROSS JOIN is not
  supported” — the SQL parses and is submitted, so `EmittedSqlParsesTest` cannot
  catch it. It is also unnecessary: unlike COMBINE’s `filter(...)` array, the ANY_OF
  array is positional (`ARRAY[a, b]`) and never empty, so an all-null candidate row
  yields N null-keyed rows and survives `CROSS JOIN UNNEST` regardless. ANY_OF
  therefore always emits **`CROSS JOIN UNNEST`**, including on a preserved outer side.
  **Self-join:** pick the same registered table on source and target —
  no separate feature. **`POST /api/analysis/match.sql`** plans and returns display
  SQL without executing (same path as the streamed match).

- **Analysis column groups.** A match criterion is a **group** of 1..N columns
  per side, so the two sides need not be the same size — one `full_name` against
  `first_name` + `last_name`. A group with one column on each side emits
  byte-identical SQL to the criterion pair it replaced.
  - **COMBINE** folds the many side with
    `array_join(filter(ARRAY[...], x -> x IS NOT NULL AND x <> ''), sep)` — NOT
    `concat_ws`, which would leave a doubled separator where a middle name is
    NULL and charge every such row a Levenshtein edit. Column order is the
    officer's and it matters.
  - **ANY_OF** matches when one side equals any of the other's columns, and is
    emitted with **`CROSS JOIN UNNEST`, NEVER as `OR` in the ON clause**. A
    disjunctive ON costs Presto the hash join and drops it to a nested loop over
    the cross product — the same failure the top-500 pre-sample was removed for.
    Each ANY_OF side projects a `matched_on` column saying which candidate
    column matched, since two columns holding one value legitimately return the
    pair twice.
  - A **multi-column COMBINE always compares as text**, so `compare_as` does not
    apply to it: reading a concatenated name as a number would `TRY_CAST` every
    row to NULL and return nothing, silently.
  - **Fuzzy is decided per GROUP, not per column.** Registered columns decide as
    a bloc — an unregistered column beside a registered one gets no vote, so the
    `*name*` guess can never overturn an explicit Admin setting, and the guess
    applies only when nothing in the group is registered. When registrations
    inside a group **disagree, fuzzy wins**: a group wrongly forced exact
    returns almost nothing and reads as "these datasets do not overlap", while
    one wrongly made fuzzy returns extra rows that carry `match_score_pct` and
    are tunable with the officer's own threshold. Visible over silent — the same
    trade `CompareAs.resolve` makes when TEXT wins. `RecordMatchService.isGroupFuzzy`
    and `analysis/page.tsx`'s `pairIsFuzzy` must stay in lockstep.
  - Caps: 8 groups, `SRSE_ANALYSIS_MAX_GROUP_COLUMNS` (4) columns per side per
    group, `SRSE_ANALYSIS_MAX_ANYOF_GROUPS` (2) ANY_OF groups per side — two
    UNNESTs on one side cross-multiply that side's rows.
  - **Emitted SQL is checked against Presto's OWN grammar**, not only against
    the substrings a test expected: `EmittedSqlParsesTest` runs every shape
    through `com.facebook.presto:presto-parser`, test-scoped and pinned to the
    same version as the driver. A new construct that a string assertion would
    wave through fails here instead of in front of an officer. **Syntax only** —
    it proves nothing about types resolving or rows being right.
  - Both modes have been **executed end-to-end** against the local
    `prestodb/presto:0.297` container, cross-catalog (`iceberg` ↔
    `iceberg_silver`), with a `bigint`/`varchar` ANY_OF candidate pair: the
    UNNEST pivot returns the same pair once per matching column with
    `matched_on` naming it, and a COMBINE fold matched `Geeta Kumari` against
    `Geetha` + `Kumari` at 92.3%. Not automated — it needs the container — so
    re-run it by hand when this seam changes.
  - **Join types (Package 4, manual):** re-run all four join types on the local
    Presto container when changing `RecordMatchService` join emission — including
    (a) fuzzy pair, (b) ANY_OF with an all-NULL candidate row on the preserved
    side under LEFT, (c) a source row with no target match visible under LEFT.
    `EmittedSqlParsesTest` proves syntax only; outer joins that parse but mis-route
    predicates pass it while returning wrong rows.
    **Recorded run (2026-09, `prestodb/presto:0.297`, cross-catalog
    `iceberg.srse.beneficiary` 86,000 rows ↔ `iceberg_silver.silver_txn.tbl_txn_bankdtl`
    20,001 rows):** LEFT with fuzzy similarity in **ON** → 86,000 rows (unmatched
    source preserved). Same plan with similarity in **WHERE** → **0 rows** (silent
    INNER). RIGHT → 100 rows (unmatched target preserved). FULL → 200 rows (100 with
    NULL source). LEFT + ANY_OF on preserved side with **`CROSS JOIN UNNEST`** → 200
    unmatched preserved rows.     All-null ANY_OF candidate through `CROSS JOIN UNNEST`
    → 2 null-keyed rows (survives).
    **Multi-target per-target LEFT (2026-09, same container, hub
    `iceberg.srse.beneficiary` ↔ `iceberg_silver.silver_txn.tbl_txn_bankdtl`,
    target joinType LEFT, fuzzy name pair):** hub rows with no match in that target
    appear in the merged grid with hub columns populated and that target's
    prefixed columns empty (not dropped, not the string "null").
    **Post-join comparison (Package 12, manual):** key join on `m_id` with three
    `comparisonGroups` where one joined row differs in exactly one column — row
    appears with that column's `cmp_*_match` false and others true; a both-null
    compared pair reads as match. **LEFT + one source row with no target:** that
    row shows `match_status = NO_TARGET` and NULL (not false) on every verdict.
    Re-run when `planComparisons`, `match_status`, or NULL semantics change
    (confirm `IS DISTINCT FROM` on PrestoDB 0.297).
    **Analyzer validate (optional, catches parse-green / analyze-red defects):**
    {@code SRSE_PRESTO_INTEGRATION=true mvn -pl backend test -Dtest=AnalysisEmittedSqlPrestoValidateIT}
    runs {@code EXPLAIN (TYPE VALIDATE)} on documented shapes — not in the default build.
    **Fan-out pre-check (2026-09, same tables):** `m_id` join estimate
    ~19,382 vs **20,001** actual rows (allowed). `district` join estimate
    ~245,726,571 vs **245,675,819** actual (refused above 50M ceiling).

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
- RajSewadwar SSO payload + role→authority mapping (binds onto **`SRSE_ADMIN`**
  and **`STATE_OFFICER`** at `RajSewadwarAuthenticationFilter.grantedAuthoritiesFromSsoRoles`;
  Aadhaar OTP / project dev team wires here — not `SecurityConfig`) — **Arvind**
- CP4BA version + IBM enablement scheduling — **Arvind / IBM**

## Environment cheat-sheet

| | Local (laptop) | Client Dev |
|---|---|---|
| DATA_MODE | synthetic | live |
| Default UI label (display only) | Development | Production (Live) |
| Optional override | `SRSE_ENV_LABEL` (e.g. UAT) — UI only; does not change `DATA_MODE` | same |
| Presto | local container | on-prem PrestoDB 0.297 |
| Operational DB2 | local container | on-prem DB2 (SRSE schema) |
| Auth | mock JWT issuer (`?role=admin` mints admin+officer; **seam only**, not ACL) | RajSewadwar SSO |
| Field mapping rows | `field_column_mapping` keyed by `SYNTHETIC` | keyed by `LIVE` |

`DATA_MODE` / `DataMode.SYNTHETIC | LIVE` are unchanged on the wire, in
`application.yml`, and as the key of `field_column_mapping`. Admin UI labels
(Development / Production (Live) / custom via `SRSE_ENV_LABEL`) are display
only and must not be confused with the mapping editor's binding-set switch.

Both modes resolve field bindings the same way, through `field_column_mapping`
keyed by `DataMode` (`MetadataFieldResolver`). There is no longer a hardcoded
synthetic resolver: the Admin mapping editor is authoritative in synthetic mode
too, so an edit there takes effect immediately and a newly added field
resolves. Synthetic bindings are seeded from `field-catalog-seed.yml` at boot.
