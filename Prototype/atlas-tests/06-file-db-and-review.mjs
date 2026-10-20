import fs from 'fs'; import { execFileSync } from 'child_process'; import { serve, launch } from './harness.mjs'; import { LOG, checker, Z, finish } from './helpers.mjs';
const log=LOG(), c=checker(log); await serve(8765); setTimeout(()=>{log('HARD TIMEOUT');process.exit(2)},170000);
const UD='/tmp/atlas-profile3'; fs.rmSync(UD,{recursive:true,force:true});
const open=async()=>{ const ctx=await launch({userData:UD}); const page=ctx.pages()[0]||await ctx.newPage(); const errs=[]; page.on('pageerror',e=>errs.push(e.message)); page.on('console',m=>{ if(m.type()==='error') errs.push(m.text()); });
  // stand-in for the native "save as" dialog: a REAL FileSystemFileHandle (origin-private file system) so permission, persistence in IndexedDB and createWritable are the real code paths
  await page.addInitScript(()=>{ window.showSaveFilePicker=async()=>{ const d=await navigator.storage.getDirectory(); return d.getFileHandle('aitken-atlas.sqlite',{create:true}); }; });
  await page.goto('http://localhost:8765/workbench.html',{waitUntil:'domcontentloaded'}); await page.waitForSelector('html[data-atlas-ready="1"]',{timeout:20000}); return {ctx,page,errs}; };
const ev=(p,f,a)=>p.evaluate(f,a);
const readFile=(p)=>ev(p,async()=>{ const d=await navigator.storage.getDirectory(); const f=await (await d.getFileHandle('aitken-atlas.sqlite')).getFile(); return {bytes:Array.from(new Uint8Array(await f.arrayBuffer())),mod:f.lastModified}; });
const q=(sql)=>execFileSync('python3',['-c','import sqlite3,sys;c=sqlite3.connect("/tmp/atlas-file.sqlite");print(c.execute(sys.argv[1]).fetchone()[0])',sql]).toString().trim();

log('# database in a file of your choosing');
let {ctx,page,errs}=await open();
await page.click('#modeSwitch [data-mode=atlas]'); await page.setInputFiles('#atZipInput',['city_01','city_03','city_06'].map(n=>Z+n+'.zip'));
await page.waitForFunction(()=>AitkenAtlas.Model.sessions.size>=3&&!document.querySelector('#atSessList .at-row.build'),null,{timeout:30000}); await page.waitForTimeout(1500);
await page.click('#atTabs [data-tab=data]'); 
c('Data tab offers "Choose file location" (File System Access available)',await ev(page,()=>!!document.querySelector('[data-db=link]')));
await page.click('[data-db=link]'); await page.waitForTimeout(1800);
c('status chip names the file',await ev(page,()=>/aitken-atlas\.sqlite/.test(document.querySelector('#atDbChip').textContent)),await ev(page,()=>document.querySelector('#atDbChip').textContent));
let f=await readFile(page); fs.writeFileSync('/tmp/atlas-file.sqlite',Buffer.from(f.bytes));
c('file written on link, valid SQLite',Buffer.from(f.bytes.slice(0,15)).toString()==='SQLite format 3'&&f.bytes.length>20000,f.bytes.length);
c('file already holds the 3 sessions',q('select count(*) from sessions')==='3');
await page.click('#atTheme'); await page.waitForTimeout(2600); f=await readFile(page); fs.writeFileSync('/tmp/atlas-file.sqlite',Buffer.from(f.bytes));
c('a later change (theme) is auto-saved to the file within ~1.2 s',/"theme":"light"/.test(q("select value from ui_state where key='prefs'")),q("select substr(value,1,80) from ui_state where key='prefs'"));
await page.setInputFiles('#atZipInput',[Z+'city_04.zip']); await page.waitForFunction(()=>AitkenAtlas.Model.sessions.size>=4,null,{timeout:20000}); await page.waitForTimeout(2600); f=await readFile(page); fs.writeFileSync('/tmp/atlas-file.sqlite',Buffer.from(f.bytes));
c('newly loaded session lands in the file too',q('select count(*) from sessions')==='4');
c('Data tab shows the linked file as writing',await ev(page,()=>/writing/.test(document.querySelector('#atDbBox').textContent)));
const mod1=f.mod; await ctx.close();

log('\n# reopen: file is the home copy');
await new Promise(r=>setTimeout(r,4500));
// someone/something edits the file outside the app (e.g. restored from a backup): delete one session from it
({ctx,page,errs}=await open()); await page.waitForTimeout(1200);
c('reopens with 4 sessions',await ev(page,()=>AitkenAtlas.Model.sessions.size)===4);
c('linked file remembered across browser restarts',await ev(page,()=>AitkenAtlas.Store.status.linkedName)==='aitken-atlas.sqlite');
const edited=execFileSync('python3',['-c',`import sqlite3,sys
c=sqlite3.connect('/tmp/atlas-file.sqlite'); sid=c.execute('select id from sessions limit 1').fetchone()[0]
for t in ['gps','segments','tags','patches']: c.execute('delete from '+t+' where session_id=?',(sid,))
c.execute('delete from sessions where id=?',(sid,)); c.commit(); c.close(); print(sid)`]).toString().trim();
await new Promise(r=>setTimeout(r,3500));
await ev(page,async(b)=>{ const d=await navigator.storage.getDirectory(); const w=await (await d.getFileHandle('aitken-atlas.sqlite')).createWritable(); await w.write(new Uint8Array(b)); await w.close(); },Array.from(fs.readFileSync('/tmp/atlas-file.sqlite')));
await page.reload({waitUntil:'domcontentloaded'}); await page.waitForSelector('html[data-atlas-ready="1"]'); await page.waitForTimeout(1500);
c('a newer file on disk wins over the browser copy (3 sessions after external edit)',await ev(page,()=>AitkenAtlas.Model.sessions.size)===3,await ev(page,()=>AitkenAtlas.Model.sessions.size));
c('source reported as "file"',await ev(page,()=>AitkenAtlas.Store.status.source)==='file');
await page.click('#atTabs [data-tab=data]'); await page.click('[data-db=unlink]'); await page.waitForTimeout(600);
c('unlinking keeps data in the browser',await ev(page,()=>AitkenAtlas.Store.status.linkedName===null&&AitkenAtlas.Model.sessions.size===3));
c('no console errors (file path)',errs.length===0,errs.slice(0,3)); await ctx.close();

log('\n# Review mode is untouched');
fs.rmSync('/tmp/atlas-profile4',{recursive:true,force:true}); ctx=await launch({userData:'/tmp/atlas-profile4'}); page=ctx.pages()[0]||await ctx.newPage(); const errs2=[]; page.on('pageerror',e=>errs2.push(e.message)); page.on('console',m=>{ if(m.type()==='error') errs2.push(m.text()); });
await page.goto('http://localhost:8765/workbench.html',{waitUntil:'domcontentloaded'}); await page.waitForSelector('html[data-atlas-ready="1"]');
c('opens in Review by default',await ev(page,()=>!document.body.classList.contains('mode-atlas')&&getComputedStyle(document.querySelector('#grid')).display!=='none'));
c('Review toolbar controls visible, Atlas hidden',await ev(page,()=>getComputedStyle(document.querySelector('#btnOpen')).display!=='none'&&getComputedStyle(document.querySelector('#atlas')).display==='none'));
await page.setInputFiles('#fileInput',Z+'session_20260913_095700_v11.zip'); await page.waitForFunction(()=>/session|tmp|0913|trimmed|095700/i.test(document.querySelector('#sessionName').textContent)&&!/No session/.test(document.querySelector('#sessionName').textContent),null,{timeout:20000}).catch(()=>{}); await page.waitForTimeout(1500);
const rv=await ev(page,()=>({name:document.querySelector('#sessionName').textContent,segs:document.querySelectorAll('#segList .row, #segList > *').length,cw:(document.querySelector('canvas')||{}).width}));
c('Review still loads a session with its own loader',!/No session/.test(rv.name),rv);
await page.click('#modeSwitch [data-mode=atlas]'); await page.waitForTimeout(500); await page.keyboard.press('Control+z'); await page.keyboard.press('Delete');
c('Atlas mode: Review shortcuts are inert (no console errors, still in Atlas)',await ev(page,()=>document.body.classList.contains('mode-atlas')));
await page.click('#modeSwitch [data-mode=review]'); await page.waitForTimeout(500);
c('back in Review: session still loaded, canvases sized',await ev(page,()=>!/No session/.test(document.querySelector('#sessionName').textContent)&&[...document.querySelectorAll('canvas')].some(cv=>cv.width>50)));
c('Review map (its own Leaflet instance) coexists with Atlas map',await ev(page,()=>document.querySelectorAll('.leaflet-container').length===2),await ev(page,()=>document.querySelectorAll('.leaflet-container').length));
c('no console errors (Review path)',errs2.length===0,errs2.slice(0,4));
await ctx.close(); process.exit(await finish(c));
