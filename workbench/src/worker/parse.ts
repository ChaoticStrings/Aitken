// Plan §5.8. Pure, no DOM — runs identically inside the real Worker
// (`RealWorkerBridge`) and inline in tests (`InlineWorkerBridge`).
import type { Segment, Tag, TagKind, Ns } from '../core/types';
import { ImportError } from '../import/errors';

export type ImportWarning = { level: 'error' | 'warn' | 'info'; code: string; text: string };

export interface ParsedSensor {
  n: number;
  schemaVersion: number;
  tNs: BigInt64Array;
  tSec: Float64Array;
  ax: Float64Array;
  ay: Float64Array;
  az: Float64Array;
  gx: Float64Array;
  gy: Float64Array;
  gz: Float64Array;
  vertical: Float64Array;
  jerk: Float64Array;
  rollStd: Float64Array;
  gyroCoverage: number;
  gaps: Array<{ atIdx: number; dtMs: number }>;
}

export interface ParsedGps {
  n: number;
  schemaVersion: number;
  tNs: BigInt64Array;
  lat: Float64Array;
  lon: Float64Array;
  speedMps: Float64Array;
  accuracyM: Float64Array;
}

// Plan §6.1's config.json shape. Not in §5.8 by name — defined here, next to
// its only consumer, rather than in core/types.ts, since nothing else reads
// it yet (ticket 03's detector is what actually uses these numbers).
export interface SessionConfig {
  schemaVersion: number;
  calibrationDurationMs: number;
  stdFactor: number;
  floorStd: number;
  endQuietMs: number;
  minSegmentDurationMs: number;
  turnYawThresholdRadS: number;
  mildSeverityDeviation: number;
  moderateSeverityDeviation: number;
  tagDebounceMs: number;
  longSegmentWarningMs: number;
  calibratedShortStdThreshold: number | null;
  calibratedLongStdThreshold: number | null;
}

// E-05's defaults, from `Tunables.kt`, for the fields it names explicitly.
// `tagDebounceMs`/`longSegmentWarningMs`/the two `calibrated*Threshold`
// fields aren't given defaults by E-05 (the latter two are *outputs* of a
// real calibration run, not tunable inputs, so "default" doesn't really
// apply to them) — picked conservative placeholders, flagged here for
// whoever's the first ticket to actually depend on them.
export const DEFAULT_SESSION_CONFIG: SessionConfig = {
  schemaVersion: 1,
  calibrationDurationMs: 10000,
  stdFactor: 3.0,
  floorStd: 0.05,
  endQuietMs: 500,
  minSegmentDurationMs: 30,
  turnYawThresholdRadS: 1.0,
  mildSeverityDeviation: 5,
  moderateSeverityDeviation: 15,
  tagDebounceMs: 500, // not specified by E-05 — placeholder, revisit when a ticket depends on it
  longSegmentWarningMs: 20000, // not specified by E-05 — placeholder, revisit when a ticket depends on it
  calibratedShortStdThreshold: null,
  calibratedLongStdThreshold: null,
};

export interface ParsedSession {
  sensor: ParsedSensor;
  gps: ParsedGps | null;
  segments: Segment[];
  tags: Tag[];
  config: SessionConfig | null;
  review: null; // review.json restoration is ticket 13's job (export-verify-reimport); always null until then
  warnings: ImportWarning[];
  t0Ns: Ns;
}

// ---- CSV primitives -------------------------------------------------------

/** Strips a leading UTF-8 BOM and splits into non-empty lines, tolerating
 * CRLF, bare CR, LF, and trailing blank lines (E-09). */
function splitLines(text: string): string[] {
  const stripped = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
  return stripped.split(/\r\n|\r|\n/).filter((line) => line.length > 0);
}

interface RawCsv {
  columns: string[];
  rows: string[][];
}

function parseCsv(text: string): RawCsv {
  const lines = splitLines(text);
  if (lines.length === 0) return { columns: [], rows: [] };
  const columns = (lines[0] ?? '').split(',');
  const rows = lines.slice(1).map((line) => line.split(','));
  return { columns, rows };
}

/** Column lookup by *name*, never by position (E-10 — headers may be
 * reordered). Returns a function so each row access is an O(1) map lookup,
 * not a per-row `indexOf` over ~200k rows. */
function columnIndexer(columns: string[]): (name: string) => number {
  const index = new Map<string, number>();
  columns.forEach((name, i) => index.set(name, i));
  return (name: string) => index.get(name) ?? -1;
}

function cell(row: string[], idx: number): string {
  if (idx < 0) return '';
  return row[idx] ?? '';
}

function num(v: string): number {
  return v === '' ? NaN : Number(v);
}

function numOrNull(v: string): number | null {
  return v === '' ? null : Number(v);
}

/** First row's `schema_version` column, or 1 if the file has no data rows
 * (an empty-but-present CSV is treated as "nothing to disagree about"). */
function detectSchemaVersion(raw: RawCsv): number {
  if (raw.rows.length === 0) return 1;
  const col = columnIndexer(raw.columns)('schema_version');
  const first = raw.rows[0];
  if (col < 0 || !first) return 1;
  return Number(cell(first, col));
}

// ---- sensor.csv ------------------------------------------------------------

function parseSensorV1(raw: RawCsv): Omit<ParsedSensor, 'schemaVersion'> {
  const idx = columnIndexer(raw.columns);
  const iT = idx('timestamp_sensor_ns');
  const iAx = idx('accel_x_ms2');
  const iAy = idx('accel_y_ms2');
  const iAz = idx('accel_z_ms2');
  const iGx = idx('gyro_x_rads');
  const iGy = idx('gyro_y_rads');
  const iGz = idx('gyro_z_rads');
  const iV = idx('vertical_ms2');
  const iJ = idx('jerk_ms3');
  const iR = idx('roll_std_dev');

  const n = raw.rows.length;
  const tNsRaw: bigint[] = new Array(n);
  const ax = new Float64Array(n);
  const ay = new Float64Array(n);
  const az = new Float64Array(n);
  const gx = new Float64Array(n);
  const gy = new Float64Array(n);
  const gz = new Float64Array(n);
  const vertical = new Float64Array(n);
  const jerk = new Float64Array(n);
  const rollStd = new Float64Array(n);

  let gyroPresent = 0;
  let reordered = 0;
  let prevT = 0n;
  for (let i = 0; i < n; i++) {
    const row = raw.rows[i] ?? [];
    const t = BigInt(cell(row, iT) || '0');
    tNsRaw[i] = t;
    ax[i] = num(cell(row, iAx));
    ay[i] = num(cell(row, iAy));
    az[i] = num(cell(row, iAz));
    const gxv = cell(row, iGx);
    gx[i] = num(gxv);
    gy[i] = num(cell(row, iGy));
    gz[i] = num(cell(row, iGz));
    if (gxv !== '') gyroPresent++;
    vertical[i] = num(cell(row, iV));
    jerk[i] = num(cell(row, iJ));
    rollStd[i] = num(cell(row, iR));
    if (i > 0 && t < prevT) reordered++;
    prevT = t;
  }

  // Stable sort by tNs (E-12). Native Array#sort is stable (ES2019+), but the
  // explicit index tiebreak documents that intent rather than relying on it
  // silently.
  const order = Array.from({ length: n }, (_, i) => i);
  order.sort((a, b) => {
    const ta = tNsRaw[a] as bigint;
    const tb = tNsRaw[b] as bigint;
    return ta < tb ? -1 : ta > tb ? 1 : a - b;
  });

  const pick = <T extends Float64Array>(src: T): T => {
    const out = new (src.constructor as new (n: number) => T)(n);
    for (let i = 0; i < n; i++) out[i] = src[order[i] as number] as number;
    return out;
  };
  const sortedTNs = new BigInt64Array(n);
  for (let i = 0; i < n; i++) sortedTNs[i] = tNsRaw[order[i] as number] as bigint;

  const t0Ns = n > 0 ? (sortedTNs[0] as bigint) : 0n;
  const tSec = new Float64Array(n);
  for (let i = 0; i < n; i++) tSec[i] = Number((sortedTNs[i] as bigint) - t0Ns) / 1e9;

  const gaps: Array<{ atIdx: number; dtMs: number }> = [];
  for (let i = 1; i < n; i++) {
    const dtMs = Number((sortedTNs[i] as bigint) - (sortedTNs[i - 1] as bigint)) / 1e6;
    if (dtMs > 500) gaps.push({ atIdx: i, dtMs });
  }

  return {
    n,
    tNs: sortedTNs,
    tSec,
    ax: pick(ax),
    ay: pick(ay),
    az: pick(az),
    gx: pick(gx),
    gy: pick(gy),
    gz: pick(gz),
    vertical: pick(vertical),
    jerk: pick(jerk),
    rollStd: pick(rollStd),
    gyroCoverage: n === 0 ? 0 : gyroPresent / n,
    gaps,
    // `reordered` isn't part of ParsedSensor's own shape (§5.8 doesn't list
    // it there) — surfaced instead as an ImportWarning by the caller, which
    // has the row-count context to word it. Stashed here transiently.
    __reordered: reordered,
  } as Omit<ParsedSensor, 'schemaVersion'> & { __reordered: number };
}

const SENSOR_PARSERS: Record<number, (raw: RawCsv) => Omit<ParsedSensor, 'schemaVersion'>> = {
  1: parseSensorV1,
};

// ---- gps.csv ---------------------------------------------------------------

function parseGpsV1(raw: RawCsv): Omit<ParsedGps, 'schemaVersion'> {
  const idx = columnIndexer(raw.columns);
  const iT = idx('timestamp_sensor_ns');
  const iLat = idx('latitude');
  const iLon = idx('longitude');
  const iSpeed = idx('speed_mps');
  const iAcc = idx('accuracy_m');

  const n = raw.rows.length;
  const tNs = new BigInt64Array(n);
  const lat = new Float64Array(n);
  const lon = new Float64Array(n);
  const speedMps = new Float64Array(n);
  const accuracyM = new Float64Array(n);

  for (let i = 0; i < n; i++) {
    const row = raw.rows[i] ?? [];
    tNs[i] = BigInt(cell(row, iT) || '0');
    lat[i] = num(cell(row, iLat));
    lon[i] = num(cell(row, iLon));
    speedMps[i] = num(cell(row, iSpeed));
    accuracyM[i] = num(cell(row, iAcc));
  }

  return { n, tNs, lat, lon, speedMps, accuracyM };
}

const GPS_PARSERS: Record<number, (raw: RawCsv) => Omit<ParsedGps, 'schemaVersion'>> = {
  1: parseGpsV1,
};

// ---- segments.csv / labels.csv ---------------------------------------------

function parseSegmentsV1(raw: RawCsv, warnings: ImportWarning[]): Segment[] {
  const idx = columnIndexer(raw.columns);
  const iStart = idx('start_ns');
  const iDur = idx('duration_ns');
  const iPeak = idx('peak_m');
  const iRms = idx('rms_m');
  const iSpeed = idx('speed_mps');
  const iEpoch = idx('epoch_ms');

  const seen = new Set<string>();
  const out: Segment[] = [];
  for (const row of raw.rows) {
    const startNs = BigInt(cell(row, iStart) || '0');
    const key = startNs.toString();
    let id: Segment['id'];
    if (seen.has(key)) {
      id = `s:${key}#2`;
      warnings.push({
        level: 'warn',
        code: 'DUPLICATE_SEGMENT_START',
        text: `More than one segment starts at ${key} ns; the later one is ${id}`,
      });
    } else {
      id = `s:${key}`;
      seen.add(key);
    }
    out.push({
      id,
      startNs,
      durationNs: BigInt(cell(row, iDur) || '0'),
      peakM: num(cell(row, iPeak)),
      rmsM: num(cell(row, iRms)),
      speedMps: numOrNull(cell(row, iSpeed)),
      epochMs: num(cell(row, iEpoch)),
      provenance: 'recorded',
      edited: false,
    });
  }
  return out;
}

function parseLabelsV1(raw: RawCsv, segments: Segment[], warnings: ImportWarning[]): Tag[] {
  const idx = columnIndexer(raw.columns);
  const iT = idx('timestamp_sensor_ns');
  const iKind = idx('kind');
  const iSegStart = idx('segment_start_ns');
  const iLabel = idx('label');
  const iOffset = idx('tap_offset_ms');
  const iEpoch = idx('epoch_ms');

  // Exact bigint equality (never a tolerance window) — matches AC3. When
  // more than one segment shares a start_ns (E-11), the tag links to the
  // first one seen; the duplicate already got its own #2 warning above.
  const byStart = new Map<bigint, Segment>();
  for (const seg of segments) {
    if (!byStart.has(seg.startNs)) byStart.set(seg.startNs, seg);
  }

  const seenTagT = new Set<string>();
  const out: Tag[] = [];
  for (const row of raw.rows) {
    const tNs = BigInt(cell(row, iT) || '0');
    const key = tNs.toString();
    const id: Tag['id'] = seenTagT.has(key) ? `t:${key}#2` : `t:${key}`;
    seenTagT.add(key);

    const segStartText = cell(row, iSegStart);
    let segmentId: Segment['id'] | null = null;
    if (segStartText !== '') {
      const seg = byStart.get(BigInt(segStartText));
      if (seg) {
        segmentId = seg.id;
      }
    }
    if (segmentId === null) {
      warnings.push({
        level: 'warn',
        code: 'TAG_UNMATCHED',
        text: `Tag at ${key} ns (${cell(row, iLabel) || 'unlabeled'}) has no matching segment`,
      });
    }

    out.push({
      id,
      tNs,
      kind: cell(row, iKind) as TagKind,
      label: cell(row, iLabel),
      segmentId,
      tapOffsetMs: numOrNull(cell(row, iOffset)),
      epochMs: num(cell(row, iEpoch)),
      provenance: 'recorded',
      edited: false,
    });
  }
  return out;
}

// ---- config.json -------------------------------------------------------

function parseConfig(text: string): SessionConfig {
  const raw = JSON.parse(text) as Partial<SessionConfig>;
  return { ...DEFAULT_SESSION_CONFIG, ...raw };
}

// ---- top level ---------------------------------------------------------

export function parseSession(files: Map<string, string>): ParsedSession {
  const warnings: ImportWarning[] = [];

  const sensorText = files.get('sensor.csv');
  if (sensorText === undefined) {
    throw new ImportError('MISSING_SENSOR_CSV');
  }
  const sensorRaw = parseCsv(sensorText);
  const sensorSchema = detectSchemaVersion(sensorRaw);
  const sensorParserFn = SENSOR_PARSERS[sensorSchema];
  if (!sensorParserFn) {
    throw new ImportError('UNKNOWN_SCHEMA_VERSION', { file: 'sensor.csv', version: sensorSchema });
  }
  const sensorParsed = sensorParserFn(sensorRaw) as Omit<ParsedSensor, 'schemaVersion'> & { __reordered: number };
  const { __reordered: reordered, ...sensorRest } = sensorParsed;
  if (reordered > 0) {
    warnings.push({
      level: 'warn',
      code: 'REORDERED_TIMESTAMPS',
      text: `sensor.csv had ${reordered} out-of-order timestamp(s); rows were stable-sorted by time`,
    });
  }
  const sensor: ParsedSensor = { ...sensorRest, schemaVersion: sensorSchema };
  if (sensor.gyroCoverage === 0 && sensor.n > 0) {
    warnings.push({ level: 'info', code: 'NO_GYRO', text: 'No gyroscope data in this session (0% coverage)' });
  }

  let gps: ParsedGps | null = null;
  const gpsText = files.get('gps.csv');
  if (gpsText === undefined) {
    warnings.push({ level: 'warn', code: 'MISSING_GPS', text: 'gps.csv not found — map and speed features disabled' });
  } else {
    const gpsRaw = parseCsv(gpsText);
    const gpsSchema = detectSchemaVersion(gpsRaw);
    const gpsParserFn = GPS_PARSERS[gpsSchema];
    if (!gpsParserFn) {
      throw new ImportError('UNKNOWN_SCHEMA_VERSION', { file: 'gps.csv', version: gpsSchema });
    }
    gps = { ...gpsParserFn(gpsRaw), schemaVersion: gpsSchema };
  }

  let segments: Segment[] = [];
  const segmentsText = files.get('segments.csv');
  if (segmentsText === undefined) {
    warnings.push({
      level: 'warn',
      code: 'MISSING_SEGMENTS',
      text: 'segments.csv not found — every tag will be unmatched; try Simulation once available',
    });
  } else {
    segments = parseSegmentsV1(parseCsv(segmentsText), warnings);
  }

  let tags: Tag[] = [];
  const labelsText = files.get('labels.csv');
  if (labelsText === undefined) {
    warnings.push({ level: 'info', code: 'MISSING_LABELS', text: 'labels.csv not found — session has zero tags' });
  } else {
    tags = parseLabelsV1(parseCsv(labelsText), segments, warnings);
  }

  let config: SessionConfig | null = null;
  const configText = files.get('config.json');
  if (configText === undefined) {
    warnings.push({
      level: 'warn',
      code: 'MISSING_CONFIG',
      text: 'config.json not found — using Tunables.kt defaults (E-05)',
    });
  } else {
    config = parseConfig(configText);
  }

  return {
    sensor,
    gps,
    segments,
    tags,
    config,
    review: null,
    warnings,
    t0Ns: sensor.n > 0 ? (sensor.tNs[0] as bigint) : 0n,
  };
}
