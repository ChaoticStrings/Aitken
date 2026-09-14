import type { BundleSource } from '../import/BundleSource';
import type { WorkerBridge, ProgressCallback } from '../bridge/WorkerBridge';
import type { Persistence, StoredBundle } from '../persistence/Persistence';
import type { ImportWarning } from '../worker/parse';

export interface LibraryEntry {
  hash: string;
  sessionName: string;
  importedAt: number;
  sizeBytes: number;
  segments: number;
  tags: number;
  durationSec: number;
  dateMs: number; // first segment's epoch_ms, or importedAt if there are none
  // Always UNREVIEWED for now — a real value needs `core/reducer.ts`'s
  // `SessionState.reviewState` (ticket 05), which doesn't exist yet. A
  // freshly-imported bundle's journal is empty, so UNREVIEWED happens to be
  // correct regardless; this stops being true the day ticket 05 lands and
  // this needs revisiting.
  reviewState: 'UNREVIEWED';
  warnings: ImportWarning[];
}

export interface ImportOutcome {
  entry: LibraryEntry;
  /** True when this hash already existed — AC7/E-15: no duplicate card,
   * caller shows "already imported — opened" instead of a normal success
   * toast. */
  alreadyImported: boolean;
}

function toEntry(bundle: StoredBundle): LibraryEntry {
  return {
    hash: bundle.hash,
    sessionName: bundle.sessionName,
    importedAt: bundle.importedAt,
    sizeBytes: bundle.sizeBytes,
    segments: bundle.summary.segments,
    tags: bundle.summary.tags,
    durationSec: bundle.summary.durationSec,
    dateMs: bundle.summary.firstEpochMs ?? bundle.importedAt,
    reviewState: 'UNREVIEWED',
    warnings: bundle.summary.warnings,
  };
}

function totalBytes(files: Map<string, Blob>): number {
  let total = 0;
  for (const blob of files.values()) total += blob.size;
  return total;
}

export class LibraryStore {
  constructor(
    private readonly persistence: Persistence,
    private readonly workerBridge: WorkerBridge,
  ) {}

  async listEntries(): Promise<LibraryEntry[]> {
    const bundles = await this.persistence.listBundles();
    // "Sort by last edited" (§7.8). Ticket 02 has no edits yet — every
    // journal is empty — so `importedAt` and "last edited" coincide; this
    // sort key should become the journal's `updatedAt` once ticket 05 makes
    // edits possible.
    return bundles.sort((a, b) => b.importedAt - a.importedAt).map(toEntry);
  }

  async importFromSource(source: BundleSource, onProgress?: ProgressCallback): Promise<ImportOutcome> {
    const { files, hash, sessionName } = await source.read();

    const existing = await this.persistence.getBundle(hash);
    if (existing) {
      return { entry: toEntry(existing), alreadyImported: true };
    }

    const session = await this.workerBridge.parseSession(files, onProgress);
    const importedAt = Date.now();
    const durationSec = session.sensor.n > 0 ? Number((session.sensor.tNs[session.sensor.n - 1] as bigint) - session.t0Ns) / 1e9 : 0;
    const bundle: StoredBundle = {
      hash,
      sessionName,
      files,
      importedAt,
      sizeBytes: totalBytes(files),
      summary: {
        segments: session.segments.length,
        tags: session.tags.length,
        warnings: session.warnings,
        durationSec,
        firstEpochMs: session.segments[0]?.epochMs ?? null,
      },
    };
    await this.persistence.saveImportedBundle(bundle, { hash, entries: [], cursor: 0, updatedAt: importedAt });

    return { entry: toEntry(bundle), alreadyImported: false };
  }

  async deleteEntry(hash: string): Promise<void> {
    await this.persistence.deleteBundle(hash);
  }
}
