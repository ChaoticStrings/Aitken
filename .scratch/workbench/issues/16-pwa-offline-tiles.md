# 16 — PWA: installable, offline app shell, map tile cache

**What to build:** The deployed build registers a service worker that
precaches the app shell and caches OSM tiles CacheFirst (2,000 entries / 30
days); the app is installable (manifest, icons); after one online load it
works fully offline, with the map degrading to cached/grey tiles. The
`file://` build path keeps working with no service worker.

**Blocked by:** 11, 13

**Status:** ready-for-agent

**Plan sections:** §1.3 D5, §7.9 (tiles), §9 (`sw.ts`, `vite-plugin-pwa`), §11 E-48.

**Acceptance criteria**
- [ ] `vite-plugin-pwa` configured: manifest (name, short name, theme colours for dark/light, maskable icons generated from an SVG in repo), `registerType: 'prompt'` with an in-app "Update available — reload" toast.
- [ ] Service worker: app shell precache; runtime route for `tile.openstreetmap.org` (and subdomains) `CacheFirst` with expiration plugin (2,000 / 30 d); everything else network-only.
- [ ] Offline tile fallback: a generated grey tile with an "offline" watermark returned when the network fails and no cache exists.
- [ ] `file://` open: SW registration skipped (guard on `location.protocol`), no errors in console (E-48).
- [ ] Two build outputs from one config: `dist/index.html` (single-file, no SW) and `dist-pwa/` (multi-file with SW) — both produced by `npm run build`; CI uploads both.
- [ ] Manual verification recorded in the note: install on Android Chrome, airplane mode, open, load a session from the Library, step the queue, export.
- [ ] e2e: with `context.setOffline(true)` after first load, the Library and a stored session still open.

**Tests to write first:** e2e offline test; unit test for the tile-fallback response generator.
