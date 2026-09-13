# Ticket 01 — Scaffold, tokens, fixture, CI — Verification

## What was built

- `workbench/` project: Vite 8.3.0 + TypeScript 7.0.2 (strict,
  `noUncheckedIndexedAccess`) + Preact 10.29.8 + `@preact/signals` 2.11.2 +
  Vitest 5.0.0 + `@playwright/test` 1.56.0. Every dependency version was
  checked against the live npm registry at implementation time (not
  recalled), per this project's ground-truth-over-documentation habit.
- `npm run build` produces exactly one file, `dist/index.html` (~411 KB):
  JS, CSS, and self-hosted Manrope/JetBrains Mono woff2 (via `@fontsource`)
  all inlined as data URIs by `vite-plugin-singlefile`. No `<script src>`,
  no external `url()`, no `http(s)://` reference anywhere in the built file
  (checked directly, not just asserted).
- Token-based dark/light theme (`src/styles/tokens.css`) following
  `prefers-color-scheme`; `prefers-reduced-motion: reduce` zeroes
  `--dur-1/2/3`.
- Minimal shell: `TopBar` + empty `Library` placeholder, mounted via Preact.
- `scripts/make-synthetic.mjs`: generates all 9 required synthetic
  micro-fixtures (no-gyro, no-gps, no-labels, no-segments, gaps,
  unknown-label, schema-v2, range-problems, crlf-bom), each documented
  in-script and regenerable from nothing (no external data needed).
- `scripts/make-fixture.mjs`: trims a real session bundle, offsets GPS by a
  fixed constant, copies `config.json` byte-for-byte, writes
  `expected/counts.json`, and zips the result.
- **Real fixture generated**: run against the real session bundle
  `session_20260905_140717` (uploaded directly by Vision rather than
  committed to GitHub, since it carries real GPS — the same reason this
  script exists). `test/fixtures/session_trimmed_90s/` and
  `session_trimmed_90s.zip` are committed; the raw source session is not,
  and never touches the repo.
- Vitest: 2 unit tests (harness sanity + a genuine bigint-precision check
  past `2^53` ns, E-13) + 2 integration tests (happy-dom DOM globals +
  a real fake-indexeddb round-trip) — 4/4 green.
- Playwright: 3 smoke tests × 2 projects (desktop 1440×900, mobile Pixel 7
  emulation) — 6/6 green, run against the actual built `dist/index.html`
  over `file://`, in a real Chromium (not simulated).
- `.github/workflows/workbench-ci.yml`: typecheck → test → build → install
  Playwright browsers → e2e, scoped to `workbench/**` changes, uploads
  `dist/index.html`. `.github/workflows/android-ci.yml` untouched.
- `Documentation/Core Docs/CONTEXT.md` gained the 8 §2 terms (Source
  Bundle, Journal, Edit, Review Item, Clean Bundle, Simulation, Channel,
  Layout, Panel — 9 terms total, Panel included), in the existing style
  with `_Avoid_` lines preserved where the plan specified them.

## Discrepancies from the plan, and why

1. **`vite-plugin-pwa` dropped from this ticket.** The plan's dependency
   list (§9) includes it, but wiring it up (even with an empty manifest)
   makes `vite-plugin-pwa`'s `generateSW` mode emit `dist/sw.js` and
   `dist/workbox-*.js` as separate files — directly breaking this ticket's
   own "produces exactly one file" acceptance criterion, for a service
   worker that has nothing to precache yet (no `MapPanel`, no offline
   tiles). Removed the plugin and the dependency; the ticket that adds
   `src/sw.ts` for real (offline map tiles, §9) should re-add it then. Flow
   discovered this by actually building and inspecting `dist/`, not by
   reading the plan — worth flagging since the plan doesn't call it out.

2. **`environmentMatchGlobs` doesn't exist in Vitest 5.0.0.** The obvious
   config (`environmentMatchGlobs: [['test/integration/**', 'happy-dom']]`)
   silently did nothing — `document`/`window` were `undefined` in the
   integration test even with the option set. Confirmed by grepping the
   installed package's type definitions (absent) rather than assuming a
   typo. Replaced with `test.projects`, the current mechanism for two
   globs → two environments in one config file. Test names split into
   `unit` and `integration` projects accordingly.

3. **`@playwright/test` pinned to 1.56.0, not the npm-registry-latest
   1.63.0.** This sandbox ships a pre-baked Chromium at revision 1194 with
   no network path to Playwright's browser-download CDN (outside the
   allowed domain list). 1.63.0 expects revision 1243 and fails to launch;
   1.56.0's `browsers.json` asks for exactly 1194. CI doesn't share this
   constraint (it runs `playwright install --with-deps chromium` fresh) —
   this pin is specifically about being able to verify e2e tests *in this
   environment*, not a permanent ceiling. Worth revisiting the pin once
   this is running somewhere with normal Playwright CDN access.

4. **Two of my own first-draft tests were wrong, caught by actually
   running them** (rule 0.2's "expected values ... never from running the
   code under test" is about not deriving expectations from the
   implementation — it doesn't protect against a hand-traced expectation
   that's simply arithmetic-wrong, which is what happened twice here):
   - The original bigint test used a real timestamp from the 140717
     fixture (~9.57×10¹³) as a "past `Number.MAX_SAFE_INTEGER`" example —
     it isn't; `MAX_SAFE_INTEGER` is ~9.007×10¹⁵. Real `timestamp_sensor_ns`
     values are nanoseconds since device boot, comfortably inside safe-
     integer range for a normal ride. Rewrote the test to actually
     construct a value past `2^53`, matching what E-13 is actually about
     (a session recorded after ~104+ days of phone uptime).
   - The "no network requests" e2e test asserted a literal `0` requests,
     but Playwright's own `page.on('request')` fires once for the `file://`
     document navigation itself. Fixed to assert zero requests *other than*
     the document itself, and separately that nothing is `http(s)://`.
   - Relatedly, Chromium's computed-style serializer normalises a zero
     `<time>` value to `"0s"` even when the source CSS wrote `0ms` — so the
     reduced-motion e2e test now parses the numeric value instead of
     string-matching `"0ms"`.

## Test names and counts

- Vitest — `unit` project (2): `runs a trivial assertion`,
  `supports bigint arithmetic past 2^53 ns (E-13: long-uptime session)`
- Vitest — `integration` project (2): `has a DOM global from happy-dom`,
  `round-trips a value through a real IndexedDB implementation`
- Playwright — `smoke.spec.ts` × 2 projects (desktop, mobile) = 6:
  `build produces single html and no network requests on file:// open`,
  `light colour scheme applies (prefers-color-scheme)`,
  `reduced motion zeroes durations`

All 10 green.

## How it was demoed

`npm run build && open dist/index.html` (verified via Playwright loading
the real built file over `file://`, not the dev server) — shows the dark
top bar ("Aitken Workbench") and the "No sessions yet" Library placeholder;
switching the OS color scheme flips the palette live; toggling
"reduce motion" in OS/devtools zeroes the transition durations.

## What's still open

- `expected/counts.json` for the real fixture currently only records the
  four pass/fail-relevant row counts (`sensor`, `segments`, `labels`,
  `gps`) written by `make-fixture.mjs` itself. Ticket 03
  (detector/DSP work, which needs `expected/calibration.json` and
  `expected/simulated.json` per Plan §12) will add to this file rather
  than replace it — flagging so ticket 03 doesn't assume it starts empty.
- The synthetic `crlf-bom` fixture only has `sensor.csv` + `config.json`
  (no gps/segments/labels) since the point of that fixture is exercising
  the parser's CRLF/BOM tolerance specifically, not every file type at
  once — narrower than the other 8 synthetic fixtures on purpose.

## Status

Status: done.
