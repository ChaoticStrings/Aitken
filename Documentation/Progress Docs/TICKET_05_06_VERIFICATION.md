# Tickets 05 & 06 — SessionRecorder & TagMatcher, implementation notes

## Integrity check (requested before starting)
Full diff against your uploaded project zip: every file from tickets 01–03
and 19 is byte-for-byte identical to what was delivered — no drift, no
unexpected edits. `app/build.gradle.kts` and `libs.versions.toml` both carry
the correct dependencies. One real gap found: `SegmentDetectorTest.kt` was
never merged (only `NoiseFloorCalibratorTest.kt` made it into
`com/chaotic/segment` test sources), even though ticket 04's checkbox for
full edge-case test coverage got marked done. Re-sent separately this turn.
Also updated memory with your repo's actual confirmed build settings
(`com.chaotic.aitken`, compileSdk 37, minSdk 29, Java 11 target) so future
tickets are grounded in what's real rather than assumed.

Update - [RESOLVED segmentdetectortest.kt]

## SessionRecorder (05)

**Design decisions made and why:**
- `speedMps`/`epochMs` are caller-supplied parameters, not held
  dependencies — this class never touches `GpsProvider` or a clock
  directly. Ticket 10's pipeline already has to query both at the moment a
  segment closes or a tap lands, so passing the values in keeps this a
  simple I/O sink, consistent with SessionRecorder/TagMatcher/
  ClassifierRunner all being "pure-logic... accept dependencies, return
  results" per SPEC.md's Testing Decisions.
- `epochMs` (wall-clock) is recorded on segments and labels only, not on
  every ~100Hz sensor/gps row — user story 26 asks for time-of-day context
  per *segment*, not per sample.
- `writeLabel`'s `kind` parameter is a plain String, not TagMatcher's
  `TagKind` enum — avoids ticket 05 depending on a type ticket 06 hasn't
  shipped yet, so each ticket still compiles standalone the way every prior
  one has.
- Numeric CSV fields use `String.format(Locale.US, "%.3f", x)`, not
  Prototype 1's bare `"%.3f".format(x)` — the bare version silently emits a
  comma decimal separator on a non-US-locale device, corrupting a
  comma-delimited CSV (a pitfall already identified from the Prototype 1
  audit). Explicit `Locale.US` fixes it. Lat/lng use full `Double.toString()`
  precision instead — 3 decimal degrees is only ~111m resolution, useless
  for GPS.
- Crash safety: periodic flush (default every 100 rows, matching
  Prototype 1's audited default), not flush-per-row — the guarantee is "a
  crash loses only unflushed samples," which periodic flush satisfies
  without paying I/O cost on every single sample. Worth being precise:
  `flush()` guarantees bytes have left the app and reached the OS, which
  protects against an app crash. It is not an `fsync()` durability
  guarantee against power loss — a stronger, different claim that user
  story 8 doesn't actually ask for.

**Tests** use JUnit's `TemporaryFolder` against real files — this module
has no Android dependency (T2's carve-out: the session-file format itself
isn't a vendor dependency, so it isn't behind an interface), and crash
safety can only be proven against a real filesystem, not a mock. The crash
test writes 5 rows with `flushEveryNRows=3`, reads the file *before*
calling `close()` (simulating a crash) and confirms only 3 rows are
visible, then calls `close()` and confirms all 5 are. All formatting
expectations (rounding, locale) are hand-verified in comments.

## TagMatcher (06)

**Design decisions:**
- `tap_offset_ms` is defined the same way for both open and closed
  matches: milliseconds since the segment's last signal-true sample.
  For an open segment that's `OpenSegment.lastSignalNs` directly; for a
  closed one it's `startNs + durationNs`, which is *exactly*
  `lastSignalNs` by construction in `SegmentDetector` (duration is
  measured to it) — so both cases reduce to the same underlying quantity.
- "Range-tag events matched independently from point taps" is implemented
  as a `TagKind` (`POINT`/`RANGE_START`/`RANGE_END`) carried through to the
  result, rather than a separate matching method — the actual "which
  segment does this belong to" question is identical for both, so
  duplicating the lookback logic would just be a source of drift between
  two copies. They're independently *trackable*, which is what the ticket
  asks for, without independently *reimplementing the search*.
- History of recently-closed segments is trimmed lazily on each `match()`
  call, using the tap's own timestamp — not bounded any harder than that.
  Real segment counts per ride are sparse (tens to low-hundreds) compared
  to sensor rate, so unbounded-but-slow growth between matches is fine;
  flagged as a deliberate simplification rather than an oversight.

**Tests**: 6 cases, pure Kotlin, no I/O — open-segment match ignoring
lookback entirely, closed-segment match via lookback, expired-tap
unmatched, most-recent-of-multiple wins, an expired segment getting
trimmed without blocking a valid one behind it, and range-kind tracking.
All hand-traced against the implementation.

## What compiles today
Both are pure Kotlin. `SessionRecorder` needs nothing beyond `java.io`/
`java.util.Locale` (already available). `TagMatcher` needs nothing beyond
`kotlin.collections`. No new Gradle dependencies for either ticket.

## Frontier after this
- Ticket 07 (ClassifierConfigLoader) and 08 (BackupAgent) — both blocked
  only by 03, which is done.
- Ticket 10 (Continuous recording session) — blocked by 02 and 05, both
  now done — this is where SessionRecorder, GpsProvider, and the DSP→
  SegmentDetector pipeline actually get wired together into a running
  foreground service.
- Ticket 11 (Manual tagging UI) — blocked by 06 and 10.
