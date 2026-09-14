import type { Persistence, StoredBundle, StoredJournal, StorageEstimate } from './Persistence';

/** Test adapter (§4.2) and the runtime fallback when IndexedDB throws on
 * first use — Safari private browsing exposes `indexedDB` but throws from
 * `open()`, so feature-detection alone doesn't catch it (E-49). Nothing
 * persists across a reload; the Library shows a banner when this is in use
 * (wired in `panels/Library.tsx`). */
export class MemoryPersistence implements Persistence {
  private bundles = new Map<string, StoredBundle>();
  private journals = new Map<string, StoredJournal>();

  async saveImportedBundle(bundle: StoredBundle, journal: StoredJournal): Promise<void> {
    this.bundles.set(bundle.hash, bundle);
    this.journals.set(journal.hash, journal);
  }

  async getBundle(hash: string): Promise<StoredBundle | null> {
    return this.bundles.get(hash) ?? null;
  }

  async listBundles(): Promise<StoredBundle[]> {
    return Array.from(this.bundles.values());
  }

  async getJournal(hash: string): Promise<StoredJournal | null> {
    return this.journals.get(hash) ?? null;
  }

  async deleteBundle(hash: string): Promise<void> {
    this.bundles.delete(hash);
    this.journals.delete(hash);
  }

  async estimateStorage(): Promise<StorageEstimate | null> {
    return null; // there is no real storage to estimate
  }
}
