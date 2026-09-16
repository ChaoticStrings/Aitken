// `?worker&inline` (typed by vite/client.d.ts) makes Vite emit the worker's
// compiled JS as a string embedded in the main bundle, constructed into a
// Worker at runtime via `new Blob([...]) + URL.createObjectURL()` — not a
// separate dist/ file. This is what keeps ticket 01's "exactly one file"
// acceptance criterion true now that a real Worker exists; a plain
// `new Worker(new URL('./worker/index.ts', import.meta.url))` would make
// Vite emit a second file that vite-plugin-singlefile has no idea how to
// inline (checked: the plugin has no worker-specific handling at all).
import RawWorker from '../worker/index.ts?worker&inline';
import type { WorkerToMainMessage, DetectorCheckSummary } from '../worker/index';
import { ImportError, type ImportErrorCode } from '../import/errors';
import type { ParsedSession } from '../worker/parse';
import type { ProgressCallback, WorkerBridge, DetectorCheckInput } from './WorkerBridge';

export class RealWorkerBridge implements WorkerBridge {
  private readonly worker = new RawWorker();
  private nextRequestId = 0;
  /** The most recent `runId` requested via `runDetectorCheck` — a
   * `detectorChecked`/matching `error` message whose `runId` doesn't equal
   * this when it arrives back is stale (E-40) and is dropped rather than
   * resolving/rejecting its caller's promise (see `WorkerBridge`'s doc
   * comment on why that promise is left unsettled instead). */
  private latestDetectorRunId: string | null = null;

  parseSession(files: Map<string, Blob>, onProgress?: ProgressCallback): Promise<ParsedSession> {
    const requestId = String(this.nextRequestId++);
    return new Promise((resolve, reject) => {
      const handleMessage = (ev: MessageEvent<WorkerToMainMessage>) => {
        const msg = ev.data;
        if (msg.requestId !== requestId) return; // a concurrent request's message
        if (msg.type === 'progress') {
          onProgress?.(msg.phase, msg.fraction);
          return;
        }
        this.worker.removeEventListener('message', handleMessage);
        if (msg.type === 'parsed') {
          resolve(msg.session);
        } else if (msg.type === 'detectorChecked') {
          return; // not this request's message type; unreachable in practice
        } else if (msg.code === 'UNKNOWN') {
          reject(new Error(msg.message));
        } else {
          reject(new ImportError(msg.code as ImportErrorCode, msg.details ?? {}));
        }
      };
      this.worker.addEventListener('message', handleMessage);
      this.worker.postMessage({ type: 'parse', requestId, files });
    });
  }

  runDetectorCheck(input: DetectorCheckInput, runId: string): Promise<DetectorCheckSummary> {
    const requestId = String(this.nextRequestId++);
    this.latestDetectorRunId = runId;
    return new Promise((resolve, reject) => {
      const handleMessage = (ev: MessageEvent<WorkerToMainMessage>) => {
        const msg = ev.data;
        if (msg.requestId !== requestId) return; // a concurrent request's message
        this.worker.removeEventListener('message', handleMessage);
        if (runId !== this.latestDetectorRunId) return; // E-40: superseded by a newer run; drop silently
        if (msg.type === 'detectorChecked') {
          resolve(msg.summary);
        } else if (msg.type === 'error') {
          reject(msg.code === 'UNKNOWN' ? new Error(msg.message) : new ImportError(msg.code as ImportErrorCode, msg.details ?? {}));
        }
      };
      this.worker.addEventListener('message', handleMessage);
      // Deliberately NOT a transfer list: `input`'s typed arrays are the
      // same buffers the caller's already-imported `ParsedSession` (and
      // anything else on the main thread reading it, e.g. a future
      // WaveformView) still holds live references to. Transferring them
      // would detach and zero out the main thread's own copies out from
      // under it. A one-time structured-clone copy (well under a
      // millisecond even for the 90s fixture's ~9k rows) is the right
      // trade here, unlike `parseSession`'s result, which nothing else on
      // the main thread has seen yet.
      this.worker.postMessage({ type: 'detectorCheck', requestId, runId, ...input });
    });
  }

  terminate(): void {
    this.worker.terminate();
  }
}
