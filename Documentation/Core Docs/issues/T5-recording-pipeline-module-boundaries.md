## Question

Design Aitken's recording-pipeline module boundaries to pseudocode level: sensor
stream → DSP (reused from Prototype 1) → SegmentDetector (new, open-ended/hysteresis)
→ session writer (continuous CSV) → retroactive tag matcher. Signatures,
responsibilities, and the seams between them.

Type: grilling (HITL)
Status: closed
Blocked by: none

Additional scope folded in from T2's resolution: where automatic session backup
writes to (invariant 5b), and the interface shape for non-blocking classifier
auto-sync that falls back to last-known-good config (invariant 4 correction).

## In-progress notes

**SegmentDetector** — designed to pseudocode, resolved:
- Two RollingStats windows (short trigger / long sustain, Q1); RMS+peak accumulate
  over signal-true samples only, never quiet in-segment samples; duration measured to
  last signal-true sample, excluding the hysteresis quiet tail; forced close on
  session stop so an in-progress segment is never silently dropped; turn suppression
  (new, not originally scoped) gates new segment starts only, never truncates an
  already-open one. Ten edge cases enumerated → direct TDD test names.

**Storage mechanism (resolves Q4/Q5's "how")**: Storage Access Framework, one-time
`ACTION_OPEN_DOCUMENT_TREE` picker, persisted URI permission — not direct File-path
writes (blocked by scoped storage) and not MediaStore-into-Documents (undocumented,
fragile). Primary session writes stay in app-private storage the whole time (fast, no
permission risk); BackupAgent does a decoupled best-effort copy to the SAF folder after
a session closes. Same folder/mechanism serves ClassifierConfigLoader's update check.

Remaining for T5: SessionRecorder, TagMatcher, GpsProvider, ClassifierRunner,
ClassifierConfigLoader, BackupAgent.

## Resolution

Closed. SegmentDetector got full pseudocode + edge-case treatment (it's the novel,
high-risk piece the whole redesign depends on). The remaining six modules
(SessionRecorder, TagMatcher, GpsProvider, ClassifierRunner, ClassifierConfigLoader,
BackupAgent) are considered sufficiently designed at the module-table interface level
already recorded in this ticket's original round — thinner risk, mostly straightforward
wrapper/CRUD-shaped logic. Detailed edge cases for these six are deliberately deferred
to each module's own /tdd red-green loop during /implement, rather than enumerated
abstractly now — explicit scoping call, not an oversight.
