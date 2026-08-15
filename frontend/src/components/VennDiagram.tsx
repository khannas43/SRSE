"use client";

export type VennSet = { label: string; color: string };

/** Region counts keyed by membership bitmask, e.g. for 3 sets "100" = A only, "111" = A∩B∩C. */
export type VennCounts = Record<string, number>;

type Layout = {
  viewBox: string;
  width: number;
  height: number;
  circles: { cx: number; cy: number; r: number }[];
  labelPositions: Record<string, { x: number; y: number }>;
};

// Schematic (not area-proportional) layouts — same visual arrangement as a
// standard 3-circle Venn: circle 0 top-left, circle 1 top-right, circle 2 bottom.
const LAYOUT_3: Layout = {
  viewBox: "0 0 400 400",
  width: 340,
  height: 340,
  circles: [
    { cx: 150, cy: 150, r: 150 },
    { cx: 250, cy: 150, r: 150 },
    { cx: 200, cy: 250, r: 150 },
  ],
  labelPositions: {
    "100": { x: 85, y: 95 },
    "010": { x: 315, y: 95 },
    "001": { x: 200, y: 335 },
    "110": { x: 200, y: 85 },
    "101": { x: 125, y: 235 },
    "011": { x: 275, y: 235 },
    "111": { x: 200, y: 195 },
  },
};

const LAYOUT_2: Layout = {
  viewBox: "0 0 400 300",
  width: 340,
  height: 255,
  circles: [
    { cx: 140, cy: 150, r: 120 },
    { cx: 260, cy: 150, r: 120 },
  ],
  labelPositions: {
    "10": { x: 80, y: 150 },
    "01": { x: 320, y: 150 },
    "11": { x: 200, y: 150 },
  },
};

type VennDiagramProps = Readonly<{ sets: VennSet[]; counts: VennCounts }>;

/** 2- or 3-circle schematic Venn diagram with counts overlaid per region. */
export function VennDiagram({ sets, counts }: VennDiagramProps) {
  const layout = sets.length === 3 ? LAYOUT_3 : LAYOUT_2;

  return (
    <div style={{ display: "flex", gap: "1.75rem", flexWrap: "wrap", alignItems: "center" }}>
      <svg viewBox={layout.viewBox} width={layout.width} height={layout.height}>
        {layout.circles.map((c, i) => (
          <circle
            key={sets[i]?.label ?? `circle-${c.cx}-${c.cy}`}
            cx={c.cx}
            cy={c.cy}
            r={c.r}
            fill="none"
            stroke={sets[i]?.color}
            strokeWidth={4}
          />
        ))}
        {Object.entries(layout.labelPositions).map(([key, pos]) => (
          <text
            key={key}
            x={pos.x}
            y={pos.y}
            textAnchor="middle"
            dominantBaseline="middle"
            fontSize={22}
            fontWeight={700}
            fill="var(--srse-text, #1a1a1a)"
          >
            {counts[key] ?? 0}
          </text>
        ))}
      </svg>
      <div style={{ display: "flex", flexDirection: "column", gap: "0.5rem" }}>
        {sets.map((s) => (
          <div key={s.label} style={{ display: "flex", alignItems: "center", gap: "0.5rem", fontSize: "0.85rem" }}>
            <span
              style={{
                width: 14,
                height: 14,
                borderRadius: "50%",
                background: s.color,
                display: "inline-block",
                flexShrink: 0,
              }}
            />
            {s.label}
          </div>
        ))}
      </div>
    </div>
  );
}
