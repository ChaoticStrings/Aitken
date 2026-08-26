## Question

Ratify a small, durable set of architecture invariants that every later phase
(Aitken-maturity, Crater, Artemis) gets checked against, so a late-discovered bad
dependency becomes a swap instead of a rewrite. Starter draft to react to:

- Replaceability: third-party integrations (maps SDK, location provider, storage) sit
  behind our own interface, never called directly from business logic.
- Schema versioning: every CSV/data format Aitken produces is versioned from day one.
- No network-only / no network-required assumptions.
- Solo-maintainable: no dependency that assumes a team, an ops function, or infra
  Vision isn't running personally.

Type: grilling (HITL)
Status: closed
Blocked by: none

## Resolution

Six architecture invariants ratified, two full grilling rounds (the second triggered
by a factual correction: Vision has network connectivity everywhere, which changed the
reasoning behind invariant 4 without changing its conclusion):

1. **Replaceability (scoped, not blanket)** — map rendering, location access, and the
   classifier/model-config loader sit behind our own interface, never called directly
   from business logic. Scoped via codebase-design's seam test (two adapters make a
   real seam, one is hypothetical): all three already have a real second adapter in
   view (Mapbox/osmdroid per T3; Play-Services-free devices for location;
   thresholds-now/model-later per Prototype 1's ADR D-1). Compose/AndroidX and the
   session file writer are explicitly *not* wrapped — no real second adapter exists for
   the former, and the latter is an internal format choice, not a vendor dependency.

2. **Schema versioning, per-file** — `sensor`, `gps`, `segments`, and `labels` files
   each carry their own `schema_version`, versioned independently. Rejected a single
   bundle-wide version number: the four files already evolve at different rates
   (`segments` is new, `sensor` is stable), and a shared version would force a bump
   every time any one of them changed.

3. **Solo-maintainable (reworded)** — no dependency that requires a team, an on-call
   rotation, or infrastructure only an organization would run. Corrected from the
   draft's "infra Vision isn't running personally," which read as anti-managed-service
   — backwards for a solo dev, who typically wants *more* managed services, not fewer.

4. **No-network-required, scoped to Aitken** — Aitken's recording path (capture →
   detect → write) never requires connectivity to function. Workbench and Colab may
   assume it. Reasoning corrected mid-session: not "Vision's connectivity might drop"
   (it doesn't) but "Crater's future public riders won't all have it, and they'll be on
   exactly the rural/highway stretches this project cares about most" — same
   conclusion, right reason, now recorded accurately.

5. **Data integrity & consent (split a/b/c)**:
   (a) session writes are incremental and crash-safe — a crash or a destroyed phone
   loses only what wasn't flushed yet, never the whole session;
   (b) session data may be automatically backed up to storage Vision alone controls —
   a safety net, not a disclosure, and enabled specifically because connectivity is
   guaranteed (surfaced by the same correction that touched invariant 4);
   (c) session data is never shared with any other party or service — Crater's future
   community layer included — except through an explicit, user-initiated action.
   (b) and (c) look contradictory at first read; they aren't — the line is backup
   (moving to storage only Vision controls) vs. disclosure (becoming visible to anyone
   else), and only the second is what "no silent egress" was ever protecting against.

6. **Naming** — "architecture invariant" always written in full; never abbreviated to
   bare "invariant," which collides with this codebase's existing DSP sense of the word
   (`VerticalizerTest`'s orientation invariance).

Two open sub-questions surfaced by (b)/(4) that didn't exist before this session —
folded into T5 rather than spun up as separate tickets, since both are really shaping
the session-writer and classifier-loader module interfaces, not standalone decisions:
where does the automatic backup write to, and what does non-blocking classifier
auto-sync (fall back to last-known-good config, never gate a recording session) look
like as an interface.
