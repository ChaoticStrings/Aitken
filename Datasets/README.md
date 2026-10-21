# Datasets

Where contractor and road data is prepared for **Atlas** (`Prototype/workbench.html`, Atlas mode). This replaces the standalone Crater Feasibility test map, which is retired: its data, scripts and findings live here, and Atlas is the only viewer.

```
upstream repos (outside this repo) ──► tools/*.py ──► work/ (git-ignored) ──► tools/make_packs.py ──► packs/ (git-ignored) ──► Atlas
 pothole-reporter, Vonter/blr-…,         build_data, compile_contracts,       compiled/ + data/                          datasource_*.json     drop on the map
 india-states.geojson                    match_blr, build_highways
```

| Path | What |
|---|---|
| `tools/make_packs.py` | Feasibility output → Atlas `aitken-datasource` packs (+ `MANIFEST.json`). Stdlib only. |
| `tools/build_data.py`, `compile_contracts.py`, `match_blr.py`, `build_highways.py`, `overpass_pune.py` | The feasibility pipeline, moved here with paths changed and nothing else. Re-run on 2026-10-20 from the upstream repos, they reproduce all eight feasibility outputs byte-for-byte (`pune.geojson`, `blr_roads_all.geojson`, `contracts.jsonl`, `blr.geojson`, `contractors.json`, `nh.geojson`, `meta.json`, `nh_contracts_located.jsonl`). |
| `SOURCE_REGISTER.md` | Snapshot of the living "Open-access source register" Doc: every source to be integrated next, ranked, with licence and status, plus an "Atlas status" table. |
| `docs/DATA_LEDGER.md` | Findings, funnel numbers and run instructions from the feasibility work (body unchanged). |
| `tests/` | Converter tests and fictional fixtures. Atlas-side coverage is `Prototype/atlas-tests/08-datasets.mjs`. |
| `work/`, `packs/`, `raw/` | Git-ignored. Intermediates, generated packs, anything downloaded. |

## Quick start

**From the Crater Feasibility zip** (no downloading, a second):

```bash
unzip crater-feasibility.zip -d ~/crater-feasibility
python3 Datasets/tools/make_packs.py --feasibility ~/crater-feasibility --out ~/atlas-packs
```

Open `Prototype/workbench.html`, switch to **Atlas**, and drop the three `datasource_*.json` files on the map (or **+ Add source**). Each opens the mapping dialog once: press **Import**. Re-importing a regenerated pack replaces the old copy (a source is identified by pack name + file name).

What you should see (default run; `MANIFEST.json` lists the same numbers):

| Pack | Roads | Blue (named, confirmed) | Awards listed | Contractors |
|---|---|---|---|---|
| `datasource_blr_awards.json` | 308 | 193 | 569 | 247 |
| `datasource_nh_morth.json` | 451 (403 whole-highway lines, 48 located sections) | 42 | 917 | 600 |
| `datasource_pune_osm_roads.json` | 3,217 | 0 | 0 | 0 |

With `--keep-individuals`: 308 and 48 blue, 1,659 and 1,027 awards: every award link the feasibility project counted.

**Rebuilding from the upstream repositories** (what the feasibility README did; needs `pip install pandas pyarrow numpy shapely`):

```bash
git clone --depth 1 https://github.com/coding-parrot/pothole-reporter  ~/up/pothole-reporter
git clone --depth 1 https://github.com/Vonter/blr-tenders-bids          ~/up/blr-tenders-bids
git clone --depth 1 https://github.com/mraxays/india-states.geojson    ~/up/india-states
cd Datasets/tools
python3 build_data.py ~/up/pothole-reporter
python3 compile_contracts.py ~/up/pothole-reporter ~/up/blr-tenders-bids
python3 match_blr.py
python3 build_highways.py ~/up/pothole-reporter ~/up/india-states/states_india.geojson
python3 make_packs.py                      # reads Datasets/work/data, writes Datasets/packs
```

`DATASETS_WORK=/some/dir` moves `work/` elsewhere. `overpass_pune.py` (real OSM way ids for Pune, own network only) needs `DATASETS_CONTACT` set to a monitored address: it goes in the User-Agent, as the register requires.

## Rules

- **Raw downloads and generated packs stay out of git.** `.gitignore` covers `work/`, `packs/`, `raw/`. A pack names contractors, two thirds of the Bengaluru awards (1,090 of 1,659) go to names that do not look like firms, and this repository is public: the DPDP Act 2023 question in the register is unanswered, and nothing here may be published naming a company until a falsifiable, archived match exists (Artemis constraint). Only the tools, the docs and fictional fixtures are committed.
- **Individuals are held by default.** A name with no firm marker (constructions, infra, enterprises, Pvt, Ltd, JV, ...) is left out of the pack and counted in the road's `awards held`; `Person( FIRM )` is shown as FIRM. The test is a keyword heuristic, so it over-holds firms it does not recognise and never the reverse. `--keep-individuals` turns it off: for local analysis, never for a pack that leaves your machine.
- **Licences travel with the pack** (`source.license`): Vonter data and OpenStreetMap are ODbL, share-alike on derived databases, attribution required. The MoRTH terms have not been read.
- **Candidates are not facts.** Bengaluru matches mean "the OSM road name is unique in the extract and appears verbatim in the tender title". The award is real; the road link is not verified. The pack notes say so, and the card shows it.
- **Nothing is archived yet** (register rank 26). Archive a source before any figure citing it is shown publicly.

## Pack format (`aitken-datasource`, version 1)

Version 1 as Atlas always read it, plus optional fields that older Atlas builds simply ignore.

| Where | Field | Meaning |
|---|---|---|
| `source` | `name`, `license`, `url`, `sample` | As before. `url` must be http(s) or no link is shown. |
| `source` | `retrieved` *(new)* | Date of the data, kept as given (≤ 40 chars). Without it Atlas shows the import date. |
| `source` | `notes` *(new)* | Caveats, shown on the source card and under the road's Source (≤ 600 chars). |
| road | `id`, `name`, `ref`, `geometry` (Line/MultiLineString), `props` | As before. `props` values should be strings; keys starting `__` are dropped. |
| road | `contractor` | The primary contractor: drives colour, Index and focus. Defaults to the first award's. |
| road | `match` | `supplied` (geometry came with the data) or `unique` (matched to one OSM road): blue when a contractor is named. `unlocated` *(new)*: the award is real but its stretch is not located, so the whole road is drawn: purple, contractor shown as unconfirmed. |
| road | `match_note` | Shown on the card under the contractor. |
| road | `awards` *(new)* | List of `{contractor, ref, work, year, cost, status, office, note, as_published}`, all text (numbers are converted; objects and unknown keys are dropped). At most 200 per road. Every contractor listed is indexed, searchable and focusable; the card lists up to 30. Stored in `props_json.__awards`, so no schema migration. |

## What the converter decides (handoff section 6.4)

- **One row per road, many awards**, not one row per award: a Bengaluru road has up to 112 awards and a highway-in-state line up to 23, and one row per award would copy the 117,000 highway points 2.4 times over.
- **Names are normalised at the producer**, not in Atlas: case, `M/s`, punctuation, `&` / `and`, `Pvt`/`Private`, `Ltd`/`Limited`. The most common published spelling is the display name; each award keeps `as_published` when it differs. Distinct joint ventures stay distinct. On the real data, 54 firms turn up under more than one spelling (111 spellings in all) and become one Index row each.
- **Match values follow Atlas's own rule** (section 2 of the Atlas verification doc): a name earns blue only through supplied geometry or one unambiguous OSM match. Bengaluru is `unique`; located highway sections are `supplied`; the other 403 highway lines are `unlocated`.
- **Region** is auto-detected by Atlas from the geometry (Pune, Bengaluru); the national highways source has none.

## Where the old test map's features went

| Crater Feasibility page | Atlas |
|---|---|
| Pune / Bengaluru / Highways tabs | Three sources in the sidebar, each toggled on or off; region chips (Pune, Bengaluru, India) |
| Tap a road → contractor card | Same, on the road card: contractor, awards with tender ref, date, amount, office, work text, and the match basis |
| "CANDIDATE – not verified" tag | `match_note` on the card, source `notes` under Source |
| "section located" tag with end places and km check | The located-section card (`supplied`, blue) lists the end places and straight-line vs chainage |
| Highway line "unverified segment" | `unlocated`: purple, "Unconfirmed: the award is real per the source, but its stretch is not located" |
| Contractor profile by name | Index → Contractors, search, and the focus button on every award row |
| "No public award record for this road" empty state (Pune) | A Pune baseline pack: the card says "This road is in the dataset, but the record names nobody". Atlas has no card for roads outside any dataset. |
| "Feasibility evidence" counts (GePNIC notices, PMGSY agreements) | Not carried: they are findings, kept in the ledger and register (rank 23), not map data |
| `npx serve public` and the vendored Leaflet | Not needed: Atlas is one HTML file |

## Adding a source

1. Find it in `SOURCE_REGISTER.md`; respect its handling rules (robots.txt, terms, rate limit, real User-Agent contact, no CAPTCHA, no login).
2. Write a tool in `tools/` that reads the raw file from outside the repo, drops contact fields, and writes `work/data/<name>.geojson` in the shape `make_packs.py` reads, or emits a pack directly. Keep geometry honest: if it did not come with the data, say `unique` or `unlocated`, not `supplied`.
3. Add a builder and a `SOURCES` entry (licence, retrieval date, notes) to `make_packs.py`, a fictional fixture and a test.
4. Update the "Atlas status" table in `SOURCE_REGISTER.md`, and the Doc it was copied from.

## Tests

```bash
python3 -m unittest discover -s Datasets/tests -v        # converter: names, hold policy, match values, determinism (22 tests)
cd Prototype/atlas-tests && npm test -- 08                # Atlas: runs the converter, imports the packs, checks colour, Index, cards, search, persistence (40 checks)
```

## Known limits

- The sidebar's "N named" counts records that name a contractor (stored at import). Header chip and line colour count confirmed ones. For the highways that reads "415 named" beside "42" blue.
- Some Bengaluru "road works" are not road works (water-line contracts name a road in their title): the work text is on the card so this is visible.
- The Pune baseline paints 3,217 roads purple. Untick that source when it is in the way.
- 403 highway lines show a whole highway for contracts that cover tens of kilometres. That is the honest reading of the data, not a bug; locating them needs kilometre markers (register ranks 21 and 22).
- `FIRM_WORDS` will miss firms with unusual wording and hold them; extend it when you see one.
