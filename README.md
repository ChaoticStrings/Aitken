# Aitken

Learning what a road actually does to a vehicle, and eventually, what to do about it.

Aitken is an ongoing, four-stage project. It starts with a phone mounted on a
scooter, records what the road does to it, and is meant to grow — piece by piece,
as each piece proves itself — into a system that can say how rough a stretch of
road actually is, share that with other riders, and eventually turn a bad enough
one into an actual report to whoever's responsible for it. Only the first stage is
built today. This document explains all four, what's real right now versus what's
still an idea, how to run the parts that exist, and what's still genuinely
unresolved.

## The four-stage idea

```text
   ┌─────────┐      ┌─────────┐      ┌──────────┐      ┌──────────┐
   │  Luna   │ ───▶ │ Aitken  │ ───▶ │  Crater  │ ───▶ │ Artemis  │
   │ detect  │      │classify │      │aggregate │      │   act    │
   └─────────┘      └─────────┘      └──────────┘      └──────────┘
      built,          being            not yet            not yet
   being tuned       explored           started            started
```

Each stage answers a different question, and each is deliberately kept as its own
thing rather than folded into one enormous app:

- **Luna** — a phone records a ride and picks out disturbances in the motion
  signal. *"Something happened here."*
- **Aitken** — takes what Luna found and works out what it actually was.
  *"What happened here?"*
- **Crater** — combines many riders' classified segments into a shared, corroborated
  picture of road conditions. *"What does this whole road look like?"*
- **Artemis** — turns a sufficiently corroborated hazard into a real, human-reviewed
  report. *"What can actually be done about it?"*

They're connected by data flowing downstream, not by shared code or a shared
release cycle. A change to how Crater renders a map should never block a fix to
Luna's detector, and a slow, careful design process for Artemis (which touches
real-world reporting, and needs to get that right) shouldn't hold up Crater's map
from shipping. Right now, only Luna has working code. Aitken is active research.
Crater and Artemis are brainstorming — real design constraints have been decided,
but nothing has been built.

---

## Luna — recording and detection

Luna's job is narrow on purpose: turn raw phone-sensor data into a clean, labeled
record of what the road did, without losing information a later stage might need.

### How a ride becomes a segment

```text
 accelerometer + gyroscope samples (~100+ Hz)
              │
              ▼
    GravityEstimator          — a slow-moving low-pass estimate of "which way
              │                  is down," so results don't depend on exactly
              ▼                  how the phone is mounted
     Verticalizer              — projects the raw 3-axis reading onto that
              │                  direction → one signed "vertical" number
      ┌───────┴────────┐
      ▼                ▼
 short window      long window     — rolling standard deviation, ~200ms and
 (~200 ms)          (~30 s)          ~30s respectively; either one crossing
      └───────┬────────┘             its own threshold counts as "signal"
              ▼
       SegmentDetector          — hysteresis state machine (below)
              │
              ▼
   closed segment: peak M, RMS M, duration D
```

A **segment** is the atomic thing Aitken measures: one continuous disturbance in
that vertical signal, with no assumption about how long it should last. A
fifty-millisecond pothole and a twenty-minute stretch of broken road are both just
segments — one short, one long.

### Why the detector has no fixed event length

The detector is a small state machine, not a fixed-width event window. It opens a
segment when either rolling window reads above its calibrated threshold, and keeps
it open as long as either one still does — the long window is what lets a brief
smooth patch in the middle of a genuinely rough stretch not prematurely end the
segment, since it still remembers the recent roughness even when the short window
alone would momentarily read "quiet":

```kotlin
fun push(timestampNs: Long, verticalM: Float, turning: Boolean): ClosedSegment? {
    val shortStd = shortWindow.push(verticalM).std
    val longStd = longWindow.push(verticalM).std
    val anySignal = shortStd >= shortStdThreshold || longStd >= longStdThreshold

    return when (phase) {
        IDLE -> { if (anySignal && !turning) open(timestampNs, verticalM); null }
        OPEN -> {
            if (anySignal) { accumulate(verticalM); null }
            else if (quietFor >= endQuietMs) close() else null
        }
    }
}
```

`turning` suppresses a *new* segment from starting (a hard turn produces lateral
forces that look like signal but aren't a road feature) but never cuts an
already-open one short. Peak and RMS magnitude are measured as **deviation from
resting gravity**, not raw magnitude — an earlier version tracked
`abs(vertical)` directly, which quietly favored an ordinary positive spike over a
genuinely larger negative-side swing (a sample that dips well below zero can have
a smaller *raw* magnitude than an average positive one, even though its actual
distance from gravity is the bigger event). Fixing that changed peak-severity
attribution on roughly a quarter of segments in real ride data — a good example of
why this project leans so heavily on riding the actual app over reasoning about it
on paper.

Both thresholds themselves come from a short quiet-road calibration pass at the
start of every session (`NoiseFloorCalibrator`), not a hand-picked constant — a
number tuned for one phone mount, vehicle, and road surface doesn't transfer to
another, and a bad guess would quietly skew which bumps count as signal across the
whole dataset.

### Tagging without taking your hands off the bars

A rider can't safely tap a "pothole" button the instant they hit one — both hands
need to stay on the bars. So Luna lets a tag land late and snaps it backward onto
the moment that actually caused it:

```text
 time ──────────────────────────────────────────────────▶

      [======= segment =======]
                               \
                                \   both hands stay on the bars,
                                 \  ride stabilizes...
                                  \
                                   [ tap: "pothole" ]   ← a few seconds later

 TagMatcher checks the currently-open segment first (if one is still open,
 it's almost certainly the one being tagged). Otherwise it searches recently
 closed segments, most recent first, within a lookback window:
```

```kotlin
fun match(tapTimestampNs: Long, kind: TagKind, openSegment: OpenSegment?): TagMatch {
    if (openSegment != null) return Matched(kind, openSegment.startNs, offsetFrom(openSegment))
    for (segment in recentlyClosedSegments.asReversed()) {
        val offset = tapTimestampNs - segment.endNs
        if (offset in 0..tagLookbackMs) return Matched(kind, segment.startNs, offset)
    }
    return Unmatched(kind, tapTimestampNs)   // surfaced for review, never silently dropped
}
```

An unmatched tap isn't discarded — it's kept and surfaced later for a human to
reconcile, in case the lookback window genuinely missed something.

### What gets recorded

Every session writes five files, each independently versioned so the format can
change over time without breaking older sessions:

| File | Contents |
|---|---|
| `sensor.csv` | Raw accel/gyro, plus the derived vertical, jerk, and rolling std-dev, at full sample rate |
| `gps.csv` | Lat/long, speed, and accuracy, as fixes arrive |
| `segments.csv` | Every closed segment: start time, duration, peak M, RMS M, speed at close |
| `labels.csv` | Every tag: kind (point / range start / range end), which segment it matched (if any), and how late the tap was |
| `config.json` | The exact detection parameters and calibrated thresholds active for that session, written once calibration completes |

That last file matters more than its size suggests. Before it existed, a session
didn't record which parameters produced it, so a strange result couldn't be
distinguished from a genuinely different road without guessing. It's what makes a
session **self-describing** and safely comparable to any other session later,
which everything downstream — the Workbench, and eventually a training corpus —
depends on.

### Where Luna stands right now

The pipeline above runs end-to-end on a real device: continuous foreground-service
recording (screen can be off), live waveform and tag buttons, GPS-backed segments,
and a confirm-before-stop safeguard so an accidental tap can't split one ride into
two. The current work is tuning it — getting the detector's sense of "how bad was
that" to line up with what actually happened on the road, rather than with a
placeholder number.

---

## Aitken — classification

Once Luna has detected a segment, someone still has to decide what it *was*: a
pothole, a speed bump, a stretch of gravel, a rail crossing, noise from a rough
mount. Right now a human does that by tapping a button mid-ride. Aitken's job is to
eventually do it automatically, using **M** (magnitude), **D** (duration), and
**V** (vehicle speed at the time) as the shared representation everything else is
built from:

```text
                    speed (V)
                       │
                       ▼
              ┌─────────────────┐
              │   M/D behavior   │   ← the same physical pothole produces a
              │   at that speed  │      different M and D depending on how
              └─────────────────┘      fast it was hit
                       │
                       ▼
                 feature class
        (pothole / speed bump / rough patch / …)
```

That speed dependency is exactly why this is genuinely unsolved rather than a
matter of picking thresholds: a severity number that ignores speed will call the
same pothole mild at low speed and severe at high speed, even though it's the same
physical defect. This is treated as an empirical question, not a solved one, and a
few directions are being explored in parallel — none committed to yet:

- **Bucketing** — sorting peaks and other derived measurements into explicit M/D/V
  ranges, closer to a rulebook than a model.
- **A learned classifier** — trained on a growing corpus of human-labeled segments
  as it accumulates, rather than hand-picked rules.
- **A "feel-based" classifier** — a human can often recognize a road feature from
  its waveform shape without consciously computing peak or RMS values at all; this
  direction explores learning from comparisons between examples (closer to
  RLHF-style preference learning than to a fixed formula) to try to capture that.
- **Corpus generation in the background** — none of the above is worth calibrating
  against a handful of sessions. Every reviewed session between now and then is
  direct progress, independent of which modeling approach eventually wins.

Whichever approach wins, it has to be judged against real recorded behavior, not
synthetic test cases — a hand-simulated waveform doesn't actually look like a real
road event. Real events show a multi-signed oscillation over 150ms or more,
consistent with how suspension actually responds; a hand-drawn "pothole" tends to
look like a single clean spike regardless of what it's meant to represent. That gap
is a big part of why this stage needs real ride data before it can move fast.

---

## Crater and Artemis — the two layers after this one

Neither of these has any code yet. Both are past the "just an idea" stage and into
real, written-down design constraints — but they're future work, built on whatever
corpus Luna and Aitken produce.

**Crater** would aggregate classified segments from many independent riders into a
shared, corroborated map — closer to how ride-hailing apps infer traffic from many
anonymous trips than to a check-in app. A single accelerometer blip could be a
manhole cover or a curb, not a real road defect; the design leans on requiring
several independent riders to hit the same spot before a segment counts as a
public hazard, rather than trusting any one pass. A few constraints already
decided: no personal leaderboard or "N potholes reported by you" gamification; a
map location visited repeatedly by the same phone is a real de-anonymization risk
and has to be handled as one, not just documented; and tapping a hazard on the map
would show a factual, aggregate record — never a ranked "worst of" list.

**Artemis** would turn a sufficiently corroborated hazard into an actual complaint
routed to whoever's responsible for that stretch of road — with a person always
reviewing and sending, nothing auto-filed. A defect is confirmed fixed the same way
it was confirmed real in the first place: independent riders no longer detecting it
at that location, not a volunteer moderator closing a ticket by hand. Anyone can
contribute a report here, even without a phone mount that qualifies for Crater's
central dataset.

---

## Prototype Workbench

Reviewing hundreds of segments by staring at a CSV doesn't scale, and a phone
screen mid-ride is the wrong place to fix a mislabeled tag. The Workbench is a
desktop tool for reviewing a session after the fact — currently a single,
self-contained HTML file (`Prototype/workbench.html`), no build step, no install.

### What it can do

- **Waveform, map, and segment list side by side.** Pan and zoom the waveform,
  jump between segments with `[` / `]`, click-drag or shift-drag to multiselect,
  and see the GPS track for the same session on a Leaflet/OSM map.
- **Direct manipulation.** Drag a segment's boundary handles right on the
  waveform to correct where it opened or closed; draw a brand-new segment by hand
  if the detector missed one entirely.
- **Undo/redo**, up to 100 steps back (`Ctrl+Z` / `Ctrl+Shift+Z`), and autosave to
  the browser's local storage every few seconds, with a "resume a recent session"
  picker so a closed tab doesn't cost you the review.
- **Bulk operations** across a multiselection: relabel several tags at once,
  auto-match every currently-unmatched tag in the selection against nearby
  segments, or delete several segments/tags in one action.
- **An in-browser "recompute" overlay** — a second, independent implementation of
  the vertical/jerk/rolling-std/crest-factor math (plus a few extras like a
  band-pass filter and an EMA-smoothed RMS) running in JavaScript against the raw
  recorded accelerometer values, drawn alongside the on-device numbers as a sanity
  check that the two agree.
- **A calibration/detector simulator.** Re-run the *same* detection logic shown
  above, in-browser, against different threshold values, and see a diff (added /
  removed / moved / unchanged) against the segments actually recorded — without
  needing to re-ride anything.
- **Data-quality warnings on load** — a dedicated tab flags things like sensor
  gaps, missing gyro coverage, or missing GPS speed before you sink time into
  reviewing a session that's incomplete.
- **A four-stage review status** per session — `UNREVIEWED → IN_REVIEW →
  REVIEWED → EXPORTED` — that automatically drops back to `IN_REVIEW` if you edit
  something after marking it reviewed, so a stale "done" badge never sits next to
  fresh, unreviewed changes.
- **Export with a round-trip check.** Writing the "clean" bundle re-reads its own
  zip immediately afterward and confirms the row counts match before calling the
  export trustworthy, rather than assuming a successful write means a correct one.

### Running it

No backend, no dependencies to install first:

```bash
cd Prototype
npx serve .
```

Open the local address `serve` prints, and open `workbench.html` from the
directory listing (there's no `index.html`, since it's a single deliberately
self-contained file). Serving it over HTTP rather than opening it directly with
`file://` matters here — a few browser features the tool relies on (local file
drag-and-drop, some storage APIs) don't behave consistently under `file://`.

A more structured rewrite — TypeScript, a real component tree, a proper
undo/redo journal instead of a snapshot stack — is being scaffolded separately in
`workbench/`. It's early (a project scaffold, not yet a working tool), and it isn't
what session review actually runs on today. The file above is.

---

## Building Aitken yourself

The recording app is a standard Android project — Kotlin + Jetpack Compose,
built with Gradle.

**Prerequisites**

- Android Studio (recommended), or the Android SDK command-line tools on their own
- A device or emulator running Android 10 (API 29) or newer
- Nothing to install for the JDK itself — the project pins a toolchain version and
  Gradle will fetch it automatically on first build if it isn't already present
  locally

**Get it running**

```bash
git clone https://github.com/ChaoticStrings/Aitken.git
cd Aitken
./gradlew assembleDebug       # builds a debug APK
# or, with a device/emulator connected and USB debugging enabled:
./gradlew installDebug
```

Opening the project root directly in Android Studio and pressing **Run** does the
same thing without touching the command line.

**Tests**

```bash
./gradlew test                  # the pure-logic suite — SegmentDetector,
                                 # TagMatcher, NoiseFloorCalibrator, and the rest
                                 # of the DSP chain are plain Kotlin with no
                                 # Android dependency, so this runs on the JVM
                                 # directly, no device needed
./gradlew connectedAndroidTest  # instrumented tests, needs a device/emulator
```

**Permissions, and why**

The app requests fine, coarse, and background location (to keep logging the GPS
track continuously, including with the screen off); a foreground service with a
declared `location` type, which Android 14+ requires for any continuous
background-location use; and notification permission, to show the persistent
recording notification a foreground service is required to display. Nothing here
enables any network call the app doesn't already make on its own — recorded data
stays on-device unless you point a backup folder at somewhere you control.

---

## Open questions

Documenting what's *unresolved* felt more honest than pretending a coherent design
exists for all of it. In roughly the order they need to be answered:

- **Peak vs. RMS as the severity anchor.** `segments.csv` records both, but only
  peak value is currently used for severity bucketing. Early calibration analysis
  against real matched tags suggests the two behave differently enough that this
  isn't a free choice — but there isn't yet enough labeled data to say confidently
  which one (or some combination, like a crest-factor blend) actually tracks how
  bad an event felt.
- **How much does speed actually change a segment's meaning?** The M/D/V
  interdependency mentioned above is assumed, not yet measured with enough real
  examples to characterize.
- **Is a "feel-based" classifier trainable at all from a realistic corpus size?**
  Learning from human comparisons is an appealing idea specifically because
  hand-designed thresholds are brittle, but it's also the direction that needs the
  most labeled data to even evaluate, let alone train.
- **How well does a calibration transfer across mounts and vehicles?** Every
  session currently calibrates its own thresholds from scratch. Whether that's
  actually necessary, or whether a mount-type/vehicle-type profile could be reused,
  is untested.
- **How big does the corpus need to be before any threshold is safe to hardcode?**
  Related to the first question above — some directional evidence exists already,
  but not yet enough events to treat as statistically meaningful rather than
  suggestive.

---

## In this repo

```text
Aitken/
├── app/              the Android app — Kotlin + Jetpack Compose (Luna, built)
├── Prototype/        workbench.html — the single-file review tool described above
├── workbench/        an early, in-progress structured rewrite of the review tool
└── Documentation/    specs, architecture notes, and design/progress notes
```

---

## Status

Solo, early-stage, and moving as real ride data comes in rather than on a fixed
plan. Luna's pipeline works and is being tuned; Aitken is active research with no
model chosen yet; Crater and Artemis are design constraints on paper, not code.
Not looking for contributors, issues, or stars right now — this is here so the
project is documented as it goes, not as a pitch for help with it yet.
