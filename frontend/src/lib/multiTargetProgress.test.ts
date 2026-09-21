import { describe, expect, it } from "vitest";
import { initTargetProgress, mergeTargetProgress, phaseLabel } from "@/lib/multiTargetProgress";

describe("multi-target stream progress", () => {
  const labels = ["A", "B", "C"];

  it("pending → error without started marks failed, not pending", () => {
    let status = initTargetProgress(labels);
    status = mergeTargetProgress(status, {
      targetIndex: 1,
      label: "B",
      phase: "error",
      message: "planning failed",
    }, labels);
    const b = status.find((s) => s.label === "B");
    expect(b?.phase).toBe("error");
    expect(phaseLabel(b!)).toBe("planning failed");
    expect(status.find((s) => s.label === "A")?.phase).toBe("pending");
  });

  it("budget skip renders distinctly from failure", () => {
    let status = initTargetProgress(labels);
    status = mergeTargetProgress(status, {
      targetIndex: 2,
      label: "C",
      phase: "skipped",
      reason: "time budget",
    }, labels);
    const c = status.find((s) => s.label === "C");
    expect(c?.phase).toBe("skipped");
    expect(phaseLabel(c!)).toContain("time budget");
    expect(phaseLabel(c!)).toContain("not the same as zero matches");
  });
});
