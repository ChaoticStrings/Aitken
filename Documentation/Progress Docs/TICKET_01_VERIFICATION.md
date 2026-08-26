# Ticket 01 — port verification

## What was done
`GravityEstimator`, `Verticalizer`, `JerkFilter`, `RollingStats`, and their four
test files were copied from the Crater repo's `com.crater.dsp` package into this
project's `com.aitken.dsp` package using a scripted `sed` substitution of the
package declaration only — no manual retyping, to remove transcription risk.

## Diff proof (no behavioral drift)
A line-by-line `diff` was run between each original file and its ported
counterpart. Every file shows exactly one changed line — the `package`
declaration — and nothing else:

```
--- GravityEstimator.kt ---       1c1  package com.crater.dsp / com.aitken.dsp
--- Verticalizer.kt ---           1c1  package com.crater.dsp / com.aitken.dsp
--- JerkFilter.kt ---             1c1  package com.crater.dsp / com.aitken.dsp
--- RollingStats.kt ---           1c1  package com.crater.dsp / com.aitken.dsp
--- GravityEstimatorTest.kt ---   1c1  package com.crater.dsp / com.aitken.dsp
--- VerticalizerTest.kt ---       1c1  package com.crater.dsp / com.aitken.dsp
--- JerkFilterTest.kt ---         1c1  package com.crater.dsp / com.aitken.dsp
--- RollingStatsTest.kt ---       1c1  package com.crater.dsp / com.aitken.dsp
```

None of the four DSP files import from each other's package (each is
self-contained, "pure Kotlin, no Android imports" per their own docstrings), so
no import-statement fixes were needed anywhere.

## What's NOT verified here
This sandbox has a JVM (`java 21`) but no `kotlinc`, no Gradle, and no network
access to fetch either — so the ported test files could not actually be
compiled or executed here. Given the diff above shows zero logic change, the
tests should pass exactly as they did in Crater, but that's an inference, not a
run result.

**Before merging:** run `./gradlew :app:testDebugUnitTest` (or your IDE's test
runner) against this module locally to turn that inference into a real green
run, and satisfy ticket 01's remaining checkbox — re-verifying against a real
session CSV differential replay — the same way Prototype 1's audit did.

## Scaffolding added
No Aitken Gradle project existed yet, so a minimal one was added to host the
port: `settings.gradle.kts`, top-level `build.gradle.kts`,
`gradle/libs.versions.toml`, `app/build.gradle.kts`, and a placeholder
`AndroidManifest.xml` with no components. Versions (AGP 9.3.1, compileSdk 36,
minSdk 28, JDK 17) mirror Crater's proven configuration, including the
Prototype 1 finding that no separate Kotlin Gradle plugin should be applied —
AGP 9.3.1's built-in Kotlin support conflicts with
`org.jetbrains.kotlin.android`. Compose was deliberately left out of this
module for now; it lands with the front-end tickets (10/11) once there's UI to
build.
