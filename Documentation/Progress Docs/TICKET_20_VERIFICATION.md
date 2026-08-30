# Ticket 20 (+ ticket 11 correction) — implementation notes

## What prompted this
Vision pointed out, correctly, that despite tickets 10 and 11 being
"done," the app had no way to actually be opened — no `MainActivity`, no
launcher entry, nothing requesting the runtime permissions the recording
path depends on. This wasn't a small gap: it meant nothing built so far
could actually run end-to-end on a device. Filed as ticket 20 rather than
silently patched in, same as ticket 19's precedent.

## Manifest changes needed (source only — see ticket 03's standing
process correction, no full-file overwrites)

```xml
<!-- New, on top of ticket 02's ACCESS_FINE_LOCATION and ticket 10's
     FOREGROUND_SERVICE / FOREGROUND_SERVICE_LOCATION / POST_NOTIFICATIONS /
     ACCESS_BACKGROUND_LOCATION -- if you haven't applied ticket 10's
     manifest instructions yet, do those too, listed again here for
     completeness: -->
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<application ...>
    <!-- New: the actual launcher entry -->
    <activity
        android:name="com.aitken.app.MainActivity"
        android:exported="true"
        android:screenOrientation="portrait"
        android:theme="@style/Theme.Aitken">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity>

    <!-- From ticket 10, in case not yet applied -->
    <service
        android:name="com.aitken.recording.AitkenRecordingService"
        android:foregroundServiceType="location"
        android:exported="false" />
</application>
```

`android:exported="true"` is mandatory for a launcher activity on Android
12+ (any activity with an intent-filter must explicitly declare exported).
`android:name` is fully qualified for the same reason as ticket 10's
service — your build namespace (`com.chaotic.aitken`) doesn't match this
class's actual package declaration (`com.aitken.app`).

## Design decisions

**Idle state is a single big START button, not auto-start.** Confirmed
directly rather than assumed — a deliberate tap before setting off, not a
recording that starts as a side effect of opening the app.

**Same-process service reference, not a bound service/Binder.**
`AitkenRecordingService.tag()` needs to be callable from the UI, but the
service was already a plain `startForegroundService` (unbound) design from
ticket 10. Rather than introduce AIDL/Binder machinery for what's
in-process method calls, added a nullable static `companion object`
reference (`instance`), set in `onCreate`/cleared in `onDestroy`. Simplest
thing that works for a single-process app; flagged here rather than
silently chosen, since it's a real architectural trade-off (this pattern
doesn't survive process death the way a bound service's lifecycle would,
though `START_STICKY` already covers the more common "service killed and
restarted" case).

**`AitkenUiState` is a plain object, not a ViewModel.** The *service* owns
the recording lifecycle, and needs to keep running (and updating this
state) even when the Activity isn't in the foreground — a ViewModel is
scoped to the Activity/Fragment lifecycle, which is the wrong owner here.

**Severity coloring is an explicitly-labeled placeholder, not a real
M-scale.** `docs/DEFAULT_CALIBRATION.md` (Prototype 1) has a genuinely
useful real M0–M10 scale — but it's built from *jerk* magnitude with real
audited anchors (J0=59.9 noise floor, Jmax=2542.2 for M7), and Aitken's
`SegmentDetector.peakM` is *vertical-acceleration* magnitude, a different
signal entirely. Copying Prototype 1's specific numbers onto a different
signal would be a category error dressed up as calibration. What *does*
carry over is the methodology (anchor a log scale to a measured noise
floor and a real observed maximum) — that's future work once Aitken has
its own ride data, cited explicitly in the code so it doesn't get lost.

**One real number did carry forward as-is**: the audited device sample
rate is 125.45 Hz, not the nominal 100 Hz this codebase's comments have
been assuming. Doesn't change any default (window sizes are sample-counted
so they self-adapt), but it's in `Tunables.kt`'s doc for anyone reasoning
about "how many ms is N samples" later.

## Sizing, for the record
`BigTagButton` fills its full grid cell — on a typical phone in portrait,
that's comfortably over 100dp in both dimensions, well above Android's
48dp minimum recommendation. Sliders get their own extra vertical padding
beyond Material3's default specifically because a shaking mount makes
small targets miss-prone; values are quantized to coarse steps rather than
continuous drag for the same reason. None of this is unit-testable —
noted honestly below.

## What's testable here vs. what isn't
- `severityOf()` (`Severity.kt`) is the one genuinely pure, testable piece
  of this whole ticket — 5 tests, hand-verified, covering the mild/
  moderate/severe boundaries and confirming thresholds come from the
  injected `Tunables`, not hardcoded.
- Everything else — `MainActivity`, `AitkenSessionScreen`,
  `SettingsScreen`, `AitkenUiState`, the `AitkenRecordingService` updates —
  is Compose UI and Android framework glue, same category as every prior
  real adapter in this project (`AndroidGpsProvider`,
  `AndroidSafStorageAdapter`). Can't execute or Robolectric-test any of it
  in this sandbox. Caught one real bug on self-review before shipping: a
  missing `setValue` import in `AitkenSessionScreen.kt` (two `var x by
  remember { mutableStateOf(...) }` locals get reassigned later in the
  file, which needs both `getValue` and `setValue` in scope, not just
  `getValue` — same class of mistake as the `StorageGrantScreen` import
  bug from ticket 03, caught this time before shipping instead of after).
- Also fixed a live bug before shipping: the "severe" severity slider's
  range was originally set dynamically to `mildDeviation..40f`, which
  could put the slider in an invalid state if mild was dragged above the
  stored severe value. Changed to a static range with an explicit warning
  in the help text instead.

## RecordingPipeline.kt changes (cumulative, on top of ticket 10)
Purely additive — confirmed via `diff` against the ticket-10-delivered
version: `tag()` method, `lastSensorTimestampNs` tracking (used by `tag()`
instead of any independently-read clock — see that field's doc, which
cites Prototype 1's own audited finding on clock mismatch), and two new
optional UI-observer callbacks (`onLiveVertical`, `onSegmentClosedForUi`).
Nothing from the already-verified ticket 10 logic was touched. Four new
tests added to `RecordingPipelineTest.kt` for `tag()`, same hand-trace
discipline as everything else.

## Frontier after this
Tickets 09 and 12 are still outstanding (deliberately set aside this turn
to focus on the UI gap) — both are otherwise unblocked. Ticket 13
(Auto-tagging integration) is the natural next UI-adjacent ticket once 09
exists, since it's what finally wires the confidence indicator this screen
already has a placeholder for.
