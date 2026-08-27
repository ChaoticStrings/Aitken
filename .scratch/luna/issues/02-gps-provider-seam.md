# 02 — GpsProvider location seam

**What to build:** Location access wrapped behind a `GpsProvider` interface so Aitken
never calls `FusedLocationProviderClient` directly from business logic, satisfying the
replaceability architecture invariant (T2) and giving every downstream module
(`SessionRecorder`, future Crater) a fake to test against.

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [x] `GpsProvider` interface defined (current fix + fix stream)
- [x] Real adapter implemented over `FusedLocationProviderClient`
- [x] Fake implementation available for tests, emitting scripted fixes
- [x] No call site outside the adapter references `FusedLocationProviderClient`
      directly
- [x] Consumer-facing unit tests exercise logic against the fake only
