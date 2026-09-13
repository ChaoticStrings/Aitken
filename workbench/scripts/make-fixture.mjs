#!/usr/bin/env node
// Trims a REAL recorded session bundle to a fixture usable in this repo,
// per Plan D13/§9: `test/fixtures/session_trimmed_90s/`.
//
//   node scripts/make-fixture.mjs <path-to-session-folder> [--seconds=90]
//
// What it does:
//  1. Reads sensor.csv, gps.csv, segments.csv, labels.csv, config.json from
//     the given folder.
//  2. Keeps sensor rows with `timestamp_sensor_ns <= windowNs` (default 90 s
//     from the first row).
//  3. Keeps segments.csv rows with `start_ns <= windowNs` (a segment
//     straddling the cut is kept whole rather than truncated, since
//     `duration_ns` must stay internally consistent with `peak_m`/`rms_m`
//     computed over the full segment).
//  4. Keeps labels.csv rows with `timestamp_sensor_ns <= windowNs`.
//  5. Keeps gps.csv rows with `timestamp_sensor_ns <= windowNs`, and
//     translates every `latitude`/`longitude` by a FIXED constant offset
//     (`LAT_OFFSET`/`LON_OFFSET` below) so the trimmed fixture never encodes
//     Vision's real ride location. The offset is the same for every row, so
//     relative GPS shape (turns, speed) is preserved for map-rendering
//     tests; absolute position is not.
//  6. Copies config.json byte-for-byte (pass-through raw file, rule 0.8).
//  7. Writes `expected/counts.json` — literal row counts computed here, by
//     this independent script, for the ticket's
//     `fixture has expected row counts` test (rule 0.2: expected values
//     never come from the code under test).
//  8. Zips the same file set to `session_trimmed_90s.zip` alongside the
//     folder.
//
// NOT YET RUN: this script has not been executed against a real session in
// this environment — no real Aitken session bundle (e.g. 140717) is present
// in this repo or sandbox to run it against. See
// Documentation/Progress Docs/WORKBENCH_TICKET_01_VERIFICATION.md for what
// this means for ticket 01's acceptance criteria and what unblocks it.

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import JSZip from 'jszip';

const LAT_OFFSET = 0.0731; // fixed, arbitrary — documented, not Vision's real offset
const LON_OFFSET = -0.1187;

const here = path.dirname(fileURLToPath(import.meta.url));
const outDir = path.join(here, '..', 'test', 'fixtures', 'session_trimmed_90s');

function parseArgs(argv) {
  const [srcArg, ...rest] = argv;
  let seconds = 90;
  for (const arg of rest) {
    const m = /^--seconds=(\d+)$/.exec(arg);
    if (m) seconds = Number(m[1]);
  }
  if (!srcArg) {
    console.error('usage: node scripts/make-fixture.mjs <path-to-session-folder> [--seconds=90]');
    process.exit(1);
  }
  return { srcDir: srcArg, seconds };
}

function readCsvIfExists(dir, name) {
  const p = path.join(dir, name);
  if (!existsSync(p)) return null;
  const text = readFileSync(p, 'utf-8').replace(/^\uFEFF/, '');
  const lines = text.split(/\r\n|\n/).filter((l) => l.length > 0);
  const header = lines[0];
  const rows = lines.slice(1).map((l) => l.split(','));
  return { header, rows };
}

function writeCsv(dir, name, header, rows) {
  writeFileSync(path.join(dir, name), [header, ...rows.map((r) => r.join(','))].join('\n') + '\n', 'utf-8');
}

function colIndex(header, col) {
  return header.split(',').indexOf(col);
}

async function main() {
  const { srcDir, seconds } = parseArgs(process.argv.slice(2));
  const windowNs = BigInt(seconds) * 1_000_000_000n;

  const sensor = readCsvIfExists(srcDir, 'sensor.csv');
  if (!sensor) {
    console.error(`sensor.csv not found in ${srcDir} — sensor.csv is required (Plan §6.2)`);
    process.exit(1);
  }
  const tCol = colIndex(sensor.header, 'timestamp_sensor_ns');
  const firstNs = BigInt(sensor.rows[0][tCol]);
  const cutoffNs = firstNs + windowNs;

  const trimmedSensor = sensor.rows.filter((r) => BigInt(r[tCol]) <= cutoffNs);

  mkdirSync(outDir, { recursive: true });
  writeCsv(outDir, 'sensor.csv', sensor.header, trimmedSensor);

  const counts = { sensor: trimmedSensor.length };

  const segments = readCsvIfExists(srcDir, 'segments.csv');
  if (segments) {
    const sCol = colIndex(segments.header, 'start_ns');
    const trimmed = segments.rows.filter((r) => BigInt(r[sCol]) <= cutoffNs);
    writeCsv(outDir, 'segments.csv', segments.header, trimmed);
    counts.segments = trimmed.length;
  }

  const labels = readCsvIfExists(srcDir, 'labels.csv');
  if (labels) {
    const lCol = colIndex(labels.header, 'timestamp_sensor_ns');
    const trimmed = labels.rows.filter((r) => BigInt(r[lCol]) <= cutoffNs);
    writeCsv(outDir, 'labels.csv', labels.header, trimmed);
    counts.labels = trimmed.length;
  }

  const gps = readCsvIfExists(srcDir, 'gps.csv');
  if (gps) {
    const gCol = colIndex(gps.header, 'timestamp_sensor_ns');
    const latCol = colIndex(gps.header, 'latitude');
    const lonCol = colIndex(gps.header, 'longitude');
    const trimmed = gps.rows
      .filter((r) => BigInt(r[gCol]) <= cutoffNs)
      .map((r) => {
        const out = [...r];
        if (out[latCol]) out[latCol] = (Number(out[latCol]) + LAT_OFFSET).toFixed(6);
        if (out[lonCol]) out[lonCol] = (Number(out[lonCol]) + LON_OFFSET).toFixed(6);
        return out;
      });
    writeCsv(outDir, 'gps.csv', gps.header, trimmed);
    counts.gps = trimmed.length;
  }

  const configPath = path.join(srcDir, 'config.json');
  if (existsSync(configPath)) {
    writeFileSync(path.join(outDir, 'config.json'), readFileSync(configPath));
  }

  const expectedDir = path.join(outDir, 'expected');
  mkdirSync(expectedDir, { recursive: true });
  writeFileSync(path.join(expectedDir, 'counts.json'), JSON.stringify(counts, null, 2) + '\n');

  const zip = new JSZip();
  for (const name of ['sensor.csv', 'segments.csv', 'labels.csv', 'gps.csv', 'config.json']) {
    const p = path.join(outDir, name);
    if (existsSync(p)) zip.file(name, readFileSync(p));
  }
  const zipBuf = await zip.generateAsync({ type: 'nodebuffer' });
  writeFileSync(path.join(here, '..', 'test', 'fixtures', 'session_trimmed_90s.zip'), zipBuf);

  console.log('counts:', counts);
  console.log(`wrote ${outDir} and session_trimmed_90s.zip`);
}

main();
