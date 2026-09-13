#!/usr/bin/env node
// Generates the synthetic micro-fixtures Plan §9 / ticket 01 list under
// test/fixtures/synthetic/<name>/. Unlike make-fixture.mjs (which trims a
// *real* recorded session), every row here is deterministically synthesised
// — no field is copied from a real ride — so these fixtures can be committed
// and regenerated without any external session data.
//
// Formula (documented per ticket 01's acceptance criterion): each sensor row
// is 100 Hz (10,000,000 ns per tick). `vertical_ms2` is
// `9.81 + 1.5 * sin(2*pi*3*t) `— a clean 3 Hz oscillation around gravity, so
// a segment detector fed this data would see a plausible signal-true
// stretch without needing a real recording. `jerk_ms3` is the discrete
// derivative of `vertical_ms2` over the row's `dt`. `roll_std_dev` is a
// rolling std over the last 20 samples of `vertical_ms2` (or fewer at the
// start). `accel_x/y/z` hold gravity on Z (9.81) plus the same oscillation
// on X, zero on Y. Gyro, when present, is a small constant turn rate.
//
// Run: `node scripts/make-synthetic.mjs` (also `npm run make-synthetic`).

import { mkdirSync, writeFileSync, rmSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const outRoot = path.join(here, '..', 'test', 'fixtures', 'synthetic');

const HZ = 100;
const TICK_NS = 1_000_000_000 / HZ; // 10,000,000 ns
const EPOCH_MS_0 = 1_788_000_000_000; // arbitrary fixed epoch, documented here only

/** @param {number} n number of rows @param {{ gyro?: boolean, startNs?: bigint }} [opts] */
function buildSensorRows(n, opts = {}) {
  const gyro = opts.gyro ?? true;
  const startNs = opts.startNs ?? 0n;
  const rows = [];
  const verticals = [];
  for (let i = 0; i < n; i++) {
    const tNs = startNs + BigInt(i) * BigInt(TICK_NS);
    const tSec = Number(tNs) / 1e9;
    const vertical = 9.81 + 1.5 * Math.sin(2 * Math.PI * 3 * tSec);
    verticals.push(vertical);

    const window = verticals.slice(Math.max(0, i - 19), i + 1);
    const mean = window.reduce((a, b) => a + b, 0) / window.length;
    const variance = window.reduce((a, b) => a + (b - mean) ** 2, 0) / window.length;
    const rollStdDev = Math.sqrt(variance);

    const jerk = i === 0 ? '' : ((vertical - verticals[i - 1]) / (TICK_NS / 1e9)).toFixed(3);
    const accelX = (1.5 * Math.sin(2 * Math.PI * 3 * tSec)).toFixed(3);
    const gx = gyro ? '0.020' : '';
    const gy = gyro ? '-0.010' : '';
    const gz = gyro ? '0.005' : '';

    rows.push(
      [
        1,
        tNs.toString(),
        accelX,
        '0.000',
        '9.810',
        gx,
        gy,
        gz,
        vertical.toFixed(3),
        jerk,
        rollStdDev.toFixed(4),
      ].join(','),
    );
  }
  return rows;
}

function sensorHeader() {
  return 'schema_version,timestamp_sensor_ns,accel_x_ms2,accel_y_ms2,accel_z_ms2,gyro_x_rads,gyro_y_rads,gyro_z_rads,vertical_ms2,jerk_ms3,roll_std_dev';
}

function gpsHeader() {
  return 'schema_version,timestamp_sensor_ns,latitude,longitude,speed_mps,accuracy_m';
}

function segmentsHeader() {
  return 'schema_version,start_ns,duration_ns,peak_m,rms_m,speed_mps,epoch_ms';
}

function labelsHeader() {
  return 'schema_version,timestamp_sensor_ns,kind,segment_start_ns,label,tap_offset_ms,epoch_ms';
}

function buildGpsRows(startNs, count) {
  const rows = [];
  for (let i = 0; i < count; i++) {
    const tNs = startNs + BigInt(i) * BigInt(1_000_000_000);
    rows.push([1, tNs.toString(), (18.5204 + i * 0.0001).toFixed(6), (73.8567 + i * 0.0001).toFixed(6), i === 0 ? '' : '4.200', '5.000'].join(','));
  }
  return rows;
}

function config(overrides = {}) {
  return JSON.stringify(
    {
      schemaVersion: 1,
      calibrationDurationMs: 10000,
      stdFactor: 3.0,
      floorStd: 0.05,
      endQuietMs: 400,
      minSegmentDurationMs: 50,
      turnYawThresholdRadS: 0.6,
      mildSeverityDeviation: 3.0,
      moderateSeverityDeviation: 8.0,
      tagDebounceMs: 800,
      longSegmentWarningMs: 20000,
      calibratedShortStdThreshold: 3.64617,
      calibratedLongStdThreshold: 5.0351295,
      ...overrides,
    },
    null,
    2,
  );
}

function writeFixture(name, files) {
  const dir = path.join(outRoot, name);
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
  for (const [filename, content] of Object.entries(files)) {
    writeFileSync(path.join(dir, filename), content, { encoding: 'utf-8' });
  }
  console.log(`wrote test/fixtures/synthetic/${name}/ (${Object.keys(files).join(', ')})`);
}

function csv(header, rows) {
  return [header, ...rows].join('\n') + '\n';
}

// --- no-gyro: gyro columns present, every value empty (matches the real
//     140717 fixture's actual behaviour per Plan §6.1) ---
{
  const rows = buildSensorRows(200, { gyro: false });
  writeFixture('no-gyro', {
    'sensor.csv': csv(sensorHeader(), rows),
    'gps.csv': csv(gpsHeader(), buildGpsRows(0n, 2)),
    'segments.csv': csv(segmentsHeader(), [[1, 0, 2_000_000_000, '1.500', '0.950', '4.200', EPOCH_MS_0].join(',')]),
    'labels.csv': csv(labelsHeader(), [[1, 500_000_000, 'POINT', 0, 'Pothole', 500, EPOCH_MS_0 + 500].join(',')]),
    'config.json': config(),
  });
}

// --- no-gps: gps.csv absent entirely ---
{
  const rows = buildSensorRows(200);
  writeFixture('no-gps', {
    'sensor.csv': csv(sensorHeader(), rows),
    'segments.csv': csv(segmentsHeader(), [[1, 0, 2_000_000_000, '1.500', '0.950', '4.200', EPOCH_MS_0].join(',')]),
    'labels.csv': csv(labelsHeader(), [[1, 500_000_000, 'POINT', 0, 'Pothole', 500, EPOCH_MS_0 + 500].join(',')]),
    'config.json': config(),
  });
}

// --- no-labels: labels.csv absent entirely ---
{
  const rows = buildSensorRows(200);
  writeFixture('no-labels', {
    'sensor.csv': csv(sensorHeader(), rows),
    'gps.csv': csv(gpsHeader(), buildGpsRows(0n, 2)),
    'segments.csv': csv(segmentsHeader(), [[1, 0, 2_000_000_000, '1.500', '0.950', '4.200', EPOCH_MS_0].join(',')]),
    'config.json': config(),
  });
}

// --- no-segments: segments.csv absent entirely (labels exist but every tag
//     is necessarily unmatched) ---
{
  const rows = buildSensorRows(200);
  writeFixture('no-segments', {
    'sensor.csv': csv(sensorHeader(), rows),
    'gps.csv': csv(gpsHeader(), buildGpsRows(0n, 2)),
    'labels.csv': csv(labelsHeader(), [[1, 500_000_000, 'POINT', '', 'Pothole', '', EPOCH_MS_0 + 500].join(',')]),
    'config.json': config(),
  });
}

// --- gaps: one 2 s hole. 100 rows (1 s), a 2 s timestamp jump, then 100
//     more rows (1 s) — 2 s of actual samples, per ticket 01's "≤ 2 s of
//     synthetic data" budget, spanning a 4 s wall-clock range. ---
{
  const before = buildSensorRows(100, { startNs: 0n });
  const gapStartNs = 100n * BigInt(TICK_NS) + 2_000_000_000n;
  const after = buildSensorRows(100, { startNs: gapStartNs });
  writeFixture('gaps', {
    'sensor.csv': csv(sensorHeader(), [...before, ...after]),
    'gps.csv': csv(gpsHeader(), buildGpsRows(0n, 4)),
    'segments.csv': csv(segmentsHeader(), []),
    'labels.csv': csv(labelsHeader(), []),
    'config.json': config(),
  });
}

// --- unknown-label: a label string outside the seeded vocabulary
//     (Pothole/Bump/Speedbreaker/Rough stretch, Plan §6.6) ---
{
  const rows = buildSensorRows(200);
  writeFixture('unknown-label', {
    'sensor.csv': csv(sensorHeader(), rows),
    'gps.csv': csv(gpsHeader(), buildGpsRows(0n, 2)),
    'segments.csv': csv(segmentsHeader(), [[1, 0, 2_000_000_000, '1.500', '0.950', '4.200', EPOCH_MS_0].join(',')]),
    'labels.csv': csv(labelsHeader(), [[1, 500_000_000, 'POINT', 0, 'Gravel Patch', 500, EPOCH_MS_0 + 500].join(',')]),
    'config.json': config(),
  });
}

// --- schema-v2: sensor.csv declares a schema_version this Workbench does
//     not know (user story 6: refused with a precise message) ---
{
  const rows = buildSensorRows(50).map((row) => row.replace(/^1,/, '2,'));
  writeFixture('schema-v2', {
    'sensor.csv': csv(sensorHeader(), rows),
    'gps.csv': csv(gpsHeader(), buildGpsRows(0n, 1)),
    'config.json': config({ schemaVersion: 2 }),
  });
}

// --- range-problems: an unpaired RANGE_START, an inverted pair (END before
//     START), and an overlapping pair (Plan §11 E-31/E-32 territory) ---
{
  const rows = buildSensorRows(200);
  const labelRows = [
    // Unpaired: RANGE_START with no matching RANGE_END anywhere in the file.
    [1, 100_000_000, 'RANGE_START', '', 'Rough stretch', '', EPOCH_MS_0 + 100].join(','),
    // Inverted: the END's timestamp precedes its START's.
    [1, 900_000_000, 'RANGE_START', '', 'Rough stretch', '', EPOCH_MS_0 + 900].join(','),
    [1, 850_000_000, 'RANGE_END', '', 'Rough stretch', '', EPOCH_MS_0 + 850].join(','),
    // Overlapping: two well-formed pairs whose spans intersect.
    [1, 1_000_000_000, 'RANGE_START', '', 'Rough stretch', '', EPOCH_MS_0 + 1000].join(','),
    [1, 1_400_000_000, 'RANGE_END', '', 'Rough stretch', '', EPOCH_MS_0 + 1400].join(','),
    [1, 1_200_000_000, 'RANGE_START', '', 'Rough stretch', '', EPOCH_MS_0 + 1200].join(','),
    [1, 1_600_000_000, 'RANGE_END', '', 'Rough stretch', '', EPOCH_MS_0 + 1600].join(','),
  ];
  writeFixture('range-problems', {
    'sensor.csv': csv(sensorHeader(), rows),
    'gps.csv': csv(gpsHeader(), buildGpsRows(0n, 2)),
    'segments.csv': csv(segmentsHeader(), []),
    'labels.csv': csv(labelsHeader(), labelRows),
    'config.json': config(),
  });
}

// --- crlf-bom: same content as a plain fixture, but CRLF line endings and a
//     leading UTF-8 BOM, written raw (bypassing the `csv()`/writeFixture
//     helpers' LF + no-BOM defaults) ---
{
  const rows = buildSensorRows(200);
  const body = [sensorHeader(), ...rows].join('\r\n') + '\r\n';
  const withBom = '\uFEFF' + body;
  const dir = path.join(outRoot, 'crlf-bom');
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
  writeFileSync(path.join(dir, 'sensor.csv'), withBom, { encoding: 'utf-8' });
  writeFileSync(path.join(dir, 'config.json'), config(), { encoding: 'utf-8' });
  console.log('wrote test/fixtures/synthetic/crlf-bom/ (sensor.csv, config.json)');
}

console.log('done.');
