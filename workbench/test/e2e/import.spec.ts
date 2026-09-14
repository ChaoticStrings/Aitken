import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { test, expect } from '@playwright/test';
import { distIndexUrl } from './dist-url';

const here = path.dirname(fileURLToPath(import.meta.url));
const fixturesRoot = path.join(here, '..', 'fixtures');

// This is the only place `RealWorkerBridge` (the actual Worker +
// postMessage protocol, including the inline-Blob worker trick that keeps
// the build to one file) gets exercised — everything else tests
// `InlineWorkerBridge`, which runs the same `parseSession` but on the main
// thread. A bug specific to the postMessage boundary (a non-transferable
// value, a lost `requestId`, a Worker that fails to construct from the
// inlined Blob) would only show up here.

test('imports the real trimmed session via the file input and shows a card', async ({ page }) => {
  await page.goto(distIndexUrl);

  // Storage estimate line (AC6) — real IndexedDB usage/quota via
  // navigator.storage.estimate(), which only a real browser can answer.
  await expect(page.locator('.library__storage')).toContainText('used of');

  const filesInput = page.locator('input[type="file"]').first();
  await filesInput.setInputFiles(path.join(fixturesRoot, 'session_trimmed_90s.zip'));

  const card = page.locator('.library-card', { hasText: 'session_trimmed_90s' });
  await expect(card).toBeVisible({ timeout: 10000 });
  await expect(card).toContainText('8 segments');
  await expect(card).toContainText('3 tags');
  await expect(page.locator('.toast')).toContainText('Imported');
});

test('re-importing the same session opens the existing card instead of duplicating (E-15)', async ({ page }) => {
  await page.goto(distIndexUrl);
  const filesInput = page.locator('input[type="file"]').first();

  await filesInput.setInputFiles(path.join(fixturesRoot, 'session_trimmed_90s.zip'));
  await expect(page.locator('.library-card')).toHaveCount(1, { timeout: 10000 });

  await filesInput.setInputFiles(path.join(fixturesRoot, 'session_trimmed_90s.zip'));
  await expect(page.locator('.toast', { hasText: 'already imported' })).toBeVisible({ timeout: 10000 });
  await expect(page.locator('.library-card')).toHaveCount(1);
});

test('importing a synthetic fixture with missing gps.csv shows the warning on its card', async ({ page }) => {
  await page.goto(distIndexUrl);
  const folderInput = page.locator('input[type="file"]').nth(1);

  // A single directory path selects the whole folder for a webkitdirectory
  // input (Playwright's setInputFiles docs: "single directory path is
  // supported") — this is what actually exercises FilesBundleSource's
  // webkitRelativePath-derived session name, unlike passing individual file
  // paths (which wouldn't set webkitRelativePath at all).
  await folderInput.setInputFiles(path.join(fixturesRoot, 'synthetic', 'no-gps'));

  const card = page.locator('.library-card', { hasText: 'no-gps' });
  await expect(card).toBeVisible({ timeout: 10000 });
  await expect(card).toContainText('warning');
});

test('Delete removes a card after confirmation', async ({ page }) => {
  page.on('dialog', (dialog) => dialog.accept());
  await page.goto(distIndexUrl);
  const filesInput = page.locator('input[type="file"]').first();

  await filesInput.setInputFiles(path.join(fixturesRoot, 'synthetic', 'no-labels', 'sensor.csv'));
  await expect(page.locator('.library-card')).toHaveCount(1, { timeout: 10000 });

  await page.locator('.library-card__delete').click();
  await expect(page.locator('.library-card')).toHaveCount(0);
});
