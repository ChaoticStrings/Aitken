> **Carried over from the Crater Feasibility project (compiled 2026-10-17), body unchanged.** Paths below refer to that project's layout:
> `scripts/` is now `Datasets/tools/`, `compiled/` is `Datasets/work/compiled/`, `public/data/` is `Datasets/work/data/`, and the standalone
> test page (`public/index.html`) is retired in favour of Atlas (`Prototype/workbench.html`), which loads the same data as packs made by
> `Datasets/tools/make_packs.py`. The run commands in sections 4 and 6 work with the new paths (`python3 Datasets/tools/<script>.py ...`); see `Datasets/README.md`.
> The ported scripts were re-run from the upstream repositories on 2026-10-20 and reproduce every output listed here byte-for-byte.

# Contractor-to-road data ledger (India)

Written 2026-10-17. Every source I tried, what happened, and what to do next. "Verified" = I saw the data myself in this session; "per repo receipt" = recorded by pothole-reporter's own fetch receipts, not re-checked by me.

## 1. Result so far

| Compiled source | Records with a named contractor | Distinct names (normalised) | Road link available |
|---|---|---|---|
| MoRTH Data Lake, NH projects under implementation (via pothole-reporter snapshot, 2026-10-07) | 1,122 of 2,193 | 762 | NH number + chainage text on 928; no segment geometry join yet |
| Bengaluru awarded road works, KPPP + eProc (Vonter/blr-tenders-bids, ODbL) | 16,715 | 2,929 | Tender title only: 80% name a ward, ~1.8% match one unique road |
| Rest of Karnataka (pothole-reporter KPPP snapshot) | 3 | 3 | None (snapshot is almost all Bengaluru) |
| **Total** | **17,840** | **3,683** | |

Bengaluru road matching: 35 roads (1,121 awards) became 308 roads (1,659 award links, 814 contractors) once the 16.7k dataset replaced the small one. 9,071 more name hits were dropped because the road name is shared by several roads (falsifiability gate). 13,353 of 16,715 titles name a ward, so ward-level joins are the next big yield.
Files: `compiled/contracts.jsonl`, `compiled/summary.json`, `public/data/blr.geojson`.

## 2. Findings that change the plan

1. **Maharashtra/Pune has no usable public contractor-to-road feed.** GePNIC notices (104 statewide, 5 from Pune bodies) and PMGSY agreements (1,224) carry no contractor field (per repo registry, checked in the files). In Aug 2026 Pune's own Mayor said the road department lacks complete information on roads built, spend and DLP repairs, so no authoritative internal registry exists to scrape either.
2. **Highways are the best national return.** One public source (MoRTH Data Lake) names contractors for 1,122 projects across 30+ states, with NH number and often chainage. Gap: chainage to geometry. OSM NH ways carry no chainage.
3. **Bengaluru is the proof that the pipeline works**, but only because KPPP publishes awardees anonymously.
4. **Naive name matching is not enough anywhere.** Titles describe layouts and wards, not OSM street names.

## 3. Source ledger

Tier 0 = already compiled. Tier 1 = best returns, do first. Tier 2 = moderate effort. Tier 3 = hardest.

| Tier | Source | Contractor field | Status |
|---|---|---|---|
| 0 | MoRTH Data Lake NH projects | Yes (1,122) | Compiled from snapshot. Refresh needs own network |
| 0 | Vonter/blr-tenders-bids (ODbL) | Yes (16,715 road works) | Compiled. Verified |
| 0 | Vonter/karnataka-tenders | No (tenders only, `;`-delimited CSV) | Checked, not useful for awardees |
| 0 | pothole-reporter GePNIC notices, 29 states, 6,185 notices | No | Kept as notice index for later award joins |
| 0 | pothole-reporter PMGSY MH agreements | No (limitations list says so) | Checked |
| 1 | KPPP statewide awarded works (anonymous API, 98,499 rows per repo receipt) | Yes | Not pulled: the repo kept only a road-scope subset with almost no awardees outside Bengaluru. Rerun the Vonter pipeline with a wider department scope on your network |
| 1 | NH ref to OSM geometry join (pothole-reporter NH tiles, 143,123 ways) | n/a | **Built 2026-10-17**, see section 6 |
| 1 | BBMP ward polygons (not in the repo, no KA ward pack) | n/a | Needed to use the 80% ward-naming titles. Look at DataMeet municipal spatial data |
| 2 | GePNIC "Result of Tenders / AOC" per state (Maharashtra, UP, WB, Kerala...) | Yes | Registry says conditional access with CAPTCHA on search. Not attempted: I will not solve or bypass CAPTCHAs |
| 2 | Bihar rural works DLP lists (PDFs naming contractor per road) | Yes (seen in a search snippet) | Not downloaded (sandbox cannot reach it). OCR needed, Hindi |
| 2 | PMRDA published list of 148 km roads under DLP (Dec 2024, press-reported) | Likely | Locate on pmrda.gov.in from your network |
| 2 | Parliament / Assembly Q&A PDFs (e.g. Goa reply listing NH package contractors) | Yes, ad hoc | Manual leads for NH gap filling |
| 2 | Pune Smart City RFPs (e.g. PSCDCL/Road/03/2018, Baner roads) | Winner not in RFP | Award letters needed |
| 3 | PMC (Pune) road DLP register | Unknown | RTI to PMC Road Dept; the Mayor's ordered report may surface it |
| 3 | BMC / MCGM road-wise contractors | Reported in press, no structured feed found | RTI or tender AOCs |
| 3 | PMGSY eMARG contractor per road | Access-controlled for registered stakeholders per its user manual | I will not register under a false role with the burner email |
| 3 | Delhi, Hyderabad, Chennai, Kolkata municipal feeds | Not researched | Pending |

Not found by me: any OCDS-style open contracting dataset for Indian road works. GitHub search surfaced scrapers but no further awardee datasets (CivicDataLab/Gepnic-Tender-Scraper is an empty repo; datameet/pmgsy-geosadak is 2.5 GB of road geometry with no contractors).

## 4. Run on your own network

    git clone https://github.com/Vonter/blr-tenders-bids && cd blr-tenders-bids   # follow its README (fetch.py, parse.py); widen the department scope in its config for other Karnataka bodies. I have not tested that setting.
    git clone https://github.com/coding-parrot/pothole-reporter                   # see docs/SOURCES.md for its refresh tools (MoRTH Data Lake, GePNIC, KPPP)
    python3 scripts/compile_contracts.py ../pothole-reporter ../blr-tenders-bids
    python3 scripts/match_blr.py

## 5. Ethics and limits

- Only public pages and anonymous endpoints. No CAPTCHA solving, no login circumvention, no registering under false roles. Polite rates, real contact in the User-Agent (the burner email is fine for that). The burner email has not been used.
- Many awardees are individuals or proprietors. Treat names as personal data: check DPDP Act 2023 exemptions with a lawyer before any public display.
- Everything here is a candidate: award_verified true only means the source says awarded; segment_verified is false everywhere. Public display still needs the multi-source match and archived source document from your Artemis rules.
- Licences: Vonter datasets ODbL (attribution + share-alike on derived databases), OSM ODbL, pothole-reporter MIT (code).

## 6. Highways layer (built 2026-10-17)

`scripts/build_highways.py` joins the 1,122 contractor-named MoRTH records to OSM highway geometry (pothole-reporter tiles, ODbL).
1. Each of 143,123 OSM highway ways is assigned to a state by its midpoint (state outlines: mraxays/india-states.geojson, MIT; it predates the Ladakh split, so Ladakh counts as J&K).
2. Contractor records are attached to the whole highway in that state: **403 highway-in-state lines** carry contractor cards. This is the honest default: the award is real per source, the segment is unverified.
3. A section is only drawn as "located" when a falsifiable check passes: both endpoint place names (parsed from the title) exist in the OSM gazetteer (305,906 places), each lies within 3 km of the highway, and their straight-line distance fits the chainage length (0.35x to 1.1x, with small slack). Result: **48 located**, median straight-line/chainage ratio 0.89.

Funnel for the 1,122 records: located 48; no place name in the title 385; place not in the gazetteer 439; places too far from the highway 66; straight line inconsistent with chainage 41; highway not mapped in OSM tiles 48; mapped only in another state 46; no highway number in the record 49.

Why the yield is low: most titles are maintenance contracts ("PBMC from Km x to Km y of NH-z") that name no place at all. Chainage alone cannot be placed on OSM because OSM highway ways carry no kilometre markers.

Ways to raise it: (a) NH kilometre-stone points from OSM (`highway=milestone`, `distance=*`) to place chainage directly; I have not checked how complete that tagging is; (b) fuzzy place matching (OSM spelling variants); (c) NHAI/MoRTH project GIS layers if any are published; (d) the 46 "other state" cases look like state-line clipping errors, fixable with better outlines.

Run: `git clone https://github.com/mraxays/india-states.geojson ../india-states && pip install numpy shapely && python3 scripts/build_highways.py ../pothole-reporter ../india-states/states_india.geojson`
Outputs: `public/data/nh.geojson` (2.9 MB), `compiled/nh_contracts_located.jsonl` (every record with its locate status).
