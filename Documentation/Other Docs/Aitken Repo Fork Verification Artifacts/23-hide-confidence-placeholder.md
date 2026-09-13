# 23 — Remove the confidence indicator (Aitken-phase, not Luna)

**What to build:** `AitkenUiState.confidenceLabel` is a hardcoded `"—"` that
no code path ever changes (verified by reading the source — no writer
exists anywhere except its own default). Confirmed with Vision: this
belongs to the Aitken phase (ticket 13, once `ClassifierRunner` exists),
not the current Luna manual-tagging phase, so pull it out now rather than
leave a dead placeholder implying something is live.

**Note:** what replaces this slot on screen is now its own ticket — see 27.
This ticket is just the removal.

**Blocked by:** None.

**Status:** ready-for-agent

- [ ] Remove the "Confidence: —" row from `AitkenSessionScreen`
- [ ] Comment out (don't delete) `AitkenUiState.confidenceLabel` and its
      UI wiring, marked clearly for ticket 13 to pick back up later
- [ ] No pipeline or test changes — one composable, one field
