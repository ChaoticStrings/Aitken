# 21 — Debounce manual tag taps

**What to build:** A per-button cooldown so a shaking mount can't register
the same tag twice from one bump of the finger against the screen.

**Prompted by:** field feedback (b) — "the bike and my phone mounted on it
shake so much that my finger taps a button twice."

**Blocked by:** None — `RecordingPipeline.tag()` and `TagButtons` (both from
ticket 11/20) already exist and are merged.

**Status:** ready-for-agent

- [ ] Cooldown is a `Tunables` field, not a hardcoded constant — persisted
      via `SettingsStore` like the rest of `Tunables`
- [ ] Exposed as a slider in `SettingsScreen`, range 200–1200ms
- [ ] **Live-editable mid-session** — explicitly *not* "applies on next
      START SESSION" like the rest of `Tunables`, since it never touches
      the data pipeline (it only gates whether a tap reaches `tag()` at
      all, same as `Tunables` values that are already read fresh each
      session — this one's just read fresh on every tap instead)
- [ ] A second tap of the *same* button within the cooldown window is
      ignored, not queued or merged
- [ ] Taps on *different* buttons are never debounced against each other —
      only identical repeats
- [ ] Range-tag START/END taps are debounced independently from point taps
- [ ] Lives in `RecordingPipeline.tag()`, not the UI layer — testable on
      the JVM without Compose, consistent with this project's pure-logic-
      first pattern
- [ ] Unit test: two `tag()` calls for the same kind inside the cooldown
      produce one recorded label
- [ ] Unit test: two `tag()` calls for the same kind outside the cooldown
      both record
- [ ] Unit test: changing the cooldown value between two calls uses the
      value in effect at the second call's time, not the first

**Note:** doesn't touch `TagMatcher`'s lookback/matching logic — it only
decides whether a tap reaches `tag()` a second time in the first place.
