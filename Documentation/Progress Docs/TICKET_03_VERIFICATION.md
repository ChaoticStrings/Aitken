# Ticket 03 — SafStorageAdapter, implementation notes

## A process correction first
Your build error path (`.../com/chaotic/aitken/AndroidGpsProvider.kt`) showed
me something I'd missed: your real repo already has its own established
`applicationId`/namespace, `compileSdk`, `minSdk`, and Gradle conventions —
different from the from-scratch scaffold I'd been generating alongside
ticket 01. You've clearly been taking the `.kt` source files and merging them
into your real project yourself rather than using my scaffold files, and
that's the right call. From this ticket on I'm only shipping source
files, plus precise "add these lines" instructions for build-file changes —
no more full `build.gradle.kts`/`AndroidManifest.xml` overwrites that risk
clobbering what you've already got.

## TDD sequence followed
1. `SafStorageAdapter.kt` (+ `StorageEntry`) and `SafFolderGrant.kt` —
   interfaces first, pure Kotlin.
2. `FakeSafStorageAdapterTest.kt` and `FakeSafFolderGrantTest.kt` written
   first, red — round-trip, overwrite, delete, and the `list()` direct-
   children-only semantics, all before the fakes existed.
3. `FakeSafStorageAdapter.kt` and `FakeSafFolderGrant.kt` — written to make
   those tests pass, green. (Hand-traced the `list()` logic against both test
   cases myself since I can't execute Kotlin here — walked through the map
   contents step by step for both the root and nested-path calls.)
4. `SharedPreferencesSafFolderGrant.kt`, `AndroidSafStorageAdapter.kt`,
   `StorageGrantScreen.kt` — the real, Android-coupled pieces, written last
   against the interfaces only.

## What compiles today vs. what needs build-file additions
- `SafStorageAdapter.kt`, `SafFolderGrant.kt`, both fakes, both test files —
  pure Kotlin + JUnit, no new dependency, should compile and run as-is.
  `./gradlew :app:testDebugUnitTest` should stay green after dropping these
  in.
- `AndroidSafStorageAdapter.kt` needs one dependency:
  ```kotlin
  implementation("androidx.documentfile:documentfile:1.1.0")
  ```
  This one I checked against Google's current AndroidX release notes rather
  than guessing, so it should be a one-shot addition, not another
  round-trip.
- `StorageGrantScreen.kt` needs Compose, which isn't in your `app/build.gradle.kts`
  yet (tickets 01/02 didn't need it). Add:
  ```kotlin
  plugins {
      // ...your existing plugins...
      id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
  }

  android {
      // ...
      buildFeatures {
          compose = true
      }
  }

  dependencies {
      // ...your existing dependencies...
      implementation("androidx.activity:activity-compose:1.10.0")
      implementation(platform("androidx.compose:compose-bom:2024.12.01"))
      implementation("androidx.compose.ui:ui")
      implementation("androidx.compose.material3:material3")
      implementation("androidx.compose.ui:ui-tooling-preview")
      debugImplementation("androidx.compose.ui:ui-tooling")
  }
  ```
  These are the exact versions from Crater's already-working `app/build.gradle.kts`
  (the one in the documents you shared at the start), not re-picked — lowest
  risk option available.

## No manifest change needed
Storage Access Framework grants come from the user's picker action at
runtime, not a manifest `<uses-permission>` — nothing to add here, unlike
ticket 02's `ACCESS_FINE_LOCATION`.

## Architecture invariant check
No call site outside `AndroidSafStorageAdapter.kt` and
`SharedPreferencesSafFolderGrant.kt` references `DocumentFile`, `Uri`, or
`SharedPreferences` — satisfies replaceability (T2, invariant 1).
`StorageGrantScreen` only ever calls through `SafFolderGrant`, never
`SafStorageAdapter` directly, keeping the "grant" and "use" concerns
separate per T5's original design.

## Still open
`StorageGrantScreen` is a standalone composable, not yet hosted anywhere —
per T7, the settings/onboarding surface it lives in belongs to the front-end
tickets (10/11), not this one.
