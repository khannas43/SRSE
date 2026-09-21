"""Build docs/SRSE_Test_Cases.xlsx — run with openpyxl in a venv."""
from __future__ import annotations

from pathlib import Path

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill

OUT = Path(__file__).resolve().parent.parent / "SRSE_Test_Cases.xlsx"

HEADER_FONT = Font(name="Arial", bold=True, size=10)
BODY_FONT = Font(name="Arial", size=10)
HEADER_FILL = PatternFill("solid", fgColor="D9E1F2")
WRAP = Alignment(wrap_text=True, vertical="top")

# id, module, title, steps, expected, priority, type, traceability, status
CASES = [
    (
        "TC-H-001",
        "Health",
        "Both data planes report UP",
        "1. Deploy stack with DB2 and Presto reachable.\n2. GET /api/health/planes.",
        "JSON shows operational=up and analytical=up.",
        "Must",
        "Smoke / SIT",
        "BRD §16",
        "",
    ),
    (
        "TC-H-002",
        "Health",
        "Operational plane DOWN handling",
        "1. Stop DB2 or break JDBC URL.\n2. GET /api/health/planes.",
        "operational=down (or equivalent); analytical may still be up.",
        "Should",
        "Negative",
        "NFR-3",
        "",
    ),
    (
        "TC-AU-001",
        "Authentication",
        "Unauthenticated decision call rejected (prod mode)",
        "1. Set SRSE_AUTH_MODE=rajsewadwar.\n2. POST /api/decision/preview without token.",
        "HTTP 401/403; no simulation result returned.",
        "Must",
        "Security",
        "FR-S1",
        "",
    ),
    (
        "TC-AU-002",
        "Authentication",
        "Mock login and preview (dev mode)",
        "1. SRSE_AUTH_MODE=mock.\n2. POST /api/auth/mock-login.\n3. POST /api/decision/preview with valid ruleset and Bearer token.",
        "Preview returns totalCount >= 0 and optional breakdown.",
        "Must",
        "Functional",
        "FR-S4, FR-R3",
        "",
    ),
    (
        "TC-R-001",
        "Rules — Preview",
        "Preview with valid Ekal Naari ruleset",
        "1. Open Rules UI or API.\n2. Build rules: age >= 18, income < 48000, domicile, exemptions.\n3. POST /api/decision/preview with includeBreakdown=true.",
        "Count returned; breakdown rows for district, gender, age_band.",
        "Must",
        "Functional / UAT",
        "FR-R2, FR-R3, BRD §15",
        "",
    ),
    (
        "TC-R-002",
        "Rules — Preview",
        "Preview does not persist scenario",
        "1. Run preview twice with different thresholds.\n2. GET /api/decision/scenarios?schemeId=X.",
        "Scenario list unchanged unless user explicitly saved.",
        "Must",
        "Functional",
        "FR-R2",
        "",
    ),
    (
        "TC-R-003",
        "Rules — Preview",
        "Adjust numeric threshold and observe count change",
        "1. Baseline preview with income threshold 48000.\n2. Preview with income threshold 50000.",
        "totalCount differs when population crosses threshold (document both values).",
        "Must",
        "UAT",
        "FR-R4, BO-1",
        "",
    ),
    (
        "TC-R-004",
        "Rules — Preview",
        "IN list and boolean toggles",
        "1. Build rule with categorical IN list.\n2. Toggle boolean field Any/Yes/No.\n3. Preview each variant.",
        "SQL executes without error; counts reflect selection.",
        "Should",
        "Functional",
        "FR-R4",
        "",
    ),
    (
        "TC-R-005",
        "Rules — Compiler safety",
        "Unknown field key rejected",
        "1. POST preview with fieldKey not in catalogue.",
        "HTTP 4xx with clear error; no SQL executed with user identifier.",
        "Must",
        "Negative",
        "FR-R1",
        "",
    ),
    (
        "TC-R-006",
        "Rules — Cohort",
        "Cohort sample respects hard cap",
        "1. POST /api/decision/cohort with ruleset matching large population.\n2. Request limit > SRSE_COHORT_CAP.",
        "Response rows <= SRSE_COHORT_CAP; response indicates cap applied/truncated.",
        "Must",
        "Functional",
        "FR-R5, NFR-6",
        "",
    ),
    (
        "TC-R-007",
        "Rules — Cohort",
        "Cohort is only row-level beneficiary API",
        "1. Verify preview/breakdown responses contain aggregates only.\n2. Cohort returns row maps.",
        "Only /cohort returns row-level fields.",
        "Must",
        "Security / Privacy",
        "FR-R5",
        "",
    ),
    (
        "TC-SC-001",
        "Scenarios",
        "Save scenario with snapshot",
        "1. POST /api/decision/scenarios with name, schemeIds, ruleset, includeBreakdown=true.",
        "Scenario id returned; GET /scenarios/{id} shows ruleset and stored totalCount/breakdown.",
        "Must",
        "Functional",
        "FR-R6, NFR-5",
        "",
    ),
    (
        "TC-SC-002",
        "Scenarios",
        "List scenarios by scheme",
        "1. Save two scenarios for same scheme.\n2. GET /api/decision/scenarios?schemeId=...",
        "Both scenarios listed with summaries.",
        "Should",
        "Functional",
        "FR-R6",
        "",
    ),
    (
        "TC-SC-003",
        "Scenarios",
        "Compare two scenarios",
        "1. Save scenario A (baseline thresholds).\n2. Save scenario B (changed income).\n3. GET /api/decision/compare?a=&b=.",
        "totalCountDelta and breakdownDeltas returned; B differs from A when threshold changed.",
        "Must",
        "UAT",
        "FR-R7, BO-3, BRD §16",
        "",
    ),
    (
        "TC-AN-001",
        "Analysis — Single match",
        "Exact match on registered columns",
        "1. Register two tables.\n2. POST /api/analysis/match with one criterion pair.\n3. Consume NDJSON stream to done.",
        "Matching rows streamed; meta includes column info; done event received.",
        "Must",
        "Functional / SIT",
        "FR-A1",
        "",
    ),
    (
        "TC-AN-002",
        "Analysis — Single match",
        "Fuzzy name match returns match_score_pct",
        "1. Configure fuzzy group on name columns.\n2. Run match against data with spelling variants.",
        "Rows include score; threshold slider filters in UI.",
        "Must",
        "Functional",
        "FR-A2",
        "",
    ),
    (
        "TC-AN-003",
        "Analysis — Column groups",
        "COMBINE name matching",
        "1. Match combined first+last on one side to single full_name on other.",
        "Expected pairs found (e.g. partial name fold).",
        "Must",
        "Functional",
        "FR-A3",
        "",
    ),
    (
        "TC-AN-004",
        "Analysis — Column groups",
        "ANY_OF returns matched_on",
        "1. ANY_OF group with two candidate columns.\n2. Value present in second column only.",
        "Row returned once with matched_on naming column.",
        "Must",
        "Functional",
        "FR-A3",
        "",
    ),
    (
        "TC-AN-005",
        "Analysis — Display columns",
        "Display-only columns in result not in JOIN",
        "1. Add sourceDisplayColumns / targetDisplayColumns.\n2. Run match.",
        "Extra columns appear in grid; changing them does not change join keys.",
        "Must",
        "Functional",
        "FR-A4",
        "",
    ),
    (
        "TC-AN-006",
        "Analysis — Multi-target",
        "Hub vs two targets with progress",
        "1. POST /api/analysis/match-multi with hubSide and two TargetMatchSpec.\n2. Watch NDJSON progress per target.",
        "Rows tagged by target; partial failure isolated if one target invalid.",
        "Must",
        "Functional",
        "FR-A5",
        "",
    ),
    (
        "TC-AN-007",
        "Analysis — CSV",
        "CSV download complete for large match",
        "1. Run match producing >10k rows.\n2. POST match.csv with same request.",
        "CSV row count equals Presto result (not limited to UI buffer).",
        "Must",
        "SIT",
        "FR-A6, FR-A7, BRD §16",
        "",
    ),
    (
        "TC-AN-008",
        "Analysis — UI cap",
        "Grid not rendered above 10k rows",
        "1. Run match >10k in Analysis UI.",
        "UI offers CSV; does not render full grid/charts.",
        "Should",
        "UI",
        "FR-A7",
        "",
    ),
    (
        "TC-AN-009",
        "Analysis — Limits API",
        "GET /api/analysis/limits matches config",
        "1. GET /api/analysis/limits.\n2. Compare to SRSE_ANALYSIS_* env defaults.",
        "maxTargetSets, group columns, anyOf caps returned.",
        "Should",
        "Functional",
        "TDD §6",
        "",
    ),
    (
        "TC-AN-010",
        "Analysis — Validation",
        "Unregistered table rejected",
        "1. POST match referencing table not in registry.",
        "4xx before Presto execution.",
        "Must",
        "Negative",
        "FR-S3, BO-5",
        "",
    ),
    (
        "TC-AN-011",
        "Analysis — Join keys",
        "Suggest keys metadata-only",
        "1. Pick two registered tables.\n2. POST /api/analysis/suggest-keys probe=false.",
        "Ranked pairs; no Presto query beyond registry column list.",
        "Must",
        "Functional",
        "Packages 6",
        "",
    ),
    (
        "TC-AN-012",
        "Analysis — Join types",
        "LEFT join unmatched source rows",
        "1. Run two-table match joinType=LEFT with non-matching source rows.",
        "Unmatched source rows visible with NULL target columns.",
        "Must",
        "Integration",
        "Package 4",
        "",
    ),
    (
        "TC-SEC-001",
        "Security — RBAC",
        "Officer forbidden on live browse",
        "1. Officer token GET /api/admin/lakehouse/browse/catalogs.",
        "403 Forbidden.",
        "Must",
        "Security",
        "Package 5",
        "",
    ),
    (
        "TC-SEC-002",
        "Security — RBAC",
        "Admin token on officer endpoints",
        "1. Admin token GET /api/metadata/fields and POST /api/decision/preview.",
        "200 OK.",
        "Must",
        "Security",
        "Package 5",
        "",
    ),
    (
        "TC-RU-001",
        "Rules — Template",
        "Scheme template loads; save forks",
        "1. Select scheme with template.\n2. Edit and Save.\n3. Re-select scheme.",
        "Official template unchanged; new scenario created.",
        "Must",
        "Functional",
        "Package 7",
        "",
    ),
    (
        "TC-AD-001",
        "Admin — Lakehouse",
        "Register Gold table",
        "1. Browse live catalog as admin.\n2. POST registration with SILVER/GOLD layer tag.",
        "Table appears in officer lakehouse picker.",
        "Must",
        "Functional",
        "FR-M1",
        "",
    ),
    (
        "TC-AD-006",
        "Admin — Lakehouse",
        "Legacy null-layer registration reachable as UNTAGGED",
        "1. Import or retain registered_table row with layer=null.\n2. Officer Analysis cascade: filter Layer=UNTAGGED.\n3. Select table and column.",
        "Table selectable; match payload uses catalog.schema.table only (no layer field).",
        "Must",
        "Functional",
        "Lakehouse layer filter",
        "",
    ),
    (
        "TC-RU-002",
        "Rules — Preview",
        "Preview sample size clamped to cohort cap",
        "1. Set SRSE_COHORT_CAP=100 and SRSE_PREVIEW_SAMPLE_SIZE=500.\n2. Open Rules UI sample control.\n3. POST /api/decision/cohort with sample size from UI.",
        "Effective sample ≤ 100; response states cohort cap applied.",
        "Must",
        "Functional",
        "SRSE_PREVIEW_SAMPLE_SIZE",
        "",
    ),
    (
        "TC-AN-013",
        "Analysis — Multi-target",
        "Canvas and form produce identical match-multi JSON",
        "1. Configure multi-target in Form mode; capture request JSON.\n2. Switch to Join canvas with equivalent hub/target/edges.\n3. Compare serialised MultiTargetRecordMatchRequest.",
        "Byte-identical JSON (automated test: multiTargetMatchBuild.test.ts).",
        "Must",
        "Functional",
        "Package 9 Phase A",
        "",
    ),
    (
        "TC-AD-002",
        "Admin — Lakehouse",
        "Live column drop removes from picker",
        "1. Register table.\n2. Simulate upstream column drop (or use test table).\n3. Refresh officer column list.",
        "Dropped column no longer selectable.",
        "Should",
        "Integration",
        "FR-M2",
        "",
    ),
    (
        "TC-AD-003",
        "Admin — Metadata",
        "Analysis column metadata compare-as TEXT",
        "1. PUT analysis column metadata compareAs=TEXT on varchar/bigint pair.\n2. Run exact match.",
        "Match succeeds without type error.",
        "Must",
        "Functional",
        "FR-M3",
        "",
    ),
    (
        "TC-AD-004",
        "Admin — Field mapping",
        "LIVE mapping four-part path",
        "1. PUT /api/metadata/mappings/{fieldKey} with catalog.schema.table.column.\n2. Preview rule using field.",
        "Preview resolves column; Presto query succeeds in live mode.",
        "Must",
        "Integration",
        "FR-M4, BRD §16",
        "",
    ),
    (
        "TC-AD-005",
        "Admin — JSON config",
        "Export configuration bundle",
        "1. GET /api/admin/config/export.",
        "JSON schemaVersion 1.0; contains connections, fieldCatalog, mappings, registeredTables, schemes.",
        "Must",
        "Functional",
        "FR-M5, BRD §10",
        "",
    ),
    (
        "TC-AD-006",
        "Admin — JSON config",
        "Import bundle round-trip",
        "1. Export bundle.\n2. POST import on clean DB or after delete.\n3. Verify mappings and registrations.",
        "ImportResult counts match; officer preview still works.",
        "Must",
        "SIT",
        "FR-M5",
        "",
    ),
    (
        "TC-AD-007",
        "Admin — JSON config",
        "Reject unsupported schemaVersion",
        "1. POST import with schemaVersion 99.0.",
        "400/422 with clear message; no partial corrupt state.",
        "Must",
        "Negative",
        "TDD §7",
        "",
    ),
    (
        "TC-AD-008",
        "Admin — Connections",
        "Update analytical JDBC and run preview",
        "1. PUT /api/admin/connections/analytical with valid Presto URL.\n2. GET /api/health/planes.\n3. Run preview.",
        "Analytical up; preview succeeds.",
        "Must",
        "Integration",
        "TDD §6",
        "",
    ),
    (
        "TC-MD-001",
        "Metadata",
        "Field catalogue CRUD",
        "1. POST new field.\n2. GET /api/metadata/fields.\n3. PUT label.\n4. DELETE (or deactivate).",
        "Field lifecycle reflected in Rules builder pickers.",
        "Should",
        "Functional",
        "FR-M4",
        "",
    ),
    (
        "TC-MD-002",
        "Metadata",
        "Scheme create and link to scenario",
        "1. POST /api/schemes.\n2. Save scenario with schemeIds including new scheme.",
        "Scenario lists scheme association.",
        "Should",
        "Functional",
        "FR-R6",
        "",
    ),
    (
        "TC-NF-001",
        "Non-functional",
        "Query timeout on long Presto job",
        "1. Set low SRSE_QUERY_TIMEOUT_SECONDS.\n2. Run heavy preview or match.",
        "Request fails with timeout error within bounded time; no hung thread.",
        "Must",
        "Non-functional",
        "NFR-1",
        "",
    ),
    (
        "TC-NF-002",
        "Non-functional",
        "Synthetic DATA_MODE offline demo",
        "1. docker compose up with DATA_MODE=synthetic.\n2. Complete TC-R-001 end-to-end.",
        "No live lakehouse required; seed data used.",
        "Must",
        "Smoke",
        "BO-1",
        "",
    ),
    (
        "TC-NF-003",
        "Non-functional",
        "Live DATA_MODE client-dev",
        "1. client-dev profile with live Presto/DB2.\n2. Admin registers real table.\n3. Preview or Analysis.",
        "End-to-end against client endpoints.",
        "Must",
        "SIT",
        "BRD §12",
        "",
    ),
    (
        "TC-UI-001",
        "Frontend — Rules",
        "Rules page build and preview E2E",
        "1. Login (mock).\n2. Open /rules.\n3. Adjust threshold; run simulation.",
        "Charts/table update; no console errors.",
        "Must",
        "UAT",
        "FR-R3, FR-R4",
        "",
    ),
    (
        "TC-UI-002",
        "Frontend — Analysis",
        "Analysis stream and filter",
        "1. Open /analysis.\n2. Run match; observe streaming rows.\n3. Apply set filter in multi-target mode.",
        "Progress visible; grid filters by target set.",
        "Must",
        "UAT",
        "FR-A5",
        "",
    ),
    (
        "TC-UI-003",
        "Frontend — Admin",
        "JSON backup from admin456",
        "1. Open /admin456.\n2. Export JSON.\n3. Import same file (test env).",
        "Success toast/counts; config restored.",
        "Should",
        "UAT",
        "FR-M5",
        "",
    ),
]


def style_header(ws, row: int, cols: int) -> None:
    for c in range(1, cols + 1):
        cell = ws.cell(row=row, column=c)
        cell.font = HEADER_FONT
        cell.fill = HEADER_FILL
        cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)


def main() -> None:
    wb = Workbook()
    ws = wb.active
    ws.title = "Test cases"

    headers = [
        "Test Case ID",
        "Module",
        "Test Case Title",
        "Test Steps",
        "Expected Result",
        "Priority",
        "Test Type",
        "Traceability",
        "Execution Status",
        "Executed By",
        "Execution Date",
        "Comments",
    ]
    for col, h in enumerate(headers, start=1):
        ws.cell(row=1, column=col, value=h)
    style_header(ws, 1, len(headers))

    for i, case in enumerate(CASES, start=2):
        for col, val in enumerate(case, start=1):
            cell = ws.cell(row=i, column=col, value=val)
            cell.font = BODY_FONT
            cell.alignment = WRAP
        for col in range(9, 13):
            ws.cell(row=i, column=col, value="").font = BODY_FONT

    ws.column_dimensions["A"].width = 12
    ws.column_dimensions["B"].width = 18
    ws.column_dimensions["C"].width = 32
    ws.column_dimensions["D"].width = 42
    ws.column_dimensions["E"].width = 38
    ws.column_dimensions["F"].width = 8
    ws.column_dimensions["G"].width = 14
    ws.column_dimensions["H"].width = 16
    ws.column_dimensions["I"].width = 14
    ws.column_dimensions["J"].width = 14
    ws.column_dimensions["K"].width = 14
    ws.column_dimensions["L"].width = 24
    ws.freeze_panes = "A2"
    ws.auto_filter.ref = f"A1:L{1 + len(CASES)}"

    ws_sum = wb.create_sheet("Summary")
    ws_sum.cell(row=1, column=1, value="Module").font = HEADER_FONT
    ws_sum.cell(row=1, column=2, value="Count").font = HEADER_FONT
    style_header(ws_sum, 1, 2)
    from collections import Counter

    counts = Counter(c[1] for c in CASES)
    for ri, (mod, cnt) in enumerate(sorted(counts.items()), start=2):
        ws_sum.cell(row=ri, column=1, value=mod).font = BODY_FONT
        ws_sum.cell(row=ri, column=2, value=cnt).font = BODY_FONT
    ws_sum.cell(row=ri + 2, column=1, value="Total test cases").font = HEADER_FONT
    ws_sum.cell(row=ri + 2, column=2, value=len(CASES)).font = BODY_FONT
    ws_sum.column_dimensions["A"].width = 28
    ws_sum.column_dimensions["B"].width = 10

    ws_inst = wb.create_sheet("Instructions")
    lines = [
        "SRSE Test Case Workbook — Instructions",
        "",
        "Purpose: SIT and UAT traceability to BRD/TDD requirements.",
        "",
        "Execution Status: leave blank, or use Not Run / Pass / Fail / Blocked.",
        "Executed By / Execution Date / Comments: fill during test cycles.",
        "",
        "Environments:",
        "  • Synthetic — DATA_MODE=synthetic, docker compose (smoke, developer regression).",
        "  • Client Dev — DATA_MODE=live, client Presto + DB2 (SIT).",
        "  • UAT — production-like auth and live mappings (departmental sign-off).",
        "",
        "Related documents: docs/SRSE_BRD.docx v1.1, docs/SRSE_Technical_Design_Document.docx v1.2.",
        "",
        f"Generated test cases: {len(CASES)}",
    ]
    for i, line in enumerate(lines, start=1):
        c = ws_inst.cell(row=i, column=1, value=line)
        c.font = Font(name="Arial", size=11, bold=(i == 1))
    ws_inst.column_dimensions["A"].width = 90

    wb.save(OUT)
    print(f"Wrote {OUT} ({len(CASES)} test cases)")


if __name__ == "__main__":
    main()
