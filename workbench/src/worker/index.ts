// Plan §9: `worker/index.ts (message protocol)`. Ticket 02 built the
// `parse` phase; ticket 03 adds `detectorCheck` (§4.4's `recompute` step,
// plus calibration/simulation/divergence) as a second, independent request
// type on the same worker — not a second worker, since spinning up a
// second `RealWorkerBridge` per Web Worker instance for what's still one
// small pure-logic job would cost more (another thread, another
// postMessage round-trip setup) than it buys.
import { parseSession, DEFAULT_SESSION_CONFIG, type ParsedSession, type SessionConfig } from './parse';
import { calibrate, simulate, type DetectionTunables } from './detector';
import { recompute, divergence } from './dsp';
import { ImportError, type ImportErrorCode } from '../import/errors';
import type { Segment } from '../core/types';

export interface ParseRequest {
  type: 'parse';
  requestId: string;
  files: Map<string, Blob>;
}

/** Everything `calibrate`/`simulate`/`recompute`/`divergence` need, lifted
 * straight out of a `ParsedSession` (§4.4: `recompute` runs right after
 * `parse` in the import pipeline) rather than re-parsing CSV text — the
 * caller already has a `ParsedSession` in hand by the time it wants a
 * detector check. */
export interface DetectorCheckRequest {
  type: 'detectorCheck';
  requestId: string;
  /** Distinguishes concurrent requests so a newer one's result isn't
   * shadowed by a slower older one (E-40). The *bridge*, not this worker,
   * is what actually drops a stale result — this module only has to carry
   * the id through unchanged. */
  runId: string;
  tNs: BigInt64Array;
  vertical: Float64Array;
  ax: Float64Array;
  ay: Float64Array;
  az: Float64Array;
  gz: Float64Array;
  gyroCoverage: number;
  segments: Segment[];
  config: SessionConfig | null;
}

export interface DetectorCheckSummary {
  simulatedCount: number;
  recordedCount: number;
  divergentCount: number;
  /** E-37: session shorter than the calibration window — `simulatedCount`
   * is correctly 0, but that 0 means something different than "a full
   * calibration run found nothing", so the Library card can word it. */
  calibrationIncomplete: boolean;
}

export type WorkerToMainMessage =
  | { type: 'progress'; requestId: string; phase: string; fraction: number }
  | { type: 'parsed'; requestId: string; session: ParsedSession }
  | { type: 'detectorChecked'; requestId: string; runId: string; summary: DetectorCheckSummary }
  | { type: 'error'; requestId: string; runId?: string; code: ImportErrorCode | 'UNKNOWN'; message: string; details?: Record<string, unknown> };

function tunablesOf(config: SessionConfig | null): DetectionTunables {
  const c = config ?? DEFAULT_SESSION_CONFIG;
  return {
    calibrationDurationMs: c.calibrationDurationMs,
    stdFactor: c.stdFactor,
    floorStd: c.floorStd,
    endQuietMs: c.endQuietMs,
    minSegmentDurationMs: c.minSegmentDurationMs,
    turnYawThresholdRadS: c.turnYawThresholdRadS,
  };
}

/** `> 10%` is D8/E's divergence line, same number `core/queue.ts`'s future
 * `peak-divergence` review item will use — kept as a local literal here
 * (rule 0.5: `core/queue.ts` doesn't exist yet) rather than a shared
 * constant with nothing on the other end to import it. Guards
 * `recordedPeak === 0` (a segment with no recorded deviation at all)
 * against a division by zero — not exercised by any real segment in the
 * fixture, but a segment created later in the Workbench (ticket 10) could
 * legitimately start at `peakM: 0`. */
function isDivergent(recordedPeak: number, recomputedPeak: number): boolean {
  if (recordedPeak === 0) return recomputedPeak > 0;
  return Math.abs(recomputedPeak - recordedPeak) / recordedPeak > 0.1;
}

/** Exported (not just used by `self.onmessage` below) so `InlineWorkerBridge`
 * can call the exact same function synchronously, the same pattern ticket
 * 02 established for `parseSession` — behavioural parity with
 * `RealWorkerBridge` comes from sharing the function, not reimplementing
 * it twice. */
export function runDetectorCheck(req: Omit<DetectorCheckRequest, 'type' | 'requestId' | 'runId'>): DetectorCheckSummary {
  const t = tunablesOf(req.config);
  const c = calibrate(req.vertical, req.tNs, t);
  const gz = req.gyroCoverage > 0 ? req.gz : null; // E-06: no gyro column at all -> turning always false
  const simulated = simulate(req.vertical, req.tNs, gz, t, c);
  const { vertical: recomputedVertical } = recompute(req.ax, req.ay, req.az);
  const div = divergence(req.segments, req.tNs, recomputedVertical);
  const divergentCount = div.filter((d) => isDivergent(d.recordedPeak, d.recomputedPeak)).length;
  return {
    simulatedCount: simulated.length,
    recordedCount: req.segments.length,
    divergentCount,
    calibrationIncomplete: req.vertical.length > 0 && c.calibEndIdx >= req.vertical.length - 1,
  };
}

/** Every typed-array buffer inside a `ParsedSession`, for `postMessage`'s
 * transfer list — AC4 requires these are *transferred*, not structurally
 * cloned/copied (a 55 MB session, E-14, would otherwise be copied twice:
 * once into the message, once out of it). */
export function collectTransferables(session: ParsedSession): ArrayBuffer[] {
  const s = session.sensor;
  const buffers: ArrayBuffer[] = [
    s.tNs.buffer as ArrayBuffer,
    s.tSec.buffer as ArrayBuffer,
    s.ax.buffer as ArrayBuffer,
    s.ay.buffer as ArrayBuffer,
    s.az.buffer as ArrayBuffer,
    s.gx.buffer as ArrayBuffer,
    s.gy.buffer as ArrayBuffer,
    s.gz.buffer as ArrayBuffer,
    s.vertical.buffer as ArrayBuffer,
    s.jerk.buffer as ArrayBuffer,
    s.rollStd.buffer as ArrayBuffer,
  ];
  if (session.gps) {
    const g = session.gps;
    buffers.push(
      g.tNs.buffer as ArrayBuffer,
      g.lat.buffer as ArrayBuffer,
      g.lon.buffer as ArrayBuffer,
      g.speedMps.buffer as ArrayBuffer,
      g.accuracyM.buffer as ArrayBuffer,
    );
  }
  return buffers;
}

async function toTextFiles(files: Map<string, Blob>): Promise<Map<string, string>> {
  const out = new Map<string, string>();
  for (const [name, blob] of files) {
    out.set(name, await blob.text());
  }
  return out;
}

function post(msg: WorkerToMainMessage, transfer?: ArrayBuffer[]): void {
  // `self` here is the WorkerGlobalScope; typed narrowly at the call site
  // instead of module-wide so this file still type-checks when bundled for
  // the main thread by mistake (it never should be, but noUncheckedIndexedAccess-
  // strict mode makes `self` in a shared tsconfig ambiguous otherwise).
  (self as unknown as Worker).postMessage(msg, transfer as Transferable[]);
}

self.onmessage = async (ev: MessageEvent<ParseRequest | DetectorCheckRequest>) => {
  const data = ev.data;
  if (data.type === 'parse') {
    const { requestId, files } = data;
    try {
      post({ type: 'progress', requestId, phase: 'reading', fraction: 0 });
      const textFiles = await toTextFiles(files);
      post({ type: 'progress', requestId, phase: 'parsing', fraction: 0.5 });
      const session = parseSession(textFiles);
      post({ type: 'progress', requestId, phase: 'parsing', fraction: 1 });
      post({ type: 'parsed', requestId, session }, collectTransferables(session));
    } catch (e) {
      if (e instanceof ImportError) {
        post({ type: 'error', requestId, code: e.code, message: e.message, details: e.details });
      } else {
        post({ type: 'error', requestId, code: 'UNKNOWN', message: e instanceof Error ? e.message : String(e) });
      }
    }
    return;
  }

  const { requestId, runId } = data;
  try {
    const summary = runDetectorCheck(data);
    post({ type: 'detectorChecked', requestId, runId, summary });
  } catch (e) {
    post({ type: 'error', requestId, runId, code: 'UNKNOWN', message: e instanceof Error ? e.message : String(e) });
  }
};
