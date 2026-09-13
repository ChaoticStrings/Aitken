// Ticket 01: static shell only — session name, review state, undo/redo,
// Export, and the overflow menu (Plan §8.1) are wired once a session can
// actually be open (later tickets, once `core/store` exists).
export function TopBar() {
  return (
    <header class="top-bar">
      <span class="top-bar__name">Aitken Workbench</span>
    </header>
  );
}
