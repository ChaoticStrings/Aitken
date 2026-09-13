# 06 — Session Sanity Report

**What to build:** On opening a session, a Report panel appears with the ten
status lines of Plan §7.1 (duration/rate, gaps, gyro, GPS, config, peak
divergence, tap offsets, label histogram, range pairs, queue summary), each
with an ok/warn/bad dot and a one-line explanation, and two buttons: "Start
review" (focuses the Tree for now; the Queue takes over in 07) and "Dismiss".
The report is reachable again from the top bar.

**Blocked by:** 03, 05

**Status:** ready-for-agent

**Plan sections:** §5.5 (`buildRanges` only; `buildQueue` counts may be stubbed to the blocking/advisory rule for unmatched tags + range problems until 07), §7.1, §11 E-02…E-07, E-32.

**Acceptance criteria**
- [ ] `core/report.ts` `buildReport(parsed, state, divergence, settings): ReportLine[]` is pure; every threshold in §7.1 has a unit test crossing it both ways.
- [ ] `buildRanges(tags)` pairs START/END in time order per label, classifies `unpaired`, `inverted`, `overlap`; unit-tested against the `range-problems` synthetic fixture and the fixture's real unlinked `RANGE_START` (E-07, E-32).
- [ ] Tap-offset statistics (median, p90) computed only over tags with a finite offset.
- [ ] Report panel renders progressively: available lines as soon as `parsed` exists, divergence line fills in when ticket 03's result arrives (skeleton state meanwhile).
- [ ] Missing gps / labels / segments / config / gyro each produce the exact wording in §7.1 (assert text in unit tests using the synthetic fixtures).
- [ ] "Start review" focuses the Tree panel and selects the first segment; "Dismiss" hides the report; top-bar button re-shows it.
- [ ] Demo: open the fixture → report shows gyro 0 % bad, one unmatched range tag warn, divergence ok; open `gaps` → gaps warn.

**Tests to write first:** `report thresholds` table test, `buildRanges` classification tests, text-assertion tests per missing file.
