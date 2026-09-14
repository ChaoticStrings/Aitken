import type { Persistence } from './Persistence';
import { IdbPersistence } from './IdbPersistence';
import { MemoryPersistence } from './MemoryPersistence';

export interface PersistenceHandle {
  persistence: Persistence;
  /** True when IndexedDB was unavailable or threw on open (Safari private
   * browsing exposes `indexedDB` but throws from `open()` — a plain
   * `'indexedDB' in window` check doesn't catch this, E-49). The Library
   * shows a persistent "edits won't survive reload" banner when this is
   * true. */
  usingMemoryFallback: boolean;
}

export async function createPersistence(): Promise<PersistenceHandle> {
  if (typeof indexedDB === 'undefined') {
    return { persistence: new MemoryPersistence(), usingMemoryFallback: true };
  }
  try {
    const persistence = await IdbPersistence.open();
    return { persistence, usingMemoryFallback: false };
  } catch {
    return { persistence: new MemoryPersistence(), usingMemoryFallback: true };
  }
}
