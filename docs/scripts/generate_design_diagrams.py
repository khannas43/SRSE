"""Architecture PNGs for Design Document → docs/design_assets/."""
from __future__ import annotations

from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch

ASSETS = Path(__file__).resolve().parent.parent / "design_assets"


def _box(ax, x, y, w, h, text, fc="#E8EEF7", ec="#1E2761", fs=8.5):
    ax.add_patch(
        FancyBboxPatch(
            (x, y), w, h,
            boxstyle="round,pad=0.02,rounding_size=0.06",
            linewidth=1.2, edgecolor=ec, facecolor=fc,
        )
    )
    ax.text(x + w / 2, y + h / 2, text, ha="center", va="center", fontsize=fs, wrap=True)


def _arrow(ax, x1, y1, x2, y2):
    ax.add_patch(
        FancyArrowPatch(
            (x1, y1), (x2, y2),
            arrowstyle="-|>", mutation_scale=11, linewidth=1.1, color="#444444",
        )
    )


def _save(fig, name: str) -> None:
    ASSETS.mkdir(parents=True, exist_ok=True)
    fig.savefig(ASSETS / name, dpi=160, bbox_inches="tight", facecolor="white")
    plt.close(fig)


def logical_deployment():
    fig, ax = plt.subplots(figsize=(11, 6.5))
    ax.set_xlim(0, 11)
    ax.set_ylim(0, 6.5)
    ax.axis("off")
    ax.set_title("Logical deployment architecture", fontsize=14, fontweight="bold", color="#1E2761")
    _box(ax, 0.4, 5.0, 2.0, 0.9, "Officers /\nAdmins\n(Browser)", fc="#CADCFC")
    _box(ax, 3.0, 4.6, 2.4, 1.5, "SRSE Frontend\nNext.js 16\nContainer :3000", fc="#FFFFFF")
    _box(ax, 6.0, 4.6, 2.6, 1.5, "SRSE Backend\nSpring Boot 3.3\nFat JAR :8080", fc="#FFFFFF")
    _box(ax, 9.2, 5.2, 1.5, 0.7, "RajSewadwar\nSSO", fc="#F0F0F0", fs=8)
    _box(ax, 6.0, 2.5, 2.6, 1.2, "DB2\nOperational plane\n(JPA metadata)", fc="#FFF4E5")
    _box(ax, 3.0, 2.5, 2.4, 1.2, "PrestoDB 0.297\nIceberg tables\n(analytical)", fc="#FFF4E5")
    _box(ax, 0.4, 2.5, 2.0, 1.2, "MinIO /\nObject store\n(local dev)", fc="#F5F5F5", fs=8)
    _box(ax, 9.2, 2.5, 1.5, 1.2, "watsonx.data\n(client)", fc="#F5F5F5", fs=8)
    _arrow(ax, 2.4, 5.4, 3.0, 5.2)
    _arrow(ax, 5.4, 5.2, 6.0, 5.2)
    _arrow(ax, 7.3, 4.6, 7.3, 3.7)
    _arrow(ax, 6.5, 4.6, 4.2, 3.7)
    _arrow(ax, 2.4, 3.1, 3.0, 3.1)
    _arrow(ax, 9.2, 5.2, 8.6, 5.2)
    _save(fig, "arch01_logical_deployment.png")


def dual_data_plane():
    fig, ax = plt.subplots(figsize=(10, 6))
    ax.set_xlim(0, 10)
    ax.set_ylim(0, 6)
    ax.axis("off")
    ax.set_title("Dual data plane architecture", fontsize=14, fontweight="bold", color="#1E2761")
    _box(ax, 3.5, 4.8, 3.0, 0.9, "SRSE Backend API layer", fc="#CADCFC")
    _box(ax, 0.8, 2.8, 3.5, 1.5, "Operational plane\nDB2 + Spring Data JPA\n\nCatalogue, mappings,\nscenarios, registry", fc="#FFFFFF")
    _box(ax, 5.7, 2.8, 3.5, 1.5, "Analytical plane\nPresto JDBC + JdbcTemplate\n\nCount, breakdown,\nAnalysis match SQL", fc="#FFFFFF")
    _box(ax, 0.8, 0.8, 3.5, 1.0, "Small, transactional,\nentity-shaped data", fc="#FFF4E5", fs=8)
    _box(ax, 5.7, 0.8, 3.5, 1.0, "Set-based queries;\nno ORM on Presto", fc="#FFF4E5", fs=8)
    _arrow(ax, 4.2, 4.8, 2.5, 4.3)
    _arrow(ax, 5.8, 4.8, 7.5, 4.3)
    _arrow(ax, 2.5, 2.8, 2.5, 1.8)
    _arrow(ax, 7.5, 2.8, 7.5, 1.8)
    _save(fig, "arch02_dual_data_plane.png")


def backend_modules():
    fig, ax = plt.subplots(figsize=(10, 7))
    ax.set_xlim(0, 10)
    ax.set_ylim(0, 7)
    ax.axis("off")
    ax.set_title("Backend module map", fontsize=14, fontweight="bold", color="#1E2761")
    _box(ax, 3.2, 6.0, 3.6, 0.75, "REST Controllers (web, decision, analysis, metadata, lakehouse)")
    modules = [
        (0.4, 4.5, "compiler\nRule AST + SQL"),
        (2.6, 4.5, "execution\nPresto push-down"),
        (4.8, 4.5, "metadata\nField resolver"),
        (7.0, 4.5, "scenario\nStore & compare"),
        (1.5, 2.8, "analysis\nRecord match"),
        (4.0, 2.8, "lakehouse\nBrowse & registry"),
        (6.5, 2.8, "security\nAuth filters"),
        (3.2, 1.0, "config\nDual datasource, DATA_MODE, guardrails"),
    ]
    for x, y, t in modules:
        _box(ax, x, y, 2.0, 1.1, t, fc="#FFFFFF")
    _arrow(ax, 5.0, 6.0, 5.0, 5.6)
    for x, y, _ in modules[:4]:
        _arrow(ax, 5.0, 5.6, x + 1.0, y + 1.1)
    _save(fig, "arch03_backend_modules.png")


def rule_preview_flow():
    fig, ax = plt.subplots(figsize=(10, 5.5))
    ax.set_xlim(0, 10)
    ax.set_ylim(0, 5.5)
    ax.axis("off")
    ax.set_title("Rule preview — request flow", fontsize=14, fontweight="bold", color="#1E2761")
    steps = [
        (0.5, 4.2, "Rules UI"),
        (2.3, 4.2, "POST /api/decision/preview"),
        (4.5, 4.2, "RuleCompiler\n(parameterised SQL)"),
        (6.7, 4.2, "MetadataFieldResolver\n(DB2 cache)"),
        (8.5, 4.2, "ExecutionService\nJdbcTemplate"),
        (4.5, 2.2, "Presto: COUNT +\nGROUP BY breakdown"),
        (2.3, 0.6, "JSON aggregates\nto UI"),
    ]
    for x, y, t in steps[:5]:
        _box(ax, x, y, 1.6, 0.85, t, fs=7.5)
    _box(ax, 4.5, 2.2, 2.2, 0.9, steps[5][2], fs=8)
    _box(ax, 2.3, 0.6, 2.0, 0.75, steps[6][2], fs=8)
    _arrow(ax, 2.1, 4.6, 2.3, 4.6)
    _arrow(ax, 3.9, 4.6, 4.5, 4.6)
    _arrow(ax, 6.1, 4.6, 6.7, 4.6)
    _arrow(ax, 8.3, 4.6, 8.5, 4.6)
    _arrow(ax, 5.6, 4.2, 5.6, 3.1)
    _arrow(ax, 5.6, 2.2, 3.3, 1.35)
    _save(fig, "arch04_rule_preview_flow.png")


def main() -> None:
    logical_deployment()
    dual_data_plane()
    backend_modules()
    rule_preview_flow()
    print(f"Wrote architecture diagrams to {ASSETS}")


if __name__ == "__main__":
    main()
