// Plan §5.1 — this ticket only needs the shapes that `worker/parse.ts`
// produces (Segment, Tag) plus the id/time primitives they're built from.
// `ReviewItem`, `SessionState`, `Resolution`, `Blocking` are also in §5.1 but
// belong to `core/reducer.ts`/`core/queue.ts` (a later ticket, per the
// vertical-slice ordering in §10) — adding them now with nothing to produce
// or consume them would be exactly the "speculative abstraction" rule 0.5
// warns against. They're added when that ticket needs them.

/** Nanoseconds on the sensor clock (`timestamp_sensor_ns`). Plain `number`
 * loses integer precision above 2^53 (~9.0e15) — device uptime clocks can
 * exceed that after ~104 days (E-13), so every timestamp in this codebase
 * is a `bigint`, not a `number`, from the moment it's parsed. */
export type Ns = bigint;

/** `s:<start_ns>` for a segment that came from the recorded `segments.csv`
 * (or was derived from one, e.g. `s:<start_ns>#2` for a duplicate-start
 * collision, §6.2/E-11). `n:<id>` is reserved for segments *created* in the
 * Workbench (split/merge/manual) — not produced by this ticket, which only
 * imports recorded segments. */
export type SegId = `s:${string}` | `n:${string}`;

/** Mirrors `SegId`: `t:<...>` for a tag with a stable derived id, `n:<...>`
 * for one created in the Workbench. Ticket 02 always produces `t:` ids,
 * derived from the tag's own `timestamp_sensor_ns` + row index (labels.csv
 * has no natural unique key on its own — two POINT taps can share a
 * timestamp in principle, even though the real fixture doesn't). */
export type TagId = `t:${string}` | `n:${string}`;

export type TagKind = 'POINT' | 'RANGE_START' | 'RANGE_END';

export interface Segment {
  id: SegId;
  startNs: Ns;
  durationNs: Ns;
  peakM: number;
  rmsM: number;
  speedMps: number | null;
  epochMs: number;
  provenance: 'recorded' | 'created' | 'adopted' | 'split' | 'merged';
  edited: boolean;
}

export interface Tag {
  id: TagId;
  tNs: Ns;
  kind: TagKind;
  label: string;
  segmentId: SegId | null;
  tapOffsetMs: number | null;
  epochMs: number;
  provenance: 'recorded' | 'created';
  edited: boolean;
}
