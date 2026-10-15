# Rough patches — behaviour, known gaps, parked ideas

Working notes for the distance-based rough-patch logic (`RoughPatchTracker`, ticket 29).
Nothing here is a spec; it is what has been observed and what is deliberately *not* solved yet.

## How it works today

- A **chain of roughness** is any run of rough samples whose gaps stay within the patch distance
  (`roughPatchDistanceM`, default 2.5 m, logarithmic 1–100 m in Settings) of *travelled distance*.
  A sample is rough if it breaches the M threshold inside an open segment, or if the sliding-window
  roughness (D) is at/above the D threshold.
- A chain registers as a **rough patch** when it holds two or more segments (`CLUSTER`), or spans at
  least the patch distance (`LENGTH` with one segment, `ROUGHNESS` with none — gravel/washboard).
- Distance comes from integrating GPS speed along the sensor timeline (`Odometer`); a speed fix
  older than 10 s counts as unknown (no distance), not as "still moving at that speed".
- Patches go to `patches.csv`, deliberately not `labels.csv` (that file is the human-tag corpus).

## Known open problem (parked on purpose): periodic road features

**Concrete-slab joints.** Rhythmic dips every few metres on a highway could register as one patch
that runs for kilometres. Two separate mechanisms could cause it:

1. **Chain linking.** If the joint spacing is within the patch distance, every joint chains to the
   next. At the 2.5 m default this only happens if joints are closer than that; a rider who raises
   the distance to e.g. 10 m links them all.
2. **Sliding-window smear.** Joints too weak to open a segment never trigger the chain rule, but
   they keep the D reading elevated: a 1 s window at highway speed spans ~25 m and always contains
   several joints. If that holds D above its threshold, the result is a `ROUGHNESS` patch lasting
   as long as the joints do. The memory guard (below) does not help here — there is no segment
   for it to key off.

**What to look for on the first real concrete-highway ride:** `patches.csv` rows with
`trigger = ROUGHNESS` and `length_m` in the hundreds or thousands; `max_d` sitting almost flat;
segments of near-constant peak at near-constant spacing in `segments.csv`.

**Ideas, none implemented — pick after seeing real data:**

- *Periodicity test.* Resample deviation onto the odometer and look for a narrow autocorrelation
  peak at a regular lag. Very regular spacing means infrastructure, not damage: tag it as a
  periodic feature instead of a patch.
- *Hysteresis on D (Schmitt trigger).* Enter at D_hi, leave at D_lo, so an intermittent signal
  doesn't chain by hovering around one threshold. Also stops patch flicker generally.
- *Length cap / re-qualification.* Force-close a patch beyond N metres and make the next stretch
  re-earn registration, so one runaway chain can't swallow a whole highway.
- *Distance-domain sliding window.* Define the window in metres, not seconds, so smear length
  doesn't scale with speed. Needs a fallback when speed is ~0 (calibration is often stationary).
- *Speed normalisation / gating.* Joint impact scales steeply with speed, and D-only patches at
  highway speed are the suspect case. Suppress or re-scale D-only registration above a speed.
- *Crowd resolution (Crater).* A periodic feature seen by every rider along a whole road is a
  property of the road ("jointed concrete"), not a hazard. Aggregation can recognise and demote it.

## Things replay of the real fixture session already showed

- **Window memory makes a lone pothole look like a patch.** At city speeds a 1 s window keeps D
  above threshold for ~1 s after a big impact, which is 7–18 m of road. `RoughPatchTracker`
  now ignores D for one window-width after a segment breach (and while a segment is open). On the
  90 s fixture, default sensitivity: 6 patches → 3.
- **A noisy mount makes segments run long, and long segments become patches.** With
  `stdFactor` 3.0 on this mount the M threshold (5.8 m/s²) sits just above ordinary noise peaks, so
  noise breaches keep a real event open (one segment ran 2.7 s). Any segment longer than ~0.4 s at
  6 m/s covers ≥ 2.5 m and so registers as a `LENGTH` patch. Raising sensitivity to 4.0–5.0 cut
  segments from 11 to 5 and the longest from 2.7 s to 0.7–1.9 s without losing the three tagged
  events. This is a calibration question, not a logic one.
- **A smoother mount would change all of this.** The fixture session was recorded with a known
  mount rattle; the numbers above are that mount's, not a general result.
