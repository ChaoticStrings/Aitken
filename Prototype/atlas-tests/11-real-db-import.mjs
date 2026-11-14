// Imports a REAL Atlas database (ATLAS_DB=/path/to/aitken-atlas.sqlite) through the actual UI
// (Data tab -> Import .sqlite) and reconciles what Atlas holds against an independent python sqlite3 read.
// Not part of the default fixtures: skipped when ATLAS_DB is unset.  Never commit the file (it is gitignored).
import fs from 'fs'; import { execFileSync } from 'child_process'; import { serve, launch } from './harness.mjs'; import { LOG, checker, finish } from './helpers.mjs';
const DB=process.env.ATLAS_DB; const log=LOG(), c=checker(log);
if(!DB||!fs.existsSync(DB)){ log('ATLAS_DB not set - skipping real-database import suite'); process.exit(0); }
await serve(8765); setTimeout(()=>{log('HARD TIMEOUT');process.exit(2)},170000);
const py=(sql)=>execFileSync('python3',['-c','import sqlite3,sys;c=sqlite3.connect("file:"+sys.argv[1]+"?mode=ro",uri=True);print(c.execute(sys.argv[2]).fetchone()[0])',DB,sql]).toString().trim();
const want={ sessions:+py('select count(*) from sessions'), gps:+py('select count(*) from gps'), segments:+py('select count(*) from segments'), tags:+py('select count(*) from tags'), patches:+py('select count(*) from patches'), sources:+py('select count(*) from sources'), roads:+py('select count(*) from roads') };
log('# source file: '+JSON.stringify(want));
fs.rmSync('/tmp/atlas-real-profile',{recursive:true,force:true});
const ctx=await launch({userData:'/tmp/atlas-real-profile'}); const page=ctx.pages()[0]||await ctx.newPage(); const errs=[];
page.on('pageerror',e=>errs.push('PAGEERROR '+e.message)); page.on('console',m=>{ if(m.type()==='error') errs.push(m.text()); });
await page.goto('http://localhost:8765/workbench.html',{waitUntil:'domcontentloaded'}); await page.waitForSelector('html[data-atlas-ready="1"]',{timeout:20000});
c('starts empty',await page.evaluate(()=>AitkenAtlas.Model.sessions.size)===0);
await page.click('#modeSwitch [data-mode=atlas]'); await page.click('#atTabs [data-tab=data]'); page.once('dialog',d=>d.accept());
const t0=Date.now(); await page.setInputFiles('#atDbFile',DB);
await page.waitForFunction((n)=>AitkenAtlas.Model.sessions.size===n,want.sessions,{timeout:60000}).catch(()=>{});
log('# import took '+(Date.now()-t0)+' ms');
const got=await page.evaluate(()=>{ const q=(t)=>AitkenAtlas.Store.get('select count(*) n from '+t).n; return { sessions:AitkenAtlas.Model.sessions.size, gps:q('gps'), segments:q('segments'), tags:q('tags'), patches:q('patches'), sources:q('sources'), roads:q('roads'), err:AitkenAtlas.Store.status.error, backend:AitkenAtlas.Store.status.backend }; });
log('# Atlas now holds: '+JSON.stringify(got));
for(const k of Object.keys(want)) c('rows match for '+k,got[k]===want[k],[got[k],want[k]]);
c('no store error',!got.err,got.err); c('backend sqlite',got.backend==='sqlite');
const toast=await page.evaluate(()=>[...document.querySelectorAll('.toast,#toast,[class*=toast]')].map(e=>e.textContent).join('|')); log('# toast: '+toast);
// hydrate every session and check the derived data really builds (this is where a bad import would blow up)
const hyd=await page.evaluate(()=>{ const out=[]; for(const [id,s] of AitkenAtlas.Model.sessions){ try{ const h=AitkenAtlas.hydrate(id); const ev=AitkenAtlas.eventsOf(id); out.push({id,gps:h.gps.n,ev:ev.length,seg:h.segs?h.segs.n??h.segs.length:null}); }catch(e){ out.push({id,error:e.message}); } } return out; });
c('every session hydrates without error',hyd.every(x=>!x.error),hyd.filter(x=>x.error));
c('hydrated GPS counts equal database per session',hyd.every(x=>x.gps===+py(`select count(*) from gps where session_id='${x.id.replace(/'/g,"''")}'`)),hyd.map(x=>x.gps).join());
// tick everything and draw
await page.evaluate(()=>{ AitkenAtlas.Prefs.selectedSessions=[...AitkenAtlas.Model.sessions.keys()]; AitkenAtlas.refresh&&AitkenAtlas.refresh(); }); await page.waitForTimeout(2500);
const drawn=await page.evaluate(()=>({ events:[...AitkenAtlas.Active.events.values()].reduce((a,l)=>a+l.length,0), routes:AitkenAtlas.Active.routes?AitkenAtlas.Active.routes.size:null, canvases:document.querySelectorAll('#atlas canvas, #atlas .leaflet-overlay-pane path').length }));
log('# drawn: '+JSON.stringify(drawn)); c('events built for all sessions',drawn.events>=want.segments*0.9,[drawn.events,want.segments]);
c('map has drawn geometry',drawn.canvases>0,drawn);
await page.screenshot({path:'/tmp/atlas-shots/real-import.png'});
// persistence: reload, DB must come back from IndexedDB
await page.waitForTimeout(2200); await page.reload({waitUntil:'domcontentloaded'}); await page.waitForSelector('html[data-atlas-ready="1"]'); await page.waitForTimeout(1500);
c('survives reload',await page.evaluate(()=>AitkenAtlas.Model.sessions.size)===want.sessions);
// round trip: export -> independent sqlite3 integrity + equal counts
const b64=await page.evaluate(async()=>{ await AitkenAtlas.Store.flush(); const u=AitkenAtlas.Store.exportBytes(); let s=''; for(let i=0;i<u.length;i+=0x8000) s+=String.fromCharCode.apply(null,u.subarray(i,i+0x8000)); return btoa(s); }); fs.writeFileSync('/tmp/atlas-real-roundtrip.sqlite',Buffer.from(b64,'base64'));
const rt=(sql)=>execFileSync('python3',['-c','import sqlite3,sys;c=sqlite3.connect("/tmp/atlas-real-roundtrip.sqlite");print(c.execute(sys.argv[1]).fetchone()[0])',sql]).toString().trim();
c('round-trip integrity_check ok',rt('pragma integrity_check')==='ok'); c('round-trip gps rows equal',+rt('select count(*) from gps')===want.gps); c('round-trip roads rows equal',+rt('select count(*) from roads')===want.roads);
c('no console errors',errs.length===0,errs.slice(0,4));
await ctx.close(); process.exit(await finish(c));
