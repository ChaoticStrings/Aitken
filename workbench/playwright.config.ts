import { defineConfig, devices } from '@playwright/test';

// Pinned to @playwright/test 1.56.0 (not the latest 1.63.0) because that's
// the version whose Chromium revision (1194) matches this environment's
// browser install; see the ticket 01 verification note for detail. CI
// should run `npx playwright install --with-deps chromium` itself rather
// than relying on a pre-baked cache, so it isn't tied to this constraint.
//
// Plan §9/§12: e2e runs against the built single-file app, opened straight
// off disk (`file://`) — matching how Vision actually uses it (D5) — not a
// dev server, so "no network request on file:// open" (ticket 01) is a real
// assertion about the shipped artifact, not the Vite dev server.
export default defineConfig({
  testDir: './test/e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
    {
      name: 'mobile',
      use: { ...devices['Pixel 7'] },
    },
  ],
});
