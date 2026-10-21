# Prototype Workbench: Atlas (multi-session map) — Verification

Scope: `Prototype/workbench.html` only (the single-file prototype, **not** the `workbench/`
TypeScript project). Review mode is untouched; Atlas is an additive second mode behind a
`REVIEW | ATLAS` switch in the existing toolbar.

## What was built

* **Multi-session load.** Select any number of session `.zip`s (or drop them on the map). Zips
  holding several session folders are split. The same ride is never loaded twice (identity is the
  GPS track, robust to float formatting); a Workbench "clean bundle" of a ride *replaces* its plain
  import.
* **One map, every ride.** Routes stack translucently (repeated roads read darker; A→B and B→A are
  distinguishable with *Heading* colouring). Events: shape = type, fill = severity grade
  (`WB.severityColor(M)`, the same function Review uses), filled = marked, hollow = unmarked.
  Zoomed out, events group into bubbles coloured by their 90th-percentile M.
* **Contractor sources** (separate module, section E of the script). GeoJSON, CSV/TSV (WKT or
  lat/lon columns), JSON arrays, and the `aitken-datasource` pack format. Field names are
  auto-mapped from an alias table and confirmed in a dialog. Purple = road is in a dataset; blue =
  contractor named *and* geometry trusted.
* **Cross-reference.** Clicking a dataset road shows the contractor, provenance, and *your* rides on
  it: passes, direction (with/against the road's drawn direction), events, worst M.
* **Sidebar.** Per-session and per-source checkboxes with click-drag multi-select and edge
  auto-scroll; marked/unmarked; multi-select type filters; min severity; layer toggles; route
  colouring; index (roads / contractors / sessions, A–Z); regions; data/DB tab; global search (`/`).
* **Persistence.** A real SQLite database (sql.js) in IndexedDB, optionally mirrored to a file the
  user chooses (File System Access API), plus a synchronous localStorage mirror for UI prefs.

## Decisions, and why

1. **`peak_m` has three meanings across generations.** V1.0 recorded raw `|vertical|` (≈ +9.81 of
   gravity); V1.1+ record deviation from gravity (commit 67bc71d). Confirmed on the repo's real V1.1
   fixture: the same segments re-derived as V1.0 read 18.23 / 25.25 / … vs 8.08 / 15.44 / … recorded
   (offset ≈ 9.8). Atlas therefore (a) detects the generation (`GENERATIONS` table), (b) for
   config-less bundles **probes the raw `sensor.csv`** to see which basis the recorded peaks match,
   and (c) by default puts V1.0 on the deviation basis (`peak − 9.81`) before the shared M-scale.
   Data tab → *Put V1.0 peaks on the deviation basis* switches this off, which reproduces Review
   exactly. **Open question for the owner:** `M_SCALE.FLOOR_MS2 = 10` was set when peaks were raw
   magnitudes (so ≈ gravity ⇒ M0); on the deviation basis, anything under 10 m/s² reads M0. That is
   the existing peak-vs-rms / M-scale calibration question, not something Atlas decides.
2. **Contractor names are claims.** A name only turns a road blue when the geometry came with the
   data, or a lookup matched exactly one road. A name that appears in separate parts of a region is
   `ambiguous`: drawn purple/dashed, contractor withheld (same bar as ARTEMIS-brainstorm).
3. **Everything derived is recomputed on load.** The DB stores raw rows plus extra/unknown columns;
   M, colours, filters, ranges and positions are derived. Changing the scale or detector never
   needs a data migration. Sessions hydrate lazily (only ticked ones).
4. **No bulk tile prefetch.** "Preloading" warms a one-tile ring and the next zoom level near the
   viewport, capped per call, because the OSM tile policy forbids bulk downloading.
5. **Theme is scoped to Atlas** (toolbar stays Review's dark). Basemap *auto* = OSM standard in
   light, CSS-filtered OSM in dark (keeps OSM hues), or CARTO dark.

## Extension points (forward-facing)

| To support… | Edit |
|---|---|
| a renamed / new CSV column | `TABLE_SPECS` alias (`"duration_ms*1e6"` scales units) |
| a new recorder generation | one entry in `GENERATIONS` (`match()`, `peakBasis`) |
| a newer detector / schema | warns above `MAX_KNOWN_DETECTOR` / `MAX_KNOWN_SCHEMA`; still loads |
| a new event type | one entry in `EVENT_TYPES` (id, colour, shape, label regex) |
| a new contractor dataset | `FIELD_ALIASES`, or ship an `aitken-datasource` pack |
| a new DB table/column | append to `MIGRATIONS`, bump `SCHEMA_VERSION` |

Pack format:

```json
{ "format": "aitken-datasource", "version": 1,
  "source": { "name": "…", "license": "…", "url": "https://…", "sample": false },
  "roads": [ { "id": "r1", "name": "…", "ref": "…", "contractor": "…",
               "geometry": { "type": "LineString", "coordinates": [[lon, lat], …] },
               "props": { "work": "…" } } ] }
```

Optional additions to the pack (2026-10-20, all ignored by older builds; details in `Datasets/README.md` and
`PROTOTYPE_ATLAS_DATASETS_TRANSITION.md`): `source.retrieved` and `source.notes`; road `match: "unlocated"`
(award is real, its stretch is not located, whole road drawn purple); road `awards: [{contractor, ref, work, year,
cost, status, office, note, as_published}]` for several contractors on one road (kept in `props_json.__awards`, so
`SCHEMA_VERSION` is unchanged).

DB tables: `sessions, gps, segments, tags, patches, sources, roads, regions, ui_state, meta`
(`PRAGMA user_version = 1`). Ordinary SQLite: open it with `sqlite3` or DB Browser.

## Verification

`Prototype/atlas-tests` (`npm install && npm test`): real Chrome, real mouse/keyboard events.
Suites 01–07 cover formats and generations, filters (compared with what is *drawn*), the drag
multi-select and auto-scroll, persistence across refresh and full browser restart, an independent
`sqlite3` integrity check, the on-disk database path, contractor import/resolve/render, search, and
Review-mode regression. Bugs the tests caught while building: list rows not re-synced after a
button-driven selection; imported DB prefs clobbered by the flush that follows an import; search
ranking a road above the contractor it mentions; a focus chip colliding with the stats HUD; dataset
roads hidden under ride routes; HUD counts one render behind.

## Not verified here (needs the live internet or a native dialog)

Real OSM/CARTO tile rendering and the dark filter on real tiles; a real Overpass server (logic is
tested against a mock, including unique / ambiguous / none); Nominatim place search; the native OS
file picker; Firefox/Safari (no File System Access API there: Export / Import fallback only).
Serve the page over `http://localhost` (as before), not `file://`: OSM tile servers require a
Referer.

## Housekeeping

`.gitignore` now ignores `*.sqlite*`, `*.db`, `aitken-atlas*`, raw ride zips/folders (trimmed test
fixtures stay trackable), `/.idea/`, `*.logcat`, `*.log`. Already-tracked files are not untracked by
ignore rules; to stop tracking the 2.5 MB logcat and the IDE settings:

```
git rm -r --cached Logcat .idea
git commit -m "Stop tracking Logcat and .idea"
```
