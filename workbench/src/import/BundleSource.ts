// Plan §4.2 (seam: `BundleSource`), §6.2 (bundle discovery rules).
import JSZip from 'jszip';
import { ImportError } from './errors';

/** The only files this Workbench ever looks for. Anything else — a stray
 * `.nomedia`, a logcat dump, editor swap files — is silently ignored
 * (E-17), not warned about: it was never part of a session bundle to begin
 * with. */
const KNOWN_BASENAMES = ['sensor.csv', 'gps.csv', 'segments.csv', 'labels.csv', 'config.json', 'review.json'] as const;

/** Order the hash is computed over — matches `scripts/make-fixture.mjs` and
 * §6.4's "sha256 of concatenated pass-through files". `review.json` is
 * excluded: it's written *by* the Workbench, never part of what identifies
 * a recorded session. */
const PASSTHROUGH_ORDER = ['sensor.csv', 'gps.csv', 'segments.csv', 'labels.csv', 'config.json'] as const;

export interface BundleRead {
  files: Map<string, Blob>;
  hash: string;
  sessionName: string;
}

export interface BundleSource {
  read(): Promise<BundleRead>;
}

function basenameOf(pathOrName: string): string {
  const parts = pathOrName.split('/');
  return (parts[parts.length - 1] ?? pathOrName).toLowerCase();
}

function isIgnored(fullPath: string, basename: string): boolean {
  if (fullPath.includes('__MACOSX/')) return true;
  if (basename.startsWith('.')) return true;
  return !(KNOWN_BASENAMES as readonly string[]).includes(basename);
}

/** Shared by both adapters: takes whatever "list of paths with a way to get
 * their bytes" each source shape naturally provides, applies the ignore
 * filter (E-01, E-17), and detects ambiguous basenames. */
async function collect(entries: Array<{ fullPath: string; getBlob: () => Promise<Blob> }>): Promise<Map<string, Blob>> {
  const files = new Map<string, Blob>();
  const seenAt = new Map<string, string>();
  for (const entry of entries) {
    const basename = basenameOf(entry.fullPath);
    if (isIgnored(entry.fullPath, basename)) continue;
    if (files.has(basename)) {
      throw new ImportError('AMBIGUOUS_FILE', { basename, first: seenAt.get(basename), second: entry.fullPath });
    }
    files.set(basename, await entry.getBlob());
    seenAt.set(basename, entry.fullPath);
  }
  return files;
}

async function hashPassthroughFiles(files: Map<string, Blob>): Promise<string> {
  const buffers: ArrayBuffer[] = [];
  for (const name of PASSTHROUGH_ORDER) {
    const blob = files.get(name);
    if (blob) buffers.push(await blob.arrayBuffer());
  }
  let total = 0;
  for (const b of buffers) total += b.byteLength;
  const concatenated = new Uint8Array(total);
  let offset = 0;
  for (const b of buffers) {
    concatenated.set(new Uint8Array(b), offset);
    offset += b.byteLength;
  }
  const digest = await crypto.subtle.digest('SHA-256', concatenated);
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
}

function stripZipExtension(name: string): string {
  return name.replace(/\.zip$/i, '');
}

export class ZipBundleSource implements BundleSource {
  constructor(private readonly zipBlob: Blob, private readonly zipName: string) {}

  async read(): Promise<BundleRead> {
    const zip = await JSZip.loadAsync(this.zipBlob);
    const entries: Array<{ fullPath: string; getBlob: () => Promise<Blob> }> = [];
    zip.forEach((relativePath, entry) => {
      if (entry.dir) return;
      entries.push({ fullPath: relativePath, getBlob: () => entry.async('blob') });
    });
    const files = await collect(entries);
    const hash = await hashPassthroughFiles(files);
    return { files, hash, sessionName: stripZipExtension(this.zipName) };
  }
}

export class FilesBundleSource implements BundleSource {
  constructor(private readonly fileList: readonly File[]) {}

  async read(): Promise<BundleRead> {
    const entries = this.fileList.map((file) => ({
      // `webkitRelativePath` is set when the person picked a folder
      // (`webkitdirectory`); plain multi-file selection has no path prefix.
      fullPath: (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name,
      getBlob: () => Promise.resolve(file as Blob),
    }));
    const files = await collect(entries);
    const hash = await hashPassthroughFiles(files);

    let sessionName = `session_${Date.now()}`;
    const withPath = entries.find((e) => e.fullPath.includes('/'));
    if (withPath) {
      sessionName = withPath.fullPath.split('/')[0] ?? sessionName;
    }
    return { files, hash, sessionName };
  }
}
