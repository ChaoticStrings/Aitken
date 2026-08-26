# Luna — wayfinder map

Tracker: local-markdown (no connector available in this chat context).

## Destination

Aitken's recording pipeline + the Workbench, designed to pseudocode with inline
teaching, built on a small set of ratified cross-phase architecture invariants, with
dependency-lock-in risk toward Aitken-maturity/Crater/Artemis resolved via targeted
research now rather than full downstream design. Done when ready to hand to
`/to-spec` → `/to-tickets` → `/implement`.

## Notes

- **Stack (ratified)**: Kotlin + Jetpack Compose (Aitken) · HTML/JS + Node.js
  verification harness (Workbench) · Python + Google Colab (ML/calibration notebook).
  Alternatives-and-why-not writeup: see T1.
- **Standing requirements** (not up for re-litigation per ticket): TDD is mandatory for
  all implementation — `/implement` drives `/tdd` per slice. Data-safety practices are
  core, not optional. Vision's background is Python/Django — Kotlin/Android/Compose
  concepts get explained inline as they come up, not assumed.
- **Pseudocode workflow (ratified)**: per ticket — module table row (signature +
  purpose) → pseudocode taught inline → edge cases in prose → those edge cases become
  TDD test names directly → implement. Pseudocode is disposable working material, not
  maintained documentation; only the module table persists as a living reference.
- **Skills every session should consult**: karpathy-guidelines, tdd, codebase-design.
- Explain reasoning, don't silently decide — especially anywhere a choice could lock in
  a dependency Crater/Artemis would later have to work around.

## Decisions so far

- [Tracker](./map.md) — no connector available; defaulting to the local-markdown
  tracker (this file + `issues/`), portable into the real repo's `.scratch/` later.
- [Scope](./map.md) — this map covers Luna only. Aitken-maturity, Crater, and Artemis
  are each their own future wayfinder effort. The lock-in risk that motivated designing
  them now instead is mitigated by named research (T3, T4) plus architecture invariants
  (T2), not by full downstream design.
- [Feasibility: map rendering](./issues/T3-map-rendering-feasibility.md) — Google Maps
  SDK for Android requires Play Services and has a thin offline story; Mapbox and
  osmdroid/OSM both support real offline tile packs and Play-Services-free builds.
  Recommendation: hide map rendering behind our own interface from day one.
- [Feasibility: background location](./issues/T4-background-location-feasibility.md) —
  Android 14+ requires a declared foreground service type plus
  `ACCESS_BACKGROUND_LOCATION` for continuous location in a foreground service — this
  is exactly Prototype 1's existing plan, no architecture change needed for Luna. The
  real cost lands at Crater/Artemis: Play Console reviews background-location
  foreground services against a "minimum scope" policy — a publishing-time risk to flag
  for that phase, not a Luna blocker.

- [Architecture invariants (T2)](./issues/T2-architecture-invariants.md) — six
  ratified: scoped replaceability (map rendering, location, classifier loader),
  per-file schema versioning, solo-maintainable reworded around team/on-call rather
  than managed-services, no-network-required scoped to Aitken specifically, data
  integrity & consent split into crash-safe writes / permitted self-controlled backup /
  no third-party sharing without explicit action, and "architecture invariant" always
  spelled out in full.

- [Module boundaries (T5)](./issues/T5-recording-pipeline-module-boundaries.md) —
  closed. SegmentDetector fully pseudocoded (open-ended hysteresis, dual rolling-std
  windows, ten enumerated edge cases). Remaining modules kept at interface-table depth
  by explicit choice — lower risk, edge cases deferred to each module's own /tdd loop.
- [Storage mechanism (T5)](./issues/T5-recording-pipeline-module-boundaries.md) —
  Storage Access Framework folder picker, not direct-path or MediaStore-into-Documents
  (the latter is undocumented platform behavior). Primary writes stay app-private;
  backup and classifier-config sync are decoupled, best-effort, through the same
  granted folder.
- [Workbench structure (T6)](./issues/T6-workbench-module-structure.md) — closed.
- [Front-end design (T7)](./issues/T7-aitken-frontend-design.md) — closed, adapting
  Prototype 1's DebugScreen rather than redesigning.

## Not yet specified

- Exact CSV/session schema finalization beyond what Aitken_Build_Guide.md v0.2 already
  sketched — sharpens once T5 starts.
- Workbench's internal UI structure and interaction model — sharpens once T6 starts.
- Front-end visual language for Aitken's session screen — sharpens once T7 starts.
- Precise confidence-threshold mechanics for autonomous tagging (build guide §5.3) —
  needs Luna's first real recorded sessions to exist before it's specifiable; stays fog
  until then.

## Out of scope

(none yet)

## Status

All tickets closed (T1–T7). The way is clear — merged onto `/to-spec`; see SPEC.md.
