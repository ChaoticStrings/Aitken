"""Tests for Datasets/tools/make_packs.py. Fixtures are FICTIONAL (Datasets/tests/fixtures).

    python3 -m unittest discover -s Datasets/tests -v
"""
import importlib.util, json, pathlib, tempfile, unittest

HERE = pathlib.Path(__file__).resolve().parent
FIX = HERE / "fixtures"
spec = importlib.util.spec_from_file_location("make_packs", HERE.parent / "tools" / "make_packs.py")
mp = importlib.util.module_from_spec(spec); spec.loader.exec_module(mp)


def run(*extra):
    out = pathlib.Path(tempfile.mkdtemp())
    mp.main(["--data", str(FIX), "--out", str(out), "--quiet", *extra])
    load = lambda f: json.loads((out / f).read_text(encoding="utf-8"))
    return out, load("datasource_pune_osm_roads.json"), load("datasource_blr_awards.json"), load("datasource_nh_morth.json"), load("MANIFEST.json")


def road(pk, rid):
    return next(r for r in pk["roads"] if r["id"] == rid)


class Names(unittest.TestCase):
    def test_spelling_variants_share_a_key(self):
        k = {mp.canon_key(x) for x in ["M/s Fictional Infra Pvt. Ltd.", "FICTIONAL INFRA PRIVATE LIMITED", "Ms Fictional Infra (P) Ltd"]}
        self.assertEqual(len(k), 1, k)

    def test_ampersand_and_and(self):
        self.assertEqual(mp.canon_key("S & P Infra Pvt Ltd"), mp.canon_key("S AND P INFRA PRIVATE LIMITED"))

    def test_jv_is_not_a_proprietor_suffix(self):
        self.assertIsNone(mp.Names.firm_part("M/s Alpha Infra - Beta Constructions (JV)"))
        self.assertEqual(mp.Names.firm_part("ANIL V( TEST ANIL BUILDERS )"), "TEST ANIL BUILDERS")

    def test_two_different_jvs_stay_different(self):
        n = mp.Names(["M/s Alpha Infra - Beta Constructions (JV)", "M/s Gamma Infra - Beta Constructions (JV)"], False)
        a, b = (n.resolve(x)[0] for x in ["M/s Alpha Infra - Beta Constructions (JV)", "M/s Gamma Infra - Beta Constructions (JV)"])
        self.assertNotEqual(a, b)

    def test_person_is_held_unless_kept(self):
        self.assertEqual(mp.Names(["RAVI K SHARMA"], False).resolve("RAVI K SHARMA"), ("", "person-or-unclassified", True))
        self.assertEqual(mp.Names(["RAVI K SHARMA"], True).resolve("RAVI K SHARMA")[2], False)


class Packs(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.out, cls.pune, cls.blr, cls.nh, cls.man = run()

    def test_format_and_provenance(self):
        for pk in (self.pune, self.blr, self.nh):
            self.assertEqual((pk["format"], pk["version"]), ("aitken-datasource", 1))
            s = pk["source"]
            self.assertFalse(s["sample"]); self.assertTrue(s["license"] and s["retrieved"] and s["notes"] and s["name"])
        self.assertEqual(self.nh["source"]["url"], "https://example.invalid/morth")   # taken from the data

    def test_degenerate_geometry_part_dropped(self):
        g = road(self.pune, "pune:0")["geometry"]["coordinates"]
        self.assertEqual(len(g), 1); self.assertGreaterEqual(len(g[0]), 2)

    def test_pune_has_no_contractors(self):
        self.assertTrue(all(not r["contractor"] and r["match"] == "supplied" for r in self.pune["roads"]))

    def test_one_firm_one_name(self):
        r = road(self.blr, "karnataka:1")
        firm = [a for a in r["awards"] if "fictional infra" in a["contractor"].lower()]
        self.assertEqual(len(firm), 2)
        self.assertEqual(len({a["contractor"] for a in firm}), 1)                      # two spellings, one name
        self.assertEqual({a["as_published"] for a in firm}, {"M/s Fictional Infra Pvt. Ltd.", "FICTIONAL INFRA PRIVATE LIMITED"})   # each award keeps what was published
        self.assertEqual(r["contractor"], firm[0]["contractor"])                      # most awards on the road = primary

    def test_proprietor_shows_the_firm_and_holds_the_person(self):
        r = road(self.blr, "karnataka:1"); names = {a["contractor"] for a in r["awards"]}
        self.assertIn("TEST ANIL BUILDERS", names)
        self.assertEqual(r["props"]["awards held"].split()[0], "1")          # RAVI K SHARMA
        text = (self.out / "datasource_blr_awards.json").read_text(encoding="utf-8")
        for secret in ("RAVI K SHARMA", "ANIL V", "SURESH P", "LAKSHMI N"): self.assertNotIn(secret, text)

    def test_individual_only_road_has_no_contractor_but_stays_in_the_dataset(self):
        r = road(self.blr, "karnataka:3")
        self.assertEqual((r["contractor"], r["awards"], r["match"]), ("", [], "unique"))
        self.assertIn("awards held", r["props"])

    def test_candidate_matches_are_labelled(self):
        for r in self.blr["roads"]: self.assertIn("unverified", r["match_note"]); self.assertEqual(r["match"], "unique")

    def test_jv_awards_stay_separate(self):
        self.assertEqual(len({a["contractor"] for a in road(self.blr, "karnataka:2")["awards"]}), 2)

    def test_awards_newest_first_with_money_as_text(self):
        aw = road(self.blr, "karnataka:1")["awards"]
        self.assertEqual([a.get("year") for a in aw], sorted((a.get("year") for a in aw), reverse=True))
        self.assertEqual([a.get("cost") for a in aw], ["INR 2,750,000", "INR 1,500,000", None])
        self.assertTrue(all(isinstance(v, str) for a in aw for v in a.values()))

    def test_highway_line_is_unlocated_and_lists_every_firm_contract(self):
        r = road(self.nh, "nh:NH-999:MH")
        self.assertEqual(r["match"], "unlocated")
        self.assertEqual(len(r["awards"]), 2)                                  # the individual is held
        self.assertEqual(len({a["contractor"] for a in r["awards"]}), 1)       # both spellings = one firm
        self.assertIn("awards held", r["props"])
        self.assertTrue(any("section located" in a.get("note", "") for a in r["awards"]))
        self.assertTrue(all("section not located" in a["note"] or "section located" in a["note"] for a in r["awards"]))

    def test_located_section_is_its_own_blue_line(self):
        r = road(self.nh, "nhseg:morth-upc:T999A")
        self.assertEqual((r["match"], r["contractor"]), ("supplied", "Fictional Roadways Pvt Ltd"))
        self.assertIn("28.0 km straight line vs 30.0 km chainage", r["awards"][0]["note"])

    def test_manifest_matches_the_packs(self):
        m = self.man["packs"]
        self.assertEqual(m["blr"]["roads"], 3); self.assertEqual(m["blr"]["roads_with_contractor"], 2); self.assertEqual(m["blr"]["awards_held"], 3)
        self.assertEqual(m["nh"]["roads"], 3); self.assertEqual(m["nh"]["blue_roads"], 1)
        self.assertEqual(m["nh"]["awards_in_source_on_highway_lines"], 4)
        self.assertFalse(self.man["keep_individuals"])

    def test_deterministic(self):
        out2 = pathlib.Path(tempfile.mkdtemp()); mp.main(["--data", str(FIX), "--out", str(out2), "--quiet"])
        for f in ("datasource_pune_osm_roads.json", "datasource_blr_awards.json", "datasource_nh_morth.json"):
            self.assertEqual((self.out / f).read_bytes(), (out2 / f).read_bytes(), f)


class KeepIndividuals(unittest.TestCase):
    def test_keep_flag_keeps_people_and_their_published_spelling(self):
        out, _, blr, nh, man = run("--keep-individuals")
        r = road(blr, "karnataka:3")
        self.assertEqual({a["contractor"] for a in r["awards"]}, {"SURESH P", "LAKSHMI N"})
        self.assertEqual(r["contractor"], "LAKSHMI N")
        prop = next(a for a in road(blr, "karnataka:1")["awards"] if a["contractor"] == "TEST ANIL BUILDERS")
        self.assertEqual(prop["as_published"], "ANIL V( TEST ANIL BUILDERS )")
        self.assertTrue(man["keep_individuals"]); self.assertEqual(man["packs"]["blr"]["awards_held"], 0)
        self.assertEqual(len(road(nh, "nh:NH-999:MH")["awards"]), 3)


class Cli(unittest.TestCase):
    def test_missing_input_is_a_clear_error(self):
        with self.assertRaises(SystemExit) as e: mp.main(["--data", str(HERE), "--out", tempfile.mkdtemp()])
        self.assertIn("missing", str(e.exception))

    def test_only_selects_packs(self):
        out = pathlib.Path(tempfile.mkdtemp()); mp.main(["--data", str(FIX), "--out", str(out), "--only", "nh", "--quiet"])
        self.assertEqual(sorted(p.name for p in out.iterdir()), ["MANIFEST.json", "datasource_nh_morth.json"])

    def test_validator_rejects_a_bad_match_value(self):
        pk = {"format": "aitken-datasource", "version": 1, "roads": [{"id": "x", "match": "confirmed", "geometry": {"type": "LineString", "coordinates": [[73, 18], [73.1, 18.1]]}, "props": {}, "contractor": ""}]}
        with self.assertRaises(AssertionError): mp.validate(pk)


if __name__ == "__main__":
    unittest.main()
