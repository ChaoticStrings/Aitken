# 18 — Workbench: BundleExporter

**What to build:** Export a "clean," reviewed version of a session, so only
verified data reaches the Colab notebook.

**Blocked by:** 17

**Status:** ready-for-agent

- [ ] Exports a corrected bundle reflecting every TagEditor edit (the export/edit
      disconnection bug class from Prototype 1 explicitly re-checked here)
- [ ] Export only includes sessions/segments that have actually been through
      review
- [ ] Exported bundle round-trips cleanly back through SessionLoader
