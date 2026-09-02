#!/bin/bash
# Idempotent wrapper around apache/hive:3.1.3's own /entrypoint.sh.
#
# THE BUG THIS FIXES
# The stock entrypoint runs `schematool -initSchema` unconditionally and exits
# 1 when it fails:
#
#     SKIP_SCHEMA_INIT="${IS_RESUME:-false}"
#     if [[ "${SKIP_SCHEMA_INIT}" == "false" ]]; then initialize_hive; fi
#
# The Derby metastore lives in the container's writable layer (there is no
# volume), so the schema survives a container restart but NOT a recreate. That
# makes the stock behaviour correct exactly once:
#
#   - fresh container  -> no schema -> initSchema succeeds
#   - restarted one    -> schema present -> "FUNCTION 'NUCLEUS_ASCII' already
#                         exists" -> exit 1 -> metastore never comes up, and
#                         Presto's Iceberg catalog is dead with it
#
# So `docker compose up` worked on day one and failed on every subsequent
# `docker compose start`/`restart` — which is what made it look intermittent.
#
# THE FIX
# Ask whether the schema is already there, and only initialise when it is not.
# `IS_RESUME=true` is then exported so the stock entrypoint skips its own
# unconditional attempt; everything else it does (HIVE_CONF_DIR, heap opts,
# METASTORE_PORT, exec'ing the service) is left untouched by delegating to it.
#
# Deliberately NOT fixed here: the metastore has no volume, so a container
# *recreate* still loses all table metadata and the `seed` service has to
# repopulate. That is the existing design of this local stack — seed runs on
# every `up` — and giving Derby a bind-mounted directory has its own failure
# mode (Derby refuses to create into a non-empty directory). Changing it is a
# separate decision, not a cold-start bug.
set -euo pipefail

DB_TYPE="${DB_DRIVER:-derby}"

if "$HIVE_HOME"/bin/schematool -dbType "$DB_TYPE" -info >/dev/null 2>&1; then
  echo "[srse-metastore] $DB_TYPE schema already initialised — skipping initSchema"
else
  echo "[srse-metastore] no $DB_TYPE schema found — initialising"
  "$HIVE_HOME"/bin/schematool -dbType "$DB_TYPE" -initSchema
fi

# Tell the stock entrypoint the schema is handled; it does the rest.
export IS_RESUME=true
exec /entrypoint.sh
