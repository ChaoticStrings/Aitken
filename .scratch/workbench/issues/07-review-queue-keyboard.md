# 07 — Review Queue with keyboard-driven review

**What to build:** The Queue panel: an ordered list of Review Items built
from state, with priority/time ordering, progress "n / N" and pace estimate,
J/K navigation that auto-focuses the waveform viewport and selection, 1–9 to
label, Enter to confirm (applying a suggestion if present), X to dismiss, and
a completion state offering "Mark session REVIEWED". Resolutions are Edits,
so they undo and persist.

**Blocked by:** 05, 06

**Status:** ready-for-agent

**Plan sections:** §5.1 (`ReviewItem*`), §5.4, §5.5, §7.2, §8.4, §8.7 (queue advance motion), §11 E-26, E-33…E-36.

**Acceptance criteria**
- [ ] `core/matcher.suggestMatch` mirrors `TagMatcher.kt` (open segment wins; else most recent closed whose end is within lookback before the tap; tap offset from segment end); tests mirror `TagMatcherTest.kt` cases.
- [ ] `buildQueue` emits items for every kind in §5.1 that is computable now (`untagged-high-m`, `unmatched-tag`, `tag-link-disagrees`, `range-unpaired`, `range-inverted`, `range-overlap`, `peak-divergence`, `long-segment`, `edited-unconfirmed`, `unknown-label`), with `blocking` per §7.2, stable `id`, `focusNs`, `score`, and `suggestion` where §7.2 defines one; resolved items excluded; items for missing targets dropped (E-33).
- [ ] `orderQueue` implements the score formula and time mode exactly; unit tests with hand-built states assert order.
- [ ] Store: `queue` computed signal, `queueIndex` clamped after rebuilds (E-35); stepping sets `selection.primary`, animates viewport to `focusNs` padded 25 % (min 2 s) via the store's 180 ms interpolation (0 under reduced motion).
- [ ] Keys per §8.4: `J/K/↓/↑`, `1–9` (untagged segment → `TagCreate` POINT at segment end, `tapOffsetMs 0`, provenance created — E-26), `Enter` (apply suggestion if unapplied, then `ReviewResolve confirmed`), `X` (dismissed), `A` (suggestion only); keys ignored in inputs; store validates a suggestion's target still exists, else toasts "suggestion outdated" (E-36).
- [ ] Auto-advance after resolve (setting, default on); completion state with "Mark session REVIEWED" → `ReviewStateSet`; re-evaluates when new items appear (E-34).
- [ ] Progress "n / N" and pace estimate (rolling mean of last 10 intervals, hidden until 3 resolutions).
- [ ] Queue advance motion per §8.7; rows are keyboard-focusable and clickable.
- [ ] Demo: open fixture → Start review → clear the queue with keys only → mark REVIEWED → reload → still REVIEWED with resolutions intact.

**Tests to write first:** `core/matcher` and `core/queue` rows of Plan §12; `app/store` (`queue step sets viewport and selection`, `resolve persists across reopen`, `stale suggestion ignored`).
