-- ============================================================================
-- SRSE migration 002 — per-column comparison override (compare_as)
-- Target: DB2 (operational plane).  Run ONCE per environment that already has
-- an analysis_column_metadata table AND does not run ddl-auto — i.e. client
-- Dev.  Safe to skip on local: `ddl-auto: update` adds a NULLABLE column
-- happily (that is how `visible` and `layer` appeared), and this is one.
-- ============================================================================
--
-- WHAT CHANGES
--
-- Two tables rarely agree on a type: the same account number is `varchar` in
-- the transaction table and `bigint` in the Golden Layer.  Presto does not
-- coerce across type families, so that comparison did not return zero rows —
-- it failed the whole query with "'=' cannot be applied to varchar, bigint".
-- SRSE now casts such a pair automatically, comparing as NUMBERS by default so
-- '0123' still matches 123.  `compare_as` is the per-column admin override for
-- that decision (AUTO / NUMBER / TEXT), set on the Admin page beside the
-- business name, fuzzy and visibility flags.
--
-- WHY IT IS NOT AUTOMATIC HERE
--
-- Same reason as 001: ddl-auto is scoped to the `local` profile
-- (application.yml), deliberately NOT active in client-dev, where pointing
-- Hibernate's schema tooling at a real on-prem DB2 schema would be dangerous.
-- Skipping this leaves the application starting cleanly and then failing at
-- runtime with SQLCODE=-206 (undefined column) the first time the Analysis tab
-- reads column metadata — a failure that names COMPARE_AS and nothing else, so
-- it reads as an unrelated breakage.
--
-- NO BACKFILL IS NEEDED.  NULL is read as AUTO by the entity, which is exactly
-- the behaviour every existing row already had.
-- ============================================================================

-- VARCHAR(16), matching @Column(length = 16) on the entity.  The values are
-- persisted as their enum NAMES (@Enumerated(EnumType.STRING)) — AUTO, NUMBER,
-- TEXT — so the column stays readable in a DB2 session, and adding a fourth
-- mode later does not need a schema change.
ALTER TABLE analysis_column_metadata ADD COLUMN compare_as VARCHAR(16);

-- DB2 can leave a table in reorg-pending after an ALTER; the table holds only
-- admin overrides (tens of rows), so this is cheap insurance rather than a
-- maintenance window.
CALL SYSPROC.ADMIN_CMD('REORG TABLE analysis_column_metadata');

-- Verify:
--   SELECT colname, typename, length, nulls
--     FROM syscat.columns
--    WHERE tabname = 'ANALYSIS_COLUMN_METADATA' AND colname = 'COMPARE_AS';
--
-- Expected: COMPARE_AS | VARCHAR | 16 | Y
