// Plan §5.8 / §6.2: `BundleSource.read()` and `parseSession()` both throw a
// typed `ImportError` rather than a plain `Error` — the Library UI needs the
// `code` to show a specific message (and, per E-08, to guarantee nothing was
// stored when parsing fails), not just "something went wrong".
export type ImportErrorCode = 'AMBIGUOUS_FILE' | 'UNKNOWN_SCHEMA_VERSION' | 'MISSING_SENSOR_CSV' | 'STORAGE_QUOTA_EXCEEDED';

export class ImportError extends Error {
  readonly code: ImportErrorCode;
  readonly details: Record<string, unknown>;

  constructor(code: ImportErrorCode, details: Record<string, unknown> = {}) {
    super(ImportError.messageFor(code, details));
    this.name = 'ImportError';
    this.code = code;
    this.details = details;
  }

  private static messageFor(code: ImportErrorCode, details: Record<string, unknown>): string {
    switch (code) {
      case 'AMBIGUOUS_FILE':
        return `More than one file matches basename "${details.basename}"`;
      case 'UNKNOWN_SCHEMA_VERSION':
        return `${details.file} declares schema_version ${details.version}, which this Workbench doesn't know how to read`;
      case 'MISSING_SENSOR_CSV':
        return 'sensor.csv is required but was not found in this bundle';
      case 'STORAGE_QUOTA_EXCEEDED':
        return 'Not enough storage space to save this session. Try deleting an older one first.';
      default:
        return code;
    }
  }
}
