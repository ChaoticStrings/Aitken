// Datasets -> Atlas: packs made by the REAL converter (Datasets/tools/make_packs.py, from FICTIONAL fixtures) are imported
// through the real mapping dialog and checked for what is drawn, shown, searched and stored. Plus the pack extensions
// (awards, retrieved, notes, match: "unlocated") with hostile input, and persistence across a reload.
import fs from 'fs'; import path from 'path'; import { execFileSync } from 'child_process'; import { serve, ROOT } from './harness.mjs'; import { LOG, checker, fresh, finish } from './helpers.mjs';
const log=LOG(), c=checker(log); await serve(8765); setTimeout(()=>{log('HARD TIMEOUT');process.exit(2)},170000);
const OUT='/tmp/atlas-packs-08'; fs.rmSync(OUT,{recursive:true,force:true}); fs.mkdirSync(OUT,{recursive:true});
const DS=path.join(ROOT,'..','..','Datasets');
execFileSync('python3',[path.join(DS,'tools','make_packs.py'),'--data',path.join(DS,'tests','fixtures'),'--out',OUT,'--quiet']);

// a hand-made pack with hostile strings, bad award entries and an oversized award list
const evil={format:'aitken-datasource',version:1,source:{name:'hostile',retrieved:'<img src=x onerror=alert(1)>',notes:'<b>n</b> notes',url:'javascript:alert(2)',license:'x'},roads:[
  {id:'h1',name:'Hostile Road',match:'unlocated',geometry:{type:'LineString',coordinates:[[73.80,18.50],[73.81,18.51]]},
   props:{k:'v',__awards:[{contractor:'<img src=x onerror=alert(3)>'}]},
   awards:[{contractor:'<script>alert(4)</script> & Co',work:'<img src=x onerror=alert(5)>',note:'n'},{contractor:{x:1}},null,7,{work:'no contractor'},{contractor:'Second Firm',year:2024,cost:12345}]},
  {id:'h2',name:'Long Road',geometry:{type:'LineString',coordinates:[[73.82,18.50],[73.83,18.51]]},
   awards:Array.from({length:250},(_,i)=>({contractor:'Bulk Firm '+(i%7),ref:'R'+i,work:'job '+i}))}]};
fs.writeFileSync(OUT+'/evil_awards.json',JSON.stringify(evil));

let dialogs=0;
const {ctx,page,errs}=await fresh(); page.on('dialog',d=>{ dialogs++; d.dismiss(); });
const ev=(f,a)=>page.evaluate(f,a); const settle=(ms=500)=>page.waitForTimeout(ms);
await page.click('#modeSwitch [data-mode=atlas]'); await settle(600);
const addSource=async(file)=>{ await page.setInputFiles('#atSrcInput',[file]); await page.waitForSelector('#atModal.on'); await page.click('#atmGo'); await page.waitForSelector('#atModal.on',{state:'detached',timeout:3000}).catch(()=>{}); await settle(700); };
const src=(name)=>ev((n)=>{ const s=[...AitkenAtlas.Model.sources.values()].find(s=>s.meta.name.includes(n)); return s&&s.meta; },name);
const drawn=(name)=>ev((n)=>{ const e=[...AitkenAtlas.Model.sources.entries()].find(([k,s])=>s.meta.name.includes(n)); const a=AitkenAtlas.Active.sources.get(e[0]); const cs=getComputedStyle(document.querySelector('#atlas')); const contr=cs.getPropertyValue('--at-contr').trim(), ds=cs.getPropertyValue('--at-ds').trim();
  return Object.fromEntries((a?a.items:[]).map(i=>[i.road.name,{blue:i.layer.options.color===contr,purple:i.layer.options.color===ds,dash:!!i.layer.options.dashArray,op:i.layer.options.opacity}])); },name);
const selectRoad=(srcName,roadName)=>ev(([sn,rn])=>{ const e=[...AitkenAtlas.Model.sources.entries()].find(([k,s])=>s.meta.name.includes(sn)); const r=AitkenAtlas.ensureRoads(e[0]).roads.find(r=>r.name===rn); AitkenAtlas.selectRoad(r,e[0],{pan:false}); },[srcName,roadName]).then(()=>settle(500));
const cardText=()=>ev(()=>document.querySelector('#atCard').innerText);

log('# import the three converter packs');
await addSource(OUT+'/datasource_blr_awards.json'); await addSource(OUT+'/datasource_nh_morth.json'); await addSource(OUT+'/datasource_pune_osm_roads.json');
const names=await ev(()=>[...AitkenAtlas.Model.sources.values()].map(s=>s.meta.name));
c('three sources imported',names.length===3,names);
const B=await src('Bengaluru'), N=await src('National highways'), P=await src('Pune named');
c('pack retrieval date is kept (not the import date)',B.retrieved==='2026-10-17'&&N.retrieved==='2026-10-07'&&P.retrieved==='2026-10-06',[B.retrieved,N.retrieved,P.retrieved]);
c('pack notes and licence are stored',/Candidate matches/.test(B.notes)&&/ODbL/.test(B.license)&&/DATA_LEDGER/.test(P.notes),[B.notes,B.license]);
c('regions auto-detected from the geometry (Pune, Bengaluru)',P.regionId==='pune'&&B.regionId==='bengaluru',[P.regionId,B.regionId]);
c('BLR: 3 roads, 2 with a contractor (the all-individuals road is held)',B.nRoads===3&&B.nNamed===2,[B.nRoads,B.nNamed]);

log('\n# what is drawn: blue / purple split');
const db=await drawn('Bengaluru'), dn=await drawn('National highways'), dp=await drawn('Pune named');
c('BLR: two named roads blue, the held road purple',db['Test Indiranagar Road'].blue&&db['Test Jayanagar Road'].blue&&db['Test Hebbal Road'].purple,db);
c('NH: located section blue; whole-highway lines purple and solid (not dashed)',Object.entries(dn).filter(([k,v])=>v.blue).map(([k])=>k).join()==='NH-999 · MH · located section'&&dn['NH-999 · MH'].purple&&!dn['NH-999 · MH'].dash&&dn['NH-998 · KA'].purple,dn);
c('Pune baseline: every road purple',Object.values(dp).length===3&&Object.values(dp).every(v=>v.purple),dp);
c('roads stay translucent until selected',Object.values({...db,...dn}).every(v=>v.op<1));

log('\n# index: one firm = one row, secondary contractors listed, nobody held leaks');
await page.click('#atTabs [data-tab=index]'); await page.click('#atIdxSeg [data-k=contractors]'); await settle(400);
const idx=await ev(()=>[...document.querySelectorAll('#atIdxList .at-row')].map(r=>r.innerText.replace(/\s+/g,' ').trim()));
const idxTxt=idx.join(' | ');
c('six distinct contractors in the Index',idx.length===6,idx);
c('the two spellings of the Indiranagar firm are ONE row',idx.filter(t=>/Fictional Infra/i.test(t)).length===1,idx);
c('the two JVs stay two rows',idx.filter(t=>/Alpha Infra/.test(t)).length===1&&idx.filter(t=>/Gamma Infra/.test(t)).length===1,idx);
c('a secondary contractor (not the primary on its road) is indexed: proprietor shown as its firm',/TEST ANIL BUILDERS/.test(idxTxt),idx);
c('same firm on a highway line and its located section: 2 roads, 1 confirmed',/Fictional Roadways Pvt Ltd\s+2 roads · 1 confirmed/.test(idxTxt),idx);
c('held individuals appear nowhere',!/RAVI|SURESH|LAKSHMI|Bikash|ANIL V/.test(idxTxt)&&!/RAVI|SURESH|LAKSHMI|Bikash|ANIL V/.test(JSON.stringify(fs.readdirSync(OUT).filter(f=>/^datasource_/.test(f)).map(f=>fs.readFileSync(OUT+'/'+f,'utf8')))));
await page.click('#atIdxSeg [data-k=roads]'); await settle(300);
const ridx=await ev(()=>[...document.querySelectorAll('#atIdxList .at-row')].map(r=>r.innerText.replace(/\s+/g,' ').trim()).filter(t=>/Indiranagar|Hebbal|NH-999/.test(t)));
c('Index shows "+N more" for a multi-contractor road, and "no contractor named" for the held one',ridx.some(t=>/Test Indiranagar Road.*\+1 more/.test(t))&&ridx.some(t=>/Test Hebbal Road.*no contractor named/.test(t)),ridx);

log('\n# road card: awards, provenance, notes');
await selectRoad('Bengaluru','Test Indiranagar Road'); let ct=await cardText();
const awN=await ev(()=>document.querySelectorAll('#atCard .atc-aw').length);
c('card lists the 3 awards that were not held',awN===3&&/Awards on this road \(3\)/i.test(ct),[awN,ct.slice(0,300)]);
c('primary contractor with "+1 more"; confirmed against the unique-name match',/Fictional Infra Pvt\. Ltd\s*\+1 more/.test(ct)&&/Confirmed against matched to one road in OpenStreetMap by name/.test(ct),ct.slice(0,260));
c('the published spelling is kept per award',/as published: M\/s Fictional Infra Pvt\. Ltd\./.test(ct)&&/as published: FICTIONAL INFRA PRIVATE LIMITED/.test(ct),ct);
c('award details: date, cost, tender ref, office, work text',/2025-01-10 · INR 2,750,000 · T\/2/.test(ct)&&/TEST BBMP DIVISION/.test(ct)&&/Improvement to Test Indiranagar Road/.test(ct),ct);
c('the candidate caveat is on the card (match note + source note + retrieval date)',/candidate: OSM road name unique/.test(ct)&&/road link is NOT verified/.test(ct)&&/2026-10-17/.test(ct),ct);
c('held awards are counted, not named; internal keys never shown',/awards held\s*1 to names not recognised as firms/.test(ct)&&!/__awards|\[object/.test(ct),ct);
await selectRoad('National highways','NH-999 · MH'); ct=await cardText();
c('whole-highway line: unconfirmed, with the reason, and not a blue card',/Unconfirmed: the award is real per the source, but its stretch is not located/.test(ct)&&await ev(()=>!!document.querySelector('#atCard .atc-contr.none')),ct.slice(0,300));
c('whole-highway line lists its firm contracts with chainage and locate status',/Fictional Roadways Pvt Ltd/.test(ct)&&/km 10-40; section located/.test(ct)&&/section not located: no place names parsed/.test(ct),ct);
await selectRoad('National highways','NH-999 · MH · located section'); ct=await cardText();
c('located section: confirmed, one award, with the end-places check',/Confirmed against geometry came with the data/.test(ct)&&/28\.0 km straight line vs 30\.0 km chainage/.test(ct)&&!/Awards on this road/.test(ct),ct.slice(0,400));

log('\n# focus from an award row dims every road that does not carry that contractor');
await selectRoad('Bengaluru','Test Indiranagar Road');
await ev(()=>{ [...document.querySelectorAll('#atCard .atc-aw')].find(e=>/TEST ANIL BUILDERS/.test(e.textContent)).click(); }); await settle(500);
const foc=await ev(()=>({f:AitkenAtlas.Prefs.focusContractor,chip:document.querySelector('#atFocusChip').innerText,ops:Object.fromEntries([...AitkenAtlas.Active.sources.values()].flatMap(s=>s.items.map(i=>[i.road.name,i.layer.options.opacity])))}));
c('focus is set to the SECONDARY contractor and the chip counts 1 road',foc.f==='TEST ANIL BUILDERS'&&/1 road/.test(foc.chip),foc);
c('only the road carrying it keeps its opacity',foc.ops['Test Indiranagar Road']>0.4&&foc.ops['Test Jayanagar Road']<0.2&&foc.ops['NH-999 · MH']<0.2,foc.ops);
await page.click('#atFocusClear'); await settle(300);

log('\n# search finds contractors that are not the primary on their road');
const sr=await ev(()=>({anil:AitkenAtlas.runSearch('anil builders').filter(x=>x.kind==='Contractors').map(x=>x.label),jv:AitkenAtlas.runSearch('gamma').filter(x=>x.kind==='Contractors').map(x=>x.label),held:AitkenAtlas.runSearch('bikash')}));
c('secondary contractor found',sr.anil.includes('TEST ANIL BUILDERS'),sr); c('JV found by one of its partners',sr.jv.length===1&&/Gamma Infra/.test(sr.jv[0]),sr); c('a held name finds nothing',sr.held.length===0,sr.held);

log('\n# source card: unlocated roads are counted');
await ev(()=>{ [...document.querySelectorAll('#atSrcList .at-row')].find(r=>/National highways/.test(r.textContent)).querySelector('[data-act=info]').click(); }); await settle(500);
ct=await cardText();
c('source card shows retrieval date, note and the "stretch not located" count (2 of 3)',/2026-10-07/.test(ct)&&/Only sections whose end places/.test(ct)&&/Whole road, stretch not located\s*2/.test(ct)&&/Came with the data\s*1/.test(ct),ct);

log('\n# hostile and oversized awards');
await addSource(OUT+'/evil_awards.json');
const H=await src('hostile'); const hid=await ev(()=>[...AitkenAtlas.Model.sources.entries()].find(([k,s])=>s.meta.name==='hostile')[0]);
c('HTML in provenance is stored as text',H.retrieved==='<img src=x onerror=alert(1)>'.slice(0,40)&&H.notes==='<b>n</b> notes',[H.retrieved,H.notes]);
const st=await ev((id)=>{ const rs=AitkenAtlas.ensureRoads(id).roads; const a=rs.find(r=>r.id==='h1'), b=rs.find(r=>r.id==='h2'); return {h1:a.props.__awards,k:a.props.k,contr:a.contractor,h2n:b.props.__awards.length,h2c:b.contractor,match:a.match}; },hid);
c('the pack cannot smuggle its own props.__awards; bad award entries are dropped',st.h1.length===2&&st.h1[0].contractor==='<script>alert(4)</script> & Co'&&st.h1[1].contractor==='Second Firm'&&st.k==='v',st);
c('numbers in awards become text; primary defaults to the first award',st.h1[1].year==='2024'&&st.h1[1].cost==='12345'&&st.contr==='<script>alert(4)</script> & Co',st);
c('award lists are capped at 200 per road',st.h2n===200&&st.h2c==='Bulk Firm 0',st);
await selectRoad('hostile','Hostile Road'); ct=await cardText();
const dom=await ev(()=>({imgs:document.querySelectorAll('#atCard img, #atSrcList img, #atIdxList img, #atSearchPop img').length,scripts:document.querySelectorAll('#atCard script').length,links:[...document.querySelectorAll('#atCard a')].map(a=>a.getAttribute('href'))}));
c('HTML in awards is shown as text, never executed; javascript: link dropped',dom.imgs===0&&dom.scripts===0&&!dom.links.some(h=>/^javascript:/i.test(h||''))&&dialogs===0&&/<script>alert\(4\)<\/script> & Co/.test(ct),[dom,dialogs]);
await selectRoad('hostile','Long Road'); ct=await cardText();
c('long list: 30 rows shown, the rest announced',await ev(()=>document.querySelectorAll('#atCard .atc-aw').length)===30&&/\+170 more awards are stored but not listed here/.test(ct),ct.slice(-200));

log('\n# persistence: awards, notes and retrieval dates survive a reload');
await ev(()=>AitkenAtlas.Store.flush()); await settle(800);
await page.reload({waitUntil:'domcontentloaded'}); await page.waitForSelector('html[data-atlas-ready="1"]',{timeout:20000}); await settle(1500);
const after=await ev(()=>{ const out={}; for(const [id,s] of AitkenAtlas.Model.sources){ const rs=AitkenAtlas.ensureRoads(id).roads; out[s.meta.name.slice(0,12)]={ret:s.meta.retrieved,notes:!!s.meta.notes,aw:rs.reduce((n,r)=>n+(r.props.__awards||[]).length,0),unloc:rs.filter(r=>r.match==='unlocated').length}; } return out; });
c('all four sources are back from SQLite with their awards',Object.keys(after).length===4&&after['Bengaluru aw'].aw===5&&after['National hig'].aw===4&&after['hostile'].aw===2+200,after);
c('retrieval dates, notes and the "unlocated" value round-trip',after['Bengaluru aw'].ret==='2026-10-17'&&after['National hig'].ret==='2026-10-07'&&after['National hig'].notes&&after['National hig'].unloc===2,after);
c('no console or page errors, no dialogs',errs.length===0&&dialogs===0,errs);
await ctx.close(); process.exit(await finish(c));
