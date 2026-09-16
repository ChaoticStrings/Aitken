import type { ParsedSession } from '../worker/parse';
import type { DetectorCheckSummary } from '../worker/index';
import type { Segment } from '../core/types';
import type { SessionConfig } from '../worker/parse';

export type ProgressCallback = (phase: string, fraction: number) => void;

/** Everything a detector check needs, lifted from a `ParsedSession` —
 * mirrors `DetectorCheckRequest` in `worker/index.ts` minus the
 * message-protocol fields (`type`/`requestId`/`runId`), which are the
 * bridge's own concern, not the caller's. */
export interface DetectorCheckInput {
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

export interface WorkerBridge {
  parseSession(files: Map<string, Blob>, onProgress?: ProgressCallback): Promise<ParsedSession>;
  /**
   * `runId` is the caller's own token (e.g. a monotonically increasing
   * counter or a fresh id per user action) — E-40's "a newer request
   * cancels/ignores an older result". A result whose `runId` isn't the
   * latest one this bridge instance has seen requested is silently
   * dropped: the returned promise for a *superseded* call never settles
   * (there is nothing meaningful to resolve or reject it with — the
   * caller that issued it has already moved on to the newer request, and
   * `Promise.race`-style abandonment is exactly what "ignores" means
   * here), so callers should always await the *latest* call they made,
   * not older ones still in flight.
   */
  runDetectorCheck(input: DetectorCheckInput, runId: string): Promise<DetectorCheckSummary>;
  terminate(): void;
}
