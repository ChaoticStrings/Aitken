# Ticket 10 — Continuous recording session, implementation notes

## Prerequisite gap found and fixed
`SensorStream`/`AndroidSensorStream` — Prototype 1's sensor seam — was
never ported in any prior ticket. Ticket 01 only covered the four DSP
modules; the original 18-ticket set never gave the sensor stream its own
line item. Ported it here the same way as ticket 01: scripted `sed`
package-rename, `diff`-verified against the Crater originals, exactly one
line changed per file (the `package` declaration). Not a separate ticket —
small, mechanical, and load-bearing specifically for this one.

## Architecture: RecordingPipeline as the testable core
Everything Android-framework-coupled (the foreground service, sensor
registration, notification) is a thin shell around `RecordingPipeline`, a
pure orchestration class with zero Android imports. This is the highest-
value place to put testing effort — the framework glue is mechanical and
well-documented Android boilerplate; the actual "DSP → SegmentDetector →
SessionRecorder, feed TagMatcher, force-close on stop" wiring is where a
subtle bug would actually cost data.

**Tests push real `SensorStream.SensorSample` values through the real
`GravityEstimator`/`Verticalizer` chain** — not abstracted "vertical"
floats like earlier tickets' tests. Every sample is deliberately pure-Z-axis
(`accelX=accelY=0`), which makes `verticalComponent` reduce to exactly
`accelZ` regardless of `GravityEstimator`'s internal EMA state (the
dot-product/norm math cancels identically whether gravity starts at
`[0,0,0]` on the first sample or has already drifted — everything stays
colinear along Z). That let me reuse the exact hand-traced numbers already
proven in `SegmentDetectorTest`/`NoiseFloorCalibratorTest`, just run through
the real chain instead of injected directly. All three tests hand-traced
end to end, including the `GravityEstimator` EMA update at each step — this
is stronger verification than ticket 04's tests, which only proved the
state machine in isolation.

Three cases: calibration writes sensor rows but no segments and fires the
callback once; a full open→extend→close cycle produces the correct
`segments.csv` row (speed from a pushed GPS fix, epoch from an injected
clock) and reaches `TagMatcher`; and calling `endSession()` mid-open
force-closes end-to-end (ticket 04's acceptance criterion explicitly asked
for this to be verified above the unit level, not just within
`SegmentDetector` itself).

## AitkenRecordingService — not unit-testable here, same as prior real adapters
Real `Service`/`SensorManager`/`NotificationManager` code, same category as
`AndroidGpsProvider`/`AndroidSafStorageAdapter` — written carefully, but I
can't execute or Robolectric-test this in this sandbox. A few things worth
double-checking on your end:

- **Manifest `android:name` must be fully-qualified**, not the relative
  `.recording.AitkenRecordingService` shorthand. Your build namespace is
  `com.chaotic.aitken` (from `app/build.gradle.kts`), but this class's
  actual package declaration is `com.aitken.recording` — the same
  intentional mismatch as every other file in this repo. A relative
  manifest name resolves against the *namespace*, not the package
  declaration, and would fail to find the class at runtime. Use
  `android:name="com.aitken.recording.AitkenRecordingService"` explicitly.
- `startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)`
  needs the type-aware overload, available since API 29 — your minSdk is
  29, so no version branch was needed (this call is always safe).
- The notification icon (`android.R.drawable.ic_menu_compass`) is a system
  placeholder — swap for a real launcher-derived icon before shipping.
- `TURN_YAW_THRESHOLD_RAD_S = 1.0f` is `[CALIBRATE]`, not derived from any
  audited session.

## Manifest additions needed (not shipped as a file — see ticket 03's
process correction: source only, precise diff instructions for build files)
```xml
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name="com.aitken.recording.AitkenRecordingService"
    android:foregroundServiceType="location"
    android:exported="false" />
```
`ACCESS_FINE_LOCATION` is already there from ticket 02.

## RecordingScreen — minimal, deliberately not the full DebugScreen port
Ticket 10's acceptance criterion is the start/stop toggle only — the
waveform canvas and tap buttons are ticket 11's job (T7's design already
scoped it that way). Kept this screen to exactly the toggle so it doesn't
quietly absorb ticket 11's scope.

## What compiles today vs. what needs verification
- `SensorStream.kt`, `AndroidSensorStream.kt` — mechanical port, same
  compile profile as the original (needs `android.hardware`/`android.os`,
  already available, no new dependency).
- `RecordingPipeline.kt` + its test — pure Kotlin, no new dependency,
  should compile and run as-is.
- `AitkenRecordingService.kt`, `RecordingScreen.kt` — need the manifest
  additions above before the service will actually run; Compose is already
  in your `app/build.gradle.kts` from ticket 03.

## Frontier after this
- Ticket 11 (Manual tagging UI) — blocked by 06 and 10, both done now.
- Ticket 12 (Backup + config sync wired into lifecycle) — blocked by 07,
  08, 10, all done now.
- Ticket 09 (ClassifierRunner) — blocked by 04 and 07, both done; still
  gated in practice by real ride data existing, same fog noted in map.md.
