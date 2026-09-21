/**
 * Business audience — 2 slides: use cases + architecture
 * → docs/SRSE_Business_Overview.pptx
 */
import pptxgen from "pptxgenjs";
import { fileURLToPath } from "url";
import path from "path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(__dirname, "..", "SRSE_Business_Overview.pptx");

const NAVY = "1E2761";
const DEEP = "0F1729";
const ICE = "CADCFC";
const GOLD = "F4A261";
const TEAL = "2A9D8F";
const WHITE = "FFFFFF";
const MIST = "E8EEF7";
const GRAY = "444444";
const GRAY_MID = "555555";

/** Shared navy header band (matches slide 2). */
function addNavyHeader(pres, slide, title) {
  slide.background = { color: WHITE };
  slide.addShape(pres.ShapeType.rect, {
    x: 0,
    y: 0,
    w: 13.33,
    h: 1.05,
    fill: { color: NAVY },
    line: { color: NAVY, width: 0 },
  });
  slide.addText(title, {
    x: 0.55,
    y: 0.22,
    w: 12,
    h: 0.65,
    fontSize: 28,
    bold: true,
    color: WHITE,
    isTextBox: true,
  });
}

function slideUseCases(pres) {
  const slide = pres.addSlide();
  addNavyHeader(pres, slide, "Where SRSE creates value for departments");

  slide.addText(
    "Independent product for policy design and data confidence — no SQL or IT ticket for every what-if question.",
    {
      x: 0.55,
      y: 1.18,
      w: 12.2,
      h: 0.5,
      fontSize: 13,
      color: GRAY_MID,
      isTextBox: true,
    }
  );

  slide.addShape(pres.ShapeType.roundRect, {
    x: 0.45,
    y: 1.75,
    w: 12.45,
    h: 4.95,
    fill: { color: MIST },
    line: { color: NAVY, width: 1 },
    rectRadius: 0.08,
  });
  slide.addText("Potential use cases", {
    x: 0.45,
    y: 1.88,
    w: 12.45,
    h: 0.35,
    fontSize: 12,
    bold: true,
    align: "center",
    color: NAVY,
    isTextBox: true,
  });

  const cases = [
    {
      x: 0.65,
      y: 2.35,
      title: "Scheme impact before you notify",
      body: "Change age, income, or category rules and see how many citizens qualify — with district and demographic breakdowns for cabinet and budget discussions.",
    },
    {
      x: 4.65,
      y: 2.35,
      title: "Compare policy options side by side",
      body: "Save named scenarios (baseline vs proposal) and show exactly how eligibility shifts — reproducible evidence instead of one-off spreadsheets.",
    },
    {
      x: 8.65,
      y: 2.35,
      title: "Reconcile registers with golden data",
      body: "Match departmental lists against the golden beneficiary layer — including fuzzy names — to find gaps, duplicates, and migration quality issues at state scale.",
    },
    {
      x: 0.65,
      y: 4.25,
      title: "Multi-department hub checks",
      body: "Compare one central register to several source systems in one session — ideal when many departments feed one scheme.",
    },
    {
      x: 4.65,
      y: 4.25,
      title: "Governed, privacy-aware insight",
      body: "Day-to-day use shows totals and breakdowns only; small capped samples when you need to sanity-check who is selected — not full population extracts.",
    },
    {
      x: 8.65,
      y: 4.25,
      title: "Faster cycles, less ad-hoc IT",
      body: "Officers iterate in the browser; data teams register tables once. Configuration can be backed up and restored as JSON across environments.",
    },
  ];

  cases.forEach((c, idx) => {
    const accent = idx % 3 === 0 ? GOLD : idx % 3 === 1 ? TEAL : NAVY;
    slide.addShape(pres.ShapeType.roundRect, {
      x: c.x,
      y: c.y,
      w: 3.65,
      h: 1.72,
      fill: { color: WHITE },
      line: { color: accent, width: 1.5 },
      rectRadius: 0.08,
    });
    slide.addText(c.title, {
      x: c.x + 0.18,
      y: c.y + 0.15,
      w: 3.3,
      h: 0.5,
      fontSize: 12,
      bold: true,
      color: NAVY,
      isTextBox: true,
    });
    slide.addText(c.body, {
      x: c.x + 0.18,
      y: c.y + 0.62,
      w: 3.3,
      h: 1.0,
      fontSize: 10,
      color: GRAY,
      isTextBox: true,
    });
  });

  slide.addText(
    "Also: join canvas (multi-target), two-table join types, SRSE_ADMIN vs officer roles, layer-filtered lakehouse pickers.",
    {
      x: 0.55,
      y: 6.35,
      w: 12.2,
      h: 0.4,
      fontSize: 10,
      color: GRAY_MID,
      isTextBox: true,
    }
  );

  slide.addText(
    "SRSE — Scheme Rule Simulation Engine · standalone from the citizen portal · first deployed under Rajasthan SMART",
    {
      x: 0.55,
      y: 6.85,
      w: 12.2,
      h: 0.45,
      fontSize: 11,
      italic: true,
      color: GRAY_MID,
      isTextBox: true,
    }
  );

  slide.addNotes(
    "Talking points: SRSE is standalone from the citizen portal; first deployment Rajasthan SMART; speaks to policy, finance, and data stewardship audiences."
  );
}

function slideArchitecture(pres) {
  const slide = pres.addSlide();
  addNavyHeader(pres, slide, "How SRSE fits your enterprise — at a glance");

  // Left pillar — People
  slide.addShape(pres.ShapeType.roundRect, {
    x: 0.45,
    y: 1.35,
    w: 2.35,
    h: 5.35,
    fill: { color: MIST },
    line: { color: NAVY, width: 1 },
    rectRadius: 0.08,
  });
  slide.addText("People", {
    x: 0.45,
    y: 1.48,
    w: 2.35,
    h: 0.35,
    fontSize: 12,
    bold: true,
    align: "center",
    color: NAVY,
    isTextBox: true,
  });
  const people = [
    { y: 2.0, t: "Scheme officers", s: "Rules & scenarios" },
    { y: 3.05, t: "Data stewards", s: "Register tables" },
    { y: 4.1, t: "Leadership", s: "Evidence for decisions" },
  ];
  people.forEach((p) => {
    slide.addShape(pres.ShapeType.roundRect, {
      x: 0.6,
      y: p.y,
      w: 2.05,
      h: 0.85,
      fill: { color: WHITE },
      line: { color: TEAL, width: 1 },
      rectRadius: 0.06,
    });
    slide.addText(p.t, {
      x: 0.65,
      y: p.y + 0.12,
      w: 1.95,
      h: 0.35,
      fontSize: 11,
      bold: true,
      align: "center",
      color: NAVY,
      isTextBox: true,
    });
    slide.addText(p.s, {
      x: 0.65,
      y: p.y + 0.45,
      w: 1.95,
      h: 0.3,
      fontSize: 9,
      align: "center",
      color: "555555",
      isTextBox: true,
    });
  });

  // Center — SRSE hero block
  slide.addShape(pres.ShapeType.roundRect, {
    x: 3.15,
    y: 1.55,
    w: 4.35,
    h: 4.95,
    fill: { color: NAVY },
    line: { color: GOLD, width: 2.5 },
    rectRadius: 0.12,
  });
  slide.addText("SRSE", {
    x: 3.15,
    y: 1.75,
    w: 4.35,
    h: 0.55,
    fontSize: 26,
    bold: true,
    align: "center",
    color: GOLD,
    isTextBox: true,
  });
  slide.addText("Scheme Rule\nSimulation Engine", {
    x: 3.35,
    y: 2.25,
    w: 3.95,
    h: 0.7,
    fontSize: 14,
    align: "center",
    color: WHITE,
    isTextBox: true,
  });

  const caps = [
    { y: 3.05, t: "Rules simulation", d: "Who qualifies? Counts & breakdowns" },
    { y: 3.85, t: "Analysis matching", d: "Do two datasets align?" },
    { y: 4.65, t: "Admin & backup", d: "Governed tables & JSON config" },
  ];
  caps.forEach((c) => {
    slide.addShape(pres.ShapeType.roundRect, {
      x: 3.45,
      y: c.y,
      w: 3.75,
      h: 0.65,
      fill: { color: DEEP },
      line: { color: TEAL, width: 1 },
      rectRadius: 0.05,
    });
    slide.addText(c.t, {
      x: 3.55,
      y: c.y + 0.08,
      w: 3.55,
      h: 0.28,
      fontSize: 11,
      bold: true,
      color: ICE,
      isTextBox: true,
    });
    slide.addText(c.d, {
      x: 3.55,
      y: c.y + 0.34,
      w: 3.55,
      h: 0.25,
      fontSize: 9,
      color: WHITE,
      isTextBox: true,
    });
  });

  slide.addText("Web app + secure API\n(standalone product)", {
    x: 3.35,
    y: 5.55,
    w: 3.95,
    h: 0.45,
    fontSize: 10,
    italic: true,
    align: "center",
    color: ICE,
    isTextBox: true,
  });

  // Right — Data platform
  slide.addShape(pres.ShapeType.roundRect, {
    x: 7.85,
    y: 1.35,
    w: 5.0,
    h: 5.35,
    fill: { color: MIST },
    line: { color: NAVY, width: 1 },
    rectRadius: 0.08,
  });
  slide.addText("Enterprise data platform", {
    x: 7.85,
    y: 1.48,
    w: 5.0,
    h: 0.35,
    fontSize: 12,
    bold: true,
    align: "center",
    color: NAVY,
    isTextBox: true,
  });

  slide.addShape(pres.ShapeType.roundRect, {
    x: 8.15,
    y: 2.05,
    w: 4.4,
    h: 1.35,
    fill: { color: WHITE },
    line: { color: GOLD, width: 1.5 },
    rectRadius: 0.08,
  });
  slide.addText("Lakehouse — beneficiary & scheme data", {
    x: 8.25,
    y: 2.2,
    w: 4.2,
    h: 0.35,
    fontSize: 12,
    bold: true,
    align: "center",
    color: NAVY,
    isTextBox: true,
  });
  slide.addText("Silver · Gold · Iceberg tables\nLarge-scale counts & matching run here", {
    x: 8.25,
    y: 2.55,
    w: 4.2,
    h: 0.7,
    fontSize: 10,
    align: "center",
    color: "444444",
    isTextBox: true,
  });

  slide.addShape(pres.ShapeType.roundRect, {
    x: 8.15,
    y: 3.65,
    w: 4.4,
    h: 1.15,
    fill: { color: WHITE },
    line: { color: TEAL, width: 1.5 },
    rectRadius: 0.08,
  });
  slide.addText("SRSE configuration store", {
    x: 8.25,
    y: 3.8,
    w: 4.2,
    h: 0.3,
    fontSize: 11,
    bold: true,
    align: "center",
    color: NAVY,
    isTextBox: true,
  });
  slide.addText("Scenarios · field mappings · table registry", {
    x: 8.25,
    y: 4.15,
    w: 4.2,
    h: 0.45,
    fontSize: 10,
    align: "center",
    color: "444444",
    isTextBox: true,
  });

  slide.addShape(pres.ShapeType.roundRect, {
    x: 8.15,
    y: 5.05,
    w: 4.4,
    h: 0.75,
    fill: { color: NAVY },
    line: { color: NAVY, width: 0 },
    rectRadius: 0.06,
  });
  slide.addText("Organisation SSO — authorised access only", {
    x: 8.25,
    y: 5.25,
    w: 4.2,
    h: 0.4,
    fontSize: 10,
    bold: true,
    align: "center",
    color: WHITE,
    isTextBox: true,
  });

  // Arrows people → SRSE → data
  const arrowStyle = { color: TEAL, width: 2, endArrowType: "triangle" };
  slide.addShape(pres.ShapeType.line, {
    x: 2.85,
    y: 3.5,
    w: 0.25,
    h: 0,
    line: arrowStyle,
  });
  slide.addShape(pres.ShapeType.line, {
    x: 7.55,
    y: 3.5,
    w: 0.25,
    h: 0,
    line: arrowStyle,
  });
  slide.addShape(pres.ShapeType.line, {
    x: 5.8,
    y: 5.35,
    w: 0,
    h: 0.55,
    line: arrowStyle,
  });
  slide.addShape(pres.ShapeType.line, {
    x: 10.35,
    y: 3.4,
    w: 0,
    h: 0.95,
    line: { color: GOLD, width: 2, endArrowType: "triangle" },
  });

  slide.addText(
    "SRSE never replaces your lakehouse — it adds a governed layer for policy simulation and reconciliation on top of data you already trust.",
    {
      x: 0.55,
      y: 6.85,
      w: 12.2,
      h: 0.45,
      fontSize: 11,
      italic: true,
      color: "555555",
      isTextBox: true,
    }
  );

  slide.addNotes(
    "Architecture story: officers interact only with SRSE; heavy analytics stay in lakehouse; configuration is separate and portable; SSO at the edge."
  );
}

function main() {
  const pres = new pptxgen();
  pres.layout = "LAYOUT_WIDE";
  pres.author = "SRSE Programme";
  pres.title = "SRSE — Business Overview";

  slideUseCases(pres);
  slideArchitecture(pres);

  pres.writeFile({ fileName: OUT });
  console.log("Wrote", OUT);
}

main();
