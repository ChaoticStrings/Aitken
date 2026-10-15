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

## Detector v2 (added in tickets 28-29)

These supersede the "hysteresis-detected" wording of **Segment** above for sessions
whose `config.json` says `"detectorVersion": 2`. A segment is now opened by a plain
amplitude breach, not a rolling-std crossing. Sustained roughness is a separate,
parallel concept (below), no longer baked into segment detection.

**M threshold**:
Amplitude (m/s² of deviation from resting gravity, `abs(vertical - 9.81)`) at which a
segment opens — the instant a sample reaches it — and below which it must stay for
`endQuietMs` to close. Calibrated as (mean of the peaks of that deviation over the
clean calibration window) × Detection sensitivity (`stdFactor`).

**D (roughness)**:
In the live UI and detector v2: the sliding-window moving average of deviation from
resting gravity — the yellow line, the "hysteresis". **This overloads D (Duration)
above.** Duration is still D in the M/D/V model; the live readout is called D only
because that is what the rider's tuning vocabulary settled on. Treat "the D reading" /
"D threshold" as roughness, and "segment duration" as duration, wherever the two meet.

**D threshold**:
Roughness above which a rough patch can register. Calibrated as (highest sliding-window
mean seen over the clean calibration window) × Rough patch sensitivity
(`roughSensitivity`). Only valid for the window width it was measured with.

**Sliding window**:
Time-based (`rollingWindowMs`, 250 ms-5 s) moving average behind the D reading.

**Rough patch**:
A chain of roughness — segment breaches, or D at/above its threshold — whose internal
gaps are at most the patch distance (`roughPatchDistanceM`, logarithmic 1-100 m, default
2.5 m) of *travelled distance*, and which holds two or more segments or spans at least
the patch distance. Registered automatically; written to `patches.csv`, never to
`labels.csv`. Replaces tapping START/END on a "Rough stretch" button.
_Avoid_: Rough stretch for auto-registered patches — that is the manual tag label, kept
for old sessions.

**Dirty sample** (calibration):
A sample excluded from calibration because it falls within 500 ms of a turn or of a
GPS-speed change of ≥ 1 m/s². Clean time, not wall time, counts toward the calibration
duration.

**Calibration preset**:
A saved (M threshold, D threshold, sliding-window width) triple. Selecting one at start
skips calibration.

**Clipping**:
An accelerometer axis sitting at its saturation limit (`Sensor.getMaximumRange()`).
Anything above that on the graph is the *clipped spectrum* — real motion the sensor
could not report.

## Workbench v2 (added in ticket 01, WORKBENCH_PLAN.md §2)

**Source Bundle**:
The immutable set of files a session was imported from (`sensor.csv`, `gps.csv`,
`segments.csv`, `labels.csv`, optional `config.json`, optional `review.json`),
identified by a content hash. The Workbench never mutates it.

**Journal**:
The append-only, ordered list of Edits applied to a Source Bundle. Current
session state is always `reduce(source, journal[0..cursor])`. Undo moves the
cursor back; redo moves it forward; a new Edit after undo truncates the tail.

**Edit**:
One atomic, serialisable change to segments or tags (`SegmentMove`,
`TagRelabel`, `AdoptSimulation`, …). Has a `kind`, a `payload`, a wall-clock
`ts`, and an `origin` (`manual | queue | bulk | adopt | tighten`).
_Avoid_: "action", "command", "operation".

**Review Item**:
One thing the Queue thinks needs a human decision: an untagged high-M segment,
an unmatched tag, an inconsistent range pair, a segment whose recomputed peak
disagrees with the recorded one, etc. Has a `kind`, a `severity`, a `target`
(segId or tagId), and a `resolution` (`open | confirmed | dismissed`).
Confirming/dismissing is itself an Edit (`ReviewResolve`), so it is undoable and
survives reload.

**Clean Bundle**:
The export: pass-through raw files + Workbench-written `segments.csv`,
`labels.csv`, `review.json`. Distinct from a Source Bundle by the presence of
`review.json`.

**Simulation**:
A non-destructive re-run of the detector over the session's vertical signal
with user-chosen Tunables. Produces candidate segments and a **Simulation Diff**
(added / removed / moved / unchanged vs. current segments). Never touches state
until adopted via an `AdoptSimulation` Edit.

**Channel**:
One plottable time series over the sensor timebase (vertical, jerk, accel X,
recomputed vertical, windowed RMS, …). Provided by a `ChannelProvider`.

**Layout**:
The serialised arrangement of Panels (which panel is where, sizes, tab groups,
floating). Distinct from session state; persisted per device.

**Panel**:
One dockable UI region with a stable `PanelId`: `waveform`, `minimap`, `queue`,
`inspector`, `map`, `tree`, `tunables`, `report`.
