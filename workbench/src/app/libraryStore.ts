import type { BundleSource } from '../import/BundleSource';
import type { WorkerBridge, ProgressCallback, DetectorCheckInput } from '../bridge/WorkerBridge';
import type { Persistence, StoredBundle, StoredDerived } from '../persistence/Persistence';
import type { ImportWarning } from '../worker/parse';
import type { DetectorCheckSummary } from '../worker/index';

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
  /** Ticket 03's "Detector check" Library-card line — `null` when no
   * `derived` row exists yet (a bundle imported before this ticket, per
   * §6.5's "evictable; rebuilt if absent"; this ticket computes it once at
   * import time and doesn't yet add a way to rebuild it later on demand —
   * flagged in the verification note). */
  detectorCheck: DetectorCheckSummary | null;
}

export interface ImportOutcome {
  entry: LibraryEntry;
  /** True when this hash already existed — AC7/E-15: no duplicate card,
   * caller shows "already imported — opened" instead of a normal success
   * toast. */
  alreadyImported: boolean;
}

function toEntry(bundle: StoredBundle, derived: StoredDerived | null): LibraryEntry {
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
    detectorCheck: derived?.detectorCheck ?? null,
  };
}

function totalBytes(files: Map<string, Blob>): number {
  let total = 0;
  for (const blob of files.values()) total += blob.size;
  return total;
}

let runIdCounter = 0;
/** A fresh id per detector-check call (E-40) — a plain incrementing
 * counter is enough here since `LibraryStore` only ever has one detector
 * check in flight per import, but the id itself is opaque to
 * `WorkerBridge`, so this can become a richer id later without touching
 * the bridge's contract. */
function nextRunId(): string {
  runIdCounter += 1;
  return `detector-check-${runIdCounter}`;
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
    const sorted = bundles.sort((a, b) => b.importedAt - a.importedAt);
    return Promise.all(
      sorted.map(async (bundle) => toEntry(bundle, await this.persistence.getDerived(bundle.hash))),
    );
  }

  async importFromSource(source: BundleSource, onProgress?: ProgressCallback): Promise<ImportOutcome> {
    const { files, hash, sessionName } = await source.read();

    const existing = await this.persistence.getBundle(hash);
    if (existing) {
      return { entry: toEntry(existing, await this.persistence.getDerived(hash)), alreadyImported: true };
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

    // Ticket 03: run the detector check (§4.4's `recompute` step +
    // calibrate/simulate/divergence) once, right after parse, while the
    // full `ParsedSession` is already in hand — and cache it (§6.5's
    // `derived` store) so future `listEntries()` calls don't re-run the
    // worker. A failure here (a corrupt/edge-case session the detector
    // can't calibrate against) shouldn't fail the whole import — the
    // bundle is already saved and usable; the Library card just shows no
    // detector-check line for it.
    let derived: StoredDerived | null = null;
    try {
      const input: DetectorCheckInput = {
        tNs: session.sensor.tNs,
        vertical: session.sensor.vertical,
        ax: session.sensor.ax,
        ay: session.sensor.ay,
        az: session.sensor.az,
        gz: session.sensor.gz,
        gyroCoverage: session.sensor.gyroCoverage,
        segments: session.segments,
        config: session.config,
      };
      const detectorCheck = await this.workerBridge.runDetectorCheck(input, nextRunId());
      derived = { hash, detectorCheck, computedAt: Date.now() };
      await this.persistence.saveDerived(derived);
    } catch {
      // Left as null; see comment above.
    }

    return { entry: toEntry(bundle, derived), alreadyImported: false };
  }

  async deleteEntry(hash: string): Promise<void> {
    await this.persistence.deleteBundle(hash);
  }
}
