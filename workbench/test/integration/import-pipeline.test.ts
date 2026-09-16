import { describe, expect, it } from 'vitest';
import JSZip from 'jszip';
import { LibraryStore } from '../../src/app/libraryStore';
import { InlineWorkerBridge } from '../../src/bridge/InlineWorkerBridge';
import { MemoryPersistence } from '../../src/persistence/MemoryPersistence';
import { IdbPersistence } from '../../src/persistence/IdbPersistence';
import { createPersistence } from '../../src/persistence/createPersistence';
import { ZipBundleSource, FilesBundleSource } from '../../src/import/BundleSource';
import { loadFixtureFiles, fixturesRoot } from '../helpers/fixtures';
import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';

function realFixtureAsFiles(): File[] {
  const dir = path.join(fixturesRoot, 'session_trimmed_90s');
  return readdirSync(dir, { withFileTypes: true })
    .filter((e) => e.isFile())
    .map((e) => new File([readFileSync(path.join(dir, e.name))], e.name));
}

async function realFixtureAsZip(): Promise<Blob> {
  const zip = new JSZip();
  const dir = path.join(fixturesRoot, 'session_trimmed_90s');
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isFile()) zip.file(entry.name, readFileSync(path.join(dir, entry.name)));
  }
  return zip.generateAsync({ type: 'blob' });
}

describe('LibraryStore (InlineWorkerBridge + MemoryPersistence)', () => {
  it('same hash imported twice yields one bundle', async () => {
    const store = new LibraryStore(new MemoryPersistence(), new InlineWorkerBridge());
    const files = realFixtureAsFiles();

    const first = await store.importFromSource(new FilesBundleSource(files));
    expect(first.alreadyImported).toBe(false);
    expect(first.entry.segments).toBe(8);
    expect(first.entry.tags).toBe(3);

    const second = await store.importFromSource(new FilesBundleSource(files));
    expect(second.alreadyImported).toBe(true);
    expect(second.entry.hash).toBe(first.entry.hash);

    const all = await store.listEntries();
    expect(all).toHaveLength(1);
  });

  it('ticket 03: runs and caches the detector check on import, and it survives a listEntries round-trip', async () => {
    const store = new LibraryStore(new MemoryPersistence(), new InlineWorkerBridge());
    const { entry } = await store.importFromSource(new FilesBundleSource(realFixtureAsFiles()));
    expect(entry.detectorCheck).toEqual({
      simulatedCount: 8,
      recordedCount: 8,
      divergentCount: 0,
      calibrationIncomplete: false,
    });
    // Not just returned from import — actually cached (§6.5 `derived`
    // store) and re-attached by `listEntries`, not recomputed from scratch.
    const [listed] = await store.listEntries();
    expect(listed?.detectorCheck).toEqual(entry.detectorCheck);
  });

  it('ticket 03: a session shorter than the calibration window shows calibrationIncomplete with zero simulated segments', async () => {
    const store = new LibraryStore(new MemoryPersistence(), new InlineWorkerBridge());
    const files = loadFixtureFiles('synthetic/no-gyro'); // ~2s of data; calibrationDurationMs is 10000
    const asFiles = Array.from(files, ([name, text]) => new File([text], name));
    const { entry } = await store.importFromSource(new FilesBundleSource(asFiles));
    expect(entry.detectorCheck).toEqual({
      simulatedCount: 0,
      recordedCount: 1, // this fixture's segments.csv has one recorded row
      // This synthetic fixture's accel columns and its one recorded
      // segments.csv row weren't crafted to be physically consistent with
      // each other (unlike the real trimmed-90s fixture) — recomputed peak
      // (~0.083) genuinely diverges from the recorded 1.5, which is
      // correct behaviour to demo here, not a bug.
      divergentCount: 1,
      calibrationIncomplete: true,
    });
  });

  it('zip and loose-folder import of the same session produce the same hash and one card (AC8 demo)', async () => {
    const store = new LibraryStore(new MemoryPersistence(), new InlineWorkerBridge());

    const zipBlob = await realFixtureAsZip();
    const zipOutcome = await store.importFromSource(new ZipBundleSource(zipBlob, 'session_trimmed_90s.zip'));
    const filesOutcome = await store.importFromSource(new FilesBundleSource(realFixtureAsFiles()));

    expect(zipOutcome.entry.hash).toBe(filesOutcome.entry.hash);
    expect(filesOutcome.alreadyImported).toBe(true);
    expect(await store.listEntries()).toHaveLength(1);
  });

  it('each synthetic fixture imports with its expected warnings on the card', async () => {
    const store = new LibraryStore(new MemoryPersistence(), new InlineWorkerBridge());
    const cases: Array<[string, string]> = [
      ['no-gyro', 'NO_GYRO'],
      ['no-gps', 'MISSING_GPS'],
      ['no-labels', 'MISSING_LABELS'],
      ['no-segments', 'MISSING_SEGMENTS'],
    ];
    for (const [dir, expectedCode] of cases) {
      const files = loadFixtureFiles(`synthetic/${dir}`);
      const asFiles = Array.from(files, ([name, text]) => new File([text], name));
      const outcome = await store.importFromSource(new FilesBundleSource(asFiles));
      expect(outcome.entry.warnings.map((w) => w.code)).toContain(expectedCode);
    }
  });

  it('every remaining synthetic fixture imports without throwing (gaps, unknown-label, range-problems, crlf-bom)', async () => {
    const store = new LibraryStore(new MemoryPersistence(), new InlineWorkerBridge());
    for (const dir of ['gaps', 'unknown-label', 'range-problems', 'crlf-bom']) {
      const files = loadFixtureFiles(`synthetic/${dir}`);
      const asFiles = Array.from(files, ([name, text]) => new File([text], name));
      const outcome = await store.importFromSource(new FilesBundleSource(asFiles));
      expect(outcome.alreadyImported).toBe(false);
    }
    // schema-v2 is the one fixture that's supposed to fail, not warn.
    const schemaV2Files = loadFixtureFiles('synthetic/schema-v2');
    const asFiles = Array.from(schemaV2Files, ([name, text]) => new File([text], name));
    await expect(store.importFromSource(new FilesBundleSource(asFiles))).rejects.toMatchObject({
      code: 'UNKNOWN_SCHEMA_VERSION',
    });
  });

  it('delete removes the entry', async () => {
    const store = new LibraryStore(new MemoryPersistence(), new InlineWorkerBridge());
    const { entry } = await store.importFromSource(new FilesBundleSource(realFixtureAsFiles()));
    expect(await store.listEntries()).toHaveLength(1);
    await store.deleteEntry(entry.hash);
    expect(await store.listEntries()).toHaveLength(0);
  });
});

describe('IdbPersistence (fake-indexeddb)', () => {
  // fake-indexeddb's `indexedDB` is one process-wide instance (unlike a real
  // browser, which isolates storage per test run) — each test opens its own
  // uniquely-named database so they can't see each other's data.
  let dbCounter = 0;
  const freshDbName = () => `test_db_${++dbCounter}`;

  it('stores a bundle and an empty journal in one transaction', async () => {
    const persistence = await IdbPersistence.open(freshDbName());
    const files = new Map([['sensor.csv', new Blob(['a'])]]);
    await persistence.saveImportedBundle(
      { hash: 'abc', sessionName: 'test', files, importedAt: 1, sizeBytes: 1, summary: { segments: 0, tags: 0, warnings: [], durationSec: 0, firstEpochMs: null } },
      { hash: 'abc', entries: [], cursor: 0, updatedAt: 1 },
    );
    const bundle = await persistence.getBundle('abc');
    const journal = await persistence.getJournal('abc');
    expect(bundle?.hash).toBe('abc');
    expect(journal).toEqual({ hash: 'abc', entries: [], cursor: 0, updatedAt: 1 });
  });

  it('listBundles returns everything saved', async () => {
    const persistence = await IdbPersistence.open(freshDbName());
    for (const hash of ['h1', 'h2']) {
      await persistence.saveImportedBundle(
        { hash, sessionName: hash, files: new Map(), importedAt: 1, sizeBytes: 0, summary: { segments: 0, tags: 0, warnings: [], durationSec: 0, firstEpochMs: null } },
        { hash, entries: [], cursor: 0, updatedAt: 1 },
      );
    }
    expect((await persistence.listBundles()).map((b) => b.hash).sort()).toEqual(['h1', 'h2']);
  });

  it('deleteBundle removes both the bundle and its journal', async () => {
    const persistence = await IdbPersistence.open(freshDbName());
    await persistence.saveImportedBundle(
      { hash: 'x', sessionName: 'x', files: new Map(), importedAt: 1, sizeBytes: 0, summary: { segments: 0, tags: 0, warnings: [], durationSec: 0, firstEpochMs: null } },
      { hash: 'x', entries: [], cursor: 0, updatedAt: 1 },
    );
    await persistence.deleteBundle('x');
    expect(await persistence.getBundle('x')).toBeNull();
    expect(await persistence.getJournal('x')).toBeNull();
  });

  it('ticket 03: saveDerived/getDerived round-trip, and deleteBundle also clears the derived row', async () => {
    const persistence = await IdbPersistence.open(freshDbName());
    const detectorCheck = { simulatedCount: 8, recordedCount: 8, divergentCount: 0, calibrationIncomplete: false };
    await persistence.saveDerived({ hash: 'd1', detectorCheck, computedAt: 123 });
    expect(await persistence.getDerived('d1')).toEqual({ hash: 'd1', detectorCheck, computedAt: 123 });
    expect(await persistence.getDerived('missing')).toBeNull();

    await persistence.saveImportedBundle(
      { hash: 'd1', sessionName: 'd1', files: new Map(), importedAt: 1, sizeBytes: 0, summary: { segments: 0, tags: 0, warnings: [], durationSec: 0, firstEpochMs: null } },
      { hash: 'd1', entries: [], cursor: 0, updatedAt: 1 },
    );
    await persistence.deleteBundle('d1');
    expect(await persistence.getDerived('d1')).toBeNull();
  });
});

describe('createPersistence', () => {
  it('uses IdbPersistence when IndexedDB is available', async () => {
    const { persistence, usingMemoryFallback } = await createPersistence();
    expect(usingMemoryFallback).toBe(false);
    expect(persistence).toBeInstanceOf(IdbPersistence);
  });
});
