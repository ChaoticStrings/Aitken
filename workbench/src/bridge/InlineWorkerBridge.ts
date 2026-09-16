import { parseSession } from '../worker/parse';
import type { ParsedSession } from '../worker/parse';
import { runDetectorCheck } from '../worker/index';
import type { DetectorCheckSummary } from '../worker/index';
import type { ProgressCallback, WorkerBridge, DetectorCheckInput } from './WorkerBridge';

/** Runs the exact same `parseSession`/`runDetectorCheck` the real Worker
 * calls, synchronously on the calling thread — AC4's "runs the same code
 * synchronously for tests". Behavioural parity with `RealWorkerBridge`
 * comes from literally sharing the functions, not from re-implementing
 * them twice. There is no real thread here to race, so `runId` is accepted
 * for interface parity but every call simply resolves with its own result
 * — a synchronous call can't be superseded by a call that hasn't happened
 * yet. */
export class InlineWorkerBridge implements WorkerBridge {
  async parseSession(files: Map<string, Blob>, onProgress?: ProgressCallback): Promise<ParsedSession> {
    onProgress?.('reading', 0);
    const textFiles = new Map<string, string>();
    for (const [name, blob] of files) {
      textFiles.set(name, await blob.text());
    }
    onProgress?.('parsing', 0.5);
    const session = parseSession(textFiles);
    onProgress?.('parsing', 1);
    return session;
  }

  async runDetectorCheck(input: DetectorCheckInput): Promise<DetectorCheckSummary> {
    return runDetectorCheck(input);
  }

  terminate(): void {
    // Nothing to tear down — there is no real thread.
  }
}
