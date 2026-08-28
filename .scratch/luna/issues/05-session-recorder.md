# 05 — SessionRecorder

**What to build:** Crash-safe incremental writer owning all four session files
(sensor, gps, segments, labels) behind one interface, so a crash mid-ride only costs
unflushed samples and each closed segment is written with its speed and timestamp.

**Blocked by:** 02, 04

**Status:** ready-for-agent

- [x] Sensor, GPS, segments, and labels files each written incrementally with
      crash-safe flush
- [x] Each file carries its own independent `schema_version`
- [x] A simulated crash mid-session loses only unflushed samples, not the whole
      session (test-verified)
- [x] Each closed segment written to the segments file includes vehicle speed (from
      `GpsProvider`) and a timestamp alongside M and D
- [x] Ending a session flushes and closes all four files cleanly
