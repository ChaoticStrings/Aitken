# 18 — Metric channels (ISO-2631-style) behind ChannelProvider

**What to build:** A third `ChannelProvider`, `MetricChannels`, computed in
the worker from the vertical signal: windowed peak, windowed RMS, rolling VDV,
crest factor, MTVV (running RMS with time constant), band-pass, hybrid
peak/RMS — each with parameter chips shown under the waveform header when
selected and soft comfort-band backgrounds. Clearly labelled *unweighted /
illustrative*, as the prototype did. Purely additive; no review workflow
changes.

**Blocked by:** 04, 12

**Status:** ready-for-agent

**Plan sections:** §1.3 D10, §4.2 (`ChannelProvider`), §7.3.

**Acceptance criteria**
- [ ] `worker/channels.ts` implements the seven metrics as pure functions over `(vertical, tSec, params)` with hand-traced tests (e.g. windowed RMS of a known square wave; VDV of a single spike; crest factor of a sine ≈ √2; band-pass attenuates an out-of-band sine by ≥ 20 dB).
- [ ] `MetricChannels` provider exposes them as channels with `unit`, `description`, `params` (min/max/step/default) and `band` (comfort band key); results cached per (channel, params).
- [ ] Waveform header shows parameter chips (sliders in a popover) when a metric channel is active; comfort bands drawn as soft backgrounds with legends; a persistent "unweighted — relative comparison only" caption.
- [ ] Session-level badge for VDV total and MTVV max in the Inspector's empty state.
- [ ] Switching channels never blocks the main thread (computed in the worker with progress).
- [ ] Demo: switch to RMS, change the window, see bands; switch back to vertical.

**Tests to write first:** `worker/channels` hand-traced unit tests; `app/store` (`channel switch requests worker compute once per params`).
