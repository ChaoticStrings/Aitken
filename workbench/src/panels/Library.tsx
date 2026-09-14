import { useEffect, useRef, useState } from 'preact/hooks';
import { createPersistence } from '../persistence/createPersistence';
import { RealWorkerBridge } from '../bridge/RealWorkerBridge';
import { LibraryStore, type LibraryEntry } from '../app/libraryStore';
import { ZipBundleSource, FilesBundleSource } from '../import/BundleSource';
import type { BundleSource } from '../import/BundleSource';
import { ImportError } from '../import/errors';
import { pushToast } from '../app/Toast';

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB'];
  let value = bytes / 1024;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex++;
  }
  return `${value.toFixed(1)} ${units[unitIndex]}`;
}

function formatDuration(seconds: number): string {
  const mins = Math.floor(seconds / 60);
  const secs = Math.round(seconds % 60);
  return `${mins}:${secs.toString().padStart(2, '0')}`;
}

function formatDate(ms: number): string {
  return new Date(ms).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

interface Runtime {
  store: LibraryStore;
  usingMemoryFallback: boolean;
}

export function Library() {
  const [runtime, setRuntime] = useState<Runtime | null>(null);
  const [entries, setEntries] = useState<LibraryEntry[]>([]);
  const [storageLine, setStorageLine] = useState<string | null>(null);
  const [importing, setImporting] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const filesInputRef = useRef<HTMLInputElement>(null);
  const folderInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const { persistence, usingMemoryFallback } = await createPersistence();
      const store = new LibraryStore(persistence, new RealWorkerBridge());
      if (cancelled) return;
      setRuntime({ store, usingMemoryFallback });
      const list = await store.listEntries();
      if (cancelled) return;
      setEntries(list);
      const estimate = await persistence.estimateStorage();
      if (!cancelled && estimate) {
        setStorageLine(`${formatBytes(estimate.usageBytes)} used of ${formatBytes(estimate.quotaBytes)} available`);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  async function refresh(store: LibraryStore) {
    setEntries(await store.listEntries());
  }

  async function doImport(source: BundleSource) {
    if (!runtime) return;
    setImporting(true);
    try {
      const outcome = await runtime.store.importFromSource(source);
      if (outcome.alreadyImported) {
        pushToast(`"${outcome.entry.sessionName}" is already imported — opened`, 'info');
      } else {
        pushToast(`Imported "${outcome.entry.sessionName}"`, 'info');
      }
      await refresh(runtime.store);
    } catch (e) {
      if (e instanceof ImportError) {
        pushToast(e.message, 'error');
      } else {
        pushToast(`Import failed: ${e instanceof Error ? e.message : String(e)}`, 'error');
      }
    } finally {
      setImporting(false);
    }
  }

  function handleDrop(e: DragEvent) {
    e.preventDefault();
    setDragOver(false);
    const dropped = e.dataTransfer?.files;
    if (!dropped || dropped.length === 0) return;
    if (dropped.length === 1 && dropped[0] && /\.zip$/i.test(dropped[0].name)) {
      void doImport(new ZipBundleSource(dropped[0], dropped[0].name));
    } else {
      void doImport(new FilesBundleSource(Array.from(dropped)));
    }
  }

  async function handleDelete(entry: LibraryEntry) {
    if (!runtime) return;
    const ok = window.confirm(`Delete "${entry.sessionName}"? This can't be undone.`);
    if (!ok) return;
    await runtime.store.deleteEntry(entry.hash);
    await refresh(runtime.store);
  }

  return (
    <div class="library">
      <div class="library__toolbar">
        <button type="button" onClick={() => filesInputRef.current?.click()} disabled={importing}>
          Choose files…
        </button>
        <button type="button" onClick={() => folderInputRef.current?.click()} disabled={importing}>
          Choose folder…
        </button>
        {storageLine && <span class="library__storage">{storageLine}</span>}
      </div>

      {runtime?.usingMemoryFallback && (
        <div class="library__banner" role="alert">
          Private browsing (or storage is unavailable) — imports in this session won't survive a reload.
        </div>
      )}

      <input
        ref={filesInputRef}
        type="file"
        multiple
        accept=".csv,.json,.zip"
        style={{ display: 'none' }}
        onChange={(e) => {
          const list = (e.target as HTMLInputElement).files;
          if (list && list.length > 0) {
            const arr = Array.from(list);
            if (arr.length === 1 && arr[0] && /\.zip$/i.test(arr[0].name)) {
              void doImport(new ZipBundleSource(arr[0], arr[0].name));
            } else {
              void doImport(new FilesBundleSource(arr));
            }
          }
          (e.target as HTMLInputElement).value = '';
        }}
      />
      <input
        ref={folderInputRef}
        type="file"
        // @ts-expect-error -- webkitdirectory has no TS DOM typing but is
        // widely supported for folder selection (the whole reason this
        // second input exists instead of one). Must be an actual boolean
        // `true`, not `""` — Preact sets non-standard/vendor-prefixed props
        // like this directly as a DOM property rather than via
        // setAttribute, and an empty string is falsy, so `webkitdirectory=""`
        // silently never enables directory mode at all (found via an e2e
        // test failure: "File input does not support directories" even
        // though the attribute looked present in the JSX).
        webkitdirectory={true}
        style={{ display: 'none' }}
        onChange={(e) => {
          const list = (e.target as HTMLInputElement).files;
          if (list && list.length > 0) {
            void doImport(new FilesBundleSource(Array.from(list)));
          }
          (e.target as HTMLInputElement).value = '';
        }}
      />

      <div
        class={`library__dropzone${dragOver ? ' library__dropzone--active' : ''}`}
        onDragOver={(e) => {
          e.preventDefault();
          setDragOver(true);
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={handleDrop}
      >
        {importing ? (
          <p>Importing…</p>
        ) : entries.length === 0 ? (
          <div class="library__placeholder">
            <h2>No sessions yet</h2>
            <p>Drop a session .zip or folder here, or use the buttons above.</p>
          </div>
        ) : (
          <ul class="library__cards">
            {entries.map((entry) => (
              <li key={entry.hash} class="library-card">
                <div class="library-card__main">
                  <span class="library-card__name">{entry.sessionName}</span>
                  <span class="library-card__meta">
                    {formatDate(entry.dateMs)} · {formatDuration(entry.durationSec)} · {entry.segments} segments ·{' '}
                    {entry.tags} tags · {formatBytes(entry.sizeBytes)}
                  </span>
                  {entry.warnings.length > 0 && (
                    <span class="library-card__warnings">{entry.warnings.length} warning(s) on import</span>
                  )}
                </div>
                <span class={`library-card__badge library-card__badge--${entry.reviewState.toLowerCase()}`}>
                  {entry.reviewState}
                </span>
                <button type="button" class="library-card__delete" onClick={() => handleDelete(entry)}>
                  Delete
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
