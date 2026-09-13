# 17 — Motion polish, accessibility pass, keyboard cheat sheet

**What to build:** Every moment in Plan §8.7 has its specified motion; all
of it collapses to 0 ms under `prefers-reduced-motion`; focus order and
`aria` roles make the queue, inspector and dialogs usable by keyboard only;
`?` opens a cheat sheet listing §8.4; toasts pause on hover; light theme is
checked for contrast.

**Blocked by:** 14, 15

**Status:** ready-for-agent

**Plan sections:** §8.4, §8.6, §8.7, §11 E-51.

**Acceptance criteria**
- [ ] Each §8.7 row implemented with the named tokens (`--dur-*`, `--ease-*`); a CSS audit script (`scripts/audit-motion.mjs`) fails the build if any `transition`/`animation` in `src/` uses a literal duration instead of a token.
- [ ] `prefers-reduced-motion` → tokens 0 ms, store viewport interpolation skipped, Leaflet `flyTo` duration 0, sheet snaps instantly (E-51); e2e asserts no transition longer than 0 ms via `getComputedStyle`.
- [ ] Roles/labels: queue is `listbox` with `aria-activedescendant`; dialogs trap focus and restore it; toasts are `status`; inputs have labels; every icon button has an `aria-label`.
- [ ] Focus order: top bar → queue → waveform (focusable canvas wrapper with keyboard pan/zoom) → inspector; visible focus ring using `--accent`.
- [ ] `?` overlay lists §8.4 grouped by Review / Edit / View / Modes; closes on `Esc`.
- [ ] Contrast: all text ≥ 4.5:1 in both themes (automated axe-core pass in e2e; violations fail the test).
- [ ] Toast auto-dismiss 4 s pauses on hover/focus; stacked toasts limited to 3.
- [ ] Demo: full keyboard-only review of the fixture with the cheat sheet open once; light and dark screenshots attached to the verification note.

**Tests to write first:** e2e `reduced motion disables transitions`, axe-core pass, `cheat sheet opens with ? and closes with Esc`; unit test for the motion audit script.
