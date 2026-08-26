## Question

Write up the already-made stack decision (Kotlin+Compose / HTML+JS+Node / Python+Colab)
as a teaching document: what alternatives existed, why each was passed over, and the
common mistake an inexperienced Android/mobile-ML developer tends to make at this exact
decision point.

Type: task (AFK)
Status: closed
Blocked by: none

## Resolution

**Aitken (mobile) — Kotlin + Jetpack Compose.**
Alternatives: Flutter/Dart, React Native (both cross-platform); Java + XML Views
(older native Android). Passed over:
- Flutter/RN both put a bridge layer between the app and the sensor APIs
  (SensorManager, FusedLocationProviderClient). That bridge adds latency and
  complexity precisely in Aitken's hottest path — 100Hz+ sensor fusion — for the sake
  of iOS/web portability nothing in this project currently needs.
- Java + XML Views is native and fast, but it's Android's legacy UI paradigm.
  Starting a new project on it would be exactly the "map module needs an odd legacy
  dependency" mistake this whole design phase exists to avoid.
- **Common mistake**: reaching for a cross-platform framework "just in case we need
  iOS/web someday," before any requirement demands it — and paying that tax on the
  single most latency-sensitive part of the app. This is premature generalization:
  optimizing for a hypothetical future need at the expense of the real, present one.

**Workbench (desktop, internal tool) — HTML/JS + Node.js verification harness.**
Alternatives: Electron; a Python GUI (Tkinter/PyQt); doing this work inside Colab
itself. Passed over:
- Electron ships an entire Chromium runtime for what's a single-operator internal
  tool — heavy for no capability gained.
- A Python GUI would match Vision's background better on paper, but Prototype 1's
  workbench (pan/zoom, marker editing) already exists in HTML/JS and works. Rewriting a
  working tool for stack "purity," with no new capability, is wasted effort.
- Colab-as-workbench is tempting since it's already Python, but notebooks are built
  for linear analysis, not the fast, clicky, real-time correction workflow explicitly
  wanted here ("never think 'I wish there was a tool that did X in one click'").
- **Common mistake**: converging every tool onto one language "for consistency," even
  across genuinely different jobs. Consistency helps when tasks are similar; forcing an
  interactive editor into a notebook makes both worse, not simpler.

**ML/calibration — Python + Google Colab.**
Alternatives: on-device TFLite training; a local Jupyter install. Passed over:
- On-device ML was already rejected in Aitken_Build_Guide.md §3 on iteration-speed
  grounds — a notebook re-run is a 10-second loop; on-device is a full
  build/deploy/ride loop.
- A local Jupyter install loses free GPU/TPU access and hands Vision a GPU-driver and
  environment-management burden to maintain personally — directly against the
  solo-maintainable invariant (T2), which explicitly favors managed services over
  self-run infra for exactly this reason.
- **Common mistake**: standing up heavyweight local ML infrastructure before there's a
  proven need for iteration speed or scale that justifies the operational cost —
  premature infrastructure investment.
