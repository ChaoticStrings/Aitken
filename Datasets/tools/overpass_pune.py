#!/usr/bin/env python3
"""RUN ON YOUR OWN NETWORK. One polite Overpass request for named Pune roads, cached; replaces work/data/pune.geojson
with real OSM way IDs. Stdlib only. Re-run at most daily; never loop it."""
import json, os, sys, pathlib, urllib.request, urllib.parse, collections
ROOT = pathlib.Path(__file__).resolve().parent.parent            # Datasets/
WORK = pathlib.Path(os.environ.get("DATASETS_WORK") or ROOT / "work")   # git-ignored scratch: work/compiled, work/data
OUT = WORK / "data"; OUT.mkdir(parents=True, exist_ok=True); CACHE = OUT / "overpass_pune_raw.json"
CONTACT = os.environ.get("DATASETS_CONTACT")   # a monitored address or URL: the source register requires a real contact in the User-Agent
Q = '[out:json][timeout:120];(way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street)$"]["name"](18.38,73.65,18.72,74.05););out tags geom;'
if not CACHE.exists():
    if not CONTACT: sys.exit("Set DATASETS_CONTACT to a monitored address or URL: it goes in the User-Agent (see Datasets/README.md).")
    req = urllib.request.Request("https://overpass-api.de/api/interpreter", urllib.parse.urlencode({"data": Q}).encode(),
                                 {"User-Agent": f"aitken-datasets/0.1 (contact: {CONTACT})"})
    CACHE.write_bytes(urllib.request.urlopen(req, timeout=180).read())
g = collections.OrderedDict()
for w in json.loads(CACHE.read_text())["elements"]:
    t = w["tags"]; k = (t["name"], t.get("addr:suburb", ""))
    f = g.setdefault(k, {"type": "Feature", "properties": {"name": t["name"], "area": k[1], "city": "Pune", "minor": t["highway"] in ("residential", "living_street", "unclassified"),
        "id": f"osm:way/{w['id']}", "way_ids": [], "class": t["highway"]}, "geometry": {"type": "MultiLineString", "coordinates": []}})
    f["properties"]["way_ids"].append(w["id"]); f["geometry"]["coordinates"].append([[round(p["lon"], 5), round(p["lat"], 5)] for p in w["geometry"]])
(OUT / "pune.geojson").write_text(json.dumps({"type": "FeatureCollection", "features": list(g.values())}, separators=(",", ":")))
print(len(g), "road groups written")
