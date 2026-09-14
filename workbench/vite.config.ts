import { defineConfig, type Plugin } from 'vite';
import { viteSingleFile } from 'vite-plugin-singlefile';

// Vite's HTML pipeline needs the *source* index.html's script tag to say
// `type="module"` to recognise it as a JS entry point at all — that part
// can't be removed. This plugin strips `type="module"`/`crossorigin` from
// the *built* HTML only (`apply: 'build'`; dev still gets native ESM +
// HMR), after Vite has resolved the `src` attribute but before
// vite-plugin-singlefile inlines the script content. Combined with
// `output.format: 'iife'` below, the shipped script tag ends up a plain
// classic `<script>` with IIFE content inside — no ES-module semantics for
// any browser's file:// loader to get strict about. (This is the fix for
// the ticket 01 blank-page bug — folded into ticket 02 rather than shipped
// as its own patch, per Vision's call.)
function classicScriptForBuild(): Plugin {
  return {
    name: 'classic-script-for-build',
    apply: 'build',
    transformIndexHtml(html) {
      return html.replace('<script type="module" crossorigin', '<script');
    },
  };
}

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
    rollupOptions: {
      output: {
        // See classicScriptForBuild() above — no code-splitting reason
        // needs ESM output once everything is merged into one file anyway.
        format: 'iife',
      },
    },
  },
  worker: {
    // Module workers (the Vite default) have real gaps in Safari, and
    // Firefox lagged behind Chromium for years too — the same class of
    // problem that caused the ticket 01 blank-page bug, just for
    // `new Worker()` instead of `<script type="module">`. IIFE sidesteps it
    // the same way: the inlined worker Blob (`?worker&inline`, used in
    // bridge/RealWorkerBridge.ts) never needs `{ type: 'module' }` at
    // construction time.
    format: 'iife',
  },
  plugins: [viteSingleFile(), classicScriptForBuild()],
});
