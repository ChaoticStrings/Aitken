# 01 — Port reused DSP modules into Aitken

**What to build:** `GravityEstimator`, `Verticalizer`, `JerkFilter`, and `RollingStats`
from Prototype 1 living under `com.aitken.dsp` with no logic changes, so every
downstream Aitken module can depend on already-correct DSP primitives instead of
re-deriving them. This is a prefactor, not new behavior — it exists to make the rest
of the ticket set easy to build.

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [x] `GravityEstimator`, `Verticalizer`, `JerkFilter`, `RollingStats` moved from
      `com.crater.dsp` to `com.aitken.dsp`
- [x] Corresponding `*Test.kt` files (`GravityEstimatorTest`, `VerticalizerTest`,
      `JerkFilterTest`, `RollingStatsTest`) moved and pass unchanged under the new
      package
- [x] No behavioral changes — a diff review confirms only package/import statements
      changed
- [x] Re-verified against real session CSV differential replay (the same method used
      to audit Prototype 1) that outputs are unchanged after the move
