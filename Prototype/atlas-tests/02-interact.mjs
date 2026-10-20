import fs from 'fs'; import { serve } from './harness.mjs'; import { LOG, checker, fresh, loadZips, Z, finish } from './helpers.mjs';
const log=LOG(), c=checker(log); const srv=await serve(8765); setTimeout(()=>{log('HARD TIMEOUT');process.exit(2)},200000);
const tiles=[]; const {ctx,page,errs}=await fresh({tilesLog:tiles});
const city=fs.readdirSync(Z).filter(f=>/^city_/.test(f)); await loadZips(page,city,8); await page.waitForTimeout(2200);
const ev=(fn,a)=>page.evaluate(fn,a);
const shownMarkers=()=>ev(()=>{ let n=0; AitkenAtlas.Active.events.forEach(l=>l.forEach(e=>{ if(e.shown) n++; })); return n; });
const stats=()=>ev(()=>AitkenAtlas.computeStats());
const settle=async(ms=400)=>{ await page.waitForTimeout(ms); };

log('# zoom/aggregation');
await ev(()=>{ AitkenAtlas.map.setView([18.5575,73.8085],11,{animate:false}); }); await settle(); 
c('zoomed out: events are grouped into bubbles', await ev(()=>AitkenAtlas.Active.aggMode===true && !!AitkenAtlas.Active.agg));
await ev(()=>{ AitkenAtlas.map.setView([18.5575,73.8085],15.5,{animate:false}); }); await settle(700);
c('zoomed in: individual events drawn, no bubbles', await ev(()=>AitkenAtlas.Active.aggMode===false && !AitkenAtlas.Active.agg));
let st=await stats(); let sm=await shownMarkers();
c('stats and drawn markers agree (all on)', st.shown>0 && sm>0, {st:st.shown,drawn:sm});
c('map has marked and unmarked events', st.marked>10 && st.unmarked>10, [st.marked,st.unmarked]);

log('\n# filters');
await page.click('#atMarkSeg [data-k=unmarked]'); await settle(); st=await stats(); sm=await shownMarkers();
c('Unmarked OFF: only marked are shown', st.shown===st.marked && sm===st.marked,{shown:st.shown,marked:st.marked,drawn:sm});
await page.click('#atMarkSeg [data-k=unmarked]'); await page.click('#atMarkSeg [data-k=marked]'); await settle(); st=await stats(); sm=await shownMarkers();
c('Marked OFF: only unmarked are shown', st.shown===st.unmarked && sm===st.unmarked,{shown:st.shown,unmarked:st.unmarked,drawn:sm});
await page.click('#atMarkSeg [data-k=unmarked]'); await settle(); st=await stats();
c('both OFF: nothing shown', st.shown===0 && (await shownMarkers())===0, st.shown);
await page.click('#atMarkSeg [data-k=marked]'); await page.click('#atMarkSeg [data-k=unmarked]'); await settle();
const total=(await stats()).shown;
await page.click('.at-chip[data-t=pothole]'); await settle(); const p1=await stats(); const dp=await shownMarkers();
const expectPot=await ev(()=>{ let n=0; AitkenAtlas.Active.events.forEach((_,sid)=>AitkenAtlas.eventsOf(sid).forEach(e=>{ if(e.marked&&e.types.includes('pothole')) n++; else if(!e.marked) n++; })); return n; });
c('Pothole chip: marked potholes + unmarked remain', p1.shown===expectPot && dp===expectPot, {shown:p1.shown,expectPot,drawn:dp});
await page.click('#atMarkSeg [data-k=unmarked]'); await settle(); const p2=await stats();
c('Pothole chip + Unmarked OFF: potholes only', p2.shown===p1.byType.pothole, {shown:p2.shown,byType:p2.byType});
await page.click('.at-chip[data-t=bump]'); await settle(); const p3=await stats();
c('multiple chips act as a union (pothole + bump)', p3.shown===(p2.byType.pothole+p2.byType.bump), {shown:p3.shown,pot:p2.byType.pothole,bump:p2.byType.bump});
await page.click('.at-chip[data-t=speedbreaker]'); await page.click('.at-chip[data-t=pothole]'); await page.click('.at-chip[data-t=bump]'); await settle(); const p4=await stats();
c('speedbreaker alone', p4.shown===p4.byType.speedbreaker && p4.shown>0, p4);
await page.click('#atTypesClear'); await page.click('#atMarkSeg [data-k=unmarked]'); await settle();
c('clearing chips restores everything', (await stats()).shown===total, {total});
await ev(()=>{ const m=document.querySelector('#atMinM'); m.value=3; m.dispatchEvent(new Event('input',{bubbles:true})); }); await settle(); const m3=await stats();
c('min severity M3 hides mild events', m3.shown<total && m3.shown>0, {total,shown:m3.shown});
const lowest=await ev(()=>{ let lo=99; AitkenAtlas.Active.events.forEach(l=>l.forEach(e=>{ if(e.shown&&isFinite(e.ev.m)) lo=Math.min(lo,e.ev.m); })); return lo; });
c('nothing drawn below M3 (tags without M excepted)', lowest>=3, lowest);
await ev(()=>{ const m=document.querySelector('#atMinM'); m.value=0; m.dispatchEvent(new Event('input',{bubbles:true})); }); await settle();

log('\n# theme + basemap');
c('starts dark with night-filtered OSM', await ev(()=>document.querySelector('#atlas').dataset.theme==='dark' && document.querySelector('#atMap').classList.contains('at-night')));
await page.click('#atTheme'); await settle();
c('light theme: standard OSM, no night filter', await ev(()=>document.querySelector('#atlas').dataset.theme==='light' && !document.querySelector('#atMap').classList.contains('at-night')));
await page.screenshot({path:'/tmp/atlas-shots/shot_light.png'});
await page.click('#atTheme'); await settle();
c('back to dark', await ev(()=>document.querySelector('#atlas').dataset.theme==='dark' && document.querySelector('#atMap').classList.contains('at-night')));

log('\n# click selects (opaque) and shows a card');
const pt=await ev(()=>{ const m=AitkenAtlas.map; let best=null; AitkenAtlas.Active.events.forEach(l=>l.forEach(e=>{ if(e.shown&&e.ev.marked&&isFinite(e.ev.m)&&e.ev.m>2){ const p=m.latLngToContainerPoint(e.marker.getLatLng()); const r=m.getSize(); if(p.x>420&&p.x<r.x-380&&p.y>110&&p.y<r.y-120&&!best) best={x:p.x,y:p.y,key:e.ev.key,fo:e.marker.options.fillOpacity}; } })); return best; });
const box=await page.locator('#atMap').boundingBox();
c('found a clickable marker', !!pt, pt);
if(pt){ await page.mouse.click(box.x+pt.x,box.y+pt.y); await settle(900);
  c('card opens for the event', await ev(()=>document.querySelector('#atCard').classList.contains('on') && /M\d/.test(document.querySelector('#atCard .atc-badge').textContent)));
  const fo=await ev(()=>{ const e=[...AitkenAtlas.Active.events.values()].flat().find(e=>e.ev.key===AitkenAtlas.sel.key); return {sel:e.marker.options.fillOpacity,key:AitkenAtlas.sel.key}; });
  c('selected marker is fully opaque (was translucent)', fo.sel===1 && pt.fo<1, {fo,before:pt.fo});
  await page.screenshot({path:'/tmp/atlas-shots/shot_card.png'});
  await page.keyboard.press('Escape'); await settle();
  c('Escape closes the card and restores translucency', await ev(()=>!document.querySelector('#atCard').classList.contains('on') && AitkenAtlas.sel===null));
}

log('\n# regions + tile warming');
const before=tiles.length;
await ev(()=>AitkenAtlas.goRegion('bengaluru')); await page.waitForTimeout(2600);
const ctr=await ev(()=>{ const c=AitkenAtlas.map.getCenter(); return [c.lat,c.lng,AitkenAtlas.map.getZoom()]; });
c('Bengaluru region flies to Bengaluru', ctr[0]>12.83&&ctr[0]<13.16&&ctr[1]>77.45&&ctr[1]<77.8, ctr);
c('tiles were warmed before/around arrival', tiles.length-before>=9, tiles.length-before);
const warmed=await ev(()=>document.querySelector('#atTileStat').textContent); c('tile counter shown in Data tab', /tiles warmed/.test(warmed), warmed);
await ev(()=>AitkenAtlas.goRegion('__all')); await page.waitForTimeout(2400);
c('All rides flies back over the sessions', await ev(()=>{ const b=AitkenAtlas.map.getBounds(); return b.contains([18.5575,73.8085]); }));
c('active region pill highlighted', await ev(()=>document.querySelector('#atRegionBar button.on').dataset.r==='__all'));

c('no console errors during interaction tests', errs.length===0, errs.slice(0,5));
process.exit(await finish(c));
