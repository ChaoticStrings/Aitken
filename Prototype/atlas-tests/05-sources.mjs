import fs from 'fs'; import { serve } from './harness.mjs'; import { LOG, checker, fresh, loadZips, Z, finish } from './helpers.mjs';
const log=LOG(), c=checker(log); await serve(8765); setTimeout(()=>{log('HARD TIMEOUT');process.exit(2)},170000);
// ---- mocked Overpass: Baner = one continuous road, Aundh = two far-apart pieces (ambiguous), University = absent
const reqs=[]; const baner=[[18.5590,73.7868],[18.5575,73.8085]], aundh=[[18.5575,73.8085],[18.5535,73.8247]];
const way=(id,name,pts)=>({type:'way',id,tags:{name,highway:'residential'},geometry:pts.map(p=>({lat:p[0],lon:p[1]}))});
const mid=(a,b,t)=>[a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t];
const overpass=async(route)=>{ const body=decodeURIComponent((route.request().postData()||'').replace(/^data=/,'')); reqs.push(body);
  const els=[way(1,'Test Baner Road',[baner[0],mid(baner[0],baner[1],.5)]),way(2,'Test Baner Road',[mid(baner[0],baner[1],.5),baner[1]]),
             way(3,'Test Aundh Link',aundh),way(4,'Test Aundh Link',[[18.60,73.90],[18.601,73.91]]), way(5,'Unrelated Road',[[18.5,73.8],[18.5,73.81]])];
  await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({elements:els})}); };
const {ctx,page,errs}=await fresh({overpass});
const ev=(f,a)=>page.evaluate(f,a); const settle=(ms=500)=>page.waitForTimeout(ms);
await loadZips(page,fs.readdirSync(Z).filter(f=>/^city_/.test(f)),8); await settle(2200);
const addSource=async(file,fill)=>{ await page.setInputFiles('#atSrcInput',[Z+file]); await page.waitForSelector('#atModal.on'); const guess=await ev(()=>({n:document.querySelector('#atmName_f')?.value,r:document.querySelector('#atmRef_f')?.value,k:document.querySelector('#atmCon_f')?.value,wkt:document.querySelector('#atmWkt')?.value})); if(fill) await fill(); await page.click('#atmGo'); await page.waitForSelector('#atModal.on',{state:'detached',timeout:1500}).catch(()=>{}); await settle(700); return guess; };

log('# GeoJSON import + field auto-mapping');
let g=await addSource('contractors_geo.geojson');
c('road name column guessed (road_name)',g.n==='road_name',g); c('contractor column guessed (contractor_name)',g.k==='contractor_name',g); c('ref column guessed',g.r==='ref',g);
let S=await ev(()=>[...AitkenAtlas.Model.sources.values()].map(s=>s.meta));
c('source stored: 6 roads, 5 with a contractor',S.length===1&&S[0].nRoads===6&&S[0].nNamed===5,S);
c('region auto-detected as Pune from the geometry',S[0].regionId==='pune',S[0].regionId);
c('source enabled and drawn',await ev(()=>{ const id=[...AitkenAtlas.Model.sources.keys()][0]; return AitkenAtlas.Prefs.enabledSources.includes(id)&&AitkenAtlas.Active.sources.get(id).items.length===6; }));
const cols=await ev(()=>{ const id=[...AitkenAtlas.Model.sources.keys()][0]; const cs=getComputedStyle(document.querySelector('#atlas')); return {contr:cs.getPropertyValue('--at-contr').trim(),ds:cs.getPropertyValue('--at-ds').trim(),roads:AitkenAtlas.Active.sources.get(id).items.map(i=>({n:i.road.name,col:i.layer.options.color,op:i.layer.options.opacity}))}; });
const blue=cols.roads.filter(r=>r.col===cols.contr), purple=cols.roads.filter(r=>r.col===cols.ds);
c('5 named roads drawn blue',blue.length===5,cols.roads); c('unnamed road drawn purple',purple.length===1&&purple[0].n==='Test Shivajinagar Lane',purple);
c('roads are translucent until clicked',cols.roads.every(r=>r.op<1),cols.roads.map(r=>r.op));
await page.screenshot({path:'/tmp/atlas-shots/shot_roads.png'});

log('\n# WKT CSV + pack + point geometry');
g=await addSource('contractors_wkt.csv'); c('WKT column guessed',g.wkt==='WKT'&&g.n==='Road Name'&&g.k==='Contractor',g);
await addSource('contractors_pack.json'); await addSource('points.geojson');
S=await ev(()=>[...AitkenAtlas.Model.sources.values()].map(s=>({name:s.meta.name,n:s.meta.nRoads,sample:s.meta.sample})));
c('four sources listed',S.length===4,S); c('pack carries its own name and SAMPLE flag',S.some(s=>s.name==='Sample pack (FICTIONAL)'&&s.sample),S);
c('SAMPLE pill is visible in the list',await ev(()=>/SAMPLE/.test(document.querySelector('#atSrcList').textContent)));
c('point record drawn as a marker',await ev(()=>{ const id=[...AitkenAtlas.Model.sources.entries()].find(([k,s])=>s.meta.name==='points')[0]; const a=AitkenAtlas.Active.sources.get(id); return a&&a.items.length===1&&a.items[0].layer.options.shape==='diamond'; }));

log('\n# geometry-less CSV resolved through (mocked) Overpass');
await addSource('contractors_nogeo.csv',async()=>{ await page.selectOption('#atmRegion','pune'); });
const ng=await ev(()=>{ const s=[...AitkenAtlas.Model.sources.values()].find(s=>s.meta.name==='contractors_nogeo'); const r=AitkenAtlas.ensureRoads(s.meta.id).roads; return {id:s.meta.id,n:r.length,pending:r.filter(x=>x.match==='pending').length,named:r.filter(x=>x.contractor).length,na:r.find(x=>x.name==='TEST SHIVAJINAGAR LANE').contractor}; });
c('6 records, all waiting for geometry',ng.n===6&&ng.pending===6,ng); c('"N/A" contractor treated as none',ng.na==='' && ng.named===5,ng);
c('source row offers the lookup button',await ev(()=>!!document.querySelector('#atSrcList [data-act=resolve]')));
await page.click('#atSrcList [data-act=resolve]'); await page.waitForFunction((id)=>AitkenAtlas.ensureRoads(id).roads.every(r=>r.match!=='pending'),ng.id,{timeout:15000}); await settle(500);
const res=await ev((id)=>Object.fromEntries(AitkenAtlas.ensureRoads(id).roads.map(r=>[r.name,r.match])),ng.id);
c('Baner (one road, 2 ways) = unique',res['TEST BANER ROAD']==='unique',res); c('Aundh (name in two far places) = ambiguous',res['TEST AUNDH LINK']==='ambiguous',res); c('University (not in OSM) = none',res['TEST UNIVERSITY ROAD']==='none',res);
c('exactly one Overpass request for 6 names, case-insensitive regex, limited to the Pune bbox',reqs.length===1&&/\"\,i\]\(18\.4,73\.7,18\.68,74\.04\)/.test(reqs[0].replace(/\\/g,'')) || (reqs.length===1&&/,i\]/.test(reqs[0])&&reqs[0].includes('18.4,73.7,18.68,74.04')),reqs[0]&&reqs[0].slice(0,300));
const draw=await ev((id)=>{ const a=AitkenAtlas.Active.sources.get(id); const cs=getComputedStyle(document.querySelector('#atlas')); const contr=cs.getPropertyValue('--at-contr').trim(), ds=cs.getPropertyValue('--at-ds').trim(); const by=Object.fromEntries((a?a.items:[]).map(i=>[i.road.name,{col:i.layer.options.color,dash:i.layer.options.dashArray}])); return {contr,ds,by}; },ng.id);
c('unique match with a contractor is blue',draw.by['TEST BANER ROAD']&&draw.by['TEST BANER ROAD'].col===draw.contr,draw);
c('ambiguous match is purple + dashed: contractor withheld',draw.by['TEST AUNDH LINK']&&draw.by['TEST AUNDH LINK'].col===draw.ds&&!!draw.by['TEST AUNDH LINK'].dash,draw);
c('not-found road is not drawn',!draw.by['TEST UNIVERSITY ROAD'],Object.keys(draw.by));
c('re-resolving is not needed (no pending left, button gone)',await ev(()=>!document.querySelector('#atSrcList [data-act=resolve]')));

log('\n# ride evidence on a road (both datasets together)');
const baneKey=await ev(()=>{ const id=[...AitkenAtlas.Model.sources.entries()].find(([k,s])=>s.meta.name==='contractors_geo')[0]; const r=AitkenAtlas.ensureRoads(id).roads.find(r=>r.name==='Test Baner Road'); AitkenAtlas.selectRoad(r,id,{pan:false}); return id; });
await settle(800); const cardTxt=await ev(()=>document.querySelector('#atCard').innerText);
c('card names the contractor, confirmed',/Fictional Infra Pvt Ltd/.test(cardTxt)&&/Confirmed/.test(cardTxt),cardTxt.slice(0,200));
const evd=await ev(()=>{ const s=AitkenAtlas.sel; const e=AitkenAtlas.rideEvidence(s.road); return {passes:e.passes,fwd:e.fwd,rev:e.rev,sessions:e.sessions.size,events:e.events.length}; });
c('6 city rides pass over Baner Road (01-04, 07, 08)',evd.passes===6&&evd.sessions===6,evd); c('3 along the road direction, 3 against (A->B and back B->A)',evd.fwd===3&&evd.rev===3,evd); c('events logged on the road are listed',evd.events>5,evd);
c('card shows passes and direction',/passes/i.test(cardTxt)&&/3 along/.test(cardTxt),cardTxt.slice(200,700));
await page.screenshot({path:'/tmp/atlas-shots/shot_roadcard.png'});
await ev(()=>{ const ss=AitkenAtlas.Prefs.selectedSessions; });

log('\n# events win over the road under them');
await ev(()=>{ AitkenAtlas.map.setView([18.5582,73.8000],16,{animate:false}); }); await settle(900);
const hit=await ev(()=>{ const m=AitkenAtlas.map; let b=null; AitkenAtlas.Active.events.forEach(l=>l.forEach(e=>{ if(e.shown&&!b){ const p=m.latLngToContainerPoint(e.marker.getLatLng()); const sz=m.getSize(); if(p.x>450&&p.x<sz.x-400&&p.y>120&&p.y<sz.y-120) b={x:p.x,y:p.y,key:e.ev.key}; } })); return b; });
c('an event sits on the road in view',!!hit,hit); const box=await page.locator('#atMap').boundingBox();
if(hit){ await page.mouse.click(box.x+hit.x,box.y+hit.y); await settle(700); c('clicking it selects the EVENT, not the road',await ev(()=>AitkenAtlas.sel&&AitkenAtlas.sel.kind==='event'),await ev(()=>AitkenAtlas.sel&&AitkenAtlas.sel.kind)); }

log('\n# search, contractor focus, filters');
c('search finds a road by name',await ev(()=>AitkenAtlas.runSearch('baner').some(r=>r.label==='Test Baner Road')));
c('search finds a contractor',await ev(()=>AitkenAtlas.runSearch('fictional infra').some(r=>r.label==='Fictional Infra Pvt Ltd'&&r.kind==='Contractors')));
c('search finds a session by its HHMMSS id',await ev(()=>AitkenAtlas.runSearch('093000').some(r=>r.kind==='Sessions')));
c('search finds regions',await ev(()=>AitkenAtlas.runSearch('beng').some(r=>r.kind==='Regions')));
await page.click('#atSearch'); await page.fill('#atSearch','fictional infra'); await settle(400);
c('dropdown shows grouped results',await ev(()=>document.querySelector('#atSearchPop').classList.contains('open')&&/Contractors/.test(document.querySelector('#atSearchPop').textContent)));
await page.keyboard.press('Enter'); await settle(1500);
c('Enter on a contractor focuses them',await ev(()=>AitkenAtlas.Prefs.focusContractor==='Fictional Infra Pvt Ltd'&&document.querySelector('#atFocusChip').classList.contains('on')));
const foc=await ev(()=>{ const id=[...AitkenAtlas.Model.sources.entries()].find(([k,s])=>s.meta.name==='contractors_geo')[0]; const a=AitkenAtlas.Active.sources.get(id); return Object.fromEntries(a.items.map(i=>[i.road.name,i.layer.options.opacity])); });
c('their roads stay vivid, other contractors dim',foc['Test Baner Road']>0.4&&foc['Test University Road']<0.2,foc);
await page.click('#atFocusClear'); await settle(300);
await page.click('[data-sw=namedOnly]'); await settle(500);
c('"named contractors only" removes the unnamed road',await ev(()=>{ const id=[...AitkenAtlas.Model.sources.entries()].find(([k,s])=>s.meta.name==='contractors_geo')[0]; const a=AitkenAtlas.Active.sources.get(id); return a.items.length===5&&!a.items.some(i=>i.road.name==='Test Shivajinagar Lane'); }));
await page.click('[data-sw=namedOnly]'); await settle(300);
log('\n# source toggles + index'); 
await page.click('#atSrcList .at-row[data-idx="0"]'); await settle(500);
c('unticking a source removes its roads from the map',await ev(()=>AitkenAtlas.Active.sources.size===AitkenAtlas.Prefs.enabledSources.length));
await page.click('#atTabs [data-tab=index]'); await settle(400);
const idx=await ev(()=>({n:document.querySelectorAll('#atIdxList .at-row[data-i]').length,heads:[...document.querySelectorAll('#atIdxList .at-idx-h')].map(h=>h.textContent),first:document.querySelector('#atIdxList .at-row .nm').textContent,az:document.querySelectorAll('#atAZ button:not(.dis)').length}));
c('index lists roads alphabetically under letter headers',idx.n>6&&idx.heads.includes('T')&&idx.az>=2,idx);
await page.click('#atIdxSeg [data-k=contractors]'); await settle(300);
c('contractor index groups roads per contractor',await ev(()=>/Example Roadworks LLP/.test(document.querySelector('#atIdxList').textContent)&&/roads?/.test(document.querySelector('#atIdxList').textContent)));
await page.click('#atIdxList .at-row[data-i="0"]'); await settle(1200);
c('clicking a contractor in the index focuses them',await ev(()=>!!AitkenAtlas.Prefs.focusContractor));
c('no console errors',errs.length===0,errs.slice(0,4));
process.exit(await finish(c));
