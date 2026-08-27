# 10 — Continuous recording session

**What to build:** A rider can start a session in Aitken and it keeps recording
through screen-off, running sensor → DSP → SegmentDetector → SessionRecorder
end-to-end with no network required.

**Blocked by:** 02, 05

**Status:** ready-for-agent

- [ ] Foreground service with a declared `location`-type FGS,
      `ACCESS_BACKGROUND_LOCATION` granted, keeps recording with the screen off
- [ ] Session start/stop toggle adapted from Prototype 1's `DebugScreen`
- [ ] Full pipeline wired: sensor stream → DSP modules → SegmentDetector →
      SessionRecorder writes
- [ ] Stopping a session with an open segment force-closes it (verified
      end-to-end, not just at the SegmentDetector unit level)
- [ ] No network call anywhere on this recording path (an airplane-mode session
      completes normally)
