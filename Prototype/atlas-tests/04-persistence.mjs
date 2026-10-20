import fs from 'fs'; import { execFileSync } from 'child_process'; import { serve, launch } from './harness.mjs'; import { LOG, checker, Z, finish } from './helpers.mjs';
const log=LOG(), c=checker(log); await serve(8765); setTimeout(()=>{log('HARD TIMEOUT');process.exit(2)},170000);
const UD='/tmp/atlas-profile'; fs.rmSync(UD,{recursive:true,force:true});
const open=async(ud)=>{ const ctx=await launch({userData:ud}); const page=ctx.pages()[0]||await ctx.newPage(); const errs=[]; page.on('pageerror',e=>errs.push(e.message)); page.on('console',m=>{ if(m.type()==='error') errs.push(m.text()); });
  await page.goto('http://localhost:8765/workbench.html',{waitUntil:'domcontentloaded'}); await page.waitForSelector('html[data-atlas-ready="1"]',{timeout:20000}); return {ctx,page,errs}; };
const ev=(p,f,a)=>p.evaluate(f,a);

log('# session 1: build up state');
let {ctx,page,errs}=await open(UD);
await page.click('#modeSwitch [data-mode=atlas]'); await page.setInputFiles('#atZipInput',fs.readdirSync(Z).filter(f=>/^city_0[1-6]/.test(f)).map(f=>Z+f));
await page.waitForFunction(()=>AitkenAtlas.Model.sessions.size>=6&&!document.querySelector('#atSessList .at-row.build'),null,{timeout:30000}); await page.waitForTimeout(2200);
await page.click('#atTheme'); await page.click('.at-chip[data-t=pothole]'); await page.click('#atMarkSeg [data-k=unmarked]');
await ev(page,()=>{ const m=document.querySelector('#atMinM'); m.value=2.5; m.dispatchEvent(new Event('input',{bubbles:true})); });
await page.click('#atRouteSeg [data-k=heading]'); await page.click('#atSessList .at-row[data-idx="1"]'); // untick one session
await page.click('#atTabs [data-tab=regions]'); await page.click('#atCollapse');
await ev(page,()=>{ AitkenAtlas.map.setView([18.5400,73.8200],14.5,{animate:false}); }); await page.waitForTimeout(600);
const pt=await ev(page,()=>{ const m=AitkenAtlas.map; let b=null; AitkenAtlas.Active.events.forEach(l=>l.forEach(e=>{ if(e.shown&&!b) b=e.ev; })); return b&&{key:b.key,lat:b.lat,lon:b.lon}; });
if(pt) await ev(page,(k)=>{ const sid=k.split(':')[0]; const e=AitkenAtlas.eventsOf(sid).find(x=>x.key===k); AitkenAtlas.selectEvent(e,{pan:false}); },pt.key);
await page.waitForTimeout(600);
const S1=await ev(page,()=>({sess:AitkenAtlas.Model.sessions.size,selected:AitkenAtlas.Prefs.selectedSessions.slice().sort(),theme:AitkenAtlas.Prefs.theme,types:AitkenAtlas.Prefs.types,unm:AitkenAtlas.Prefs.show.unmarked,minM:AitkenAtlas.Prefs.minM,route:AitkenAtlas.Prefs.routeStyle,tab:AitkenAtlas.Prefs.tab,mode:AitkenAtlas.Prefs.mode,sel:AitkenAtlas.sel&&AitkenAtlas.sel.key,view:AitkenAtlas.Prefs.view}));
log(JSON.stringify(S1).slice(0,400));
// refresh with no waiting: the last click must survive via the synchronous mirror
await page.reload({waitUntil:'domcontentloaded'}); await page.waitForSelector('html[data-atlas-ready="1"]'); await page.waitForTimeout(1500);
const check=async(P,label)=>{ const S=await ev(P,()=>({sess:AitkenAtlas.Model.sessions.size,selected:AitkenAtlas.Prefs.selectedSessions.slice().sort(),theme:document.querySelector('#atlas').dataset.theme,types:AitkenAtlas.Prefs.types,unm:AitkenAtlas.Prefs.show.unmarked,minM:AitkenAtlas.Prefs.minM,route:AitkenAtlas.Prefs.routeStyle,tab:AitkenAtlas.Prefs.tab,mode:document.body.classList.contains('mode-atlas')?'atlas':'review',sel:AitkenAtlas.sel&&AitkenAtlas.sel.key,view:AitkenAtlas.map&&AitkenAtlas.map.getCenter(),zoom:AitkenAtlas.map&&AitkenAtlas.map.getZoom(),collapsed:document.querySelector('#atlas').classList.contains('side-collapsed'),routes:AitkenAtlas.Active.routes.size}));
  c(label+': 6 sessions back from the database',S.sess===6,S.sess); c(label+': ticked sessions restored (5 of 6)',JSON.stringify(S.selected)===JSON.stringify(S1.selected)&&S.selected.length===5,S.selected.length);
  c(label+': routes redrawn for the ticked sessions',S.routes===5,S.routes); c(label+': reopens in Atlas mode',S.mode==='atlas',S.mode); c(label+': light theme',S.theme==='light',S.theme);
  c(label+': type chip, unmarked toggle, min M',S.types.pothole===true&&S.unm===false&&S.minM===2.5,[S.types,S.unm,S.minM]); c(label+': route colour',S.route==='heading',S.route); c(label+': sidebar tab + collapsed',S.tab==='regions'&&S.collapsed===true,[S.tab,S.collapsed]);
  c(label+': map view restored',S.view&&Math.abs(S.view.lat-18.54)<0.002&&Math.abs(S.view.lng-73.82)<0.002&&Math.abs(S.zoom-14.5)<0.3,[S.view,S.zoom]); c(label+': selected event restored',S.sel===S1.sel&&!!S.sel,[S.sel,S1.sel]);
  c(label+': card is open for the restored selection',await ev(P,()=>document.querySelector('#atCard').classList.contains('on')));
  c(label+': marker is opaque again',await ev(P,()=>{ const e=[...AitkenAtlas.Active.events.values()].flat().find(e=>AitkenAtlas.sel&&e.ev.key===AitkenAtlas.sel.key); return !e||!e.marker||e.marker.options.fillOpacity===1; })); };
log('\n# after refresh'); await check(page,'refresh');
// real SQLite?
const bytes=await ev(page,async()=>{ await AitkenAtlas.Store.flush(); return Array.from(AitkenAtlas.Store.exportBytes()); });
fs.writeFileSync('/tmp/atlas-check.sqlite',Buffer.from(bytes));
log('\n# the exported file is genuine SQLite'); 
c('header is "SQLite format 3"',fs.readFileSync('/tmp/atlas-check.sqlite').slice(0,15).toString()==='SQLite format 3');
const q=(sql)=>execFileSync('python3',['-c','import sqlite3,sys;c=sqlite3.connect("/tmp/atlas-check.sqlite");print(c.execute(sys.argv[1]).fetchone()[0])',sql]).toString().trim();
c('independent sqlite3 reads 6 sessions',q('select count(*) from sessions')==='6',q('select count(*) from sessions'));
c('independent sqlite3 integrity_check = ok',q('pragma integrity_check')==='ok');
c('gps rows stored',+q('select count(*) from gps')>5000,q('select count(*) from gps')); c('segments stored with derived positions',+q('select count(*) from segments where lat is not null')>300,q('select count(*) from segments where lat is not null'));
c('prefs row stored in ui_state',q("select count(*) from ui_state where key='prefs'")==='1');
c('generation column queryable',q("select group_concat(distinct generation) from sessions").split(',').sort().join()==='v1.0,v1.1,v2',q("select group_concat(distinct generation) from sessions"));
c('user_version = 1',q('pragma user_version')==='1');
c('no console errors (session 1)',errs.length===0,errs.slice(0,3));
await ctx.close();

log('\n# session 2: browser closed and reopened (same profile)');
({ctx,page,errs}=await open(UD)); await page.waitForTimeout(1500); await check(page,'reopen'); c('no console errors (session 2)',errs.length===0,errs.slice(0,3)); await ctx.close();

log('\n# non-Chromium fallback: export file -> import into a brand-new profile');
fs.rmSync('/tmp/atlas-profile2',{recursive:true,force:true}); ({ctx,page,errs}=await open('/tmp/atlas-profile2'));
c('new profile starts empty',await ev(page,()=>AitkenAtlas.Model.sessions.size)===0);
await page.click('#modeSwitch [data-mode=atlas]'); await page.click('#atTabs [data-tab=data]'); page.once('dialog',d=>d.accept());
await page.setInputFiles('#atDbFile','/tmp/atlas-check.sqlite'); await page.waitForTimeout(2500);
c('import restores sessions',await ev(page,()=>AitkenAtlas.Model.sessions.size)===6);
c('import restores prefs (theme light)',await ev(page,()=>document.querySelector('#atlas').dataset.theme)==='light');
await ctx.close();

log('\n# erase');
({ctx,page,errs}=await open(UD)); await page.waitForTimeout(800); await page.click('#atSideOpen'); await page.waitForTimeout(600); await page.click('#atTabs [data-tab=data]'); page.once('dialog',d=>d.accept()); await page.click('[data-db=reset]'); await page.waitForTimeout(1500);
c('erase empties the database',await ev(page,()=>AitkenAtlas.Model.sessions.size)===0 && await ev(page,()=>AitkenAtlas.Store.get('select count(*) n from gps').n)===0);
await page.reload({waitUntil:'domcontentloaded'}); await page.waitForSelector('html[data-atlas-ready="1"]'); await page.waitForTimeout(800);
c('and it stays erased after reload',await ev(page,()=>AitkenAtlas.Model.sessions.size)===0);
c('erase also resets preferences (theme back to dark, sidebar open)',await ev(page,()=>document.querySelector('#atlas').dataset.theme==='dark'&&!document.querySelector('#atlas').classList.contains('side-collapsed')));
await ctx.close(); process.exit(await finish(c));
