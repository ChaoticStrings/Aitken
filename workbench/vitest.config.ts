import { defineConfig } from 'vitest/config';

// Plan §12: unit tests are pure-logic (node env is enough and faster);
// integration tests exercise the store against a DOM + IndexedDB, so they
// get happy-dom + fake-indexeddb. `test/e2e/**` is Playwright's territory
// (`npm run test:e2e`), not Vitest's — never included here, so `npm test`
// and `npm run test:e2e` never double-run the same file.
//
// Per-glob environments via `environmentMatchGlobs` don't exist in this
// installed Vitest (5.0.0) — confirmed by grepping node_modules, not
// assumed from memory. `test.projects` is the current mechanism for giving
// two globs two different environments in one config file.
export default defineConfig({
  test: {
    projects: [
      {
        test: {
          name: 'unit',
          environment: 'node',
          include: ['test/unit/**/*.test.ts'],
        },
      },
      {
        test: {
          name: 'integration',
          environment: 'happy-dom',
          setupFiles: ['./test/integration/setup.ts'],
          include: ['test/integration/**/*.test.ts'],
        },
      },
    ],
  },
});
