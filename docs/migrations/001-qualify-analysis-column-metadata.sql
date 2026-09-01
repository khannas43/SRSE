-- ============================================================================
-- SRSE migration 001 — qualify analysis_column_metadata by catalog + schema
-- Target: DB2 (operational plane).  Run ONCE per environment that already has
-- an analysis_column_metadata table.  Safe to skip on a brand-new database:
-- Hibernate creates the table in its final shape there.
-- ============================================================================
--
-- WHY THIS IS NOT AUTOMATIC
--
-- `ddl-auto: update` cannot perform this change and does not report that it
-- failed to.  It adds NULLABLE columns happily (that is how `visible` appears
-- on its own), but DB2 rejects ADD COLUMN ... NOT NULL on an existing table,
-- so `catalog_name` and `schema_name` are silently skipped.  The application
-- then starts cleanly and every query against the table fails at runtime with
-- SQLCODE=-206 (undefined column).  Observed exactly this way on a local stack
-- whose DB2 volume predated the change.
--
-- WHAT CHANGES
--
-- SRSE now maps several catalogs and schemas at once (the lakehouse's Silver
-- and Gold layers), so `(table_name, column_name)` is no longer unique: the
-- same table name legitimately exists in more than one layer and each needs
-- its own business name / fuzzy flag / visibility.  The key becomes the full
-- four-part address.
--
-- BEFORE YOU RUN: set these to the catalog and schema the EXISTING rows were
-- implicitly written against — i.e. whatever the JDBC URL's default
-- catalog/schema was when they were created.  Rows left NULL will not match
-- anything and are effectively ignored by the application.
-- ============================================================================

-- Step 1 — add the columns as NULLABLE (the only form DB2 accepts here).
-- VARCHAR(128), not 255: DB2 caps the total byte length of an index key, and
-- the four-column unique key in step 5 over VARCHAR(255) columns is rejected
-- with SQL0613N ("too long or has too many columns").  128 is the real bound —
-- LakehouseIdentifiers rejects any longer identifier before it can be stored.
ALTER TABLE analysis_column_metadata ADD COLUMN catalog_name VARCHAR(128);
ALTER TABLE analysis_column_metadata ADD COLUMN schema_name  VARCHAR(128);

-- If table_name / column_name are still VARCHAR(255) from the original
-- schema, narrow them too or step 5 will still exceed the key limit:
ALTER TABLE analysis_column_metadata ALTER COLUMN table_name  SET DATA TYPE VARCHAR(128);
ALTER TABLE analysis_column_metadata ALTER COLUMN column_name SET DATA TYPE VARCHAR(128);
CALL SYSPROC.ADMIN_CMD('REORG TABLE analysis_column_metadata');

-- Step 2 — backfill.  <<< EDIT THESE TWO VALUES FOR YOUR ENVIRONMENT >>>
UPDATE analysis_column_metadata
   SET catalog_name = 'iceberg',
       schema_name  = 'srse'
 WHERE catalog_name IS NULL OR schema_name IS NULL;

-- Step 3 — now that no NULLs remain, enforce NOT NULL to match the entity.
ALTER TABLE analysis_column_metadata ALTER COLUMN catalog_name SET NOT NULL;
ALTER TABLE analysis_column_metadata ALTER COLUMN schema_name  SET NOT NULL;
CALL SYSPROC.ADMIN_CMD('REORG TABLE analysis_column_metadata');

-- Step 4 — default `visible` to TRUE.  Pre-existing rows have NULL here; the
-- entity deliberately reads NULL as visible, so this is tidiness, not a fix.
UPDATE analysis_column_metadata SET visible = TRUE WHERE visible IS NULL;

-- Step 5 — replace the unique key.
--
-- The old constraint is Hibernate-generated, so its NAME DIFFERS PER
-- ENVIRONMENT (e.g. UKQB6RBU6TBAC8DJ35JKWPM1N9V).  Find yours:
--
--   SELECT CONSTNAME FROM SYSCAT.TABCONST
--    WHERE TABNAME = 'ANALYSIS_COLUMN_METADATA' AND TYPE = 'U';
--
-- then substitute it below.
--
-- ALTER TABLE analysis_column_metadata DROP CONSTRAINT <old-name-from-above>;

ALTER TABLE analysis_column_metadata
  ADD CONSTRAINT uq_analysis_column_metadata
  UNIQUE (catalog_name, schema_name, table_name, column_name);

-- Step 6 — registered_table is a NEW table, so Hibernate creates it under
-- ddl-auto: update.  Where ddl-auto is off (client Dev), create it by hand:
--
-- CREATE TABLE registered_table (
--   id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
--   catalog_name VARCHAR(128) NOT NULL,
--   schema_name  VARCHAR(128) NOT NULL,
--   table_name   VARCHAR(128) NOT NULL,
--   layer        VARCHAR(255),
--   CONSTRAINT uq_registered_table UNIQUE (catalog_name, schema_name, table_name)
-- );
