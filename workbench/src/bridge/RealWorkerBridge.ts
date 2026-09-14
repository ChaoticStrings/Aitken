// `?worker&inline` (typed by vite/client.d.ts) makes Vite emit the worker's
// compiled JS as a string embedded in the main bundle, constructed into a
// Worker at runtime via `new Blob([...]) + URL.createObjectURL()` — not a
// separate dist/ file. This is what keeps ticket 01's "exactly one file"
// acceptance criterion true now that a real Worker exists; a plain
// `new Worker(new URL('./worker/index.ts', import.meta.url))` would make
// Vite emit a second file that vite-plugin-singlefile has no idea how to
// inline (checked: the plugin has no worker-specific handling at all).
import RawWorker from '../worker/index.ts?worker&inline';
import type { WorkerToMainMessage } from '../worker/index';
import { ImportError, type ImportErrorCode } from '../import/errors';
import type { ParsedSession } from '../worker/parse';
import type { ProgressCallback, WorkerBridge } from './WorkerBridge';

export class RealWorkerBridge implements WorkerBridge {
  private readonly worker = new RawWorker();
  private nextRequestId = 0;

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

  terminate(): void {
    this.worker.terminate();
  }
}
