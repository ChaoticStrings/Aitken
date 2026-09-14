import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
export const fixturesRoot = path.join(here, '..', 'fixtures');

/** Reads every file directly inside a fixture folder as UTF-8 text, keyed by
 * basename — exactly the `Map<string, string>` shape `parseSession` takes.
 * Deliberately does NOT strip a BOM or normalise line endings here: the
 * `crlf-bom` fixture depends on those bytes surviving untouched so the
 * *parser*, not the test helper, is what's tested for tolerating them. */
export function loadFixtureFiles(relativeDir: string): Map<string, string> {
  const dir = path.join(fixturesRoot, relativeDir);
  const files = new Map<string, string>();
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isFile()) {
      files.set(entry.name, readFileSync(path.join(dir, entry.name), 'utf-8'));
    }
  }
  return files;
}
