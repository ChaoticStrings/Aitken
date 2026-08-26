# Aitken / Luna

Vocabulary for Aitken's recording pipeline and the Workbench. General programming
concepts don't belong here — only terms specific to this project.

## Language

**Session**:
One continuous recording run in Aitken, start to stop. Produces a session bundle —
sensor, GPS, segments, and labels files sharing a timestamp.

**Segment**:
A single, open-ended, hysteresis-detected disturbance interval in the vertical-
acceleration signal — the atomic unit Aitken measures and Colab calibrates against. No
fixed duration ceiling; can span tens of milliseconds or up to 20 minutes.
_Avoid_: Event, Candidate — Prototype 1's terms for its fixed-duration-window model,
retired along with that model.

**M (Magnitude)**:
The peak (and auxiliary RMS) vertical acceleration measured within a segment.

**D (Duration)**:
How long a segment lasts. Independent of M but physically coupled to it through
vehicle speed (see Aitken_Build_Guide.md §4).

**Tag**:
The rider's manual point or range annotation of a segment, applied retroactively via
backward lookback matching against an already-detected segment — never created at
tap-time.
_Avoid_: Label, as a distinct concept — same thing. `labels_*.csv` is the existing
filename; not a signal that "label" and "tag" mean different things here.

**Architecture invariant**:
A ratified, durable structural constraint every future design decision gets checked
against (see T2). Distinct from a `[CALIBRATE]` tunable constant — like an M/D scale
boundary — which is expected to move as real ride data accumulates. Always written in
full; never abbreviated to bare "invariant," which collides with this codebase's
existing DSP sense of the word (orientation invariance).

**SafStorageAdapter**:
The single adapter over a Storage Access Framework tree URI, granted once via a
folder picker and persisted. Both `BackupAgent` and `ClassifierConfigLoader` are
built on top of this one adapter rather than each independently wrapping SAF —
one seam for the whole "external synced folder" concern, not two.
