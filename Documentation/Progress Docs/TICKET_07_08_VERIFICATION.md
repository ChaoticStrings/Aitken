# Tickets 07 & 08 — ClassifierConfigLoader & BackupAgent, implementation notes

## ClassifierConfigLoader (07)

**Scope decision, made deliberately:** the config's internal structure
isn't modeled at all — `ClassifierConfig` wraps opaque `content: ByteArray`
plus `loadedAtMs`. Ticket 09 (ClassifierRunner) and the Colab notebook are
what actually define the real shape, and per map.md that's still fog
("Precise confidence-threshold mechanics for autonomous tagging... stays
fog until [real ride data exists]"). Inventing a placeholder JSON schema
now would mean either guessing wrong or quietly constraining ticket 09's
real design later. This loader's job is fetch/cache/staleness only.

`currentConfig()` never touches storage — it's a synchronous field read.
`checkForUpdate()` fails soft on every error path (no grant, missing file,
empty file) rather than throwing, so a recording session can call
`currentConfig()` at any time with zero risk regardless of sync state.

**Tests** reuse `FakeSafStorageAdapter` from ticket 03 — no new fake
needed. Six cases: null before anything loads, a seeded config returns
without touching (ungranted) storage, and the three soft-failure paths each
leave the cache untouched, plus a successful update using an injected
clock for deterministic `loadedAtMs`.

## BackupAgent (08)

**Design decision:** `enqueueBackup` is synchronous copy logic only — the
"never blocks or gates recording" guarantee comes from *when* it's called
(only after a session is fully closed), which is ticket 12's lifecycle
wiring, not something this class enforces. Keeping the class itself dumb
and synchronous makes it trivially testable; the scheduling concern is a
separate, later problem.

Per-file failures are caught individually (`runCatching` inside the loop,
not around it), so one bad file doesn't abort the rest of the bundle.
Verified with a purpose-built throwing wrapper (`SafStorageAdapter by
delegate`, scoped to the test file only — didn't touch ticket 03's shared
fake).

On the "no other egress exists" criterion: that's true of this file by
construction (its only write path is the injected `SafStorageAdapter`,
nothing else), but it's a repo-wide property a single class's unit tests
can't fully prove — flagged as worth a `/code-review` pass before this
ships, not something this ticket claims to have verified alone.

## What compiles today
Both pure Kotlin, no new dependencies. `BackupAgentTest` uses JUnit's
`TemporaryFolder` for real session files (same pattern as
`SessionRecorderTest`); `ClassifierConfigLoaderTest` needs nothing beyond
what ticket 03 already provides.
