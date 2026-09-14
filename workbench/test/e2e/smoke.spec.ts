import { existsSync } from 'node:fs';
import { test, expect } from '@playwright/test';
import { distIndexUrl } from './dist-url';

// Ticket 01's four required smoke tests (acceptance criteria + "tests to
// write first"), run against the *built* dist/index.html on both the
// `desktop` and `mobile` Playwright projects (playwright.config.ts) —
// satisfying "one desktop smoke test and one mobile smoke test" together.

test.beforeAll(() => {
  if (!existsSync(distIndexUrl.replace('file://', ''))) {
    throw new Error('dist/index.html not found — run `npm run build` before `npm run test:e2e`');
  }
});

test('build produces single html and no network requests on file:// open', async ({ page }) => {
  const requests: string[] = [];
  page.on('request', (req) => requests.push(req.url()));
  const pageErrors: string[] = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));

  await page.goto(distIndexUrl);
  // Regression guard: a script hoisted into <head> that runs before
  // <div id="app"> exists fails *silently* (a defensive `if (root)` check
  // swallows it) rather than throwing — so the real check is that the app
  // actually rendered, not just that nothing threw. (This is what caught
  // the ticket 01 blank-page bug in the first place.)
  await expect(page.locator('.top-bar__name')).toHaveText('Aitken Workbench');
  expect(pageErrors).toEqual([]);

  // The file:// document navigation itself is one "request" in Playwright's
  // eyes — expected, not a network request. A `blob:` request is also
  // expected now that RealWorkerBridge exists: constructing the inlined
  // worker via `URL.createObjectURL()` (the trick that keeps the build to
  // one file — see vite.config.ts) fires a "request" event for purely
  // in-memory bytes, with zero actual network or disk I/O. What "exactly
  // one file" actually rules out is anything that leaves the page: no
  // separately fetched CSS, font, or JS asset, and definitely nothing over
  // http(s) or a second file:// path.
  const unexpected = requests.filter((url) => url !== distIndexUrl && !url.startsWith('blob:') && !url.startsWith('data:'));
  expect(unexpected).toEqual([]);
  expect(requests.some((url) => url.startsWith('http'))).toBe(false);
});

test('light colour scheme applies (prefers-color-scheme)', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.goto(distIndexUrl);
  const darkBg = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--bg-0').trim());

  await page.emulateMedia({ colorScheme: 'light' });
  const lightBg = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--bg-0').trim());

  expect(lightBg).not.toBe(darkBg);
});

test('reduced motion zeroes durations', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  await page.goto(distIndexUrl);
  const normalDur = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--dur-2').trim());
  expect(normalDur).not.toBe('0ms');

  await page.emulateMedia({ reducedMotion: 'reduce' });
  const reducedDur1 = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--dur-1').trim());
  const reducedDur2 = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--dur-2').trim());
  const reducedDur3 = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--dur-3').trim());

  // Chromium's computed-style serializer canonicalises a zero time value to
  // "0s" regardless of what unit the source CSS used ("0ms" and "0s" are
  // the same duration) — so this checks the numeric value, not the literal
  // string "0ms" the source declares.
  expect(parseFloat(reducedDur1)).toBe(0);
  expect(parseFloat(reducedDur2)).toBe(0);
  expect(parseFloat(reducedDur3)).toBe(0);
});
