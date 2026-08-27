# 05 — SessionRecorder

**What to build:** Crash-safe incremental writer owning all four session files
(sensor, gps, segments, labels) behind one interface, so a crash mid-ride only costs
unflushed samples and each closed segment is written with its speed and timestamp.

**Blocked by:** 02, 04

**Status:** ready-for-agent

- [ ] Sensor, GPS, segments, and labels files each written incrementally with
      crash-safe flush
- [ ] Each file carries its own independent `schema_version`
- [ ] A simulated crash mid-session loses only unflushed samples, not the whole
      session (test-verified)
- [ ] Each closed segment written to the segments file includes vehicle speed (from
      `GpsProvider`) and a timestamp alongside M and D
- [ ] Ending a session flushes and closes all four files cleanly
