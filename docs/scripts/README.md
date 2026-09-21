# SRSE documentation generators

Generated deliverables live in `docs/` (`.docx`, `.pptx`, `.xlsx`). **Edit these Python/Node scripts, not the binaries** — Office diffs are opaque; generators are the reviewable source of truth. Binaries are still committed so a clone gets stakeholder-ready files without running the toolchain.

## One-time setup

From the repository root:

```bash
# Python (BRD, TDD, PNG diagrams, test-case workbook)
python3.12 -m venv docs/.gen-venv
docs/.gen-venv/bin/pip install -r docs/scripts/requirements.txt

# Node (workflow + business slide decks)
cd docs/scripts && npm ci
```

Paths `docs/.gen-venv/`, `docs/scripts/node_modules/`, and `docs/.xlsx-venv/` are gitignored.

## Regenerate everything

```bash
# Diagrams first (PNG inputs for BRD/TDD)
docs/.gen-venv/bin/python docs/scripts/generate_workflow_diagrams.py
docs/.gen-venv/bin/python docs/scripts/generate_design_diagrams.py

# Word documents
docs/.gen-venv/bin/python docs/scripts/generate_brd_docx.py
docs/.gen-venv/bin/python docs/scripts/generate_design_docx.py

# Slide decks
node docs/scripts/generate_workflows_pptx.mjs
node docs/scripts/generate_business_slides_pptx.mjs

# Test cases spreadsheet
docs/.gen-venv/bin/python docs/scripts/generate_test_cases_xlsx.py

# Project plan (standalone script in docs/)
docs/.gen-venv/bin/python docs/_build_project_plan_xlsx.py
```

## Outputs

| Generator | Output |
|-----------|--------|
| `generate_brd_docx.py` | `docs/SRSE_BRD.docx` |
| `generate_design_docx.py` | `docs/SRSE_Technical_Design_Document.docx` |
| `generate_workflows_pptx.mjs` | `docs/SRSE_Functional_Workflows.pptx` |
| `generate_business_slides_pptx.mjs` | `docs/SRSE_Business_Overview.pptx` |
| `generate_test_cases_xlsx.py` | `docs/SRSE_Test_Cases.xlsx` |
| `_build_project_plan_xlsx.py` | `docs/SRSE_PROJECT_PLAN.xlsx` |
| `generate_*_diagrams.py` | `docs/brd_assets/*.png`, `docs/design_assets/*.png` |

Human-maintained (not regenerated here): `docs/CONFIGURATION_GUIDE.md`, `docs/CONFIGURATION_GUIDE.docx` (if present), SQL migrations under `docs/migrations/`.
