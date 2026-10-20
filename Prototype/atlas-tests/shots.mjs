import fs from 'fs'; import { serve, shutdown } from './harness.mjs'; import { LOG, fresh, loadZips, Z } from './helpers.mjs';
const log=LOG(); await serve(8765); setTimeout(()=>{log('HARD TIMEOUT');process.exit(2)},120000);
const {ctx,page,errs}=await fresh(); const ev=(f,a)=>page.evaluate(f,a); const wait=(ms)=>page.waitForTimeout(ms);
await loadZips(page,fs.readdirSync(Z).filter(f=>/^city_|session_20260913_095700_v11|session_20260922|session_20261016/.test(f)),10); await wait(2400);
await page.setInputFiles('#atSrcInput',[Z+'contractors_geo.geojson']); await page.waitForSelector('#atModal.on'); await page.click('#atmGo'); await wait(900);
await page.setInputFiles('#atSrcInput',[Z+'points.geojson']); await page.waitForSelector('#atModal.on'); await page.click('#atmGo'); await wait(900);
// overview (grouped)
await ev(()=>{ AitkenAtlas.map.setView([18.545,73.82],12.25,{animate:false}); }); await wait(900); await page.screenshot({path:'/tmp/atlas-shots/v_overview.png'});
// close-up with roads + events
await ev(()=>{ AitkenAtlas.map.setView([18.5578,73.8000],15.25,{animate:false}); }); await wait(900); await page.screenshot({path:'/tmp/atlas-shots/v_closeup.png'});
// select a road
await ev(()=>{ const id=[...AitkenAtlas.Model.sources.entries()].find(([k,s])=>s.meta.name==='contractors_geo')[0]; const r=AitkenAtlas.ensureRoads(id).roads.find(r=>r.name==='Test Aundh Link'); AitkenAtlas.selectRoad(r,id,{pan:false}); }); await wait(900); await page.screenshot({path:'/tmp/atlas-shots/v_road.png'});
// light
await page.click('#atTheme'); await wait(900); await page.screenshot({path:'/tmp/atlas-shots/v_light.png'}); await page.click('#atTheme'); await wait(500);
// narrow window
await page.setViewportSize({width:1100,height:700}); await wait(800); await ev(()=>{ AitkenAtlas.map.invalidateSize(); }); await page.screenshot({path:'/tmp/atlas-shots/v_narrow.png'});
log('errors: '+JSON.stringify(errs)); await shutdown(); process.exit(0);
