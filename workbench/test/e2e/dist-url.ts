import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));

export const distIndexUrl = 'file://' + path.join(here, '..', '..', 'dist', 'index.html');
