/**
 * Editable BRD workflows + Design architecture deck
 * → docs/SRSE_Functional_Workflows.pptx
 * Run: node docs/scripts/generate_workflows_pptx.mjs
 */
import pptxgen from "pptxgenjs";
import { fileURLToPath } from "url";
import path from "path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(__dirname, "..", "SRSE_Functional_Workflows.pptx");

const NAVY = "1E2761";
const ICE = "CADCFC";
const ACCENT = "F4A261";
const GRAY = "444444";

function addTitleSlide(pres) {
  const slide = pres.addSlide();
  slide.background = { color: NAVY };
  slide.addText("SRSE — Workflows & Architecture", {
    x: 0.6,
    y: 1.6,
    w: 9,
    h: 1,
    fontSize: 34,
    bold: true,
    color: "FFFFFF",
    isTextBox: true,
  });
  slide.addText(
    "Scheme Rule Simulation Engine — SMART Project\nEditable slides for BRD (functional) and TDD (architecture / tech stack)",
    {
      x: 0.6,
      y: 2.7,
      w: 9,
      h: 1.4,
      fontSize: 15,
      color: ICE,
      isTextBox: true,
    }
  );
  slide.addNotes(
    "BRD: docs/SRSE_BRD.docx | TDD: docs/SRSE_Technical_Design_Document.docx — edit shapes on slides to match programme updates."
  );
}

function sectionSlide(pres, part, subtitle) {
  const slide = pres.addSlide();
  slide.background = { color: NAVY };
  slide.addText(part, {
    x: 0.6,
    y: 2.0,
    w: 9,
    h: 0.8,
    fontSize: 28,
    bold: true,
    color: ACCENT,
    isTextBox: true,
  });
  slide.addText(subtitle, {
    x: 0.6,
    y: 2.9,
    w: 9,
    h: 1,
    fontSize: 18,
    color: "FFFFFF",
    isTextBox: true,
  });
}

/** @param {import('pptxgenjs').default} pres */
function flowSlide(pres, title, steps, notes) {
  const slide = pres.addSlide();
  slide.addText(title, {
    x: 0.4,
    y: 0.25,
    w: 9.2,
    h: 0.6,
    fontSize: 22,
    bold: true,
    color: NAVY,
    isTextBox: true,
  });
  const boxW = 7.5;
  const boxH = 0.55;
  const x = 1.25;
  let y = 1.0;
  const gap = 0.75;
  steps.forEach((text, i) => {
    slide.addShape(pres.ShapeType.roundRect, {
      x,
      y,
      w: boxW,
      h: boxH,
      fill: { color: i === 0 ? ICE : "FFFFFF" },
      line: { color: NAVY, width: 1.2 },
      rectRadius: 0.08,
    });
    slide.addText(text, {
      x: x + 0.15,
      y: y + 0.08,
      w: boxW - 0.3,
      h: boxH - 0.1,
      fontSize: 12,
      color: GRAY,
      valign: "mid",
      isTextBox: true,
    });
    if (i < steps.length - 1) {
      slide.addShape(pres.ShapeType.line, {
        x: x + boxW / 2,
        y: y + boxH,
        w: 0,
        h: gap - 0.15,
        line: { color: GRAY, width: 1.5, endArrowType: "triangle" },
      });
    }
    y += boxH + gap;
  });
  if (notes) slide.addNotes(notes);
}

/** Horizontal swimlane-style context slide */
function contextSlide(pres) {
  const slide = pres.addSlide();
  slide.addText("Workflow 1 — System context", {
    x: 0.4,
    y: 0.25,
    w: 9.2,
    h: 0.5,
    fontSize: 22,
    bold: true,
    color: NAVY,
    isTextBox: true,
  });
  const lanes = [
    { label: "Users", y: 1.0, items: ["Departmental officer", "Lakehouse admin"] },
    { label: "SRSE", y: 2.3, items: ["Web UI (Next.js)", "API (Spring Boot)"] },
    { label: "Data", y: 3.6, items: ["DB2 operational", "Presto / Iceberg"] },
    { label: "Identity", y: 4.9, items: ["RajSewadwar SSO (prod)", "Mock JWT (dev)"] },
  ];
  lanes.forEach((lane) => {
    slide.addText(lane.label, {
      x: 0.4,
      y: lane.y,
      w: 1.1,
      h: 0.45,
      fontSize: 11,
      bold: true,
      color: NAVY,
      isTextBox: true,
    });
    lane.items.forEach((item, idx) => {
      const bx = 1.6 + idx * 3.8;
      slide.addShape(pres.ShapeType.roundRect, {
        x: bx,
        y: lane.y - 0.05,
        w: 3.4,
        h: 0.55,
        fill: { color: lane.label === "SRSE" ? ACCENT : "FFFFFF" },
        line: { color: NAVY, width: 1 },
        rectRadius: 0.06,
      });
      slide.addText(item, {
        x: bx + 0.1,
        y: lane.y + 0.05,
        w: 3.2,
        h: 0.4,
        fontSize: 11,
        isTextBox: true,
      });
    });
  });
  slide.addShape(pres.ShapeType.line, {
    x: 5.0,
    y: 1.55,
    w: 0,
    h: 0.65,
    line: { color: GRAY, width: 1.2, endArrowType: "triangle" },
  });
  slide.addShape(pres.ShapeType.line, {
    x: 5.0,
    y: 2.85,
    w: 0,
    h: 0.65,
    line: { color: GRAY, width: 1.2, endArrowType: "triangle" },
  });
  slide.addNotes("Edit lane boxes to add systems (e.g. monitoring, API gateway).");
}

/** Analysis branch slide */
function analysisBranchSlide(pres) {
  const slide = pres.addSlide();
  slide.addText("Workflow 5 — Analysis decision branches", {
    x: 0.4,
    y: 0.25,
    w: 9.2,
    h: 0.5,
    fontSize: 22,
    bold: true,
    color: NAVY,
    isTextBox: true,
  });
  slide.addShape(pres.ShapeType.roundRect, {
    x: 3.5,
    y: 0.95,
    w: 3.0,
    h: 0.5,
    fill: { color: ICE },
    line: { color: NAVY, width: 1 },
    rectRadius: 0.06,
  });
  slide.addText("Configure match", {
    x: 3.5,
    y: 1.05,
    w: 3.0,
    h: 0.4,
    align: "center",
    fontSize: 12,
    bold: true,
    isTextBox: true,
  });
  const branches = [
    { x: 0.6, t: "Single target\n(exact / fuzzy)" },
    { x: 3.5, t: "Multi-target hub\n(N × 2-table join)" },
    { x: 6.4, t: "Column groups\nCOMBINE / ANY_OF" },
  ];
  branches.forEach((b) => {
    slide.addShape(pres.ShapeType.line, {
      x: 5.0,
      y: 1.45,
      w: b.x + 1.5 - 5.0,
      h: 0.55,
      line: { color: GRAY, width: 1, endArrowType: "triangle" },
    });
    slide.addShape(pres.ShapeType.roundRect, {
      x: b.x,
      y: 2.0,
      w: 2.6,
      h: 0.85,
      fill: { color: "FFFFFF" },
      line: { color: NAVY, width: 1 },
      rectRadius: 0.06,
    });
    slide.addText(b.t, {
      x: b.x + 0.1,
      y: 2.15,
      w: 2.4,
      h: 0.7,
      fontSize: 11,
      align: "center",
      isTextBox: true,
    });
  });
  slide.addShape(pres.ShapeType.roundRect, {
    x: 2.5,
    y: 3.2,
    w: 5.0,
    h: 0.55,
    fill: { color: "FFFFFF" },
    line: { color: NAVY, width: 1 },
    rectRadius: 0.06,
  });
  slide.addText("Execute → NDJSON stream → Grid (≤10k) or CSV download", {
    x: 2.5,
    y: 3.3,
    w: 5.0,
    h: 0.4,
    align: "center",
    fontSize: 12,
    isTextBox: true,
  });
  slide.addNotes(
    "All branches validate registered columns and emit parameterised SQL. Two-table match supports INNER/LEFT/RIGHT/FULL and match.sql preview; multi-target stays INNER (N × hub↔target). Join canvas (Phase A) serialises to the same MultiTargetRecordMatchRequest as the form. Suggest-keys: metadata hints; optional probe samples source and scans target. Per-target SQL on stream progress started — not meta."
  );
}

/** Editable logical deployment (mirrors TDD Figure 3-1) */
function archDeploymentSlide(pres) {
  const slide = pres.addSlide();
  slide.addText("Architecture 1 — Logical deployment", {
    x: 0.4,
    y: 0.2,
    w: 9.2,
    h: 0.5,
    fontSize: 22,
    bold: true,
    color: NAVY,
    isTextBox: true,
  });
  const nodes = [
    { x: 0.5, y: 1.0, w: 1.8, h: 0.7, t: "Browser\n(officers)", c: ICE },
    { x: 2.6, y: 1.0, w: 2.2, h: 0.9, t: "Frontend container\nNext.js 16 :3000", c: "FFFFFF" },
    { x: 5.2, y: 1.0, w: 2.4, h: 0.9, t: "Backend container\nSpring Boot 3.3 :8080", c: "FFFFFF" },
    { x: 5.2, y: 2.3, w: 2.4, h: 0.75, t: "DB2\n(operational)", c: "FFF4E5" },
    { x: 2.6, y: 2.3, w: 2.2, h: 0.75, t: "Presto 0.297\n(Iceberg)", c: "FFF4E5" },
    { x: 0.5, y: 2.3, w: 1.8, h: 0.75, t: "MinIO\n(dev only)", c: "F5F5F5" },
    { x: 8.0, y: 1.0, w: 1.5, h: 0.7, t: "RajSewadwar\nSSO", c: "F5F5F5" },
  ];
  nodes.forEach((n) => {
    slide.addShape(pres.ShapeType.roundRect, {
      x: n.x,
      y: n.y,
      w: n.w,
      h: n.h,
      fill: { color: n.c },
      line: { color: NAVY, width: 1 },
      rectRadius: 0.06,
    });
    slide.addText(n.t, {
      x: n.x + 0.05,
      y: n.y + 0.1,
      w: n.w - 0.1,
      h: n.h - 0.15,
      fontSize: 10,
      align: "center",
      isTextBox: true,
    });
  });
  slide.addNotes("Two application containers; lakehouse external in client Dev (watsonx.data).");
}

function archDualPlaneSlide(pres) {
  const slide = pres.addSlide();
  slide.addText("Architecture 2 — Dual data plane", {
    x: 0.4,
    y: 0.2,
    w: 9.2,
    h: 0.5,
    fontSize: 22,
    bold: true,
    color: NAVY,
    isTextBox: true,
  });
  slide.addShape(pres.ShapeType.roundRect, {
    x: 3.2,
    y: 0.85,
    w: 3.6,
    h: 0.55,
    fill: { color: ICE },
    line: { color: NAVY, width: 1 },
    rectRadius: 0.06,
  });
  slide.addText("SRSE API + services", {
    x: 3.2,
    y: 0.95,
    w: 3.6,
    h: 0.4,
    align: "center",
    fontSize: 12,
    bold: true,
    isTextBox: true,
  });
  slide.addShape(pres.ShapeType.roundRect, {
    x: 0.6,
    y: 1.8,
    w: 4.0,
    h: 1.5,
    fill: { color: "FFFFFF" },
    line: { color: NAVY, width: 1.2 },
    rectRadius: 0.06,
  });
  slide.addText("Operational\nDB2 + JPA\n\nCatalogue, mappings,\nscenarios, registry", {
    x: 0.7,
    y: 1.95,
    w: 3.8,
    h: 1.3,
    fontSize: 11,
    isTextBox: true,
  });
  slide.addShape(pres.ShapeType.roundRect, {
    x: 5.4,
    y: 1.8,
    w: 4.0,
    h: 1.5,
    fill: { color: "FFFFFF" },
    line: { color: NAVY, width: 1.2 },
    rectRadius: 0.06,
  });
  slide.addText("Analytical\nPresto JDBC + JdbcTemplate\n\nCounts, breakdowns,\nAnalysis SQL", {
    x: 5.5,
    y: 1.95,
    w: 3.8,
    h: 1.3,
    fontSize: 11,
    isTextBox: true,
  });
  slide.addNotes("Never use ORM for analytical set queries; never pull full populations into app tier.");
}

function archModulesSlide(pres) {
  const slide = pres.addSlide();
  slide.addText("Architecture 3 — Backend modules", {
    x: 0.4,
    y: 0.2,
    w: 9.2,
    h: 0.5,
    fontSize: 22,
    bold: true,
    color: NAVY,
    isTextBox: true,
  });
  const mods = [
    "compiler",
    "execution",
    "decision",
    "metadata",
    "scenario",
    "analysis",
    "lakehouse",
    "security",
  ];
  mods.forEach((name, i) => {
    const col = i % 4;
    const row = Math.floor(i / 4);
    slide.addShape(pres.ShapeType.roundRect, {
      x: 0.6 + col * 2.35,
      y: 1.0 + row * 1.1,
      w: 2.1,
      h: 0.75,
      fill: { color: i === 0 ? ACCENT : "FFFFFF" },
      line: { color: NAVY, width: 1 },
      rectRadius: 0.06,
    });
    slide.addText(name, {
      x: 0.6 + col * 2.35,
      y: 1.2 + row * 1.1,
      w: 2.1,
      h: 0.4,
      align: "center",
      fontSize: 12,
      bold: true,
      isTextBox: true,
    });
  });
  slide.addText("REST controllers → services above → dual datasource config", {
    x: 0.6,
    y: 3.5,
    w: 8.8,
    h: 0.5,
    fontSize: 12,
    color: GRAY,
    isTextBox: true,
  });
  slide.addNotes("Package map under gov.rajasthan.smart.srse.*");
}

function techStackSlide(pres) {
  const slide = pres.addSlide();
  slide.addText("Architecture 4 — Technology stack (summary)", {
    x: 0.4,
    y: 0.2,
    w: 9.2,
    h: 0.5,
    fontSize: 22,
    bold: true,
    color: NAVY,
    isTextBox: true,
  });
  const rows = [
    ["Frontend", "Next.js 16, React 19, TS, HeroUI, Tailwind 4, Zustand"],
    ["Backend", "Java 17, Spring Boot 3.3.4, fat JAR, springdoc"],
    ["Operational DB", "DB2, IBM JCC 11.5.8, Spring Data JPA"],
    ["Analytics", "PrestoDB 0.297, presto-jdbc, Iceberg"],
    ["Auth", "Spring Security, JWT (mock / RajSewadwar)"],
    ["Deploy", "Docker Compose, DATA_MODE, AUTH_MODE"],
  ];
  let y = 0.85;
  rows.forEach(([layer, stack]) => {
    slide.addShape(pres.ShapeType.roundRect, {
      x: 0.5,
      y,
      w: 1.6,
      h: 0.55,
      fill: { color: ICE },
      line: { color: NAVY, width: 1 },
      rectRadius: 0.05,
    });
    slide.addText(layer, {
      x: 0.55,
      y: y + 0.12,
      w: 1.5,
      h: 0.35,
      fontSize: 11,
      bold: true,
      isTextBox: true,
    });
    slide.addShape(pres.ShapeType.roundRect, {
      x: 2.2,
      y,
      w: 7.3,
      h: 0.55,
      fill: { color: "FFFFFF" },
      line: { color: NAVY, width: 1 },
      rectRadius: 0.05,
    });
    slide.addText(stack, {
      x: 2.35,
      y: y + 0.12,
      w: 7.0,
      h: 0.35,
      fontSize: 11,
      isTextBox: true,
    });
    y += 0.68;
  });
  slide.addNotes("Full version table in SRSE_Technical_Design_Document.docx §4.");
}

function archPreviewFlowSlide(pres) {
  const slide = pres.addSlide();
  slide.addText("Architecture 5 — Rule preview data flow", {
    x: 0.4,
    y: 0.2,
    w: 9.2,
    h: 0.5,
    fontSize: 22,
    bold: true,
    color: NAVY,
    isTextBox: true,
  });
  const boxes = ["Rules UI", "Decision API", "RuleCompiler", "Field resolver", "ExecutionService", "Presto"];
  boxes.forEach((label, i) => {
    const x = 0.4 + i * 1.55;
    slide.addShape(pres.ShapeType.roundRect, {
      x,
      y: 1.5,
      w: 1.35,
      h: 0.85,
      fill: { color: i === 2 ? ACCENT : "FFFFFF" },
      line: { color: NAVY, width: 1 },
      rectRadius: 0.05,
    });
    slide.addText(label, {
      x,
      y: 1.7,
      w: 1.35,
      h: 0.5,
      fontSize: 9,
      align: "center",
      isTextBox: true,
    });
    if (i < boxes.length - 1) {
      slide.addShape(pres.ShapeType.line, {
        x: x + 1.35,
        y: 1.9,
        w: 0.2,
        h: 0,
        line: { color: GRAY, width: 1.2, endArrowType: "triangle" },
      });
    }
  });
  slide.addText("Parameterised SQL only — officer values bound via JDBC", {
    x: 0.5,
    y: 2.8,
    w: 9,
    h: 0.4,
    fontSize: 12,
    italic: true,
    color: GRAY,
    isTextBox: true,
  });
  slide.addNotes("Same pattern for Analysis with RecordMatchService planning SQL.");
}

function main() {
  const pres = new pptxgen();
  pres.layout = "LAYOUT_16x9";
  pres.author = "SRSE Programme";
  pres.title = "SRSE Workflows and Architecture";

  addTitleSlide(pres);

  sectionSlide(pres, "Part A — Functional workflows (BRD)", "Business process diagrams");
  contextSlide(pres);

  flowSlide(
    pres,
    "Workflow 2 — Admin lakehouse setup",
    [
      "Authenticate as administrator",
      "Browse catalog → schema → table (live cluster)",
      "Register table with Silver/Gold display tag",
      "Configure column metadata (name, fuzzy, compare-as)",
      "Map catalogue fields to physical columns (LIVE mode)",
      "Optional: export JSON configuration backup",
      "Officers consume registered objects in Rules & Analysis",
    ],
    "Registration is per table; columns are introspected live on each request."
  );

  flowSlide(
    pres,
    "Workflow 3 — Officer rule simulation",
    [
      "Sign in (STATE_OFFICER) and open Rules UI",
      "Select scheme and build ruleset from flat field catalogue",
      "Adjust thresholds (ranges, IN lists, boolean toggles)",
      "Request preview → compile to parameterised Presto SQL",
      "View aggregate count and breakdown (district, gender, age band)",
      "Optionally save scenario or request capped cohort sample",
    ],
    "Preview does not persist; scenarios snapshot results to DB2."
  );

  flowSlide(
    pres,
    "Workflow 4 — Scenario compare",
    [
      "Load or create baseline scenario for a scheme",
      "Change one or more thresholds in Rules UI",
      "Save second scenario with distinct name",
      "Invoke compare API with two scenario IDs",
      "Review total count delta and breakdown dimension deltas",
      "Use outcomes in policy / budget discussion",
    ],
    "Compare is pairwise; both scenarios must reference same analytical data mode."
  );

  analysisBranchSlide(pres);

  flowSlide(
    pres,
    "Workflow 6 — Deployment & data modes",
    [
      "Configure DATA_MODE=synthetic (laptop) or live (client Dev)",
      "Set JDBC URLs for DB2 and Presto in application config / env",
      "Deploy frontend + backend containers",
      "Verify GET /api/health/planes → operational + analytical UP",
      "Admin completes registration and mappings before officer UAT",
      "Switch AUTH_MODE to RajSewadwar for production",
    ],
    "See CONFIGURATION_GUIDE.md for variable reference."
  );

  sectionSlide(pres, "Part B — Architecture & tech stack (TDD)", "Technical design diagrams");
  archDeploymentSlide(pres);
  archDualPlaneSlide(pres);
  archModulesSlide(pres);
  techStackSlide(pres);
  archPreviewFlowSlide(pres);

  pres.writeFile({ fileName: OUT });
  console.log("Wrote", OUT);
}

main();
