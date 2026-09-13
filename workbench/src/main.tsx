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
import { Library } from './app/Library';

function App() {
  return (
    <>
      <TopBar />
      <div class="workspace">
        <Library />
      </div>
    </>
  );
}

const root = document.getElementById('app');
if (root) {
  render(<App />, root);
}
