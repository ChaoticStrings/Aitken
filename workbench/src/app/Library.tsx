// Ticket 01: an empty placeholder. The real Library (imported-session cards
// with review progress, D12) needs `worker/parse` and `persistence/*`,
// neither of which exist yet — this stands in until the ticket that adds
// them.
export function Library() {
  return (
    <div class="library">
      <div class="library__placeholder">
        <h2>No sessions yet</h2>
        <p>Drop a session .zip or folder here to get started.</p>
      </div>
    </div>
  );
}
