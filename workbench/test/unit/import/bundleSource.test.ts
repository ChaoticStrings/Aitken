import { describe, expect, it } from 'vitest';
import JSZip from 'jszip';
import { ZipBundleSource, FilesBundleSource } from '../../../src/import/BundleSource';
import { ImportError } from '../../../src/import/errors';

const SENSOR_CSV = 'schema_version,timestamp_sensor_ns,accel_x_ms2\n1,0,9.81\n';
const CONFIG_JSON = '{"schemaVersion":1}';

async function buildZip(entries: Record<string, string>): Promise<Blob> {
  const zip = new JSZip();
  for (const [path, content] of Object.entries(entries)) {
    zip.file(path, content);
  }
  return zip.generateAsync({ type: 'blob' });
}

describe('ZipBundleSource', () => {
  it('zip with top folder and flat zip hash equal (E-01)', async () => {
    const flat = await buildZip({ 'sensor.csv': SENSOR_CSV, 'config.json': CONFIG_JSON });
    const nested = await buildZip({
      'session_20260905_140717/sensor.csv': SENSOR_CSV,
      'session_20260905_140717/config.json': CONFIG_JSON,
    });

    const flatRead = await new ZipBundleSource(flat, 'flat.zip').read();
    const nestedRead = await new ZipBundleSource(nested, 'session_20260905_140717.zip').read();

    expect(flatRead.hash).toBe(nestedRead.hash);
    expect(flatRead.files.has('sensor.csv')).toBe(true);
    expect(nestedRead.files.has('sensor.csv')).toBe(true);
    expect(nestedRead.sessionName).toBe('session_20260905_140717');
  });

  it('ambiguous basename is an ImportError', async () => {
    const zip = await buildZip({
      'sensor.csv': SENSOR_CSV,
      'backup/sensor.csv': SENSOR_CSV,
    });
    const source = new ZipBundleSource(zip, 'weird.zip');
    await expect(source.read()).rejects.toThrow(ImportError);
    await expect(source.read()).rejects.toMatchObject({ code: 'AMBIGUOUS_FILE' });
  });

  it('ignores __MACOSX/ entries and dotfiles (E-01, E-17)', async () => {
    const zip = await buildZip({
      'sensor.csv': SENSOR_CSV,
      '__MACOSX/._sensor.csv': 'garbage',
      '.nomedia': '',
      'logcat.txt': 'not a session file',
    });
    const read = await new ZipBundleSource(zip, 'session.zip').read();
    expect(Array.from(read.files.keys())).toEqual(['sensor.csv']);
  });

  it('session name strips the .zip extension', async () => {
    const zip = await buildZip({ 'sensor.csv': SENSOR_CSV });
    const read = await new ZipBundleSource(zip, 'my_ride.zip').read();
    expect(read.sessionName).toBe('my_ride');
  });
});

describe('FilesBundleSource', () => {
  it('produces the same hash as an equivalent zip', async () => {
    const zip = await buildZip({ 'sensor.csv': SENSOR_CSV, 'config.json': CONFIG_JSON });
    const zipRead = await new ZipBundleSource(zip, 'x.zip').read();

    const files = [
      new File([SENSOR_CSV], 'sensor.csv', { type: 'text/csv' }),
      new File([CONFIG_JSON], 'config.json', { type: 'application/json' }),
    ];
    const filesRead = await new FilesBundleSource(files).read();

    expect(filesRead.hash).toBe(zipRead.hash);
  });

  it('derives session name from webkitRelativePath when a folder was picked', async () => {
    const file = new File([SENSOR_CSV], 'sensor.csv', { type: 'text/csv' });
    Object.defineProperty(file, 'webkitRelativePath', { value: 'my_ride/sensor.csv' });
    const read = await new FilesBundleSource([file]).read();
    expect(read.sessionName).toBe('my_ride');
  });

  it('ambiguous basename is an ImportError for loose files too', async () => {
    const a = new File([SENSOR_CSV], 'sensor.csv');
    const b = new File([SENSOR_CSV], 'sensor.csv');
    Object.defineProperty(a, 'webkitRelativePath', { value: 'ride/sensor.csv' });
    Object.defineProperty(b, 'webkitRelativePath', { value: 'ride/backup/sensor.csv' });
    await expect(new FilesBundleSource([a, b]).read()).rejects.toMatchObject({ code: 'AMBIGUOUS_FILE' });
  });
});
