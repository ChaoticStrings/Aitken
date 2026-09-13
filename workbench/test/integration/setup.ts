// Installs a fake global `indexedDB` for integration tests (Plan §12:
// "integration | happy-dom + fake-indexeddb"). Harmless no-op for plain unit
// tests that never touch storage.
import 'fake-indexeddb/auto';
