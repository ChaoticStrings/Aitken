// Plan §6.5 lists five IndexedDB stores (`bundles`, `journals`, `layouts`,
// `settings`, `derived`), but ticket 02 only produces and consumes the
// first two — `layouts` (ticket 14), `settings` (whichever ticket first
// reads Settings, §6.6) and `derived` (ticket 03's recompute/LOD cache)
// don't have a reader or writer yet. Adding stores/methods for them now
// would be exactly rule 0.5's "no speculative abstraction"; they're added
// to this interface (and the IDB schema, via a version bump) when their
// ticket lands.
import type { ImportWarning } from '../worker/parse';

export interface StoredBundle {
  hash: string;
  sessionName: string;
  files: Map<string, Blob>;
  importedAt: number;
  sizeBytes: number;
  /** Cached at import time so the Library can render cards (§7.8) without
   * re-running the worker parse on every app launch just to count segments
   * and tags. Recomputed if the bundle is ever re-parsed (e.g. after a
   * schema-dispatch table update), but nothing in ticket 02 does that yet. */
  summary: {
    segments: number;
    tags: number;
    warnings: ImportWarning[];
    durationSec: number;
    /** From the first segment's `epoch_ms` (§7.8: "date ... from first
     * epoch_ms in segments"); null when there are no segments to read one
     * from (falls back to `importedAt` for display). */
    firstEpochMs: number | null;
  };
}

// `entries`/`cursor` mirror §5.11's `JournalEntry[]`/cursor shape, but
// `JournalEntry` itself isn't defined until `core/edits.ts` exists (ticket
// 05). A freshly-imported bundle's journal is always empty, so `unknown[]`
// costs nothing here and ticket 05 can tighten the type when it adds the
// first real entry.
export interface StoredJournal {
  hash: string;
  entries: unknown[];
  cursor: number;
  updatedAt: number;
}

export interface StorageEstimate {
  usageBytes: number;
  quotaBytes: number;
}

export interface Persistence {
  /** Writes `bundles[hash]` and an empty `journals[hash]` in one
   * transaction (AC5) — a bundle without a journal record, or vice versa,
   * should never be observable. */
  saveImportedBundle(bundle: StoredBundle, journal: StoredJournal): Promise<void>;
  getBundle(hash: string): Promise<StoredBundle | null>;
  listBundles(): Promise<StoredBundle[]>;
  getJournal(hash: string): Promise<StoredJournal | null>;
  /** Deletes both the bundle and its journal. */
  deleteBundle(hash: string): Promise<void>;
  estimateStorage(): Promise<StorageEstimate | null>; // null when the browser can't report it
}
