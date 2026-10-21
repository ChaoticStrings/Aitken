#!/usr/bin/env python3
"""Unify every contractor-named road/civil record we can legally reach into compiled/contracts.jsonl.
Usage: python3 scripts/compile_contracts.py /path/to/pothole-reporter /path/to/blr-tenders-bids   (pip: pandas pyarrow)"""
import json, os, re, sys, pathlib, collections
import pandas as pd
REPO, VON = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
ROOT = pathlib.Path(__file__).resolve().parent.parent            # Datasets/
WORK = pathlib.Path(os.environ.get("DATASETS_WORK") or ROOT / "work")   # git-ignored scratch: work/compiled, work/data
OUT = WORK / "compiled"; OUT.mkdir(parents=True, exist_ok=True)
nm = lambda s: re.sub(r"\s+", " ", re.sub(r"\b(m/s\.?|messrs)\b|[^\w ]", " ", s.lower())).strip()
rows = []
def add(**k): k["contractor_norm"] = nm(k["contractor"]); rows.append(k)

# A. National highways: MoRTH Data Lake "projects under implementation" (via pothole-reporter snapshot)
for x in json.load(open(REPO / "data/tenders-national-highways.json"))["contracts"]:
    if x["contractor"]:
        add(source="morth-datalake", state=x["state_code"], authority=x["agency"], title=x["title"], contractor=x["contractor"].strip(), amount=None,
            date=x["start_date"], status=x["lifecycle_status"], ref=x["reference_value"], geo={"highway": x["highway_refs"], "chainages": x["chainages"]}, url=x["source_url"], award_verified=True, segment_verified=False)
# B. Bengaluru: Vonter/blr-tenders-bids (KPPP + eProc), awarded road works only
df = pd.read_parquet(VON / "data/tenders.parquet")
rd = df[(df.category == "WORKS") & df.title.str.contains(r"\broad|asphalt|resurfac|white.?top|pothole", case=False, na=False)]
for r in rd.itertuples():
    for c in (list(r.awarded_bidders) if r.awarded_bidders is not None else []):
        add(source="vonter-blr-tenders-bids", state="KA", authority=r.department, title=r.title, contractor=str(c).strip(), amount=None if pd.isna(r.award_amount) else float(r.award_amount),
            date=None if pd.isna(r.awarded_at) else str(r.awarded_at.date()), status=r.status_label, ref=r.tender_number, geo={"office": r.location}, url="https://kppp.karnataka.gov.in/", award_verified=True, segment_verified=False)
# C. Rest of Karnataka from the repo's KPPP snapshot (Bengaluru rows are superseded by B)
for t in json.load(open(REPO / "data/tenders-karnataka.json")):
    if t.get("c") and t.get("b") != "BLR":
        add(source="kppp-via-pothole-reporter", state="KA", authority=t.get("loc"), title=t["t"], contractor=t["c"].strip(), amount=None, date=t.get("d"), status="awarded", ref=t["tn"],
            geo={"bucket": t.get("b")}, url="https://kppp.karnataka.gov.in/", award_verified=True, segment_verified=False)
with open(OUT / "contracts.jsonl", "w") as f:
    for r in rows: f.write(json.dumps(r, ensure_ascii=False) + "\n")
by = collections.defaultdict(lambda: [0, set()])
for r in rows: by[r["source"]][0] += 1; by[r["source"]][1].add(r["contractor_norm"])
summ = {s: {"records": n, "distinct_contractors": len(c)} for s, (n, c) in by.items()}
summ["_total"] = {"records": len(rows), "distinct_contractors": len({r["contractor_norm"] for r in rows})}
(OUT / "summary.json").write_text(json.dumps(summ, indent=1)); print(json.dumps(summ, indent=1))
