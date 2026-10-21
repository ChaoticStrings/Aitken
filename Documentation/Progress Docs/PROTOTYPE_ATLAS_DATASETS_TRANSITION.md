# Crater Feasibility → Atlas: transition — Verification

Date: 2026-10-20. Base: `a25bd60` (the commit that merged Atlas). Companion to `PROTOTYPE_ATLAS_VERIFICATION.md`
(Atlas itself) and `Datasets/README.md` (how to use what this added).

## Why

Crater Feasibility was a standalone test (a small map, five scripts, a ledger) that asked whether the later stages
(Luna → Aitken → Crater → Artemis) hit anything impossible. Its answer is in `Datasets/docs/DATA_LEDGER.md`: nothing
impossible, but contractor-to-road links are thin outside national highways and Bengaluru. Atlas now exists, so the
test map is redundant and the open job is to feed Atlas real contractor data. This change moves everything worth keeping
into the repo in the shape Atlas reads, and retires the rest.

## What moved

| From the feasibility zip | To | How it changed |
|---|---|---|
| `scripts/build_data.py`, `compile_contracts.py`, `match_blr.py`, `build_highways.py`, `overpass_pune.py` | `Datasets/tools/` | Paths only (`work/compiled`, `work/data`, `DATASETS_WORK`). `overpass_pune.py` now requires `DATASETS_CONTACT` instead of a `YOUR-EMAIL` placeholder. |
| `public/data/*.geojson`, `meta.json` (the test map's data) | Atlas packs, made by the new `Datasets/tools/make_packs.py` | Not committed: see "Decisions". |
| `docs/DATA_LEDGER.md` | `Datasets/docs/DATA_LEDGER.md` | Body unchanged; header maps old paths to new. |
| Open-access source register (Claude Doc, rev 6) | `Datasets/SOURCE_REGISTER.md` | Transcribed; an "Atlas status" table added on top. |
| `README.md` | `Datasets/README.md` | Rewritten for the Atlas flow; the old page's features are mapped to their Atlas equivalents there. |
| `public/index.html`, `vendor/leaflet.*` | nowhere | Retired: Atlas replaces the page. Delete the zip once you have run the converter. |
| `compiled/*` (16 MB of intermediates) | nowhere | Regenerable by the tools; ignored by git. |

## Changes to `Prototype/workbench.html` (the only edits to Atlas itself)

All additive. A pack without the new fields imports exactly as before, and a pack with them still loads in an older build
(the fields are ignored). 35 lines added, 17 changed.

1. **Provenance honoured.** `source.retrieved` and `source.notes` from a pack were silently discarded (retrieval was always
   "today", notes empty). Now stored, and notes are shown on the source card and under a road's Source. (`openSourceModal`,
   `showSourceCard`, `roadCard`.)
2. **`match: "unlocated"`.** A real award whose stretch is not located: whole road drawn purple, contractor shown as
   unconfirmed with its reason; counted separately on the source card. (`roadCard`, `showSourceCard`.)
3. **Several contractors per road.** Optional `awards` list per road, stored in `props_json.__awards` (no migration,
   `SCHEMA_VERSION` still 1). `contractor` remains the primary one, so colour, Index and focus keep working; new helpers
   `cleanAwards`, `awardsOf`, `contractorsOf`, `contrLabel` make Index rows, search, focus dimming, the focus chip, tooltips and
   the card see every contractor. The card lists awards (30 shown, 200 stored) and each row highlights that contractor.
   (`packToRoads`, `indexItems`, `buildSearchIndex`, `focusContractor`, `roadStyleOpts`, `roadTipHtml`, `roadCard`.)
4. The match note (`match_note`) is now shown on the road card; before it was stored and never displayed.

Atlas is edited directly here. `atlas-src/` and `build.py` (mentioned in the handoff) are not in the repository, so if you
still generate `workbench.html` from them, port these four items there or the next build overwrites them.

## Decisions made on your behalf (reversible, all in the converter)

| Question | Decision | Where to change it |
|---|---|---|
| Normalise contractor names before import? (handoff 6.4) | Yes, at the producer: case, M/s, punctuation, `&`, Pvt/Private, Ltd/Limited | `canon_key`, `Names` in `make_packs.py` |
| Individuals' names | Held by default (register's DPDP rule); `Person( FIRM )` shown as FIRM; `--keep-individuals` to keep | `FIRM_WORDS`, `Names.resolve` |
| Bengaluru match value | `unique` (blue) with a "candidate, unverified" note: Atlas's own rule for one unambiguous OSM match | `build_blr` |
| Highway sections | Located (48) `supplied`, blue; the other 403 lines `unlocated`, purple | `build_nh` |
| Many contractors on one road | One row, awards list in `props_json`; not one row per award (would copy 117k highway points 2.4×) | `awards`, Atlas item 3 |
| Commit the packs? | No. They name contractors, about two thirds of the Bengaluru awards go to names that do not look like firms, and the repo is public. `.gitignore` covers `Datasets/work`, `packs`, `raw` and `datasource_*.json`. | `.gitignore` |

## Verification

| Check | Result |
|---|---|
| Ported scripts re-run from freshly cloned upstream repos (pothole-reporter, Vonter/blr-tenders-bids, india-states) | `pune.geojson`, `blr_roads_all.geojson`, `contracts.jsonl`, `blr.geojson`, `contractors.json`, `nh.geojson`, `meta.json`, `nh_contracts_located.jsonl` are byte-identical to the feasibility zip's (`cmp`) |
| Converter unit tests (`Datasets/tests`, fictional fixtures) | 22 pass; deterministic; no resource warnings |
| Existing Atlas suites 01–07 against the edited `workbench.html` | 41 + 27 + 17 + 40 + 45 + 20 + 7 = 197 pass, the count the handoff reports |
| New suite `08-datasets.mjs` (runs the real converter, imports through the real dialog) | 40 pass |
| Real packs imported into Atlas and reconciled with `MANIFEST.json` (`check-packs.mjs`) | Default run: Bengaluru 308 roads / 569 awards / 247 contractors / 193 blue; highways 451 / 917 / 600 / 42 blue; Pune 3,217 purple. `--keep-individuals`: 308 and 48 blue, 1,659 and 1,027 awards = every link the feasibility counted. No console errors. |
| Bug found by suite 08 before it shipped | An award whose `contractor` was an object was stored as the text "[object Object]". Fixed in `cleanAwards` (only strings and numbers are accepted). |

Suite 01 launches a fresh browser per case and stalls in a one-CPU sandbox, with or without these edits (the unmodified file
stalls too); its 11 cases and 2 trailing blocks were run one per process: 36 + 5 = 41, all pass.

## Not done, and why

- **No scraping.** The register lists what to fetch next; nothing was fetched. Atlas status per rank is in `SOURCE_REGISTER.md`.
- **Nothing archived** (register rank 26). Needs your network. Pack notes say so.
- **Legal question (DPDP) is still open.** The hold policy is the conservative reading, not an answer.
- **Sidebar "N named"** counts records that name a contractor, not confirmed ones (highways: 415 beside 42 blue). Pre-existing
  semantics, left alone; header chip and colours are the confirmed count.
- **Roads outside any dataset still have no card.** The old page's Pune "no award record" empty state survives only as the Pune
  baseline pack.
- **Ward polygons** (rank 1's next gain), kilometre markers for chainage (ranks 21, 22), `atlas-src` reconciliation, and the
  open items in the Atlas handoff (section 8) are untouched.
