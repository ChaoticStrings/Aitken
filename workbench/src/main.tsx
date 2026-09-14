import { render } from 'preact';

// Self-hosted, subset-latin woff2 (D15) via @fontsource — bundled through
// Vite's asset pipeline and inlined as data URIs by vite-plugin-singlefile
// at build time, so the shipped dist/index.html makes no font network
// request even offline (E-48). Only the weights actually used are imported.
import '@fontsource/manrope/400.css';
import '@fontsource/manrope/700.css';
import '@fontsource/jetbrains-mono/400.css';

import './styles/tokens.css';
import './styles/base.css';
import './styles/motion.css';

import { TopBar } from './app/TopBar';
import { Library } from './panels/Library';
import { ToastHost } from './app/Toast';

function App() {
  return (
    <>
      <TopBar />
      <div class="workspace">
        <Library />
      </div>
      <ToastHost />
    </>
  );
}

function mount() {
  const root = document.getElementById('app');
  if (root) {
    render(<App />, root);
  }
}

// Don't assume the script tag runs after <div id="app"> has been parsed —
// Vite hoists module-script entries into <head> at build time (safe for a
// real `type="module"`, since those are implicitly deferred). This build
// deliberately ships a classic, non-module script instead (see
// vite.config.ts: file://-origin module-script quirks in Safari/Firefox
// were producing a genuinely blank dist/index.html even though the app
// itself was correct), which loses that implicit deferral. Waiting for
// DOMContentLoaded explicitly makes mounting correct regardless of where
// in the document the script tag ends up, module or not.
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', mount);
} else {
  mount();
}
