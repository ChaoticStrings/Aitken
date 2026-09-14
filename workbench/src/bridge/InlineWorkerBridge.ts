import { parseSession } from '../worker/parse';
import type { ParsedSession } from '../worker/parse';
import type { ProgressCallback, WorkerBridge } from './WorkerBridge';

/** Runs the exact same `parseSession` the real Worker calls, synchronously
 * on the calling thread — AC4's "runs the same code synchronously for
 * tests". Behavioural parity with `RealWorkerBridge` comes from literally
 * sharing the function, not from re-implementing it twice. */
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

  terminate(): void {
    // Nothing to tear down — there is no real thread.
  }
}
