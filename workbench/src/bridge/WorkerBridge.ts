import type { ParsedSession } from '../worker/parse';

export type ProgressCallback = (phase: string, fraction: number) => void;

export interface WorkerBridge {
  parseSession(files: Map<string, Blob>, onProgress?: ProgressCallback): Promise<ParsedSession>;
  terminate(): void;
}
