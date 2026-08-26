## Question

Does our eventual map-rendering approach (needed at Crater-maturity for dynamic,
speed-aware hazard overlays) risk locking us into a legacy or deprecated dependency we
didn't see coming from Luna?

## Resolution

Google's Maps SDK for Android is tightly coupled to Google Play Services and its
offline story is limited — Google's own support channels don't describe a robust
offline-region model comparable to competitors. Mapbox's Android SDK, by contrast,
explicitly documents both removing the Play Services dependency and a full offline
tile-pack workflow (regions, storage management, a documented tile-pack limit). Fully
open alternatives (osmdroid over OpenStreetMap tiles) exist too, with a longer history
of offline-first Android use.

Conclusion: don't decide the map provider now — there's no map to render yet at Luna
stage. Decide the *seam*: map rendering sits behind our own interface
(`HazardMapRenderer` or similar), called only through that interface, never through a
vendor SDK type leaking into business logic. This satisfies the replaceability
invariant (T2) directly and means this research doesn't need repeating at Crater's
design phase — it gets re-checked against then-current SDK state, cheaply, because
nothing upstream depends on the answer.

Type: research (AFK) — resolved
Blocked by: none
