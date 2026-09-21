"""Generate PNG workflow diagrams for SRSE BRD (docs/brd_assets/)."""
from __future__ import annotations

from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch

ASSETS = Path(__file__).resolve().parent.parent / "brd_assets"
FONT = "DejaVu Sans"


def _box(ax, x, y, w, h, text, fc="#E8EEF7", ec="#1E2761", fs=9):
    patch = FancyBboxPatch(
        (x, y),
        w,
        h,
        boxstyle="round,pad=0.02,rounding_size=0.08",
        linewidth=1.2,
        edgecolor=ec,
        facecolor=fc,
    )
    ax.add_patch(patch)
    ax.text(x + w / 2, y + h / 2, text, ha="center", va="center", fontsize=fs, wrap=True)


def _arrow(ax, x1, y1, x2, y2):
    ax.add_patch(
        FancyArrowPatch(
            (x1, y1),
            (x2, y2),
            arrowstyle="-|>",
            mutation_scale=12,
            linewidth=1.2,
            color="#444444",
        )
    )


def _save(fig, name: str) -> None:
    ASSETS.mkdir(parents=True, exist_ok=True)
    path = ASSETS / name
    fig.savefig(path, dpi=160, bbox_inches="tight", facecolor="white")
    plt.close(fig)


def system_context():
    fig, ax = plt.subplots(figsize=(10, 6))
    ax.set_xlim(0, 10)
    ax.set_ylim(0, 6)
    ax.axis("off")
    ax.set_title("SRSE — System context", fontsize=14, fontweight="bold", color="#1E2761")
    _box(ax, 0.5, 4.2, 2.2, 1.0, "Departmental\nofficer\n(Rules / Analysis)", fc="#CADCFC")
    _box(ax, 0.5, 2.5, 2.2, 1.0, "Lakehouse\nadministrator", fc="#CADCFC")
    _box(ax, 3.5, 3.2, 2.8, 1.4, "SRSE web UI\n(Next.js)", fc="#FFFFFF")
    _box(ax, 6.8, 3.2, 2.5, 1.4, "SRSE API\n(Spring Boot)", fc="#FFFFFF")
    _box(ax, 6.8, 1.0, 2.5, 1.0, "DB2 operational\n(catalogue, scenarios)", fc="#FFF4E5")
    _box(ax, 3.5, 0.5, 2.8, 1.0, "Presto / Iceberg\n(beneficiary data)", fc="#FFF4E5")
    _box(ax, 0.5, 0.5, 2.2, 1.0, "RajSewadwar\nSSO (prod)", fc="#F5F5F5")
    _arrow(ax, 2.7, 4.7, 3.5, 4.0)
    _arrow(ax, 2.7, 3.0, 3.5, 3.8)
    _arrow(ax, 6.3, 3.9, 6.8, 3.9)
    _arrow(ax, 8.0, 3.2, 8.0, 2.0)
    _arrow(ax, 7.0, 3.2, 6.0, 1.5)
    _arrow(ax, 2.7, 1.0, 3.5, 1.0)
    _save(fig, "wf01_system_context.png")


def admin_onboarding():
    fig, ax = plt.subplots(figsize=(10, 7))
    ax.set_xlim(0, 10)
    ax.set_ylim(0, 8)
    ax.axis("off")
    ax.set_title("Admin — Lakehouse & metadata setup", fontsize=14, fontweight="bold", color="#1E2761")
    steps = [
        (4.0, 7.0, "Sign in (admin role)"),
        (4.0, 6.0, "Browse live cluster\n(catalog → schema → table)"),
        (4.0, 5.0, "Register table\n(Silver / Gold tag)"),
        (4.0, 4.0, "Set column metadata\n(business name, fuzzy, compare-as)"),
        (4.0, 3.0, "Map catalogue fields\n→ physical columns (LIVE)"),
        (4.0, 2.0, "Export JSON backup\n(optional)"),
        (4.0, 1.0, "Officers use registered\ntables in Rules & Analysis"),
    ]
    for i, (x, y, t) in enumerate(steps):
        _box(ax, x, y, 3.2, 0.75, t)
        if i < len(steps) - 1:
            _arrow(ax, x + 1.6, y, x + 1.6, steps[i + 1][1] + 0.75)
    _save(fig, "wf02_admin_onboarding.png")


def rule_simulation():
    fig, ax = plt.subplots(figsize=(10, 8))
    ax.set_xlim(0, 10)
    ax.set_ylim(0, 9)
    ax.axis("off")
    ax.set_title("Officer — Rule simulation (preview)", fontsize=14, fontweight="bold", color="#1E2761")
    steps = [
        (3.4, 7.8, "Open Rules UI & select scheme"),
        (3.4, 6.7, "Build ruleset from flat field catalogue\n(AND/OR, thresholds, IN lists)"),
        (3.4, 5.6, "POST /api/decision/preview"),
        (3.4, 4.5, "Compile rules → parameterised Presto SQL"),
        (3.4, 3.4, "Execute count + breakdown\n(district, gender, age band)"),
        (3.4, 2.3, "Display totals & charts\n(aggregates only)"),
        (3.4, 1.2, "Optional: save scenario\nor cohort sample (capped)"),
    ]
    for i, (x, y, t) in enumerate(steps):
        _box(ax, x, y, 4.0, 0.85, t)
        if i < len(steps) - 1:
            _arrow(ax, x + 2.0, y, x + 2.0, steps[i + 1][1] + 0.85)
    _save(fig, "wf03_rule_simulation.png")


def scenario_lifecycle():
    fig, ax = plt.subplots(figsize=(10, 6))
    ax.set_xlim(0, 10)
    ax.set_ylim(0, 6)
    ax.axis("off")
    ax.set_title("Scenario — Save, compare, decide", fontsize=14, fontweight="bold", color="#1E2761")
    _box(ax, 0.8, 4.5, 2.4, 1.0, "Adjust thresholds\nin Rules UI")
    _box(ax, 3.8, 4.5, 2.6, 1.0, "POST /scenarios\n(name + ruleset)")
    _box(ax, 7.0, 4.5, 2.2, 1.0, "Snapshot count &\nbreakdown in DB2")
    _box(ax, 3.8, 2.5, 2.6, 1.0, "List / load scenarios\nby scheme")
    _box(ax, 3.8, 0.8, 2.6, 1.0, "GET /compare?a=&b=\nΔ count & breakdown")
    _arrow(ax, 3.2, 5.0, 3.8, 5.0)
    _arrow(ax, 6.4, 5.0, 7.0, 5.0)
    _arrow(ax, 5.1, 4.5, 5.1, 3.5)
    _arrow(ax, 5.1, 2.5, 5.1, 1.8)
    _save(fig, "wf04_scenario_lifecycle.png")


def analysis_match():
    fig, ax = plt.subplots(figsize=(10, 8))
    ax.set_xlim(0, 10)
    ax.set_ylim(0, 9)
    ax.axis("off")
    ax.set_title("Analysis — Record match (single or multi-target)", fontsize=14, fontweight="bold", color="#1E2761")
    _box(ax, 0.5, 7.5, 4.0, 1.0, "Pick registered source & target tables\n+ match criteria (exact / fuzzy / groups)")
    _box(ax, 5.2, 7.5, 4.3, 1.0, "Optional: display-only columns;\nmulti-target hub mode")
    _box(ax, 2.5, 5.8, 5.0, 1.0, "Validate columns (registry + live)\n+ type coercion rules")
    _box(ax, 2.5, 4.5, 5.0, 1.0, "Plan SQL (N × two-table JOIN;\nUNNEST for ANY_OF)")
    _box(ax, 2.5, 3.2, 5.0, 1.0, "Stream NDJSON to UI\n(progress, rows, done)")
    _box(ax, 2.5, 1.9, 5.0, 1.0, "Grid / charts if ≤10k rows;\nelse CSV download (full result)")
    _arrow(ax, 2.5, 7.5, 3.5, 6.8)
    _arrow(ax, 7.0, 7.5, 6.5, 6.8)
    _arrow(ax, 5.0, 5.8, 5.0, 5.5)
    _arrow(ax, 5.0, 4.5, 5.0, 4.2)
    _arrow(ax, 5.0, 3.2, 5.0, 2.9)
    _save(fig, "wf05_analysis_match.png")


def auth_access():
    fig, ax = plt.subplots(figsize=(10, 5))
    ax.set_xlim(0, 10)
    ax.set_ylim(0, 5)
    ax.axis("off")
    ax.set_title("Access control — Roles & data planes", fontsize=14, fontweight="bold", color="#1E2761")
    _box(ax, 0.5, 3.0, 2.5, 1.2, "User request\n(UI or API)", fc="#CADCFC")
    _box(ax, 3.5, 3.0, 2.8, 1.2, "Auth filter\n(mock JWT / RajSewadwar)")
    _box(ax, 6.8, 3.8, 2.5, 0.9, "STATE_OFFICER\nRules, Analysis")
    _box(ax, 6.8, 2.2, 2.5, 0.9, "Admin\nLakehouse browse")
    _box(ax, 3.5, 0.8, 2.8, 1.0, "Operational DB2\n(JPA metadata)")
    _box(ax, 6.8, 0.8, 2.5, 1.0, "Presto\n(set queries)")
    _arrow(ax, 3.0, 3.6, 3.5, 3.6)
    _arrow(ax, 6.3, 3.9, 6.8, 4.2)
    _arrow(ax, 6.3, 3.3, 6.8, 2.7)
    _arrow(ax, 4.9, 3.0, 4.9, 1.8)
    _arrow(ax, 6.8, 2.2, 7.5, 1.8)
    _save(fig, "wf06_auth_access.png")


def main() -> None:
    system_context()
    admin_onboarding()
    rule_simulation()
    scenario_lifecycle()
    analysis_match()
    auth_access()
    print(f"Wrote diagrams to {ASSETS}")


if __name__ == "__main__":
    main()
