# 22 — Explain calibration and show what threshold it set

**What to build:** Make the already-working 10-second `CALIBRATING` phase
(ticket 19, wired in ticket 10) legible to the rider — both that it's
happening, and *what it decided*.

**Prompted by:** field feedback (c), and Vision's follow-up: not asking for
the calibration UI to look polished — asking to actually see, on the live
graph, what threshold calibration set. Scope stays as originally set: this
ticket doesn't touch calibration's math, only represents its output.

**A precision worth surfacing before implementing:** what `NoiseFloorCalibrator`
actually calibrates is **not** a single-sample amplitude and not a
time-integrated impulse — it's the **standard deviation of vertical
acceleration over a rolling window** (`shortStdThreshold` over ~200ms,
`longStdThreshold` over ~30s). So "draw a dashed line at the threshold
amplitude" is an honest simplification, not a literal 1:1 representation —
a single sample can spike past the dashed line without tripping detection
(std hasn't risen yet), and detection can trip without any one sample
visibly crossing it (sustained wobble raises std even if no single peak is
large). Recommend the dashed lines represent the derived amplitude-
equivalent of `shortStdThreshold` (e.g. threshold × some multiple, labeled
as approximate) purely so a rider has *a* visual reference — and a short
line of copy owning that it's an approximation, so it doesn't read as a
promise the graph doesn't keep.

This is exactly the gap between amplitude-based and impulse-based
calibration Vision flagged (the kickstand-triggers-a-bump-candidate
anecdote is a real symptom of it) — worth naming here, not solving here.
It's now an explicit input to ticket 26.

**Blocked by:** None — `AitkenSessionScreen` already shows a `"● CALIBRATING"`
/ `"● DETECTING"` phase label; this ticket adds explanation and a threshold
reference on top of what's already wired.

**Status:** ready-for-agent

- [ ] One line of plain-language copy visible during `CALIBRATING`, e.g.
      "Hold steady on smooth road — measuring your mount's baseline
      vibration"
- [ ] Visible progress toward `Tunables.calibrationDurationMs` (10s
      default) — countdown or filling bar
- [ ] Once `DETECTING` starts, draw the approximate threshold band (dashed
      lines above/below the waveform's centerline) derived from the
      session's actual calibrated `shortStdThreshold`, not a hardcoded
      guess — labeled as approximate per the note above
- [ ] Tag buttons stay visible but dimmed during `CALIBRATING`; a tap in
      that state is **discarded outright, not queued** — confirmed with
      Vision
- [ ] Copy makes clear this happens once per session, not once per install
