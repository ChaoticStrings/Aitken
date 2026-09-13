import { describe, expect, it } from 'vitest';

// Ticket 01 has no `persistence/IdbPersistence` yet (that's a later ticket,
// §9) — this proves the *harness* those tests will run in actually works:
// happy-dom provides a DOM-like global scope, and fake-indexeddb (installed
// in test/integration/setup.ts) provides a real, spec-compliant IndexedDB
// implementation without a browser. Plan §12 lists
// `app/store (with InlineWorkerBridge + MemoryPersistence) | integration`
// as this seam's real home once it exists.
describe('integration harness (happy-dom + fake-indexeddb)', () => {
  it('has a DOM global from happy-dom', () => {
    expect(typeof document).toBe('object');
    expect(typeof window).toBe('object');
  });

  it('round-trips a value through a real IndexedDB implementation', async () => {
    expect(typeof indexedDB).toBe('object');

    const dbName = 'ticket01-harness-check';
    const value = await new Promise<string>((resolve, reject) => {
      const openReq = indexedDB.open(dbName, 1);
      openReq.onupgradeneeded = () => {
        openReq.result.createObjectStore('store');
      };
      openReq.onsuccess = () => {
        const db = openReq.result;
        const tx = db.transaction('store', 'readwrite');
        tx.objectStore('store').put('hello', 'key');
        tx.oncomplete = () => {
          const readTx = db.transaction('store', 'readonly');
          const getReq = readTx.objectStore('store').get('key');
          getReq.onsuccess = () => resolve(getReq.result as string);
          getReq.onerror = () => reject(getReq.error);
        };
      };
      openReq.onerror = () => reject(openReq.error);
    });

    expect(value).toBe('hello');
  });
});
