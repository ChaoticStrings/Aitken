#!/usr/bin/env python3
"""National-highway layer. Joins MoRTH contractor records to OSM highway geometry (pothole-reporter tiles, ODbL).
Usage: python3 scripts/build_highways.py /path/to/pothole-reporter /path/to/india-states.geojson   (pip: numpy shapely)
Locating a section is a falsifiable check, not a guess: both endpoint places must exist in the OSM gazetteer, sit within 3 km of the
highway, and be as far apart (straight line) as the contract's chainage length allows. Anything else stays 'unlocated'."""
import json, os, re, sys, glob, math, collections, pathlib
import numpy as np, shapely
from shapely.geometry import shape, MultiLineString
from shapely.strtree import STRtree
from shapely.ops import linemerge
REPO, STATES = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
ROOT = pathlib.Path(__file__).resolve().parent.parent            # Datasets/
WORK = pathlib.Path(os.environ.get("DATASETS_WORK") or ROOT / "work")   # git-ignored scratch: work/compiled, work/data
(WORK / "data").mkdir(parents=True, exist_ok=True); (WORK / "compiled").mkdir(parents=True, exist_ok=True)
slug = lambda s: re.sub(r"[^a-z0-9]", "", s.lower())
CODE = {"andamannicobarisland": "AN", "andhrapradesh": "AP", "arunanchalpradesh": "AR", "assam": "AS", "bihar": "BR", "chandigarh": "CH", "chhattisgarh": "CG", "dadaranagarhavelli": "DH",
        "damandiu": "DH", "goa": "GA", "gujarat": "GJ", "haryana": "HR", "himachalpradesh": "HP", "jammukashmir": "JK", "jharkhand": "JH", "karnataka": "KA", "kerala": "KL", "lakshadweep": "LD",
        "madhyapradesh": "MP", "maharashtra": "MH", "manipur": "MN", "meghalaya": "ML", "mizoram": "MZ", "nctofdelhi": "DL", "nagaland": "NL", "odisha": "OD", "puducherry": "PY", "punjab": "PB",
        "rajasthan": "RJ", "sikkim": "SK", "tamilnadu": "TN", "telangana": "TS", "tripura": "TR", "uttarpradesh": "UP", "uttarakhand": "UK", "westbengal": "WB"}
GAZ_CODE = {"TS": ["TG"], "LA": ["LA", "JK"], "JK": ["JK", "LA"]}   # old state outline merges Ladakh into J&K

# 1. decode tiles (deltas on an integer grid, scale 1e5), dedupe the 60 m tile buffers
seen, ways = set(), []
for f in sorted(glob.glob(str(REPO / "docs/packs/v1/highways/*.json"))):
    for ref, _, flat in json.load(open(f))["features"]:
        key = (ref, tuple(flat))
        if key in seen: continue
        seen.add(key); x, y = flat[0], flat[1]; pts = [(x / 1e5, y / 1e5)]
        for i in range(2, len(flat), 2): x += flat[i]; y += flat[i + 1]; pts.append((x / 1e5, y / 1e5))
        ways.append((ref.split(" / "), pts))
print("unique ways", len(ways))

# 2. assign each way to a state by its midpoint
sp = json.load(open(STATES)); polys, pcodes = [], []
for ft in sp["features"]:
    polys.append(shapely.simplify(shape(ft["geometry"]).buffer(0), 0.002, preserve_topology=True)); pcodes.append(CODE.get(slug(ft["properties"]["st_nm"])))
tree = STRtree(polys); mids = shapely.points(np.array([p[len(p) // 2] for _, p in ways]))
st = [None] * len(ways); hit = tree.query(mids, predicate="within")
for i, j in zip(*hit): st[i] = pcodes[j]
miss = [i for i, s in enumerate(st) if s is None]
for i, j in zip(miss, tree.nearest(mids[miss])): st[i] = pcodes[j]
byrs = collections.defaultdict(list)
for (refs, pts), s in zip(ways, st):
    for r in refs: byrs[(r, s)].append(pts)
byrs_la = lambda r, s: byrs.get((r, s), []) + (byrs.get((r, "LA"), []) if s == "JK" else []) + (byrs.get((r, "JK"), []) if s == "LA" else [])

# 3. gazetteer: OSM named places filed under the state holding them
gz = json.load(open(REPO / "data/wards/india-localities.json"))["states"]
place = collections.defaultdict(lambda: collections.defaultdict(list))
for code, d in gz.items():
    for n, x, y in zip(d["n"], d["x"], d["y"]): place[code][n.lower().strip()].append((x / 1e5, y / 1e5))
def lookup(name, s, V=None):
    """Same-state places, or (given the highway's vertices) places of ANY state inside its bounding box: sections often end just over a state line."""
    out = []
    for c in (place if V is not None else GAZ_CODE.get(s, [s])): out += place[c].get(name.lower(), [])
    if V is not None:
        x0, y0, x1, y1 = V[:, 0].min() - .4, V[:, 1].min() - .4, V[:, 0].max() + .4, V[:, 1].max() + .4
        out = [q for q in out if x0 <= q[0] <= x1 and y0 <= q[1] <= y1]
    return out

def clean(s):
    s = re.sub(r"\b(section|road|stretch|portion|bypass|village|town|city|junction|chowk)\b.*$", "", s.strip(" .,-"), flags=re.I).strip(" .,-")
    return None if (re.search(r"\d|\bkm\b|\bch\b", s, re.I) or not 3 <= len(s) <= 40) else s
def parse_places(t):
    """Loose on purpose: every 'A to B' in brackets and every dash chain 'A - B - C' gives an (A, C) candidate pair. Validation is strict and comes later."""
    pairs = []
    for m in re.finditer(r"\(([^()]{3,90}?)\s+(?:to|-|–)\s+([^()]{3,90}?)\)", t, re.I): pairs.append((m.group(1), m.group(2)))
    for m in re.finditer(r"((?:[A-Za-z][A-Za-z.']*\s?){1,3}(?:\s*[-–—]\s*(?:[A-Za-z][A-Za-z.']*\s?){1,3})+)", t):
        nodes = [x for x in re.split(r"\s*[-–—]\s*", m.group(1)) if x.strip()]
        if len(nodes) >= 2: pairs.append((nodes[0], nodes[-1]))
    return pairs[:8]
def grams(text, side):
    w = re.sub(r"\b(section|road|stretch|portion|bypass|village|town|city|junction|chowk)\b.*$", "", text.strip(" .,-"), flags=re.I).split()
    out = []
    for k in (3, 2, 1):
        if len(w) >= k:
            g = " ".join(w[-k:] if side == "L" else w[:k]).strip(" .,-")
            if len(g) >= 3 and not re.search(r"\d", g): out.append(g)
    return out

KX, KY = 111.32, 110.57
def km(a, b): return math.hypot((a[0] - b[0]) * KX * math.cos(math.radians((a[1] + b[1]) / 2)), (a[1] - b[1]) * KY)
def vertarr(wl): return np.array([p for w in wl for p in w])
def near(V, p): d = np.hypot((V[:, 0] - p[0]) * KX * math.cos(math.radians(p[1])), (V[:, 1] - p[1]) * KY); return float(d.min())

contracts = [c for c in json.load(open(REPO / "data/tenders-national-highways.json"))["contracts"] if c["contractor"]]
funnel = collections.Counter(); out = []; segs = []; groups = collections.defaultdict(list); cache = {}; nd = {}
def fin(cl, why): cl["locate"] = why; funnel[why] += 1; out.append(cl)
def nearest(key, V, p):
    k = (key, p)
    if k not in nd: nd[k] = near(V, p)
    return nd[k]
for c in contracts:
    s = c["state_code"]; cl = {"id": c["record_id"], "contractor": c["contractor"].strip(), "title": c["title"], "chainages": c["chainages"], "status": c["lifecycle_status"], "start": c["start_date"],
        "completion": c["likely_completion_date"], "division": c["division"], "url": c["source_url"], "ref": c["reference_value"]}
    refs = [r for r in c["highway_refs"] if byrs_la(r, s)]
    if not refs:
        elsewhere = [r for r in c["highway_refs"] if any(k[0] == r for k in byrs)]
        fin(cl, "highway_mapped_only_in_other_state" if elsewhere else ("no_highway_ref_in_record" if not c["highway_refs"] else "highway_ref_not_in_osm_tiles")); continue
    r = refs[0]; cl["highway"] = r; groups[(r, s)].append(cl)
    pairs = parse_places(c["title"])
    if not pairs: fin(cl, "no_place_names_parsed"); continue
    if (r, s) not in cache: cache[(r, s)] = vertarr(byrs_la(r, s))
    V = cache[(r, s)]; Cs = [abs(x["end_km"] - x["start_km"]) for x in c["chainages"] if x["end_km"] > x["start_km"]]
    best = None; far = False; gaz = False
    for lt, rt in pairs:
        for ga in grams(lt, "L"):
            for gb in grams(rt, "R"):
                A, B = lookup(ga, s, V), lookup(gb, s, V)
                if not A or not B: continue
                gaz = True
                for pa in A[:40]:
                    if nearest((r, s), V, pa) > 3: continue
                    for pb in B[:40]:
                        if nearest((r, s), V, pb) > 3: continue
                        far = True; ch = km(pa, pb)
                        if any(0.35 * k - 1 <= ch <= 1.1 * k + 1.5 for k in Cs):
                            sc = nearest((r, s), V, pa) + nearest((r, s), V, pb)
                            if best is None or sc < best[0]: best = (sc, pa, pb, ch, ga, gb)
    if best is None: fin(cl, "chord_inconsistent_with_chainage" if far else ("places_far_from_highway" if gaz else "places_not_in_gazetteer")); continue
    _, pa, pb, ch, ga, gb = best; sel = []
    dx = (pb[0] - pa[0]) * KX * math.cos(math.radians(pa[1])); dy = (pb[1] - pa[1]) * KY; L2 = dx * dx + dy * dy or 1e-9
    for w in byrs_la(r, s):
        m = w[len(w) // 2]; mx = (m[0] - pa[0]) * KX * math.cos(math.radians(pa[1])); my = (m[1] - pa[1]) * KY
        t = (mx * dx + my * dy) / L2; d = abs(mx * dy - my * dx) / math.sqrt(L2)
        if -0.03 <= t <= 1.03 and d <= max(2.0, 0.15 * ch): sel.append(w)
    if not sel: fin(cl, "chord_inconsistent_with_chainage"); continue
    cl["places"] = [ga, gb]; cl["chord_km"] = round(ch, 1); cl["chainage_km"] = round(max(Cs), 1); fin(cl, "located")
    segs.append((r, s, cl, sel))

def geom(wl, tol):
    g = shapely.simplify(linemerge(MultiLineString([w for w in wl if len(w) > 1])), tol)
    parts = list(g.geoms) if g.geom_type == "MultiLineString" else [g]
    return {"type": "MultiLineString", "coordinates": [[[round(x, 4), round(y, 4)] for x, y in p.coords] for p in parts]}
feat = []
for (r, s), cl in groups.items():
    feat.append({"type": "Feature", "properties": {"kind": "nh", "id": f"nh:{r}:{s}", "name": r, "state": s, "contracts": cl}, "geometry": geom(byrs_la(r, s), 0.0006)})
for r, s, cl, sel in segs:
    feat.append({"type": "Feature", "properties": {"kind": "nh-seg", "id": f"nhseg:{cl['id']}", "name": r, "state": s, "contracts": [cl]}, "geometry": geom(sel, 0.0003)})
(WORK / "data/nh.geojson").write_text(json.dumps({"type": "FeatureCollection", "features": feat}, ensure_ascii=False, separators=(",", ":")))
with open(WORK / "compiled/nh_contracts_located.jsonl", "w") as f:
    for cl in out: f.write(json.dumps(cl, ensure_ascii=False) + "\n")
by_state = collections.Counter(c["state_code"] for c in contracts); loc_state = collections.Counter(s for _, s, _, _ in segs)
m = json.load(open(WORK / "data/meta.json")); m["nh"] = {"named": len(contracts), "funnel": dict(funnel), "states": {k: [v, loc_state.get(k, 0)] for k, v in sorted(by_state.items())}}
(WORK / "data/meta.json").write_text(json.dumps(m))
print(json.dumps({"named_contracts": len(contracts), "funnel": dict(funnel), "highway_state_groups": len(groups), "located_segments": len(segs)}, indent=1))
