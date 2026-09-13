# Workbench v2 — Implementation Plan

Labels: ready-for-agent
(Local-markdown tracker. One ticket file per slice lives in
`.scratch/workbench/issues/`. This document is the single source of truth the
implementing agent reads first; the ticket files are what it grabs one at a time.)

> **Read this before anything else if you are the implementing agent.**
> You are building a from-scratch replacement for a prototype
> (`aitken-workbench.html`, ~3,100 lines, not in git). You do **not** port that
> file. You build the modules described here, test-first, one ticket at a time,
> in a fresh context per ticket. §0 tells you how to work; §1–§9 tell you what
> to build; §10 is the ticket index; §11 is the edge-case register; §12 is the
> test-surface register. When a ticket and this document disagree, this document
> wins and you note the discrepancy in the ticket's verification note.

---

## 0. Operating rules for the implementing agent

These are non-negotiable constraints, distilled from the project's standing
methodology (`map.md`: "TDD is mandatory for all implementation") and the
Karpathy guidelines (think before coding, simplicity first, surgical changes,
goal-driven execution).

0.1 **One ticket per context window.** Open the ticket file, read the sections of
this plan it points at, implement, verify, write a verification note, commit.
Do not start the next ticket in the same context.

0.2 **Red before green.** Every acceptance criterion maps to at least one test
named in the ticket. Write the failing test first. Expected values in tests come
from an independent source (hand-traced arithmetic, a literal from the real
fixture, the Kotlin source) — never from running the code under test.

0.3 **Test only at the seams listed in §12.** If you feel the need to test past a
seam (reach into a module's internals), stop: the module is the wrong shape.
Re-read §4 and fix the shape, don't add the test.

0.4 **Vocabulary is fixed.** Use `CONTEXT.md` terms exactly: *Session*, *Segment*,
*Tag* (never "Label" as a concept — `labels.csv` is only a filename), *M*, *D*,
*Review Item*, *Journal*, *Edit*, *Bundle*. New terms introduced by this plan are
in §2 and must be added to `CONTEXT.md` in ticket 01.

0.5 **No speculative abstraction.** A seam exists only where §4 says it exists,
because each of those has two real adapters. Do not add interfaces, plugin
systems, or config knobs that are not in this plan.

0.6 **Types are the contract.** Every module's interface is defined as
TypeScript types in §5. Implement those signatures exactly. If a signature is
wrong, change it in this document first (one-line note in the ticket), then in
code.

0.7 **The canvas is not animated by JS timers.** All motion is CSS transitions /
Web Animations API on DOM elements. The waveform canvas redraws on demand
(`requestAnimationFrame` coalesced), never on a timer.

0.8 **Never rewrite raw data.** `sensor.csv`, `gps.csv`, `config.json` are passed
through byte-for-byte on export. Only `segments.csv`, `labels.csv`, and the new
`review.json` are produced by the Workbench.

0.9 **Every ticket ends demoable.** Run `npm run build`, open `dist/index.html`,
load the fixture, and confirm the ticket's behaviour by hand before writing the
verification note. A ticket whose slice cannot be exercised in the built app is
not done.

0.10 **Verification note per ticket** at
`Documentation/Progress Docs/WORKBENCH_TICKET_NN_VERIFICATION.md`: what was
built, test names + counts, how it was demoed, any plan discrepancies.

---

## 1. Problem, solution, decisions

### 1.1 Problem statement

Vision reviews recorded ride sessions (34 min, ~200k sensor rows, ~300 detected
segments, ~50 tags) to produce a *clean* bundle for the Colab notebook. The
prototype makes this possible but exhausting: every decision is a
click-inspect-edit-click cycle, nothing tells you what still needs a decision,
boundary edits silently compute the wrong magnitude (gravity-baseline bug),
detector what-if simulations can only be looked at, not adopted, export quietly
re-serialises raw data, there is no touch support, the layout is fixed, and it
is a single 3,100-line file with no tests.

### 1.2 Solution

A from-scratch Workbench v2: an installable, offline-capable single-file web app
with a dockable/movable/resizable panel layout on desktop and a focus-mode
bottom-sheet layout on phones. Its centre of gravity is the **Review Queue** — an
automatically built, prioritised list of every item that needs a human decision,
driven by keyboard on desktop and by swipe on mobile. Edits are an event-sourced
**Journal** over an immutable source bundle, so undo/redo, autosave, and
provenance are correct by construction. Detector simulations produce a
**diff** against recorded segments and can be adopted wholesale or per-item.
Export passes raw files through untouched and writes a `review.json` sidecar.

### 1.3 Ratified decisions (grilling round 1, all recommendations accepted)

| # | Decision |
|---|---|
| D1 | **Stack**: Vite + TypeScript (strict) + Vitest + Playwright; built to a single self-contained `dist/index.html` via `vite-plugin-singlefile`. Leaflet and JSZip bundled (no CDN at runtime). |
| D2 | **UI**: Preact + `@preact/signals` for DOM panels; imperative Canvas2D for waveform and minimap. |
| D3 | **Layout**: `dockview-core` behind our own `LayoutHost` seam. Second adapter = the mobile `SheetLayoutHost`. |
| D4 | **Mobile (< 760 px)**: Focus mode — waveform full-bleed, draggable bottom sheet with tabs (Queue, Inspector, Map, Tree), collapsed top bar. Full editing. Shared interaction state machine; only the pointer adapter differs. |
| D5 | **Delivery**: `dist/index.html` usable from a file:// URL **and** deployed as a PWA with a service worker (app shell precached, map tiles cached opportunistically). |
| D6 | **Ingestion**: parse + DSP + detector simulation in a Web Worker; typed arrays transferred; min/max LOD pyramid for O(pixels) rendering. Accepts a `.zip` **or** loose files / a folder. |
| D7 | **Repetition-killers in v1**: Review Queue (7a), Smart Filter without saved presets (7b), Simulation Diff + Adopt (7c), Snap-assist / auto-tighten / split / merge (7d), Session Sanity Report (7e). **Deferred**: learned review rules (7f — that is Aitken-maturity's job). |
| D8 | **Data semantics**: recomputed magnitude = `abs(vertical − 9.81)`; on load trust `segments.csv` but flag > 10 % peak divergence; raw files pass through byte-for-byte; export adds `review.json`. |
| D9 | **Persistence/undo**: event-sourced Journal over an immutable Source Bundle. `state = reduce(source, journal[0..cursor])`. |
| D10 | **Experimental metrics** (ISO-2631-style channels) kept as a late slice behind the `ChannelProvider` seam; Roughness modal dropped. |
| D11 | **Label vocabulary** configurable in settings, seeded with `Pothole / Bump / Speedbreaker / Rough stretch`; unknown labels warned on load. |
| D12 | **Library** screen: imported sessions as cards with review progress; no cross-session analytics. |
| D13 | **Fixtures**: trimmed real session (first 90 s of 140717, GPS translated by a constant offset) + synthetic micro-fixtures. |
| D14 | **Location**: `workbench/` in this repo, own `package.json`, own CI job. Tickets in `.scratch/workbench/issues/`. |
| D15 | **Visual language**: token-based dark + light via `prefers-color-scheme`; `prefers-reduced-motion` honoured; Manrope + JetBrains Mono; one shared motion spec. |
| D16 | **Plan artifact**: this file + one ticket file per slice. |

### 1.4 Decisions settled in round 2 (agent-recommended, user delegated)

| # | Decision |
|---|---|
| D17 | **Detector simulation must reproduce the Kotlin pipeline bit-for-bit in count and within 1e-3 in threshold**, locked by a test against `config.json`'s calibrated thresholds from the fixture (`calibratedShortStdThreshold: 3.64617`, `calibratedLongStdThreshold: 5.0351295` in the full session; the trimmed fixture's values are computed once in ticket 03 and frozen as literals). |
| D18 | **Review Queue ordering** (§7.2): blocking data problems first, then by M descending, then by time. User can switch to "by time". |
| D19 | **Keyboard map** and **gesture map** fixed in §8.4/§8.5. |
| D20 | **Segment identity** is a stable `segId` string derived from `start_ns` of the *source* row (`"s:<start_ns>"`) for recorded segments and `"n:<ulid>"` for Workbench-created ones. Tags reference segments by `segId`, never by array index or start time. |
| D21 | **One session open at a time.** The Library lists many; the Workspace edits one. |
| D22 | **`review.json` schema v1** fixed in §6.4. Re-import of a Workbench-exported bundle restores Journal and review state. |

---

## 2. Domain model additions (append to `CONTEXT.md` in ticket 01)

**Source Bundle**:
The immutable set of files a session was imported from (`sensor.csv`, `gps.csv`,
`segments.csv`, `labels.csv`, optional `config.json`, optional `review.json`),
identified by a content hash. The Workbench never mutates it.

**Journal**:
The append-only, ordered list of Edits applied to a Source Bundle. Current
session state is always `reduce(source, journal[0..cursor])`. Undo moves the
cursor back; redo moves it forward; a new Edit after undo truncates the tail.

**Edit**:
One atomic, serialisable change to segments or tags (`SegmentMove`,
`TagRelabel`, `AdoptSimulation`, …). Has a `kind`, a `payload`, a wall-clock
`ts`, and an `origin` (`manual | queue | bulk | adopt | tighten`).
_Avoid_: "action", "command", "operation".

**Review Item**:
One thing the Queue thinks needs a human decision: an untagged high-M segment,
an unmatched tag, an inconsistent range pair, a segment whose recomputed peak
disagrees with the recorded one, etc. Has a `kind`, a `severity`, a `target`
(segId or tagId), and a `resolution` (`open | confirmed | dismissed`).
Confirming/dismissing is itself an Edit (`ReviewResolve`), so it is undoable and
survives reload.

**Clean Bundle**:
The export: pass-through raw files + Workbench-written `segments.csv`,
`labels.csv`, `review.json`. Distinct from a Source Bundle by the presence of
`review.json`.

**Simulation**:
A non-destructive re-run of the detector over the session's vertical signal
with user-chosen Tunables. Produces candidate segments and a **Simulation Diff**
(added / removed / moved / unchanged vs. current segments). Never touches state
until adopted via an `AdoptSimulation` Edit.

**Channel**:
One plottable time series over the sensor timebase (vertical, jerk, accel X,
recomputed vertical, windowed RMS, …). Provided by a `ChannelProvider`.

**Layout**:
The serialised arrangement of Panels (which panel is where, sizes, tab groups,
floating). Distinct from session state; persisted per device.

**Panel**:
One dockable UI region with a stable `PanelId`: `waveform`, `minimap`, `queue`,
`inspector`, `map`, `tree`, `tunables`, `report`.

---

## 3. User stories

Actors: **Vision** (the reviewer, on desktop), **Vision-mobile** (same person,
on a phone with poor connectivity), **Colab** (the downstream consumer).

**Ingest**
1. As Vision, I want to drop a session `.zip` or a loose folder onto the app, so I can review a session from the SAF backup folder without zipping it first.
2. As Vision, I want a 15 MB session to load without the UI freezing, so I can start reading the sanity report while the waveform is still preparing.
3. As Vision, I want every imported session to appear in a Library with its review progress, so I can resume where I left off days later.
4. As Vision, I want to re-import a Clean Bundle I exported earlier and get my review state back, so an export is never a dead end.
5. As Vision, I want a session with a missing `gps.csv` / `labels.csv` / `config.json` / gyro columns to still load with a clear warning per missing thing, so an imperfect recording is still reviewable.
6. As Vision, I want a bundle whose files carry a `schema_version` this Workbench does not know to be refused with a precise message, so I never review misparsed data.

**Orient**
7. As Vision, I want a one-card Session Sanity Report on open (gaps, gyro coverage, GPS coverage, tap-offset distribution, recorded-vs-recomputed peak divergence, label histogram, queue size), so I know within ten seconds whether this session is worth reviewing.
8. As Vision, I want to see the vertical signal with segments and tags overlaid and a minimap of the whole session, so I can see detector behaviour at a glance.
9. As Vision, I want the GPS track on a map with segments as coloured markers and a cursor that follows the waveform, so I can connect what happened to where.
10. As Vision, I want to switch the plotted channel (vertical, jerk, rolling std, raw axes, gyro, recomputed variants), so I can sanity-check the on-device DSP against a recompute.

**Decide (the Queue)**
11. As Vision, I want an ordered Review Queue of everything that needs a decision, so I never hunt for the next problem.
12. As Vision, I want the Queue to auto-focus each item (zoom waveform, fly map, populate inspector) as I step through it, so I decide, not navigate.
13. As Vision, I want single-key actions (assign label 1–9, confirm, dismiss, reject/delete, next/prev, undo), so a 60-item queue takes minutes.
14. As Vision, I want a progress indicator ("17 / 63, ~4 min left at your pace"), so I know when I am done.
15. As Vision, I want dismissed items to stay dismissed after reload and to be undoable, so the queue is a real work state, not a view.
16. As Vision, I want unmatched tags to be offered their most likely segment (lookback matching mirroring `TagMatcher`) with one-key accept, so reconciliation is a glance.
17. As Vision, I want inconsistent range pairs (`RANGE_START` without `RANGE_END`, overlapping ranges, end before start) surfaced as queue items with fix suggestions, so the hardening-pass fix #4 that was skipped finally exists.
18. As Vision, I want segments whose recorded peak disagrees with the recomputed `abs(v − 9.81)` peak by > 10 % surfaced, so pre-fix sessions are visibly suspect.

**Edit**
19. As Vision, I want to drag a segment boundary on the waveform and have it snap to the nearest threshold crossing (hold a modifier to disable), so boundaries are physically meaningful without pixel-hunting.
20. As Vision, I want "auto-tighten" for one or all segments (shrink to where the signal actually exceeds the calibrated threshold), so I stop hand-trimming hysteresis tails.
21. As Vision, I want to split a segment at the cursor and merge adjacent segments, so a mis-merged rough stretch is two clicks to fix.
22. As Vision, I want boundary edits to recompute peak/RMS with `abs(vertical − 9.81)` over signal-true samples, so the Workbench never reintroduces the gravity bug.
23. As Vision, I want to add point tags and range tags, move them, relabel them, delete them, and re-match them to segments, so every tag correction is possible.
24. As Vision, I want to draw a new segment where the detector missed one, so false negatives are correctable.
25. As Vision, I want unlimited undo/redo that survives reload, so I can be aggressive.
26. As Vision, I want a Smart Filter (`M ≥ 4 AND untagged AND D < 2 s`) that selects matching items for bulk relabel / delete / confirm, so a pattern is one action, not thirty.
27. As Vision, I want to shift-drag a time range on the waveform to select everything in it, so ad-hoc bulk selection is spatial.

**Simulate**
28. As Vision, I want to adjust detector Tunables from this session's `config.json` and see simulated segments as an overlay, so I can explore detector behaviour without touching data.
29. As Vision, I want a Simulation Diff (added / removed / moved / unchanged) as a list and as waveform markers, so I see exactly what a Tunable change does.
30. As Vision, I want "adopt all", "adopt selected", and "adopt only additions" with tags automatically re-matched and any fallout listed, so a better detector run becomes the reviewed truth in one step.
31. As Vision, I want the simulation to reproduce the on-device pipeline exactly (same calibration window, same thresholds), so the overlay is trustworthy.

**Export**
32. As Vision, I want to export a Clean Bundle where raw files are byte-identical to the source and `segments.csv` / `labels.csv` / `review.json` reflect my review, so Colab gets provenance it can trust.
33. As Vision, I want the export to refuse (with a list) if blocking queue items are still open, and to warn (not refuse) if non-blocking ones are, so a clean bundle is actually clean.
34. As Vision, I want a post-export self-check that re-reads the zip and compares hashes and parsed rows, so a corrupt export never reaches Colab silently.

**Layout & platform**
35. As Vision, I want to drag any panel to any edge, into a tab group, or float it, and resize it, with the arrangement remembered per device, so the tool fits my screen and habits.
36. As Vision, I want "reset layout" and two presets (Review, Inspect), so I can recover from a mess.
37. As Vision-mobile, I want the waveform full-screen with a bottom sheet for Queue / Inspector / Map, and pinch-zoom / drag-pan / long-press-grab gestures, so I can clear a queue on the bus.
38. As Vision-mobile, I want the app installable and working with no signal (map tiles degrade to cached or blank), so a dead zone is not a blocker.
39. As Vision, I want light mode outdoors and dark mode indoors following the OS, with animations disabled when the OS asks, so it is pleasant everywhere.
40. As Vision, I want every animation to be short and purposeful (panel dock, sheet snap, selection ring, queue advance), never decorative, so the tool feels fast.

**Colab (indirect)**
41. As Colab, I want `review.json` to tell me the Workbench version, source hash, review state, and which segments were human-confirmed vs. auto, so I can weight training data.

---

## 4. Architecture

### 4.1 Module map (deep modules, few seams)

```
┌──────────────────────────────────────────────────────────────────────────┐
│ app/  (Preact shell, routing: Library ⇄ Workspace)                       │
├──────────────┬───────────────────────────┬───────────────────────────────┤
│ LayoutHost   │ Panels (Preact)           │ Canvas views (imperative)     │
│ seam ────────┤ Queue, Inspector, Tree,   │ WaveformView, MinimapView     │
│ Dockview     │ Tunables, Report, Library │ (draw from ViewportState +    │
│ SheetLayout  │ MapPanel (Leaflet)        │  Channel LOD + SessionState)  │
├──────────────┴───────────────────────────┴───────────────────────────────┤
│ Interaction  (pointer state machine; PointerAdapter seam: Mouse, Touch)  │
├──────────────────────────────────────────────────────────────────────────┤
│ SessionStore (signals): source, journal, cursor, derived SessionState,  │
│              selection, viewport, queue, simulation                      │
├──────────────────────────┬───────────────────────────────────────────────┤
│ core/ (pure, no DOM)     │ worker/ (Web Worker, pure, no DOM)            │
│  reducer  (Edit → state) │  parse   (CSV → typed arrays)                 │
│  queue    (state → items)│  dsp     (vertical/jerk/rollstd recompute)    │
│  matcher  (TagMatcher JS)│  detector(calibration + simulate, Kotlin-exact)│
│  tighten/split/merge     │  lod     (min/max pyramid)                    │
│  diff     (sim vs state) │  channels(metric channels, late slice)        │
│  filter   (SmartFilter)  │  export  (CSV writers, zip assembly, verify)  │
│  format   (time, units)  │                                               │
├──────────────────────────┴───────────────────────────────────────────────┤
│ persistence/  IndexedDB: bundles (blobs), journals, layouts, settings    │
└──────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Seams (each has exactly two real adapters — the two-adapter test)

| Seam | Adapter A | Adapter B | Why it varies |
|---|---|---|---|
| `LayoutHost` | `DockviewLayoutHost` (desktop) | `SheetLayoutHost` (mobile) | Screen class |
| `PointerAdapter` | `MousePointerAdapter` | `TouchPointerAdapter` | Input device |
| `BundleSource` | `ZipBundleSource` | `FilesBundleSource` (loose files / folder) | Import shape |
| `ChannelProvider` | `RecordedChannels` (from CSV columns) | `RecomputedChannels` (worker DSP) — later `MetricChannels` | Where the series comes from |
| `WorkerBridge` | `RealWorkerBridge` (postMessage) | `InlineWorkerBridge` (same-thread, for tests) | Test vs. runtime |
| `Persistence` | `IdbPersistence` | `MemoryPersistence` (tests) | Test vs. runtime |

Everything else is a module with one implementation and **no** interface
indirection.

### 4.3 The deepest module: `reducer`

```
reduce(state: SessionState, edit: Edit): SessionState   // pure, total
```

Every user-visible change to segments, tags, or review resolutions is an `Edit`
that goes through `reduce`. UI code never mutates `SessionState`. The Queue,
Diff, Tighten, Match, and Filter modules *produce* Edits or read state; they
never write. This gives: undo/redo for free (cursor), autosave for free (append
one record), provenance for free (`review.json` = journal summary), and a test
surface where every behaviour is `given state + edit → expect state`.

`SessionState` is immutable; `reduce` returns a new object sharing unchanged
arrays structurally. Segments are kept sorted by `startNs`; `reduce`
re-sorts after any edit that moves a start.

### 4.4 Data flow on import

```
BundleSource.read() ─► {files: Map<name, Blob>, hash}
   ─► worker.parse(files)  ─► ParsedSession (typed arrays, per-file schema dispatch)
   ─► worker.recompute(vertical) ─► RecomputedChannels + divergence stats
   ─► worker.lod(channels) ─► LOD pyramids
   ─► main: SourceBundle stored (IDB) ; SessionState = reduce(initial, journal=[])
   ─► queue.build(state, config, settings) ─► ReviewItem[]
   ─► report.build(parsed, state, divergence) ─► SanityReport
   ─► LayoutHost mounts panels; WaveformView draws.
```

Progress events (`{phase, fraction}`) stream from the worker; the Report panel
renders as soon as `parse` completes, before `lod` finishes.

### 4.5 Time base

All internal times are **`bigint` nanoseconds on the sensor clock**
(`timestamp_sensor_ns`), exactly as recorded. Conversion to seconds happens
only in `format` and in canvas mapping, never in state. Rationale: JS `number`
loses integer precision above 2^53 ≈ 9.0e15 ns; the fixture's timestamps are
~9.6e13 ns (safe today) but device uptime clocks can exceed 2^53 after ~104 days,
and round-tripping `Math.round(sec*1e9 + t0)` (the prototype's approach) already
introduces off-by-one ns drift. Typed arrays for plotting use `Float64Array` of
seconds-relative-to-t0 (derived, cached) because canvas math needs floats.

---

## 5. Module interfaces (TypeScript — implement exactly)

### 5.1 `core/types.ts`

```ts
export type Ns = bigint;                       // sensor-clock nanoseconds
export type SegId = `s:${string}` | `n:${string}`;
export type TagId = `t:${string}` | `n:${string}`;
export type TagKind = 'POINT' | 'RANGE_START' | 'RANGE_END';

export interface Segment {
  id: SegId;
  startNs: Ns;
  durationNs: Ns;
  peakM: number;          // abs(vertical - 9.81) peak over signal-true samples
  rmsM: number;
  speedMps: number | null;
  epochMs: number;
  provenance: 'recorded' | 'created' | 'adopted' | 'split' | 'merged';
  edited: boolean;        // any boundary change since source
}

export interface Tag {
  id: TagId;
  tNs: Ns;                // tap time
  kind: TagKind;
  label: string;
  segmentId: SegId | null;
  tapOffsetMs: number | null;
  epochMs: number;
  provenance: 'recorded' | 'created';
  edited: boolean;
}

export type ReviewItemKind =
  | 'untagged-high-m'          // segment with peakM >= settings.queue.minPeakM and no POINT tag
  | 'unmatched-tag'            // tag.segmentId == null
  | 'tag-link-disagrees'       // tag.segmentId != matcher.suggest(tag)
  | 'range-unpaired'           // RANGE_START without RANGE_END or vice versa
  | 'range-inverted'           // RANGE_END before its RANGE_START
  | 'range-overlap'            // two ranges overlap in time
  | 'peak-divergence'          // recorded peak vs recomputed differ > 10%
  | 'long-segment'             // durationNs >= config.longSegmentWarningMs
  | 'edited-unconfirmed'       // segment.edited && no ReviewResolve since
  | 'unknown-label';           // tag.label not in settings.labels

export type Blocking = 'blocking' | 'advisory';

export interface ReviewItem {
  id: string;                  // `${kind}:${targetId}` — stable across rebuilds
  kind: ReviewItemKind;
  blocking: Blocking;
  target: { type: 'segment'; id: SegId } | { type: 'tag'; id: TagId } | { type: 'range'; startId: TagId; endId: TagId | null };
  focusNs: [Ns, Ns];           // time window the UI should show
  suggestion?: Edit;           // one-key accept applies this Edit
  score: number;               // for ordering; see §7.2
}

export type Resolution = 'confirmed' | 'dismissed';

export interface SessionState {
  segments: readonly Segment[];       // sorted by startNs
  tags: readonly Tag[];               // sorted by tNs
  resolutions: Readonly<Record<string /*ReviewItem.id*/, Resolution>>;
  reviewState: 'UNREVIEWED' | 'IN_REVIEW' | 'REVIEWED' | 'EXPORTED';
}
```

### 5.2 `core/edits.ts`

```ts
export type Edit =
  | { kind: 'SegmentMove';      segId: SegId; startNs: Ns; durationNs: Ns; stats: {peakM:number; rmsM:number} }
  | { kind: 'SegmentCreate';    segment: Segment }
  | { kind: 'SegmentDelete';    segId: SegId }
  | { kind: 'SegmentSplit';     segId: SegId; atNs: Ns; left: Segment; right: Segment }
  | { kind: 'SegmentMerge';     segIds: [SegId, SegId]; merged: Segment }
  | { kind: 'TagCreate';        tag: Tag }
  | { kind: 'TagDelete';        tagId: TagId }
  | { kind: 'TagMove';          tagId: TagId; tNs: Ns }
  | { kind: 'TagRelabel';       tagIds: TagId[]; label: string }
  | { kind: 'TagRematch';       tagId: TagId; segmentId: SegId | null; tapOffsetMs: number | null }
  | { kind: 'AdoptSimulation';  add: Segment[]; remove: SegId[]; move: Array<{segId: SegId; startNs: Ns; durationNs: Ns; stats:{peakM:number; rmsM:number}}>; rematch: Array<{tagId: TagId; segmentId: SegId|null; tapOffsetMs:number|null}> }
  | { kind: 'ReviewResolve';    itemIds: string[]; resolution: Resolution | null }  // null = reopen
  | { kind: 'ReviewStateSet';   reviewState: SessionState['reviewState'] }
  | { kind: 'Batch';            edits: Edit[] };   // added in ticket 09; one undo step for any compound action (E-27)

export interface JournalEntry { seq: number; ts: number; origin: 'manual'|'queue'|'bulk'|'adopt'|'tighten'|'import'; edit: Edit; }

export function reduce(state: SessionState, edit: Edit): SessionState;
export function replay(initial: SessionState, entries: readonly JournalEntry[], upTo?: number): SessionState;
```

**Reducer rules** (each is a test):
- `SegmentDelete` sets `segmentId = null` on every tag pointing at it (they become unmatched, never dangling).
- `SegmentMove` re-sorts; `edited = true`; stats are *supplied* by the caller (computed by `stats.ts` from the signal) — the reducer has no signal access.
- `SegmentSplit` removes the original, inserts `left`/`right` (provenance `split`), and re-points every tag that referenced the original: a tag goes to the half whose `[start, end]` contains the tag's *matched moment* (`tNs − tapOffsetMs·1e6`, or `tNs` itself if `tapOffsetMs` is null); if neither half contains it, the tag goes to `left`. (Edge case E-22.)
- `SegmentMerge` requires the two ids to be adjacent in sorted order; otherwise the reducer returns state unchanged and the caller must have validated — reducer is total, never throws.
- `AdoptSimulation` applies `remove`, then `move`, then `add`, then `rematch`, in that order, atomically.
- `ReviewResolve` with `resolution: null` deletes the key.
- Any Edit except `ReviewResolve` / `ReviewStateSet` while `reviewState ∈ {REVIEWED, EXPORTED}` downgrades `reviewState` to `IN_REVIEW`.
- `reduce` on an unknown `kind` returns the input state unchanged (forward-compat with a newer journal).

### 5.3 `core/stats.ts`

```ts
export const GRAVITY_BASELINE_MS2 = 9.81;
export interface SignalView { tSec: Float64Array; vertical: Float64Array; n: number; }
/** Peak/RMS of abs(vertical - 9.81) over [startNs, startNs+durationNs), optionally only over signal-true samples. */
export function segmentStats(sig: SignalView, t0Ns: Ns, startNs: Ns, durationNs: Ns, signalMask?: Uint8Array): { peakM: number; rmsM: number; samples: number };
export function lowerBound(arr: Float64Array, v: number): number;
```

### 5.4 `core/matcher.ts` (JS mirror of `TagMatcher.kt`)

```ts
export const DEFAULT_TAG_LOOKBACK_MS = 8000;  // [CALIBRATE], mirrors TagMatcher.kt
/** Segment open at tNs (start <= t <= start+dur) wins; else most recent segment whose end is within lookback before tNs; else null. */
export function suggestMatch(segments: readonly Segment[], tNs: Ns, lookbackMs?: number): { segment: Segment; tapOffsetMs: number } | null;
```

### 5.5 `core/queue.ts`

```ts
export interface QueueSettings { minPeakM: number /*default 3.0 = config.mildSeverityDeviation*/; lookbackMs: number; divergencePct: number /*10*/; labels: string[]; longSegmentWarningMs: number; }
export interface Divergence { segId: SegId; recordedPeak: number; recomputedPeak: number; }
export function buildQueue(state: SessionState, divergence: readonly Divergence[], settings: QueueSettings): ReviewItem[];   // excludes resolved items
export function orderQueue(items: ReviewItem[], mode: 'priority' | 'time'): ReviewItem[];
export function buildRanges(tags: readonly Tag[]): Array<{ startId: TagId; endId: TagId | null; label: string; startNs: Ns; endNs: Ns | null; problems: Array<'unpaired'|'inverted'|'overlap'> }>;
```

### 5.6 `core/tighten.ts`, `core/splitmerge.ts`

```ts
export interface ThresholdView extends SignalView { shortStd: Float64Array; longStd: Float64Array; shortThr: number; longThr: number; }
/** Shrinks [start,end) to first..last sample where shortStd>=shortThr || longStd>=longThr. Returns null if no signal-true sample inside. */
export function tightenBounds(v: ThresholdView, t0Ns: Ns, startNs: Ns, durationNs: Ns): { startNs: Ns; durationNs: Ns } | null;
/** Nearest signal-true edge (rising for start, falling for end) within ±snapWindowMs of candidateNs. */
export function snapBoundary(v: ThresholdView, t0Ns: Ns, candidateNs: Ns, edge: 'start'|'end', snapWindowMs: number): Ns;
export function planSplit(seg: Segment, atNs: Ns, sig: SignalView, t0Ns: Ns, newId: () => SegId): Extract<Edit, {kind:'SegmentSplit'}> | null; // null if atNs outside (start, end)
export function planMerge(a: Segment, b: Segment, sig: SignalView, t0Ns: Ns, newId: () => SegId): Extract<Edit, {kind:'SegmentMerge'}> | null; // null if not adjacent
```

### 5.7 `worker/detector.ts` (Kotlin-exact mirror)

```ts
export interface DetectionTunables { calibrationDurationMs: number; stdFactor: number; floorStd: number; endQuietMs: number; minSegmentDurationMs: number; turnYawThresholdRadS: number; }
export interface Calibration { shortStdThreshold: number; longStdThreshold: number; calibEndIdx: number; }
export const SHORT_WINDOW_SAMPLES = 20, LONG_WINDOW_SAMPLES = 3000;   // RollingStats(windowSamples) in RecordingPipeline.kt
export function calibrate(vertical: Float64Array, tNs: BigInt64Array, t: DetectionTunables): Calibration;
export function simulate(vertical: Float64Array, tNs: BigInt64Array, gz: Float64Array | null, t: DetectionTunables, c: Calibration): Array<{ startNs: Ns; durationNs: Ns; peakM: number; rmsM: number }>;
export function signalMask(vertical: Float64Array, c: Calibration): Uint8Array;  // 1 where shortStd>=thr || longStd>=thr, running RollingStats from index 0 (after calibration reset)
```

Semantics locked to the Kotlin source (`SegmentDetector.kt`,
`NoiseFloorCalibrator.kt`, `RecordingPipeline.kt`):
- Calibration pushes samples while `elapsedMs < calibrationDurationMs` **inclusive of the sample that crosses** (push-then-check); the detector then *continues* with the same `RollingStats` instances (they are handed over, not reset — `RecordingPipeline.kt:127-128`).
- `threshold(std) = max(max(std, floorStd) * stdFactor, floorStd)`.
- `anySignal = shortStd >= shortThr || longStd >= longThr`.
- `turning = |gz| >= turnYawThresholdRadS` when gz is finite, else `false`.
- Magnitude accumulates `abs(vertical − 9.81)` over signal-true samples only.
- `quietMs = (t − lastSignalNs) / 1e6` integer division; close when `>= endQuietMs`.
- Duration = `lastSignalNs − startNs`; discard if `durationMs < minSegmentDurationMs` (integer ms).
- End of data force-closes an open segment.
- Float32 vs Float64: Kotlin uses `Float`. Use `Float64` in JS but assert against the fixture with tolerance 1e-3 on thresholds and exact equality on segment count and start indices (D17). If exact count cannot be reached in ticket 03, switch to `Math.fround` at every accumulation step and re-test before declaring a discrepancy.

### 5.8 `worker/parse.ts`

```ts
export interface ParsedSensor { n: number; schemaVersion: number; tNs: BigInt64Array; tSec: Float64Array; ax: Float64Array; ay: Float64Array; az: Float64Array; gx: Float64Array; gy: Float64Array; gz: Float64Array; vertical: Float64Array; jerk: Float64Array; rollStd: Float64Array; gyroCoverage: number; gaps: Array<{ atIdx: number; dtMs: number }>; }
export interface ParsedGps { n: number; schemaVersion: number; tNs: BigInt64Array; lat: Float64Array; lon: Float64Array; speedMps: Float64Array; accuracyM: Float64Array; }
export interface ParsedSession { sensor: ParsedSensor; gps: ParsedGps | null; segments: Segment[]; tags: Tag[]; config: SessionConfig | null; review: ReviewSidecar | null; warnings: ImportWarning[]; t0Ns: Ns; }
export type ImportWarning = { level: 'error'|'warn'|'info'; code: string; text: string; };
export function parseSession(files: Map<string, string /*text*/>, opts: { knownLabels: string[] }): ParsedSession;  // throws ImportError on unsupported schema_version or missing sensor.csv
```

Per-file dispatch: `SENSOR_PARSERS[schemaVersion]`, etc. — a `Record<number, fn>`
per file; unknown version → `ImportError({file, version})`. Header is matched
by **name**, never by column index. Empty field → `NaN`. Tags resolve
`segment_start_ns` → `SegId` by **exact bigint equality** with a segment's
`start_ns`; miss → `segmentId: null` + warning `TAG_UNMATCHED`.

### 5.9 `worker/lod.ts`

```ts
export interface LodPyramid { levels: Array<{ stride: number; min: Float32Array; max: Float32Array }>; }  // stride 1 omitted; levels 2,4,8,...,≤n/2
export function buildLod(series: Float64Array): LodPyramid;
export function pickLevel(p: LodPyramid, samplesPerPixel: number): number; // index, or -1 for raw
```

### 5.10 `worker/export.ts`

```ts
export interface ExportInput { source: { files: Map<string, Blob>; hash: string }; state: SessionState; journal: JournalEntry[]; t0Ns: Ns; workbenchVersion: string; sessionName: string; }
export function segmentsToCsv(segments: readonly Segment[]): string;   // schema_version,start_ns,duration_ns,peak_m,rms_m,speed_mps,epoch_ms — 3 dp for m; speed '' if null
export function labelsToCsv(tags: readonly Tag[], segments: readonly Segment[]): string;
export function buildReviewSidecar(input: ExportInput, queueOpen: ReviewItem[]): ReviewSidecar;
export async function buildCleanBundle(input: ExportInput, queueOpen: ReviewItem[]): Promise<{ blob: Blob; verification: VerifyResult }>;
export async function verifyCleanBundle(blob: Blob, expected: ExportInput): Promise<VerifyResult>;  // re-reads zip: pass-through hashes equal; parsed segments/tags deep-equal state
```

### 5.11 `app/store.ts` (signals)

```ts
export interface WorkspaceStore {
  source: Signal<SourceBundle | null>;
  parsed: Signal<ParsedSession | null>;
  journal: Signal<JournalEntry[]>; cursor: Signal<number>;        // state = replay(initial, journal, cursor)
  state: ReadonlySignal<SessionState>;
  selection: Signal<Selection>;                                    // {segIds:Set, tagIds:Set, primary?: {type,id}}
  viewport: Signal<{ startSec: number; endSec: number }>;
  channel: Signal<ChannelKey>;
  queue: ReadonlySignal<ReviewItem[]>; queueMode: Signal<'priority'|'time'>; queueIndex: Signal<number>;
  simulation: Signal<SimulationState | null>;                      // tunables, result, diff, overlay on/off
  apply(edit: Edit, origin: JournalEntry['origin']): void;         // truncates redo tail, appends, advances cursor, schedules autosave
  undo(): void; redo(): void;
}
```

### 5.12 `layout/LayoutHost.ts`

```ts
export type PanelId = 'waveform'|'minimap'|'queue'|'inspector'|'map'|'tree'|'tunables'|'report';
export interface LayoutHost {
  mount(root: HTMLElement, panels: Record<PanelId, { title: string; render: (el: HTMLElement) => () => void }>): void;
  show(id: PanelId): void; hide(id: PanelId): void; focus(id: PanelId): void;
  serialize(): unknown; restore(layout: unknown): boolean;         // false if incompatible → caller applies preset
  applyPreset(name: 'review'|'inspect'): void;
  onResize(cb: (id: PanelId, w: number, h: number) => void): () => void;
  destroy(): void;
}
```

### 5.13 `interaction/PointerAdapter.ts`

```ts
export type Gesture =
  | { type: 'down';  x: number; y: number; modifiers: Mods; pointerId: number }
  | { type: 'move';  x: number; y: number; modifiers: Mods; pointerId: number }
  | { type: 'up';    x: number; y: number; modifiers: Mods; pointerId: number }
  | { type: 'pinch'; cx: number; scale: number }                     // touch only
  | { type: 'wheel'; x: number; deltaY: number; modifiers: Mods }    // mouse only
  | { type: 'longpress'; x: number; y: number }                      // touch only
  | { type: 'tap2'; x: number; y: number };                          // two-finger tap
export interface Mods { shift: boolean; alt: boolean; ctrlOrMeta: boolean; }
export interface PointerAdapter { attach(el: HTMLElement, emit: (g: Gesture) => void): () => void; }
```

The **interaction state machine** (`interaction/machine.ts`) consumes `Gesture`
and hit-test results, and emits `Intent`s (`pan`, `zoom`, `select`,
`beginDragBoundary`, `dragBoundary`, `commitBoundary`, `marquee`, …). It is pure
and tested with scripted gesture sequences (§12).

---

## 6. Data formats

### 6.1 Source files (schema_version 1 — from `SessionRecorder.kt`)

| File | Header | Notes |
|---|---|---|
| `sensor.csv` | `schema_version,timestamp_sensor_ns,accel_x_ms2,accel_y_ms2,accel_z_ms2,gyro_x_rads,gyro_y_rads,gyro_z_rads,vertical_ms2,jerk_ms3,roll_std_dev` | ~100 Hz. Gyro may be empty for entire session (fixture 140717 has **zero** gyro rows). `jerk_ms3` empty on first row. |
| `gps.csv` | `schema_version,timestamp_sensor_ns,latitude,longitude,speed_mps,accuracy_m` | ~1 Hz. First fix commonly has empty speed. |
| `segments.csv` | `schema_version,start_ns,duration_ns,peak_m,rms_m,speed_mps,epoch_ms` | 3 dp. `peak_m` is already a deviation (post-fix sessions). |
| `labels.csv` | `schema_version,timestamp_sensor_ns,kind,segment_start_ns,label,tap_offset_ms,epoch_ms` | `kind ∈ POINT, RANGE_START, RANGE_END`. `segment_start_ns`/`tap_offset_ms` may be empty (unmatched). |
| `config.json` | `{schemaVersion, calibrationDurationMs, stdFactor, floorStd, endQuietMs, minSegmentDurationMs, turnYawThresholdRadS, mildSeverityDeviation, moderateSeverityDeviation, tagDebounceMs, longSegmentWarningMs, calibratedShortStdThreshold, calibratedLongStdThreshold}` | Optional (pre-hardening sessions lack it). |

### 6.2 Bundle discovery

A `BundleSource` yields `Map<basename, Blob>`. Matching is by lowercase
basename, ignoring any directory prefix (zips may or may not have a top folder;
loose-folder import gives a `FileList` with `webkitRelativePath`). If more than
one file matches a basename → `ImportError('AMBIGUOUS_FILE')`. `sensor.csv` is
required; everything else optional with a warning. Session name = zip name minus
`.zip`, or folder name, or `session_<epoch>` fallback.

### 6.3 Severity / colour mapping

M-scale for colour only (not exported): `peakToM(peak) = clamp(6·ln(peak/10)/ln(60/10), 0, 10)` for `peak > 10`, else 0 — carried from the prototype as `[CALIBRATE]`. Colour stops: M0 `#3C8F4A`, M3 `#C7CB3B`, M6 `#F09330`, M10 `#D6483F`, linearly interpolated. **Also** show the on-device `Severity` bucket (MILD/MODERATE/SEVERE from `config.mildSeverityDeviation` / `moderateSeverityDeviation`, defaults 3.0 / 8.0 in this fixture) as a badge in the inspector, because that is what the rider saw live.

### 6.4 `review.json` (schema v1, written by the Workbench)

```json
{
  "schemaVersion": 1,
  "workbench": { "version": "2.0.0", "build": "<git sha>" },
  "source": { "hash": "sha256:<hex of concatenated pass-through files>", "sessionName": "session_20260905_140717", "files": ["sensor.csv","gps.csv","segments.csv","labels.csv","config.json"] },
  "exportedAt": "2026-09-11T10:12:00Z",
  "reviewState": "REVIEWED",
  "queue": { "openBlocking": 0, "openAdvisory": 3, "resolved": 61 },
  "counts": { "segments": 290, "tags": 49, "segmentsCreated": 2, "segmentsDeleted": 6, "segmentsEdited": 14, "tagsCreated": 1, "tagsDeleted": 0, "tagsRelabelled": 3 },
  "segments": { "s:95748349469971": { "provenance": "recorded", "confirmed": true }, "n:01J...": { "provenance": "created", "confirmed": true } },
  "journal": [ { "seq": 1, "ts": 1788600000000, "origin": "queue", "edit": { "kind": "TagRelabel", "tagIds": ["t:..."], "label": "Pothole" } } ]
}
```

The full journal is included (it is small: hundreds of entries × ~100 bytes).
On re-import, if `review.json` is present and `source.hash` matches the
recomputed hash of the pass-through files, the journal is restored with cursor
at the end; if the hash mismatches, import proceeds without the journal and
warns `REVIEW_SIDECAR_STALE`.

### 6.5 IndexedDB layout (`persistence/idb.ts`, DB `aitken_workbench_v2`)

| Store | Key | Value |
|---|---|---|
| `bundles` | `hash` | `{ hash, sessionName, files: Map<name, Blob>, importedAt, sizeBytes }` |
| `journals` | `hash` | `{ hash, entries: JournalEntry[], cursor, updatedAt }` — written debounced 300 ms after each `apply`, and immediately on `visibilitychange → hidden` |
| `layouts` | `'desktop' \| 'mobile'` | `{ version, data: unknown }` |
| `settings` | `'settings'` | `Settings` (§6.6) |
| `derived` | `hash` | optional cache: `{ lod, recomputed, divergence }` — evictable; rebuilt if absent |

### 6.6 `Settings`

```ts
interface Settings {
  labels: Array<{ name: string; color: string; hotkey: string /*'1'..'9'*/; defaultKind: 'POINT'|'RANGE' }>;
  queue: { minPeakM: number; divergencePct: number; lookbackMs: number; mode: 'priority'|'time' };
  snap: { enabled: boolean; windowMs: number /*150*/ };
  units: { speed: 'kph'|'mps' };
  theme: 'system'|'dark'|'light';
}
```

Seeded labels: Pothole `#EF5350` `1` POINT · Bump `#66BB6A` `2` POINT · Speedbreaker `#FFA726` `3` POINT · Rough stretch `#C776DD` `4` RANGE.

---

## 7. Feature specifications

### 7.1 Session Sanity Report (panel `report`)

One card, rendered as soon as `parse` completes. Sections, each a single line
with a status dot (ok / warn / bad):

| Line | Source | Warn / bad rule |
|---|---|---|
| Duration, rows, sample rate | sensor | rate outside 80–120 Hz → warn |
| Sensor gaps | `sensor.gaps` (dt > 500 ms) | any → warn; > 5 or > 5 s → bad |
| Gyro coverage | `gyroCoverage` | < 99 % → warn; 0 % → bad (turn suppression was inert) |
| GPS fixes, coverage | gps | none → bad; gaps > 30 s → warn |
| Detector config | config | missing → warn ("simulator uses defaults") |
| Recorded vs recomputed peak | divergence | any > 10 % → warn; > 25 % of segments → bad ("pre-fix session?") |
| Tap offset | tags with offset | median, p90; p90 > lookback·0.8 → warn |
| Label histogram | tags | unknown label → warn |
| Range pairs | `buildRanges` | any problem → warn |
| Queue | `buildQueue` | "N blocking · M advisory" |

Buttons: **Start review** (focuses Queue, selects item 0), **Dismiss report**.
The report stays reachable from the top bar.

### 7.2 Review Queue (panel `queue`)

**Build.** `buildQueue` emits one `ReviewItem` per problem; `resolutions` in
state filter out confirmed/dismissed ones (items whose target no longer exists
are dropped silently — a deleted segment's items vanish).

**Blocking vs advisory.** Blocking: `unmatched-tag`, `range-unpaired`,
`range-inverted`, `unknown-label`. Advisory: everything else. Export refuses
while any blocking item is open (§7.6).

**Score (priority mode).** `score = blockingWeight + kindWeight + mClamp`
where `blockingWeight = blocking ? 1000 : 0`;
`kindWeight`: `peak-divergence` 300, `untagged-high-m` 200, `tag-link-disagrees`
150, `long-segment` 100, `edited-unconfirmed` 50, `range-overlap` 40;
`mClamp = min(peakM, 100)` of the target segment (0 for tag-only targets).
Sort by score desc, then by `focusNs[0]` asc. Time mode: by `focusNs[0]` asc.

**Suggestions.** `unmatched-tag` → `TagRematch` to `suggestMatch(...)` if any;
`tag-link-disagrees` → `TagRematch` to the suggestion; `range-unpaired` with a
`RANGE_START` → `TagCreate` of a `RANGE_END` at `min(start + 10 s, next
RANGE_START, session end)` labelled the same; `range-inverted` → one `TagMove` moving the `RANGE_END` tag to
`start + 1 s` (a single Edit; the human adjusts the end afterwards if needed);
`peak-divergence` → none (human must look); `untagged-high-m` → none (label
keys do it); `unknown-label` → `TagRelabel` to the closest known label by
case-insensitive Levenshtein ≤ 2, else none.

**Navigation.** `queueIndex` points into the ordered list. Stepping sets
`selection.primary = item.target`, `viewport` to `focusNs` padded by 25 % each
side (min span 2 s), and asks the map to fly to the segment's nearest GPS fix.
Resolving an item does **not** auto-advance unless setting
`queue.autoAdvance` (default true). After the last item, the panel shows a
completion state with "Mark session REVIEWED" (applies `ReviewStateSet`).

**Pace estimate.** Rolling mean of the last 10 resolution intervals ×
remaining; hidden until 3 resolutions exist.

### 7.3 Waveform (`WaveformView`, canvas)

Layout (top→bottom): tag lane (22 px, point tags as pins with label colour;
range tags as bars), plot area (segments as translucent bands coloured by M,
handles at both edges when selected, simulation overlay dashed), speed
sub-trace (optional, right axis), time axis (24 px). Left gutter 48 px with
value axis. DPR-aware.

Drawing rule: pick `pickLevel(lod, samplesPerPixel)`; ≥ 2 samples/pixel →
draw min/max envelope from the level; else draw raw polyline. Never iterate
more than `2 × canvasWidth` points per trace per frame.

Viewport: `startSec/endSec` clamped to `[0, totalSec]`, min span 50 ms.
Zoom anchors at cursor. Selection ring is a DOM overlay (`<div>` absolutely
positioned) so it can animate with CSS; the canvas draws only data.

Hit testing (`hitTest(x, y) → Hit | null`), priority order: segment handle
(±6 px, only when selected) → range handle → point tag pin → segment band →
range bar → empty. Touch multiplies hit radii by 2.

Snap: during boundary drag, `snapBoundary` unless `alt` held (desktop) or
snap toggle off (mobile chip). A faint tick shows the snapped position.

### 7.4 Inspector (panel `inspector`)

Renders for `selection.primary`:
- **Segment**: M badge (colour) + Severity bucket, start / end / duration inputs
  (`mm:ss.mmm`, validated: end > start, inside session), peak / RMS (live, with
  recorded value greyed beneath if different), speed (kph/mps), tags on segment,
  divergence note if any. Actions: Tighten · Split at cursor · Merge with next ·
  Delete · Confirm.
- **Tag**: label chips (hotkeys shown), kind, time input, matched segment
  (locate) or *unmatched* + Suggest/Accept, tap offset. Actions: Delete · Confirm.
- **Range**: label, start/end inputs, duration, problems list with fix buttons.
- **Multi-selection**: counts by type, label histogram, bulk actions: Relabel
  (chips) · Auto-match unmatched · Tighten all · Confirm all · Delete.
- **Nothing**: session summary (mini version of the report).

Inputs commit on blur/Enter as one Edit; invalid input shakes (motion spec)
and shows inline reason, never a toast.

### 7.5 Simulation & Diff (panel `tunables`)

Sliders for the six `DetectionTunables`, initialised from `config.json` (or
defaults + warning). Fields differing from config show a dot. **Run** (debounced
400 ms on slider release, also explicit button) posts to worker; result
overlays as dashed bands. Below: **Diff list** grouped Added / Removed / Moved
/ Unchanged with counts; clicking focuses; checkboxes select. Actions:
**Adopt all** · **Adopt selected** · **Adopt additions only** · **Clear**.

`diff(current: Segment[], simulated: SimSeg[]): Diff` pairs segments by
overlap ≥ 50 % of the shorter one (greedy by overlap desc); paired with
boundary change > 1 sample → `moved`; else `unchanged`; unpaired simulated →
`added`; unpaired current → `removed`. Adopt builds one `AdoptSimulation`
Edit: `removed` ids, `moved` with fresh stats, `added` as new segments with
`provenance: 'adopted'`, and `rematch` for every tag whose segment was removed
or moved (via `suggestMatch` against the post-adopt segment list). The fallout
(tags left unmatched) is shown in a confirm dialog before applying.

### 7.6 Export (top bar)

1. `buildQueue`; if any blocking open → dialog listing them with "Go to first";
   refuse. If advisory open → dialog "Export anyway?" listing counts.
2. Worker `buildCleanBundle` → zip with top folder `<sessionName>_clean/`,
   pass-through files copied as the original `Blob`s, plus `segments.csv`,
   `labels.csv`, `review.json`.
3. `verifyCleanBundle` re-reads the produced blob: SHA-256 of each pass-through
   entry equals source; parsed `segments.csv`/`labels.csv` deep-equal state
   (bigint-exact times, 3-dp numbers).
4. On success: download, `ReviewStateSet('EXPORTED')`, toast with counts. On
   failure: no download, error dialog with the diff — never a half-trusted file.

### 7.7 Smart Filter (in `tree` panel header)

A single text input with a tiny grammar, parsed by `core/filter.ts`:

```
expr    := clause ( 'AND' clause )*
clause  := field op number | 'untagged' | 'tagged' | 'unmatched' | 'edited' | 'confirmed' | 'label' '=' word
field   := 'M' | 'D' | 'speed' | 'rms'          // M = peakM (m/s²), D = seconds, speed in current unit
op      := '>=' | '<=' | '>' | '<' | '='
```

`compileFilter(text): (seg|tag) => boolean | ParseError`. Matching items are
highlighted in tree and waveform; **Select matching** puts them in
`selection`; bulk actions then live in the Inspector. Errors show inline under
the input with the offending token underlined. No saved presets in v1 (D7).

### 7.8 Library (route `/`)

Cards from `bundles` × `journals`: name, date (from first `epoch_ms` in
segments or file date), duration, segments, tags, review state badge, queue
"N open", last edited. Sort by last edited desc. Actions: Open · Delete
(confirm) · Import new (drop zone + file picker with `webkitdirectory` option).
Storage estimate line (`navigator.storage.estimate()`).

### 7.9 Map (panel `map`, Leaflet)

Polyline of GPS track coloured by speed (3 stops). Segment markers: circle
markers sized by `log(D)` and coloured by M; click selects. Cursor marker
follows the waveform hover/selection. `flyTo` on queue step (duration 450 ms;
0 with reduced motion). Tiles: OSM via a `CacheFirst` service-worker route
limited to 2,000 entries / 30 days; offline → grey tile with "offline" watermark.
Attribution kept.

---

## 8. Layout, interaction, and visual specification

### 8.1 Desktop layout (`DockviewLayoutHost`)

Preset **Review** (default):

```
┌──────────────────────────────────────────────────────────────────────┐
│ TopBar: [☰ Library] session_name  ●REVIEWED   ⟲ ⟳   [Export]  [⋯]    │
├──────────┬───────────────────────────────────────────┬───────────────┤
│ Queue    │ Waveform                                  │ Inspector     │
│ 17/63    │                                           │               │
│ ▸ item   ├───────────────────────────────────────────┤               │
│ ▸ item   │ Minimap                                   │               │
│          ├───────────────────────────────────────────┼───────────────┤
│ [Tree]   │ Map                                       │ Tunables/Rep. │
└──────────┴───────────────────────────────────────────┴───────────────┘
```

Preset **Inspect**: Waveform + Tunables stacked centre; Tree left; Inspector +
Map right; Queue hidden. Every panel is draggable by its tab to any edge / into
any group / floating; sizes persist to `layouts['desktop']` (debounced 500 ms).
`restore` returning `false` (dockview version mismatch, missing panel id)
applies Review preset silently.

### 8.2 Mobile layout (`SheetLayoutHost`, `< 760 px` or coarse pointer + `< 1024 px`)

```
┌──────────────────────────────┐
│ ‹  session_name   ●  17/63  ⋯│  compact bar (44 px)
├──────────────────────────────┤
│                              │
│  Waveform (full bleed)       │
│                              │
├──────────────────────────────┤
│ ▬▬  Queue · Inspect · Map · Tree   ← sheet handle + tabs
│  (sheet content)             │
└──────────────────────────────┘
```

Sheet snap points: `peek` (72 px: handle + current item title + 4 action
chips), `half` (50 %), `full` (92 %). Drag handle or swipe; velocity-based
snap. Queue actions in `peek` state: label chips (scrollable), ✓ confirm,
✕ dismiss, ⌫ delete, ‹ ›. Minimap is a 28 px strip under the compact bar.
Landscape phones: sheet becomes a right-side drawer (40 %).

### 8.3 Responsiveness rules

- Breakpoint is evaluated on `resize` (debounced 150 ms); crossing it swaps the
  `LayoutHost` adapter without losing session state (store is independent).
- Canvas resizes via `ResizeObserver`; redraw is coalesced to one rAF.
- All panels have `min-width 220 px` / `min-height 120 px` on desktop.
- Text never below 12 px; hit targets ≥ 40 px on touch.

### 8.4 Keyboard map (desktop; also works with a Bluetooth keyboard on mobile)

| Key | Action |
|---|---|
| `J` / `K` or `↓` / `↑` | Next / previous queue item |
| `[` / `]` | Previous / next segment in time |
| `1`–`9` | Assign label with that hotkey to selected segment/tags (creates a POINT tag at segment end for an untagged segment) |
| `Enter` | Confirm current item (applies suggestion if one exists and none applied yet) |
| `Backspace`/`Delete` | Delete selected (confirm dialog if > 1) |
| `X` | Dismiss current item |
| `A` | Accept suggestion |
| `T` | Tighten selected |
| `S` | Split at cursor (cursor = last hover position in plot) |
| `M` | Merge selected with next |
| `Z` / `Shift+Z` (with Ctrl/Cmd) | Undo / redo |
| `F` | Fit whole session; `Shift+F` fit selection |
| `+` / `-` | Zoom in / out at centre |
| `Space` (hold) + drag | Pan regardless of hit |
| `/` | Focus Smart Filter |
| `Esc` | Clear selection / cancel add mode / close dialog |
| `?` | Keyboard cheat sheet |
| `P` / `R` / `N` | Add point tag / range tag / new segment (arm mode; next click places) |

Keys are ignored while focus is in an input.

### 8.5 Gesture map (touch)

| Gesture | Action |
|---|---|
| 1-finger drag on plot | Pan |
| Pinch | Zoom about pinch centre |
| Tap | Select hit (segment / tag) or clear |
| Long-press (350 ms) on handle | Grab boundary; drag; release commits (snap unless snap chip off) |
| Long-press on empty plot | Arm marquee; drag selects range |
| Two-finger tap | Context sheet (Split here / Add point / Add range / Add segment) |
| Swipe left / right on sheet `peek` header | Next / previous queue item |
| Swipe on a queue row | Right = confirm, left = dismiss (with undo toast) |

### 8.6 Visual tokens (`styles/tokens.css`)

```
--bg-0/1/2/3, --line-0/1, --fg-0/1/2, --accent (#4FC3F7 dark / #0277BD light),
--recompute (#B39DDB), --sim (#80CBC4), --danger, --warn, --ok,
--sev-0 #3C8F4A  --sev-3 #C7CB3B  --sev-6 #F09330  --sev-10 #D6483F,
--radius-1 6px --radius-2 10px --radius-3 16px,
--shadow-1 (panel) --shadow-2 (floating/sheet),
--font-ui 'Manrope' --font-mono 'JetBrains Mono' (self-hosted woff2, subset latin),
--dur-1 120ms --dur-2 220ms --dur-3 360ms,
--ease-out cubic-bezier(.2,.8,.2,1) --ease-spring cubic-bezier(.34,1.56,.64,1)
```

Dark and light values defined under `:root` and `@media (prefers-color-scheme:
light)`; `data-theme` attribute overrides. Canvas reads tokens via
`getComputedStyle` once per theme change.

### 8.7 Motion spec (all CSS/WAAPI; all → 0 ms under `prefers-reduced-motion`)

| Moment | Motion |
|---|---|
| Panel dock/undock, sheet snap | transform + opacity, `--dur-3 --ease-spring` |
| Selection ring appears / moves | `--dur-2 --ease-out` on `transform`/`width` |
| Queue advance | Old row fades/slides up 8 px, new row highlights, `--dur-2` |
| Toast | slide-up `--dur-2`, auto-dismiss 4 s, hover pauses |
| Invalid input | 3-cycle 4 px shake, `--dur-3` |
| Dialog | scale .96→1 + fade, `--dur-2` |
| Map flyTo | 450 ms (Leaflet), 0 under reduced motion |
| Waveform viewport change from keyboard/queue | 180 ms interpolated `startSec/endSec` via rAF **inside the store** (the one exception to 0.7 — it drives data, not decoration), skipped under reduced motion |

---

## 9. Project structure & tooling

```
workbench/
  package.json            (scripts: dev, build, test, test:e2e, typecheck, lint)
  vite.config.ts          (vite-plugin-singlefile, worker inlined, PWA plugin)
  tsconfig.json           (strict, noUncheckedIndexedAccess)
  index.html
  src/
    main.tsx              (Preact mount, router Library ⇄ Workspace)
    app/                  store.ts, routes, TopBar.tsx, Toast.tsx, Dialog.tsx
    core/                 types.ts edits.ts reducer.ts stats.ts matcher.ts queue.ts tighten.ts splitmerge.ts diff.ts filter.ts format.ts ids.ts
    worker/               index.ts (message protocol) parse.ts detector.ts dsp.ts lod.ts export.ts channels.ts
    bridge/               WorkerBridge.ts RealWorkerBridge.ts InlineWorkerBridge.ts
    persistence/          Persistence.ts IdbPersistence.ts MemoryPersistence.ts
    layout/               LayoutHost.ts DockviewLayoutHost.ts SheetLayoutHost.ts presets.ts
    interaction/          PointerAdapter.ts Mouse.ts Touch.ts machine.ts hittest.ts keymap.ts
    panels/               Queue.tsx Inspector.tsx Tree.tsx Tunables.tsx Report.tsx Library.tsx MapPanel.tsx
    canvas/               WaveformView.ts MinimapView.ts draw.ts
    styles/               tokens.css base.css motion.css
    sw.ts                 (service worker: app-shell precache, tile CacheFirst)
  test/
    fixtures/             session_trimmed_90s/ {sensor,gps,segments,labels}.csv config.json  + expected/*.json
                          synthetic/ {no-gyro, no-gps, gaps, unknown-label, schema-v2, empty-labels, range-problems}/
    unit/                 *.test.ts (Vitest, node env)
    integration/          *.test.ts (Vitest + jsdom/happy-dom: store + inline worker + memory persistence)
    e2e/                  *.spec.ts (Playwright: desktop 1440×900, mobile Pixel-7 emulation)
  scripts/
    make-fixture.mjs      (trims a real session to N seconds, offsets GPS, writes expected/*.json)
```

Dependencies (pin exact versions in ticket 01): `preact`, `@preact/signals`,
`dockview-core`, `leaflet`, `jszip`, `vite`, `vite-plugin-singlefile`,
`vite-plugin-pwa`, `typescript`, `vitest`, `@playwright/test`, `happy-dom`,
`fake-indexeddb`.

CI: `.github/workflows/workbench-ci.yml` — on changes under `workbench/**`:
`npm ci && npm run typecheck && npm test && npm run build && npm run test:e2e`.
Artifacts: `dist/index.html`. Existing Android CI untouched.

`bigint` in Vitest/Vite targets: set `build.target = 'es2020'`.

---

## 10. Vertical slices (tickets)

Each ticket is a tracer bullet through worker/core → store → panel → built app.
Numbering is dependency order. Files: `.scratch/workbench/issues/NN-*.md`.

| # | Ticket | Blocked by | Delivers (demoable) |
|---|---|---|---|
| 01 | Scaffold, tokens, fixture, CI | — | `npm run build` yields single `dist/index.html` showing an empty shell in dark/light; fixture generated; CI green; `CONTEXT.md` terms added |
| 02 | Parse + import a bundle (worker) → Library card | 01 | Drop zip or folder → parsed in worker with progress → session stored in IDB → appears in Library with counts and warnings |
| 03 | Kotlin-exact detector mirror + recompute + divergence | 02 | Worker `calibrate/simulate/signalMask/recompute`; fixture thresholds match `config.json` (±1e-3); segment count matches recorded; divergence list produced |
| 04 | Waveform + minimap + LOD + viewport (read-only) | 02 | Open a session → vertical trace with segments/tags overlaid, minimap, pan/zoom (mouse), channel switch, DPR-correct |
| 05 | Reducer, Journal, undo/redo, autosave, re-open | 02 | Tag relabel via Inspector (first Edit) persists across reload; undo/redo; `replay` tests for every Edit kind |
| 06 | Sanity Report | 03, 05 | Report card on open with all §7.1 lines against the fixture; Start review button (no queue yet — focuses tree) |
| 07 | Review Queue + keyboard | 05, 06 | Queue panel with §7.2 ordering, J/K navigation auto-focusing waveform, 1–9 relabel, Enter/X, progress; resolutions persist |
| 08 | Segment boundary editing: drag, snap, tighten, stats | 04, 05 | Handle drag with snap, `T` tighten, live `abs(v−9.81)` peak/RMS; inspector inputs |
| 09 | Tag editing: add point/range, move, delete, rematch, range problems | 07, 08 | `P`/`R` arm modes, drag pins, Suggest/Accept for unmatched, range-problem queue items with suggestions |
| 10 | Split, merge, create segment, multi-select, Smart Filter, bulk | 09 | `S`/`M`, `N` draw, shift-marquee, filter grammar, bulk relabel/confirm/delete/tighten |
| 11 | Map panel | 04, 07 | Leaflet track + markers + cursor + flyTo on queue step |
| 12 | Simulation + Diff + Adopt | 03, 10 | Tunables sliders, dashed overlay, diff list, adopt all/selected/additions with rematch fallout dialog |
| 13 | Export Clean Bundle + verify + re-import | 05, 07, 09 | Export gate on blocking items, byte-identical pass-through, `review.json`, self-verify, re-import restores journal |
| 14 | Dockable layout (dockview) + presets + persistence | 04, 07, 11 | Drag panels anywhere, float, tabs; presets; layout survives reload; reset |
| 15 | Mobile focus mode + touch adapter + sheet | 08, 09, 14 | Under 760 px: sheet layout, pinch/pan/long-press/swipe gestures, queue clearable on Pixel-7 emulation |
| 16 | PWA + offline + tile cache | 11, 13 | Installable, works offline after first load, tiles cached, `file://` still works |
| 17 | Motion polish, a11y pass, keyboard cheat sheet | 14, 15 | Every §8.7 moment implemented; reduced-motion honoured; focus order; `?` overlay |
| 18 | Metric channels (ISO-2631-style) behind ChannelProvider | 04, 12 | Windowed peak/RMS/VDV/crest/MTVV/band-pass channels with parameter chips; comfort bands |

Frontier at start: **01**. After 02: 03, 04, 05 can run in any order (three
fresh contexts). Everything ships green at each ticket; 01–13 is the minimum
useful product on desktop, 14–16 make it the product described in D3–D5.

---

## 11. Edge-case register (each `E-xx` is referenced by at least one ticket)

**Import**
- E-01 Zip with top-level folder vs. flat; nested folders; Mac `__MACOSX/` entries → ignored by basename filter, `__MACOSX` prefix explicitly excluded.
- E-02 Missing `gps.csv` → map hidden with notice; speed features disabled; report line bad.
- E-03 Missing `labels.csv` → zero tags; report fine; queue full of `untagged-high-m`.
- E-04 Missing `segments.csv` → zero segments; every tag unmatched; report suggests running Simulation and adopting.
- E-05 Missing `config.json` → defaults from `Tunables.kt` (10000, 3.0, 0.05, 500, 30, 1.0; mild 5 / moderate 15 — **note** the repo defaults differ from the fixture's 3.0 / 8.0; use config when present), warning.
- E-06 Gyro columns entirely empty (fixture) → `turning` always false; coverage 0 % → report bad; `gz` channel greyed.
- E-07 Tag whose `segment_start_ns` matches no segment (fixture has one `RANGE_START` with empty link) → unmatched, queue item.
- E-08 `schema_version` unknown in any file → `ImportError`, nothing stored.
- E-09 CSV with CRLF, trailing newline, trailing empty lines, BOM → all tolerated.
- E-10 Header columns in a different order → still parsed (name-based).
- E-11 Duplicate segment `start_ns` rows → second gets id suffix `#2`; warning.
- E-12 Sensor timestamps non-monotonic (clock hiccup) → rows kept, warning with count; LOD and lowerBound assume sorted → parser stable-sorts by `tNs` and records `reordered: n`.
- E-13 Session > 2^53 ns timestamps → `bigint` path; plotting uses seconds relative to `t0Ns`.
- E-14 Very large session (2 h, 720k rows, ~55 MB) → parse in chunks with progress; memory budget noted in report; LOD built lazily per channel.
- E-15 Two imports of the same bundle → same hash → Library shows one card; journal preserved; toast "already imported — opened".
- E-16 `review.json` present but `source.hash` mismatch → import without journal, warning `REVIEW_SIDECAR_STALE`.
- E-17 Loose-folder import containing extra files (`.nomedia`, logcat) → ignored.

**Editing**
- E-18 Drag start past end (or end before start) → clamped to leave ≥ 1 sample; never inverted.
- E-19 Drag beyond session bounds → clamped to `[0, total]`.
- E-20 Tighten finds no signal-true sample → no Edit, inline message "no signal above threshold in range".
- E-21 Split at a point outside `(start, end)` → `planSplit` returns null; UI disabled.
- E-22 Split a segment carrying tags → each tag re-pointed per §5.2 rule; tag with null `tapOffsetMs` uses `tNs` itself as the matched moment.
- E-23 Merge non-adjacent segments → `planMerge` null; `M` disabled with tooltip.
- E-24 Merge where a segment between them exists → not adjacent (see E-23) — adjacency means consecutive in sorted order, regardless of gap.
- E-25 Delete a segment with tags → tags become unmatched (queue items appear), toast offers undo.
- E-26 Relabel via hotkey on an untagged segment → creates a POINT tag at `start + duration` with `tapOffsetMs = 0`, `provenance: 'created'`.
- E-27 Relabel hotkey with a multi-selection containing both segments and tags → selected tags are relabelled, untagged selected segments get new tags (E-26), tagged selected segments have their tags relabelled. **Rule**: a bulk action produces exactly one journal entry (one undo step). Ticket 09 adds `Edit.kind = 'Batch'` `{ edits: Edit[] }`; the reducer applies the inner edits sequentially; a `Batch` containing an unknown inner kind still applies the known ones.
- E-28 Undo after reload → journal + cursor restored, redo stack intact if cursor < length.
- E-29 Autosave collision (tab closed mid-write) → IDB transaction atomic; worst case last ≤ 300 ms of edits lost; `visibilitychange` flush mitigates.
- E-30 Custom label typed with leading/trailing spaces or different case from a known label → trimmed; case-insensitive match to known label wins.
- E-31 Range pair spanning a deleted segment → ranges are tag-only; unaffected.
- E-32 Two `RANGE_START`s in a row (fixture pattern possible) → first is `unpaired`, second pairs with next END.

**Queue**
- E-33 Item target deleted → item disappears; its resolution key stays in state harmlessly (garbage tolerated; not exported in `segments` map because target absent).
- E-34 Resolving the last item → completion state; queue rebuild after any Edit may add new items (e.g. `edited-unconfirmed`) — completion state re-evaluates.
- E-35 `queueIndex` beyond length after rebuild → clamped.
- E-36 Suggestion's target changed since build (stale Edit) → store validates `TagRematch` target exists; otherwise ignores and toasts "suggestion outdated".

**Simulation**
- E-37 Session shorter than calibration window → `calibEndIdx = n`, zero simulated segments, notice.
- E-38 Tunables producing thousands of segments (stdFactor 0.5) → diff list virtualised; adopt confirm shows counts in red if > 3× current.
- E-39 Adopt when a moved segment now overlaps another → allowed (detector can't produce overlaps, but manual segments can); report line "N overlapping segments".
- E-40 Worker busy when a new run is requested → previous run cancelled by `runId`; results with stale `runId` dropped.

**Export**
- E-41 Pass-through blob unchanged even if parser "fixed" CRLF/BOM → export uses the original `Blob`, never re-serialised text.
- E-42 Session name with characters invalid in filenames → sanitised for the zip folder, original kept in `review.json`.
- E-43 Verify fails → no download; dialog shows first 3 mismatches.
- E-44 Browser blocks download (mobile Safari) → fall back to `navigator.share` if available, else show "long-press to save" link.

**Layout / platform**
- E-45 Layout JSON from a different dockview version → `restore` false → preset.
- E-46 Panel hidden then window shrinks below breakpoint → mobile host ignores desktop visibility; all tabs available.
- E-47 Rotation mid-drag → drag cancelled, state unchanged.
- E-48 `file://` open: no service worker (unsupported) → app works, PWA features silently absent; fonts bundled as data URIs.
- E-49 IndexedDB unavailable (private mode) → `MemoryPersistence` fallback with persistent banner "edits won't survive reload".
- E-50 Storage quota exceeded on import → clear error, suggest deleting sessions; nothing half-written (single transaction).
- E-51 `prefers-reduced-motion` → all durations 0, map flyTo instant, waveform viewport jumps.
- E-52 Coarse pointer on a large tablet → desktop dock layout with touch adapter (both seams independent).

---

## 12. Test-surface register (the only places tests are written)

| Seam / module | Kind | Fixture | Sample test names |
|---|---|---|---|
| `worker/parse.parseSession` | unit | trimmed real + synthetic | `parses fixture sensor rows to exact count`, `resolves tag to segment by exact start_ns`, `leaves tag unmatched when link empty`, `refuses unknown schema_version with file and version`, `parses headers by name in any order`, `tolerates CRLF and BOM`, `reports gyro coverage 0 for fixture` |
| `worker/detector` | unit | trimmed real (`expected/calibration.json`, `expected/simulated.json`) + hand-traced synthetic | `calibration thresholds match config.json within 1e-3`, `simulated segment count equals recorded count`, `simulated starts equal recorded starts`, `turning suppresses open but not extension`, `end of data force-closes open segment`, `segment shorter than min duration is discarded`, `magnitude is deviation from 9.81` |
| `worker/dsp.recompute*` | unit | synthetic | `recomputed vertical equals recorded within 1e-2 on fixture`, `divergence list flags injected 20% error` |
| `worker/lod` | unit | synthetic | `level stride doubles`, `min/max envelope contains raw`, `pickLevel returns -1 under 2 spp` |
| `core/reducer` (`reduce`, `replay`) | unit | hand-built states | one test per rule in §5.2, plus `unknown edit kind is a no-op`, `replay upTo cursor equals sequential reduce` |
| `core/stats.segmentStats` | unit | hand-traced arrays | `peak is max abs(v-9.81)`, `mask excludes lull samples`, `empty range returns samples 0` |
| `core/matcher.suggestMatch` | unit | hand-built segments | mirrors `TagMatcherTest.kt` cases: `open segment wins regardless of lookback`, `most recent closed within lookback`, `none beyond lookback`, `tap offset computed from segment end` |
| `core/queue` | unit | hand-built states | `unmatched tag is blocking`, `high-M untagged ordered by peak desc`, `resolved items excluded`, `range problems classified`, `time mode orders by focus start` |
| `core/tighten`, `core/splitmerge` | unit | hand-traced threshold arrays | `tighten shrinks to first/last signal-true`, `tighten returns null with no signal`, `snap picks nearest rising edge for start`, `split repoints tags by matched moment`, `merge requires adjacency` |
| `core/diff` | unit | hand-built | `pairs by ≥50% overlap`, `boundary change of one sample is moved`, `unpaired classified added/removed` |
| `core/filter.compileFilter` | unit | — | grammar acceptance/rejection table, `M>=4 AND untagged selects expected ids` |
| `worker/export` | unit + integration | trimmed real | `segments csv round-trips bigint exactly`, `labels csv writes empty link for unmatched`, `pass-through entries hash-equal source`, `verify detects a corrupted entry`, `review.json includes full journal` |
| `interaction/machine` | unit | scripted gesture sequences | `drag on empty pans`, `shift-drag marquees`, `down on handle begins boundary drag`, `alt disables snap`, `longpress on handle begins drag (touch)`, `pinch zooms about centre` |
| `app/store` (with `InlineWorkerBridge` + `MemoryPersistence`) | integration | trimmed real | `import then reopen restores state`, `apply after undo truncates redo`, `autosave writes journal within 300ms`, `queue step sets viewport and selection`, `adopt simulation rematches tags` |
| `layout/LayoutHost` adapters | integration (happy-dom) | — | `serialize/restore round-trip`, `restore of garbage returns false`, `preset shows expected panels` |
| Full app | e2e (Playwright) | trimmed real | desktop: `import fixture → report → start review → J J 1 Enter → export downloads verified zip`; mobile: `pinch zoom, long-press drag handle, swipe confirm`; both: `reload keeps edits`, `light scheme applies`, `reduced motion disables transitions` |

Not tested (deliberately): canvas pixel output (visual check in verification
notes only), Leaflet internals, dockview internals, service-worker caching
(manual offline check in ticket 16's note).

---

## 13. Out of scope

- Syncing review state between devices (explicitly deferred by the user; the
  Journal design does not foreclose it).
- Learned review rules / auto-confirm heuristics (7f) — Aitken-maturity.
- Cross-session analytics, charts, corpus statistics — Colab.
- Any change to Aitken's Android code or session file formats.
- Schema-migration UI for future `schema_version` bumps (parser dispatch table
  is the hook; nothing more).
- Collaborative/multi-user review.
- Roughness (IRI/ISO 8608) modal from the prototype — dropped.

## 14. Further notes

- The prototype (`aitken-workbench.html`) is reference material for *behaviour
  seen*, not code to port. Specifically do **not** carry over: `abs(vertical)`
  magnitude, re-serialised raw CSV export, JSON-snapshot undo, index-based tag →
  segment linking, or mouse-only events.
- `Severity.kt` defaults (mild 5 / moderate 15) differ from the fixture's
  `config.json` (3 / 8); the Workbench always prefers the session's config.
- Vision's background is Python/Django; TypeScript idioms (discriminated
  unions, `bigint`, signals) should be explained inline in code comments where
  first used, matching the existing "pseudocode with inline teaching" habit.
