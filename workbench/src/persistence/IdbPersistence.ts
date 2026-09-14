import type { Persistence, StoredBundle, StoredJournal, StorageEstimate } from './Persistence';
import { ImportError } from '../import/errors';

const DB_NAME = 'aitken_workbench_v2';
// Bumped whenever a store is added — today just `bundles`/`journals`
// (ticket 02); `layouts`/`settings`/`derived` (§6.5) arrive with the
// tickets that actually read and write them.
const DB_VERSION = 1;
const BUNDLES_STORE = 'bundles';
const JOURNALS_STORE = 'journals';

function requestToPromise<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function openDb(dbName: string): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(dbName, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(BUNDLES_STORE)) {
        db.createObjectStore(BUNDLES_STORE, { keyPath: 'hash' });
      }
      if (!db.objectStoreNames.contains(JOURNALS_STORE)) {
        db.createObjectStore(JOURNALS_STORE, { keyPath: 'hash' });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export class IdbPersistence implements Persistence {
  private constructor(private readonly db: IDBDatabase) {}

  /** Opening (not the constructor) is where Safari private-browsing
   * actually throws (E-49) — callers (see `createPersistence.ts`) catch
   * this and fall back to `MemoryPersistence`. `dbName` defaults to the
   * real database name; tests pass a unique name per test so fake-indexeddb's
   * shared global `indexedDB` singleton doesn't leak data between them (a
   * real browser gets a fresh IndexedDB per test run automatically; the
   * fake one is a single process-wide instance, so tests have to ask for
   * isolation explicitly). */
  static async open(dbName: string = DB_NAME): Promise<IdbPersistence> {
    const db = await openDb(dbName);
    return new IdbPersistence(db);
  }

  saveImportedBundle(bundle: StoredBundle, journal: StoredJournal): Promise<void> {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction([BUNDLES_STORE, JOURNALS_STORE], 'readwrite');
      tx.objectStore(BUNDLES_STORE).put(bundle);
      tx.objectStore(JOURNALS_STORE).put(journal);

      const failWith = (domException: DOMException | null) => {
        if (domException?.name === 'QuotaExceededError') {
          reject(new ImportError('STORAGE_QUOTA_EXCEEDED'));
        } else {
          reject(domException ?? new Error('IndexedDB transaction failed'));
        }
      };
      tx.oncomplete = () => resolve();
      tx.onerror = () => failWith(tx.error);
      // Some engines abort a quota-exceeding transaction rather than
      // routing it through onerror (E-50) — covered either way.
      tx.onabort = () => failWith(tx.error);
    });
  }

  async getBundle(hash: string): Promise<StoredBundle | null> {
    const tx = this.db.transaction(BUNDLES_STORE, 'readonly');
    const result = await requestToPromise(tx.objectStore(BUNDLES_STORE).get(hash));
    return (result as StoredBundle | undefined) ?? null;
  }

  async listBundles(): Promise<StoredBundle[]> {
    const tx = this.db.transaction(BUNDLES_STORE, 'readonly');
    const result = await requestToPromise(tx.objectStore(BUNDLES_STORE).getAll());
    return result as StoredBundle[];
  }

  async getJournal(hash: string): Promise<StoredJournal | null> {
    const tx = this.db.transaction(JOURNALS_STORE, 'readonly');
    const result = await requestToPromise(tx.objectStore(JOURNALS_STORE).get(hash));
    return (result as StoredJournal | undefined) ?? null;
  }

  deleteBundle(hash: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction([BUNDLES_STORE, JOURNALS_STORE], 'readwrite');
      tx.objectStore(BUNDLES_STORE).delete(hash);
      tx.objectStore(JOURNALS_STORE).delete(hash);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  async estimateStorage(): Promise<StorageEstimate | null> {
    if (!navigator.storage?.estimate) return null;
    const { usage, quota } = await navigator.storage.estimate();
    if (usage === undefined || quota === undefined) return null;
    return { usageBytes: usage, quotaBytes: quota };
  }
}
