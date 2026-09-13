import { defineConfig } from 'vite';
import { viteSingleFile } from 'vite-plugin-singlefile';

// D1 (Plan §1.3): build to one self-contained dist/index.html via
// vite-plugin-singlefile. D5's service worker (installable PWA, offline map
// tiles) is deliberately NOT wired here: vite-plugin-pwa's `generateSW` mode
// emits its own dist/sw.js + workbox-*.js files even with an empty
// manifest, which would break ticket 01's "exactly one file" acceptance
// criterion for no functional benefit yet (there's no MapPanel to cache
// tiles for). It lands in the ticket that actually adds `sw.ts` (Plan §9),
// once there's real offline behaviour to justify the extra file(s). E-13:
// build.target stays ES2020 so `bigint` literals used for nanosecond
// timestamps aren't downleveled.
export default defineConfig({
  base: './',
  build: {
    target: 'es2020',
    // vite-plugin-singlefile already forces assets inline, but we're
    // explicit here since ticket 01's acceptance criterion is "exactly one
    // file" — no separate chunk, font, or worker asset on disk.
    assetsInlineLimit: 100 * 1024 * 1024,
    cssCodeSplit: false,
    reportCompressedSize: false,
  },
  worker: {
    format: 'es',
  },
  plugins: [viteSingleFile()],
});
