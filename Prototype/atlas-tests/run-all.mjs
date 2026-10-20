// Runs every NN-*.mjs suite in order (each is a real-browser test of Prototype/workbench.html).
//   npm test                 all suites
//   npm test -- 04 05        only suites whose file name starts with 04 or 05
import { spawnSync } from 'child_process'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
const here=path.dirname(fileURLToPath(import.meta.url)); const only=process.argv.slice(2);
if(!fs.existsSync(path.join(here,'zips'))){ console.log('generating fixtures...'); const r=spawnSync(process.execPath,['gen-fixtures.mjs'],{cwd:here,stdio:'inherit'}); if(r.status) process.exit(r.status); }
const suites=fs.readdirSync(here).filter(f=>/^\d\d-.*\.mjs$/.test(f)).filter(f=>!only.length||only.some(o=>f.startsWith(o))).sort();
const rows=[];
for(const f of suites){ console.log(`\n===== ${f} =====`); const t=Date.now(); const r=spawnSync(process.execPath,[f],{cwd:here,stdio:'inherit'}); rows.push([f,r.status===0?'pass':'FAIL',((Date.now()-t)/1000).toFixed(0)+'s']); }
console.log('\n----- summary -----'); rows.forEach(([f,s,t])=>console.log(s.padEnd(5),f.padEnd(34),t));
process.exit(rows.some(r=>r[1]!=='pass')?1:0);
