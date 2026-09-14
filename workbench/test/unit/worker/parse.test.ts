import { describe, expect, it } from 'vitest';
import { parseSession } from '../../../src/worker/parse';
import { ImportError } from '../../../src/import/errors';
import { loadFixtureFiles } from '../../helpers/fixtures';

describe('parseSession', () => {
  it('parses fixture sensor rows to exact count', () => {
    // Expected value from test/fixtures/session_trimmed_90s/expected/counts.json
    // (written independently by scripts/make-fixture.mjs, not by this parser).
    const files = loadFixtureFiles('session_trimmed_90s');
    const parsed = parseSession(files);
    expect(parsed.sensor.n).toBe(9041);
    expect(parsed.segments).toHaveLength(8);
    expect(parsed.tags).toHaveLength(3);
    expect(parsed.gps?.n).toBe(88);
  });

  it('resolves tag to segment by exact start_ns', () => {
    const files = loadFixtureFiles('session_trimmed_90s');
    const parsed = parseSession(files);
    // Hand-traced from the real labels.csv row:
    // 1,95763386249248,POINT,95760183974402,Pothole,1123,1788597477066
    // — segment_start_ns 95760183974402 matches a real segments.csv row.
    const tag = parsed.tags.find((t) => t.tNs === 95763386249248n);
    expect(tag).toBeDefined();
    expect(tag?.segmentId).toBe('s:95760183974402');
    const seg = parsed.segments.find((s) => s.id === 's:95760183974402');
    expect(seg?.startNs).toBe(95760183974402n);
  });

  it('leaves tag unmatched when link empty', () => {
    const files = loadFixtureFiles('synthetic/no-segments');
    const parsed = parseSession(files);
    expect(parsed.segments).toHaveLength(0);
    expect(parsed.tags).toHaveLength(1);
    expect(parsed.tags[0]?.segmentId).toBeNull();
    expect(parsed.warnings.some((w) => w.code === 'TAG_UNMATCHED')).toBe(true);
  });

  it('refuses unknown schema_version with file and version', () => {
    const files = loadFixtureFiles('synthetic/schema-v2');
    expect(() => parseSession(files)).toThrow(ImportError);
    try {
      parseSession(files);
      expect.unreachable();
    } catch (e) {
      expect(e).toBeInstanceOf(ImportError);
      const err = e as ImportError;
      expect(err.code).toBe('UNKNOWN_SCHEMA_VERSION');
      expect(err.details.file).toBe('sensor.csv');
      expect(err.details.version).toBe(2);
    }
  });

  it('parses headers by name in any order', () => {
    // Same 2 sensor rows as a hand-built fixture, columns shuffled — not one
    // of the checked-in fixtures, since the whole point is that the *order*
    // differs from every real file this Workbench will ever see.
    const shuffled =
      'vertical_ms2,timestamp_sensor_ns,schema_version,jerk_ms3,roll_std_dev,gyro_z_rads,gyro_y_rads,gyro_x_rads,accel_z_ms2,accel_y_ms2,accel_x_ms2\n' +
      '9.81,0,1,,0.0,,,,9.81,0,0\n' +
      '10.2,10000000,1,39.0,0.1,,,,9.5,0.2,0.3\n';
    const files = new Map([['sensor.csv', shuffled]]);
    const parsed = parseSession(files);
    expect(parsed.sensor.n).toBe(2);
    expect(parsed.sensor.tNs[0]).toBe(0n);
    expect(parsed.sensor.tNs[1]).toBe(10000000n);
    expect(parsed.sensor.vertical[0]).toBeCloseTo(9.81, 5);
    expect(parsed.sensor.vertical[1]).toBeCloseTo(10.2, 5);
    expect(parsed.sensor.jerk[0]).toBeNaN(); // empty field
  });

  it('tolerates CRLF and BOM', () => {
    const files = loadFixtureFiles('synthetic/crlf-bom');
    const parsed = parseSession(files);
    expect(parsed.sensor.n).toBe(200);
    // First value would be corrupted (NaN/garbage) if the BOM leaked into
    // the "schema_version" header name and broke the column lookup.
    expect(parsed.sensor.vertical[0]).toBeCloseTo(9.81, 2);
  });

  it('reports gyro coverage for the real fixture, and 0 for the synthetic no-gyro case', () => {
    // WORKBENCH_PLAN.md §6.1 claims "fixture 140717 has zero gyro rows" —
    // that's wrong. The real file's first 6 of 9041 rows (a startup
    // warm-up window before the gyroscope reports) are empty; the rest have
    // real gyro data. Verified directly against the raw CSV, not assumed
    // from the plan — flagged in the ticket 02 verification note as a
    // documentation error, per this project's own "ground truth over
    // documentation" principle.
    const real = parseSession(loadFixtureFiles('session_trimmed_90s'));
    expect(real.sensor.gyroCoverage).toBeCloseTo(9035 / 9041, 6); // (9041-6)/9041
    expect(real.warnings.some((w) => w.code === 'NO_GYRO')).toBe(false);

    const synthetic = parseSession(loadFixtureFiles('synthetic/no-gyro'));
    expect(synthetic.sensor.gyroCoverage).toBe(0);
    expect(synthetic.warnings.some((w) => w.code === 'NO_GYRO')).toBe(true);
  });

  it('stable-sorts non-monotonic timestamps and counts them (E-12)', () => {
    const files = new Map([
      [
        'sensor.csv',
        'schema_version,timestamp_sensor_ns,accel_x_ms2,accel_y_ms2,accel_z_ms2,gyro_x_rads,gyro_y_rads,gyro_z_rads,vertical_ms2,jerk_ms3,roll_std_dev\n' +
          '1,20000000,0,0,9.81,,,,9.81,,0\n' + // out of order: appears before 10000000
          '1,10000000,0,0,9.81,,,,9.80,,0\n' +
          '1,30000000,0,0,9.81,,,,9.82,,0\n',
      ],
    ]);
    const parsed = parseSession(files);
    expect(Array.from(parsed.sensor.tNs)).toEqual([10000000n, 20000000n, 30000000n]);
    expect(parsed.warnings.some((w) => w.code === 'REORDERED_TIMESTAMPS' && w.text.includes('1'))).toBe(true);
  });

  it('records a gap over 500ms (gaps fixture)', () => {
    const files = loadFixtureFiles('synthetic/gaps');
    const parsed = parseSession(files);
    expect(parsed.sensor.gaps.length).toBeGreaterThan(0);
    expect(parsed.sensor.gaps[0]?.dtMs).toBeGreaterThan(500);
  });

  it('flags an unknown label with a warning, not an error', () => {
    const files = loadFixtureFiles('synthetic/unknown-label');
    const parsed = parseSession(files);
    expect(parsed.tags).toHaveLength(1);
    expect(parsed.tags[0]?.label).toBe('Gravel Patch');
  });

  it('missing gps.csv disables gps without throwing (E-02)', () => {
    const parsed = parseSession(loadFixtureFiles('synthetic/no-gps'));
    expect(parsed.gps).toBeNull();
    expect(parsed.warnings.some((w) => w.code === 'MISSING_GPS')).toBe(true);
  });

  it('missing config.json falls back to Tunables.kt defaults (E-05)', () => {
    const parsed = parseSession(loadFixtureFiles('synthetic/no-labels'));
    // no-labels fixture does have a config.json, so use one that doesn't...
    // (no synthetic fixture omits config.json; build one inline instead)
    const noConfig = new Map(loadFixtureFiles('synthetic/no-labels'));
    noConfig.delete('config.json');
    const parsedNoConfig = parseSession(noConfig);
    expect(parsedNoConfig.config).toBeNull();
    expect(parsedNoConfig.warnings.some((w) => w.code === 'MISSING_CONFIG')).toBe(true);
    expect(parsed.config?.calibrationDurationMs).toBe(10000);
  });

  it('duplicate segment start_ns gets a #2 suffix and a warning (E-11)', () => {
    const files = new Map([
      [
        'sensor.csv',
        'schema_version,timestamp_sensor_ns,accel_x_ms2,accel_y_ms2,accel_z_ms2,gyro_x_rads,gyro_y_rads,gyro_z_rads,vertical_ms2,jerk_ms3,roll_std_dev\n' +
          '1,0,0,0,9.81,,,,9.81,,0\n',
      ],
      [
        'segments.csv',
        'schema_version,start_ns,duration_ns,peak_m,rms_m,speed_mps,epoch_ms\n' +
          '1,1000,500,3.0,1.0,4.0,100\n' +
          '1,1000,600,4.0,1.5,4.5,200\n',
      ],
    ]);
    const parsed = parseSession(files);
    expect(parsed.segments.map((s) => s.id)).toEqual(['s:1000', 's:1000#2']);
    expect(parsed.warnings.some((w) => w.code === 'DUPLICATE_SEGMENT_START')).toBe(true);
  });

  it('throws MISSING_SENSOR_CSV when sensor.csv is absent', () => {
    expect(() => parseSession(new Map())).toThrow(ImportError);
    try {
      parseSession(new Map());
    } catch (e) {
      expect((e as ImportError).code).toBe('MISSING_SENSOR_CSV');
    }
  });
});
