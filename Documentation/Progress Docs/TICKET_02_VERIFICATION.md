# Ticket 02 — GpsProvider seam, implementation notes

## TDD sequence followed
1. `GpsProvider.kt` — the interface + `GpsFix` value type (pure Kotlin).
2. `FakeGpsProviderTest.kt` written first, red — describes the double's
   required behavior before `FakeGpsProvider` exists: `currentFix()` starts
   null, `start()` registers a callback and can be scripted to fail,
   `emitFix()` updates the cache and notifies the callback, `stop()`
   unregisters the callback but the last known fix stays cached (matching
   real `FusedLocationProviderClient.getLastLocation()` semantics).
3. `FakeGpsProvider.kt` — written to make those tests pass, green.
4. `AndroidGpsProvider.kt` — the real adapter, written last, against the
   interface only.

Same sandbox constraint as ticket 01: no `kotlinc`/Gradle here, so "green" for
step 3 is inferred from a careful read, not an executed run. Run
`./gradlew :app:testDebugUnitTest` locally to confirm — this test class needs
nothing but JUnit, no Android framework, no emulator.

## What compiles today vs. what needs one more step
- `GpsProvider.kt`, `FakeGpsProvider.kt`, `FakeGpsProviderTest.kt` — pure
  Kotlin + JUnit, no new dependency, should compile and run as-is.
- `AndroidGpsProvider.kt` — needs
  `implementation("com.google.android.gms:play-services-location:<version>")`
  added to `app/build.gradle.kts`. Deliberately left unpinned rather than
  guessed, per the existing "don't guess build dependency versions" practice —
  resolve the current version locally (Android Studio's suggestion or
  `./gradlew :app:dependencies`) and add the line yourself. Nothing else in
  this ticket depends on that file compiling.

## Manifest
Added `<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />`
— required by `AndroidGpsProvider`'s permission check. Background location
(`ACCESS_BACKGROUND_LOCATION`) is deliberately not added yet; that's ticket
10's concern once there's a foreground service to attach it to (per T4).

## Architecture invariant check
No call site outside `AndroidGpsProvider.kt` references
`FusedLocationProviderClient`, `LocationCallback`, or any other Play Services
type — satisfies replaceability (T2, invariant 1). A future Play-Services-free
adapter is a new class implementing `GpsProvider`, not a rewrite of anything
that consumes it.
