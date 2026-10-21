#!/usr/bin/env python3
"""OFFLINE build. Decodes real OSM-derived street tiles (ODbL) + public award records from a clone of
coding-parrot/pothole-reporter (MIT). Usage: python3 scripts/build_data.py /path/to/pothole-reporter"""
import json, os, struct, sys, re, gzip, pathlib, collections
REPO = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "../pothole-reporter")
ROOT = pathlib.Path(__file__).resolve().parent.parent            # Datasets/
WORK = pathlib.Path(os.environ.get("DATASETS_WORK") or ROOT / "work")   # git-ignored scratch: work/compiled, work/data
OUT = WORK / "data"; OUT.mkdir(parents=True, exist_ok=True)
FIELDS = ["neighbourhood","residential","quarter","hamlet","suburb","village","town","city","municipality","state","iso","postcode"]

def vi(b, i):
    v = s = 0
    while True:
        c = b[i]; i += 1; v |= (c & 127) << s; s += 7
        if not c & 128: return v, i
zz = lambda v: (v >> 1) ^ -(v & 1)

def read_tile(p):
    b = p.read_bytes(); assert b[:4] == b"PSA1"
    h = json.loads(b[8:8 + struct.unpack("<I", b[4:8])[0]]); S = h["sections"]
    strs = b[S["strings"][0]:S["strings"][0] + S["strings"][1]].decode().split("\n")
    i = S["tuples"][0]; tup = []
    for _ in range(h["counts"]["tuples"]):
        t = []
        for _ in FIELDS: v, i = vi(b, i); t.append(strs[v])
        tup.append(dict(zip(FIELDS, t)))
    ox, oy = h["origin"]; sc = h["scale"]; i = S["streets"][0]; out = []
    for _ in range(h["counts"]["streets"]):
        fl, i = vi(b, i); name = ref = ""
        if fl & 4: v, i = vi(b, i); name = strs[v]
        if fl & 8: v, i = vi(b, i); ref = strs[v]
        ti, i = vi(b, i)
        if fl & 1: _, i = vi(b, i); _, i = vi(b, i)
        n, i = vi(b, i); v, i = vi(b, i); x = zz(v); v, i = vi(b, i); y = zz(v); pts = [(x, y)]
        for _ in range(n - 1):
            v, i = vi(b, i); x += zz(v); v, i = vi(b, i); y += zz(v); pts.append((x, y))
        if name and not fl & 1:
            out.append((name, tup[ti], [[round(ox + px / sc, 5), round(oy + py / sc, 5)] for px, py in pts], bool(fl & 2)))
    return out

def roads(region, bbox):
    g = collections.OrderedDict()
    for p in sorted((REPO / "data/streets" / region).glob("t_*.bin")):
        gx, gy = map(int, p.stem[2:].split("_")); lng, lat = gx * .25 - 180, gy * .25 - 90
        if lng > bbox[2] or lng + .25 < bbox[0] or lat > bbox[3] or lat + .25 < bbox[1]: continue
        for name, t, line, minor in read_tile(p):
            if not (bbox[0] <= line[0][0] <= bbox[2] and bbox[1] <= line[0][1] <= bbox[3]): continue
            area = t["suburb"] or t["neighbourhood"] or t["residential"] or t["village"] or ""
            k = (name, area, t["city"] or t["town"] or "")
            f = g.setdefault(k, {"type": "Feature", "properties": {"name": name, "area": k[1], "city": k[2], "minor": minor}, "geometry": {"type": "MultiLineString", "coordinates": []}})
            f["geometry"]["coordinates"].append(line)
    for n, f in enumerate(g.values()): f["properties"]["id"] = f"{region}:{n}"
    return list(g.values())

GEN = set("road main cross street st rd the of and in layout nagar colony stage phase block sector ward no".split())
norm = lambda s: " ".join(re.sub(r"[^a-z0-9 ]", " ", s.lower()).split())

pune = roads("pune", (73.65, 18.38, 74.05, 18.72))
blr = roads("karnataka", (77.45, 12.85, 77.75, 13.15))
(WORK / "compiled").mkdir(parents=True, exist_ok=True); (WORK / "compiled" / "blr_roads_all.geojson").write_text(json.dumps({"type": "FeatureCollection", "features": blr}, separators=(",", ":")))
tenders = [t for t in json.load(open(REPO / "data/tenders-karnataka.json")) if t.get("c") and t.get("b") == "BLR"]
byname = collections.defaultdict(list)
for f in blr:
    n = norm(f["properties"]["name"])
    if len(set(n.split()) - GEN) >= 1 and len(n) >= 8: byname[n].append(f)
hits = collections.defaultdict(list)
for t in tenders:
    title = " " + norm(t["t"]) + " "
    for n, fs in byname.items():
        if f" {n} " in title:
            for f in fs:
                hits[f["properties"]["id"]].append({"tn": t["tn"], "title": t["t"], "contractor": t["c"].strip(), "date": t["d"], "office": t["loc"],
                    "basis": "OSM road name appears verbatim in tender title", "ambiguous": len(fs)})
cand = [f for f in blr if f["properties"]["id"] in hits]
bl = [f for f in cand if all(a["ambiguous"] == 1 for a in hits[f["properties"]["id"]])]  # falsifiability gate: a name shared by several roads proves nothing
for f in bl: f["properties"]["awards"] = hits[f["properties"]["id"]]
prof = collections.defaultdict(lambda: {"awards": 0, "roads": set(), "dates": []})
for f in bl:
    for a in f["properties"]["awards"]:
        p = prof[a["contractor"]]; p["awards"] += 1; p["roads"].add(f["properties"]["name"]); p["dates"].append(a["date"])
for k, p in prof.items(): p["roads"] = sorted(p["roads"])
g = json.load(open(REPO / "data/gepnic-road-notices/sources/in-mh-gepnic.json"))
pn = [n for n in g["notices"] if re.search(r"pune|pimpri|pmc", n.get("organisation_chain", ""), re.I)]
pm = json.load(gzip.open(REPO / "data/pmgsy-road-agreements/sources/in-mh-pmgsy-21.json.gz"))
meta = {"pune_roads": len(pune), "blr_roads_in_bbox": len(blr), "blr_tenders_with_contractor": len(tenders), "blr_roads_with_name_candidates": len(cand),
        "blr_unambiguous_roads": len(bl),
        "mh_gepnic_notices": len(g["notices"]), "mh_gepnic_pune_org": len(pn), "mh_gepnic_fields_have_contractor": any("contractor" in json.dumps(n).lower() for n in g["notices"]),
        "mh_pmgsy_agreements": len(pm["agreements"]), "mh_pmgsy_limits": pm["limitations"], "retrieved": {"gepnic": g["retrieved_at"], "pmgsy": pm["retrieved_at"]}}
w = lambda n, o: (OUT / n).write_text(json.dumps(o, ensure_ascii=False, separators=(",", ":")))
w("pune.geojson", {"type": "FeatureCollection", "features": pune}); w("blr.geojson", {"type": "FeatureCollection", "features": bl})
w("contractors.json", prof); w("meta.json", meta); print(json.dumps({k: v for k, v in meta.items() if k != "mh_pmgsy_limits"}, indent=1))
