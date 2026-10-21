// Optional: import REAL packs made by Datasets/tools/make_packs.py into Atlas and reconcile what is drawn and stored
// with the converter's MANIFEST.json (handoff step: "check the blue/purple split and the contractor groups against the source").
// Not part of `npm test` (the packs are not in the repo):   PACKS=~/atlas-packs node check-packs.mjs
import fs from 'fs'; import { serve } from './harness.mjs'; import { LOG, checker, fresh, finish } from './helpers.mjs';
const log=LOG(), c=checker(log), DIR=process.env.PACKS; if(!DIR){ console.log('Set PACKS to the folder holding datasource_*.json and MANIFEST.json'); process.exit(2); }
await serve(8765); setTimeout(()=>{log('HARD TIMEOUT');process.exit(2)},250000);
const man=JSON.parse(fs.readFileSync(DIR+'/MANIFEST.json','utf8')).packs;
const {ctx,page,errs}=await fresh(); const ev=(f,a)=>page.evaluate(f,a);
await page.click('#modeSwitch [data-mode=atlas]'); await page.waitForTimeout(500);
for(const k of Object.keys(man)){ await page.setInputFiles('#atSrcInput',[DIR+'/'+man[k].file]); await page.waitForSelector('#atModal.on'); await page.click('#atmGo');
  await page.waitForFunction((n)=>[...AitkenAtlas.Model.sources.values()].some(s=>s.meta.nRoads===n),man[k].roads,{timeout:60000}); await page.waitForTimeout(800); }
const R=await ev(()=>{ const cs=getComputedStyle(document.querySelector('#atlas')), contr=cs.getPropertyValue('--at-contr').trim(), ds=cs.getPropertyValue('--at-ds').trim(), out={};
  for(const [id,s] of AitkenAtlas.Model.sources){ const a=AitkenAtlas.Active.sources.get(id), rs=AitkenAtlas.ensureRoads(id).roads; let blue=0,purple=0;
    (a?a.items:[]).forEach(i=>{ const col=i.layer.options.color; if(col===contr) blue++; else if(col===ds) purple++; });
    const cn=new Set(); let aw=0; rs.forEach(r=>{ if(r.contractor) cn.add(r.contractor); ((r.props&&r.props.__awards)||[]).forEach(x=>{ aw++; cn.add(x.contractor); }); });
    out[s.meta.name]={roads:rs.length,blue,purple,awards:aw,contractors:cn.size,retrieved:s.meta.retrieved}; } return out; });
log(JSON.stringify(R,null,1));
for(const [k,m] of Object.entries(man)){ const r=Object.entries(R).find(([n])=>n===m.name)?.[1]; c(k+': roads, awards, contractors and blue lines match the manifest',!!r&&r.roads===m.roads&&r.awards===m.awards&&r.contractors===m.distinct_contractors&&r.blue===m.blue_roads,[r,m]);
  c(k+': everything else is drawn purple',!!r&&r.blue+r.purple===r.roads,r); }
c('no console errors',errs.length===0,errs.slice(0,3)); await ctx.close(); process.exit(await finish(c));
