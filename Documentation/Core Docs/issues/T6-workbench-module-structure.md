## Question

Design the Workbench's module structure and interaction model to pseudocode level:
session ingestion, GPS track view, dual-trace visualization (short-window signal +
segment overlay), tag correction, clean-bundle export.

Type: grilling (HITL)
Status: closed
Blocked by: none

## Resolution

Closed. Module table: SessionLoader (per-file schema_version dispatch), WaveformView
(ported from Prototype 1's existing pan/zoom canvas, extended with segment-boundary
overlays), MapView (new, Leaflet+OSM), TagEditor (the actual review/correction
surface), BundleExporter. VerificationHarness (Node.js, reused differential-replay
pattern) and the schema-migration strategy for old sessions are both deliberately
deferred — the former waits on SegmentDetector existing, the latter is premature with
only one schema version in existence.
