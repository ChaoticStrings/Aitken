# 14 — Workbench: SessionLoader

**What to build:** Loads a session's four CSVs into the Workbench, dispatching
parsing per-file on `schema_version` so a future format change to one file doesn't
break the others.

**Blocked by:** 05

**Status:** ready-for-agent

- [ ] Parses sensor, gps, segments, and labels CSVs independently
- [ ] Each file's `schema_version` drives its own parse path
- [ ] Marker/label rows with synthetic blank fields are excluded from numerical
      data before being handed to other Workbench modules
- [ ] Loading a real recorded session (multi-file bundle) succeeds end-to-end
