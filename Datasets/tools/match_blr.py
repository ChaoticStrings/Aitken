#!/usr/bin/env python3
"""Tender-title -> OSM road match for Bengaluru using compiled/contracts.jsonl. Gate: the road name must be unique in the bbox (one road, one name)."""
import json, os, re, pathlib, collections
ROOT = pathlib.Path(__file__).resolve().parent.parent            # Datasets/
WORK = pathlib.Path(os.environ.get("DATASETS_WORK") or ROOT / "work")   # git-ignored scratch: work/compiled, work/data
GEN = set("road main cross street st rd the of and in layout nagar colony stage phase block sector ward no".split())
norm = lambda s: " ".join(re.sub(r"[^a-z0-9 ]", " ", s.lower()).split())
roads = json.load(open(WORK / "compiled/blr_roads_all.geojson"))["features"]
names = collections.defaultdict(list)
for f in roads:
    n = norm(f["properties"]["name"])
    if len(set(n.split()) - GEN) >= 1 and len(n) >= 8: names[n].append(f)
uniq = {n: fs[0] for n, fs in names.items() if len(fs) == 1}
recs = [json.loads(l) for l in open(WORK / "compiled/contracts.jsonl")]
recs = [r for r in recs if r["source"] == "vonter-blr-tenders-bids"]
hits = collections.defaultdict(list); amb = 0; ward = 0; cand = set()
for r in recs:
    t = " " + norm(r["title"]) + " "
    if re.search(r"\bward\b", t): ward += 1
    for n in names:
        if f" {n} " in t:
            cand.update(f["properties"]["id"] for f in names[n])
            if n in uniq: hits[uniq[n]["properties"]["id"]].append({"tn": r["ref"], "title": r["title"], "contractor": r["contractor"], "date": r["date"] or "", "office": r["geo"].get("office") or r["authority"],
                "amount": r["amount"], "basis": "OSM road name (unique in area) appears verbatim in tender title", "ambiguous": 1})
            else: amb += 1
feat = []
for f in roads:
    a = hits.get(f["properties"]["id"])
    if a: f["properties"]["awards"] = a; feat.append(f)
prof = collections.defaultdict(lambda: {"awards": 0, "roads": set(), "dates": []})
for f in feat:
    for a in f["properties"]["awards"]:
        p = prof[a["contractor"]]; p["awards"] += 1; p["roads"].add(f["properties"]["name"]); p["dates"].append(a["date"])
for p in prof.values(): p["roads"] = sorted(p["roads"])
D = WORK / "data"; D.mkdir(parents=True, exist_ok=True); (D / "blr.geojson").write_text(json.dumps({"type": "FeatureCollection", "features": feat}, ensure_ascii=False, separators=(",", ":")))
(D / "contractors.json").write_text(json.dumps(prof, ensure_ascii=False, separators=(",", ":")))
m = json.load(open(D / "meta.json")); m.update({"blr_tenders_with_contractor": len(recs), "blr_roads_with_name_candidates": len(cand), "blr_unambiguous_roads": len(feat)}); (D / "meta.json").write_text(json.dumps(m))
print(json.dumps({"award_records": len(recs), "titles_naming_a_ward": ward, "ambiguous_name_hits_dropped": amb, "unique_roads_matched": len(feat), "award_links": sum(len(f['properties']['awards']) for f in feat), "contractors_on_matched_roads": len(prof)}, indent=1))
