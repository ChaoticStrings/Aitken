// Plan §9: `worker/index.ts (message protocol)`. Ticket 02 only implements
// the `parse` phase — `recompute`/`lod` (§4.4's later pipeline steps) are
// ticket 03's `worker/dsp.ts`/`worker/lod.ts`, which don't exist yet.
import { parseSession, type ParsedSession } from './parse';
import { ImportError, type ImportErrorCode } from '../import/errors';

export interface ParseRequest {
  type: 'parse';
  requestId: string;
  files: Map<string, Blob>;
}

export type WorkerToMainMessage =
  | { type: 'progress'; requestId: string; phase: string; fraction: number }
  | { type: 'parsed'; requestId: string; session: ParsedSession }
  | { type: 'error'; requestId: string; code: ImportErrorCode | 'UNKNOWN'; message: string; details?: Record<string, unknown> };

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

self.onmessage = async (ev: MessageEvent<ParseRequest>) => {
  const { requestId, files } = ev.data;
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
};
