#!/usr/bin/env python3
"""Turn the compiled feasibility data into Atlas data-source packs (Prototype/workbench.html, Atlas mode).

    python3 Datasets/tools/make_packs.py                       # reads Datasets/work/data, writes Datasets/packs
    python3 Datasets/tools/make_packs.py --feasibility ~/crater-feasibility --out ~/atlas-packs

Input  : pune.geojson, blr.geojson, nh.geojson, meta.json (what scripts/build_data.py, match_blr.py and
         build_highways.py write; the Crater Feasibility zip ships them in public/data).
Output : one `aitken-datasource` pack per source, plus MANIFEST.json. Drop the packs onto Atlas.

Policy decisions made here, on purpose (see Datasets/README.md):
  * Contractor names are normalised (case, M/s, punctuation, Pvt/Private, Ltd/Limited) so one firm is one row in
    Atlas's Index. The most common published spelling is the display name; the others are kept per award.
  * Names that do not look like a firm are HELD by default (contractor left empty, counted in the road's props):
    the source register says individuals' names stay out of anything shareable until counsel has looked at the
    DPDP Act question. A name of the form "Person( FIRM )" is shown as FIRM. --keep-individuals turns this off.
    The test is a keyword heuristic (FIRM_WORDS): it over-holds firms it does not recognise, never the reverse.
  * Match values follow Atlas's own rules: a road whose name is unique in OSM is `unique` (blue when a contractor is
    named); a located highway section is `supplied`; a contractor attached to a whole highway whose stretch is not
    located is `unlocated` (purple, the contractor shown as unconfirmed).
Stdlib only.
"""
import argparse, collections, json, os, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent            # Datasets/
WORK = pathlib.Path(os.environ.get("DATASETS_WORK") or ROOT / "work")

FIRM_WORDS = re.compile(
    r"\b(constructions?|constr\w*|const|infra\w*|builders?|developers?|enterprises?|associates?|engineers?|engineering|"
    r"contractors?|projects?|pvt|private|ltd|limited|llp|company|co|corp\w*|industries|industry|trading|traders|works|"
    r"agency|agencies|group|consortium|jv|technologies|technology|solutions|services|sons|bros|brothers|ventures?|logistics|"
    r"realty|properties|concrete|asphalt|roadways|civil|electricals?|tech|systems|marketing|international|buildcon|earthmovers?|"
    r"jointventure|joint venture)\b", re.I)
PROPRIETOR = re.compile(r"^(?P<person>[^()]+?)\s*\(\s*(?P<firm>[^()]+?)\s*\)\s*$")
MATCHES = {"supplied", "unique", "unlocated"}
AWARD_KEYS = ("contractor", "ref", "work", "year", "cost", "status", "office", "note", "as_published")

# Retrieval dates and licence text come from docs/DATA_LEDGER.md and the Crater Feasibility README.
SOURCES = {
    "pune": dict(
        file="datasource_pune_osm_roads.json", name="Pune named roads (OSM baseline, no contractor data)",
        retrieved="2026-10-06", url="https://www.openstreetmap.org/copyright",
        license="Geometry: OpenStreetMap contributors, ODbL 1.0 (Geofabrik India extract 2026-10-06, via the coding-parrot/pothole-reporter tiles). "
                "Share-alike applies to derived databases.",
        notes="Baseline so a tapped Pune road shows the honest empty state: no public feed checked names a contractor for Pune roads "
              "(see Datasets/docs/DATA_LEDGER.md, finding 1)."),
    "blr": dict(
        file="datasource_blr_awards.json", name="Bengaluru awarded road works (KPPP/eProc via Vonter), candidate road matches",
        retrieved="2026-10-17", url="https://github.com/Vonter/blr-tenders-bids",
        license="Awards: Vonter/blr-tenders-bids from KPPP and eProc, ODbL 1.0. Geometry: OpenStreetMap contributors, ODbL 1.0. "
                "Share-alike applies to derived databases.",
        notes="Candidate matches: the OSM road name is unique in the Bengaluru extract and appears verbatim in an awarded tender title. "
              "The award is real per the source; the road link is NOT verified and no source document is archived yet. Compiled 2026-10-17; the upstream snapshot date was not recorded."),
    "nh": dict(
        file="datasource_nh_morth.json", name="National highways: MoRTH Data Lake contractor records",
        retrieved="2026-10-07", url=None,   # taken from the data (every record carries its source_url)
        license="MoRTH Data Lake projects-under-implementation listing, snapshot 2026-10-07 via coding-parrot/pothole-reporter (government open-data terms not read). "
                "Geometry: OpenStreetMap contributors, ODbL 1.0.",
        notes="Each award is real per the source. Only sections whose end places and chainage pass the falsifiable check are drawn as their own blue line; "
              "every other contractor is attached to the whole highway in that state (purple): the stretch is unverified. No source document is archived yet."),
}


# ----------------------------------------------------------------------------------------------- names
def canon_key(s):
    """Spelling-insensitive identity of a firm name."""
    s = s.lower()
    s = re.sub(r"\b(m/s\.?|messrs)\b", " ", s)
    s = re.sub(r"^\s*ms\.?\s+", " ", s)                    # a leading "Ms" is how M/s is often typed
    s = s.replace("&", " and ")
    s = re.sub(r"\(\s*p\s*\)", " pvt ", s)                 # (P) Ltd
    s = re.sub(r"[^\w ]", " ", s)
    s = re.sub(r"\bprivate\b", "pvt", s)
    s = re.sub(r"\blimited\b", "ltd", s)
    return " ".join(s.split())


def tidy(s):
    s = re.sub(r"^\s*(m/s\.?|messrs)\s+", "", s.strip(), flags=re.I)
    return re.sub(r"\s+", " ", s).strip(" .,;")


class Names:
    """Resolves raw published names to a display name + kind. Needs one pass over all raw names first (to pick the most common spelling)."""
    def __init__(self, raw_names, keep_individuals):
        self.keep = keep_individuals
        groups = collections.defaultdict(collections.Counter)
        for r in raw_names:
            if r and r.strip():
                shown = self.firm_part(r) or r.strip()          # a proprietor is registered as its firm: the person's name never becomes a display name
                groups[canon_key(shown)][shown] += 1
        self.display = {k: tidy(max(c.items(), key=lambda kv: (kv[1], -len(kv[0])))[0]) for k, c in groups.items()}

    @staticmethod
    def firm_part(raw):
        """The firm in "Person( FIRM )", else None. "(JV)" or "(P)" is a suffix, not a firm, and a person part that itself contains a firm word is a firm."""
        m = PROPRIETOR.match(raw.strip())
        if not m or FIRM_WORDS.search(m.group("person")) or not FIRM_WORDS.search(m.group("firm")) or len(m.group("firm").split()) < 2: return None
        return m.group("firm")

    def resolve(self, raw):
        """-> (display, kind, held). kind: firm | proprietor | person-or-unclassified | none"""
        raw = (raw or "").strip()
        if not raw: return "", "none", False
        firm = self.firm_part(raw)
        if firm:                                              # "Person( FIRM )": the firm is the contractor (the person survives only in as_published, and only with --keep-individuals)
            return self.display[canon_key(firm)], "proprietor", False
        if FIRM_WORDS.search(raw):
            return self.display[canon_key(raw)], "firm", False
        if self.keep: return self.display[canon_key(raw)], "person-or-unclassified", False
        return "", "person-or-unclassified", True


# ----------------------------------------------------------------------------------------------- helpers
def clip(v, n=240):
    v = "" if v is None else str(v).strip()
    return v if len(v) <= n else v[:n - 1].rstrip() + "…"


def award(contractor, raw, kind, keep, **kw):
    a = {"contractor": contractor}
    for k, v in kw.items():
        v = clip(v, 300 if k == "work" else 240)
        if v: a[k] = v
    # keep the published spelling only when it is a firm name that differs (never a held person's name)
    if raw and raw.strip() != contractor and (keep or kind == "firm"): a["as_published"] = clip(raw)
    return {k: a[k] for k in AWARD_KEYS if k in a}


def lines(g):
    """MultiLineString without one-point parts (Atlas drops them as well); None if nothing is left."""
    parts = [l for l in g["coordinates"] if len(l) >= 2]
    return {"type": "MultiLineString", "coordinates": parts} if parts else None


def inr(x):
    return "" if x is None else f"INR {round(x):,}"


def pack(src, roads, url=None):
    s = {k: src[k] for k in ("name", "license", "retrieved", "notes")}
    u = src.get("url") or url
    if u: s["url"] = u
    s["sample"] = False
    return {"format": "aitken-datasource", "version": 1, "source": s, "roads": roads}


def primary(awards):
    c = collections.Counter(a["contractor"] for a in awards if a.get("contractor"))
    return min(c, key=lambda k: (-c[k], k)) if c else ""


# ----------------------------------------------------------------------------------------------- builders
def build_pune(features):
    roads = []
    for f in features:
        p = f["properties"]
        props = {k: clip(v) for k, v in (("area", p.get("area")), ("city", p.get("city")), ("minor road", "yes" if p.get("minor") else "")) if v}
        roads.append({"id": p["id"], "name": p["name"], "contractor": "", "match": "supplied",
                      "match_note": "geometry from the OSM-derived tiles; no contractor data exists for this road", "geometry": lines(f["geometry"]), "props": props})
    return roads


def build_blr(features, names, keep):
    roads, held_total, award_total = [], 0, 0
    for f in features:
        p = f["properties"]; aw, held = [], 0
        recs = sorted(sorted(p["awards"], key=lambda a: a.get("tn") or ""), key=lambda a: a.get("date") or "", reverse=True)   # newest first
        for a in recs:
            name, kind, is_held = names.resolve(a["contractor"])
            award_total += 1
            if is_held: held += 1; continue
            aw.append(award(name, a["contractor"], kind, keep, ref=a.get("tn"), work=a.get("title"), year=a.get("date"), cost=inr(a.get("amount")), office=a.get("office")))
        held_total += held
        props = {k: clip(v) for k, v in (("area", p.get("area")), ("city", p.get("city"))) if v}
        props["awards in source"] = str(len(p["awards"]))
        if held: props["awards held"] = f"{held} to names not recognised as firms (individuals): held pending legal review"
        roads.append({"id": p["id"], "name": p["name"], "contractor": primary(aw), "match": "unique",
                      "match_note": "candidate: OSM road name unique in the Bengaluru extract and verbatim in the tender title; award real per source, road link unverified",
                      "geometry": lines(f["geometry"]), "props": props, "awards": aw})
    return roads, award_total, held_total


def chain_text(ch):
    return ", ".join(f"km {x['start_km']:g}-{x['end_km']:g}" for x in (ch or [])) or "chainage not given"


def nh_award(c, names, keep, note):
    name, kind, is_held = names.resolve(c["contractor"])
    if is_held: return None
    return award(name, c["contractor"], kind, keep, ref=c.get("ref"), work=c.get("title"), year=c.get("start"), status=c.get("status"), office=c.get("division"), note=note)


def build_nh(features, names, keep):
    roads, held_total, award_total = [], 0, 0
    for f in features:
        p = f["properties"]; cs = p["contracts"]
        if p["kind"] == "nh":
            aw, held = [], 0
            for c in cs:
                award_total += 1
                why = "section located (see its own line)" if c.get("locate") == "located" else "section not located: " + str(c.get("locate", "")).replace("_", " ")
                a = nh_award(c, names, keep, f"{chain_text(c.get('chainages'))}; {why}")
                if a: aw.append(a)
                else: held += 1
            held_total += held
            props = {"highway": p["name"], "state": p["state"], "contracts on this highway in this state": str(len(cs))}
            if held: props["awards held"] = f"{held} to names not recognised as firms (individuals): held pending legal review"
            roads.append({"id": p["id"], "name": f"{p['name']} · {p['state']}", "ref": p["name"], "contractor": primary(aw), "match": "unlocated",
                          "match_note": "award real per source; its stretch is not located, so the whole highway in this state is drawn",
                          "geometry": lines(f["geometry"]), "props": props, "awards": aw})
        else:   # nh-seg: a located section
            c = cs[0]
            a = nh_award(c, names, keep, f"{chain_text(c.get('chainages'))}; section located between {' and '.join(c.get('places', []))}: "
                                          f"{c.get('chord_km')} km straight line vs {c.get('chainage_km')} km chainage")
            props = {"highway": p["name"], "state": p["state"], "end places": " to ".join(c.get("places", [])),
                     "straight line vs chainage (km)": f"{c.get('chord_km')} vs {c.get('chainage_km')}"}
            if not a: props["awards held"] = "1 to a name not recognised as a firm (individual): held pending legal review"
            roads.append({"id": p["id"], "name": f"{p['name']} · {p['state']} · located section", "ref": p["name"], "contractor": a["contractor"] if a else "",
                          "match": "supplied", "match_note": "located by the end-places-and-chainage check (DATA_LEDGER section 6); approximate",
                          "geometry": lines(f["geometry"]), "props": props, "awards": [a] if a else []})
    return roads, award_total, held_total


# ----------------------------------------------------------------------------------------------- checks + main
def validate(pk):
    """Raise AssertionError if a pack would not import cleanly into Atlas."""
    assert pk["format"] == "aitken-datasource" and pk["version"] == 1
    ids = set()
    for r in pk["roads"]:
        assert r["id"] not in ids, f"duplicate road id {r['id']}"; ids.add(r["id"])
        assert r["match"] in MATCHES, r["match"]
        g = r["geometry"]; assert g["type"] in ("LineString", "MultiLineString")
        for line in (g["coordinates"] if g["type"] == "MultiLineString" else [g["coordinates"]]):
            assert len(line) >= 2
            for lon, lat in line: assert 60 < lon < 100 and 5 < lat < 40, (r["id"], lon, lat)
        assert all(isinstance(v, str) for v in r["props"].values()), r["id"]
        for a in r.get("awards", []):
            assert set(a) <= set(AWARD_KEYS) and a.get("contractor"), a
            assert all(isinstance(v, str) for v in a.values())
        if r["contractor"]: assert not r.get("awards") or r["contractor"] in {a["contractor"] for a in r["awards"]}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--data", help="folder with pune/blr/nh .geojson + meta.json (default: <work>/data)")
    ap.add_argument("--feasibility", help="an unzipped Crater Feasibility folder; reads its public/data")
    ap.add_argument("--out", default=str(ROOT / "packs"), help="output folder (default: Datasets/packs, git-ignored)")
    ap.add_argument("--only", default="pune,blr,nh", help="comma list of: pune, blr, nh")
    ap.add_argument("--quiet", action="store_true", help="no per-pack summary lines")
    ap.add_argument("--keep-individuals", action="store_true", help="keep names that are not recognised as firms (local analysis only; never share the packs)")
    a = ap.parse_args(argv)
    data = pathlib.Path(a.data or (pathlib.Path(a.feasibility) / "public" / "data" if a.feasibility else WORK / "data"))
    out = pathlib.Path(a.out); out.mkdir(parents=True, exist_ok=True)
    want = [x.strip() for x in a.only.split(",") if x.strip()]
    load = lambda n: json.loads((data / n).read_text(encoding="utf-8"))
    feats = {k: load(f"{k}.geojson")["features"] for k in want if (data / f"{k}.geojson").exists()}
    missing = [k for k in want if k not in feats]
    if missing: sys.exit(f"missing {', '.join(m + '.geojson' for m in missing)} in {data}")

    raw = []
    for f in feats.get("blr", []): raw += [x["contractor"] for x in f["properties"]["awards"]]
    for f in feats.get("nh", []): raw += [c["contractor"] for c in f["properties"]["contracts"] if f["properties"]["kind"] == "nh"]
    names = Names(raw, a.keep_individuals)
    manifest = {"keep_individuals": a.keep_individuals, "data_dir": str(data), "packs": {}}

    def emit(key, roads, extra, url=None):
        src = SOURCES[key]; pk = pack(src, roads, url); validate(pk)
        (out / src["file"]).write_text(json.dumps(pk, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        named = [r for r in roads if r["contractor"]]
        manifest["packs"][key] = {"file": src["file"], "name": src["name"], "roads": len(roads), "roads_with_contractor": len(named),
                                  "blue_roads": sum(1 for r in named if r["match"] in ("supplied", "unique")),
                                  "awards": sum(len(r.get("awards", [])) for r in roads), "distinct_contractors": len({a["contractor"] for r in roads for a in r.get("awards", [])}),
                                  "bytes": (out / src["file"]).stat().st_size, **extra}
        if not a.quiet: print(f"{src['file']}: {manifest['packs'][key]}")

    if "pune" in feats: emit("pune", build_pune(feats["pune"]), {})
    if "blr" in feats:
        roads, n, held = build_blr(feats["blr"], names, a.keep_individuals); emit("blr", roads, {"awards_in_source": n, "awards_held": held})
    if "nh" in feats:
        roads, n, held = build_nh(feats["nh"], names, a.keep_individuals)
        urls = collections.Counter(c.get("url") for f in feats["nh"] for c in f["properties"]["contracts"] if c.get("url"))
        emit("nh", roads, {"awards_in_source_on_highway_lines": n, "awards_held": held}, url=urls.most_common(1)[0][0] if urls else None)
    (out / "MANIFEST.json").write_text(json.dumps(manifest, indent=1, ensure_ascii=False), encoding="utf-8")
    if not a.quiet: print(f"wrote {len(manifest['packs'])} pack(s) to {out}")


if __name__ == "__main__":
    main()
