import type { MatchProgressEvent } from "@/lib/analysisApi";

export type TargetRunPhase =
  | "pending"
  | "started"
  | "running"
  | "done"
  | "error"
  | "skipped";

export type TargetRunStatus = {
  targetIndex: number;
  label: string;
  phase: TargetRunPhase;
  rows?: number;
  message?: string;
  reason?: string;
  sql?: string;
};

/**
 * Latest status per target label. Supports pending → error without an
 * intermediate started (planning failure before the target is announced).
 */
export function mergeTargetProgress(
  prev: TargetRunStatus[],
  event: MatchProgressEvent,
  targetLabels: string[],
): TargetRunStatus[] {
  const byLabel = new Map(prev.map((p) => [p.label, p]));
  for (const label of targetLabels) {
    if (!byLabel.has(label)) {
      byLabel.set(label, { targetIndex: targetLabels.indexOf(label), label, phase: "pending" });
    }
  }
  const current = byLabel.get(event.label) ?? {
    targetIndex: event.targetIndex,
    label: event.label,
    phase: "pending" as const,
  };
  let phase: TargetRunPhase = current.phase;
  switch (event.phase) {
    case "started":
      phase = "started";
      break;
    case "done":
      phase = "done";
      break;
    case "error":
      phase = "error";
      break;
    case "skipped":
      phase = "skipped";
      break;
    default:
      break;
  }
  byLabel.set(event.label, {
    targetIndex: event.targetIndex,
    label: event.label,
    phase,
    rows: event.rows ?? current.rows,
    message: event.message ?? current.message,
    reason: event.reason ?? current.reason,
    sql: event.sql ?? current.sql,
  });
  return targetLabels.map((label) => byLabel.get(label)!);
}

export function initTargetProgress(targetLabels: string[]): TargetRunStatus[] {
  return targetLabels.map((label, targetIndex) => ({
    targetIndex,
    label,
    phase: "pending",
  }));
}

export function phaseLabel(status: TargetRunStatus): string {
  switch (status.phase) {
    case "pending":
      return "pending";
    case "started":
      return "running…";
    case "running":
      return "running…";
    case "done":
      return `${status.rows ?? 0} rows`;
    case "error":
      return status.message ?? "failed";
    case "skipped":
      return `skipped (${status.reason ?? "time budget"}) — not the same as zero matches`;
    default:
      return status.phase;
  }
}
