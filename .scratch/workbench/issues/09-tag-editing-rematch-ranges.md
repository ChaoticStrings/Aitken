# 09 — Tag editing: add point/range, move, delete, rematch, range problems

**What to build:** `P` arms point-tag placement (next click on the plot
places, label picker appears), `R` arms range placement (two clicks),
existing pins and range handles drag (undo-correct: one Edit on release),
tags can be deleted, unmatched tags show a suggested segment with one-key
Accept, and range problems (unpaired / inverted / overlap) appear as Queue
items with their §7.2 suggestions and Inspector fix buttons.

**Blocked by:** 07, 08

**Status:** ready-for-agent

**Plan sections:** §5.2 (`TagCreate/Delete/Move/Rematch`), §5.4, §5.5 (`buildRanges`), §7.2 (suggestions), §7.3 (tag lane, range handles), §7.4 (Tag and Range variants), §8.4 (`P`, `R`, `A`, `Esc`, `Delete`), §11 E-07, E-22, E-25, E-31, E-32.

**Acceptance criteria**
- [ ] Arm modes live in the interaction machine (`armed: null | 'point' | 'rangeStart' | 'rangeEnd' | 'segment'`); `Esc` cancels; a hint chip shows the current mode; clicks while armed never select or pan.
- [ ] `TagCreate` for a placed point tag: `tNs` at click, `kind POINT`, label from picker (default: last used), `segmentId` from `suggestMatch`, `tapOffsetMs` from the match or null, `provenance created`.
- [ ] Range placement creates a `RANGE_START` and `RANGE_END` pair with the same label as **one** journal entry: this ticket introduces the `Batch` Edit (Plan §5.2) — ~15 lines of reducer plus tests; ticket 10 reuses it for bulk actions.
- [ ] Dragging a pin or a range handle: undo snapshot is the pre-drag state (one `TagMove` on release); range handles clamp so a START never passes its END (E-18 analogue).
- [ ] `Delete` on a tag → `TagDelete`; on a segment → `SegmentDelete` with toast offering undo; tags of a deleted segment become unmatched and appear in the Queue (E-25).
- [ ] Inspector Tag variant: matched segment with Locate, or *unmatched* + Suggest (shows candidate on the waveform) + Accept (`TagRematch`); `A` key does the same for the current queue item.
- [ ] Inspector Range variant: label, start/end inputs, duration, problems list with fix buttons that apply the §7.2 suggestions.
- [ ] Queue items `range-unpaired`, `range-inverted`, `range-overlap`, `unmatched-tag`, `tag-link-disagrees` carry suggestions per §7.2; e2e: fixture's real unlinked `RANGE_START` is resolvable with `A` then `Enter` (E-07).
- [ ] Demo: place a point tag on an untagged segment, drag it, delete it, undo all; fix the fixture's unpaired range via the queue.

**Tests to write first:** `core/reducer` (`Batch applies sequentially`, `Batch skips unknown inner kinds`), `core/matcher` extended with `rematch suggestion for range start`, `core/queue` (`range problems carry suggestions`), `interaction/machine` (`armed point click emits place intent and does not pan`, `range handle clamps`), `app/store` (`delete segment unmatches its tags and queue grows`).
