import { serve } from './harness.mjs'; import { LOG, checker, fresh, loadZips, sess, Z, finish } from './helpers.mjs';
const log=LOG(), c=checker(log); const srv=await serve(); setTimeout(()=>{log('HARD TIMEOUT');process.exit(2)},170000);
const one=async(label,files,n,fn)=>{ log('\n# '+label); const {ctx,page,errs}=await fresh(); try{ await loadZips(page,files,n); await fn(await sess(page),page); c(label+': no console errors',errs.length===0,errs); }catch(e){ c(label+': ran',false,e.message); } await ctx.browser().close(); };

await one('real V1.1 (config sidecar)',['session_20260913_095700_v11.zip'],1,(s)=>{ const x=s[0]; c('generation v1.1',x.gen==='v1.1',x.gen); c('basis deviation',x.basis==='deviation',x.basis); c('8 segments, 3 tags, 88 fixes',x.nSeg===8&&x.nTag===3&&x.nGps===88,x); });
await one('V1.0 with sensor.csv (probe)',['session_20260905_163505_v10.zip'],1,(s)=>{ const x=s[0]; c('generation v1.0',x.gen==='v1.0',x.gen); c('basis magnitude',x.basis==='magnitude',x.basis); c('basis verified against raw signal',/verified against the raw signal/.test(x.note),x.note); c('date parsed from folder name',x.name==='session_20260905_163505',x.name); });
await one('V1.0 without sensor.csv',['session_20260905_171000_v10_nosensor.zip'],1,(s)=>{ const x=s[0]; c('generation v1.0, magnitude by default',x.gen==='v1.0'&&x.basis==='magnitude',x); c('says it could not verify',/no sensor\.csv/.test(x.note),x.note); });
await one('V2 (detectorVersion 2 + patches.csv)',['session_20261016_081500_v2.zip'],1,async(s,page)=>{ const x=s[0]; c('generation v2',x.gen==='v2',x.gen); c('2 patches',x.nPatch===2,x.nPatch); const coords=await page.evaluate(()=>{ const m=AitkenAtlas.hydrate([...AitkenAtlas.Model.sessions.keys()][0]); return m.patches.map(p=>p.coords.length); }); c('patch paths follow GPS (>=2 points each)',coords.every(n=>n>=2),coords); });
await one('clean bundle of a V1.0 ride (no config)',['session_20260905_163505_clean.zip'],1,(s)=>{ const x=s[0]; c('generation clean',x.gen==='clean',x.gen); c('basis probed = magnitude',x.basis==='magnitude',x.basis+' '+x.note); });
await one('clean bundle of a V1.1 ride (no config)',['session_20260913_095700_clean_of_v11.zip'],1,(s)=>{ const x=s[0]; c('generation clean',x.gen==='clean',x.gen); c('basis probed = deviation',x.basis==='deviation',x.basis+' '+x.note); });
await one('BOM + CRLF',['session_20260922_090000_bomcrlf.zip'],1,(s)=>{ const x=s[0]; c('all 8 segments + 88 fixes parsed',x.nSeg===8&&x.nGps===88,x); c('config.json with BOM read (v1.1)',x.gen==='v1.1',x.gen); });
await one('future schema/detector',['session_20260923_090000_future.zip'],1,(s)=>{ const x=s[0]; c('loads (v2 semantics)',x.gen==='v2'&&x.nSeg===8,x); c('warns about detector 5',x.warn.some(w=>/detector version 5/.test(w)),x.warn); c('warns about schema 3',x.warn.some(w=>/schema_version 3/.test(w)),x.warn); c('keeps unknown column hdop',x.warn.some(w=>/hdop/.test(w)),x.warn); });
await one('no GPS',['session_20260924_090000_nogps.zip'],1,(s)=>{ const x=s[0]; c('imports with warning, 0 fixes',x.nGps===0&&x.warn.some(w=>/cannot be placed/.test(w)),x); });
await one('several session folders in one zip',['two_sessions.zip'],2,(s)=>{ c('2 sessions from one zip',s.length===2,s.map(x=>x.name)); });
await one('not a session zip',['not_a_session.zip'],0,(s,page)=>{ c('nothing imported',s.length===0); });

// dedupe / replace
log('\n# dedupe + clean replaces plain');
{ const {ctx,page}=await fresh(); await loadZips(page,['session_20260905_163505_v10.zip'],1);
  const a=await sess(page); await page.setInputFiles('#atZipInput',[Z+'session_20260905_171000_v10_nosensor.zip']); await page.waitForTimeout(1500);
  const b=await sess(page); c('same GPS track is not loaded twice',b.length===1,b.length);
  await page.setInputFiles('#atZipInput',[Z+'session_20260905_163505_clean.zip']); await page.waitForTimeout(1800);
  const d=await sess(page); c('clean bundle replaces the plain import (same id, now clean)',d.length===1&&d[0].clean===true&&d[0].id===a[0].id,d);
  await page.setInputFiles('#atZipInput',[Z+'session_20260905_163505_v10.zip']); await page.waitForTimeout(1500);
  const e=await sess(page); c('plain re-import does not overwrite the clean one',e.length===1&&e[0].clean===true,e);
  await ctx.browser().close(); }

// cross-generation consistency of M
log('\n# same physical event, three generations -> same M');
{ const {ctx,page}=await fresh(); await loadZips(page,['session_20260913_095700_v11.zip'],1);
  const r=await page.evaluate(()=>{ const A=AitkenAtlas; const dev=[8.076,15.435,35.651,32.917,7.030,7.664,3.731,23.284]; const raw=dev.map(v=>v+9.81);
    const m11=dev.map(v=>A.mOfPeak(v,'deviation')), m10=raw.map(v=>A.mOfPeak(v,'magnitude')); A.Prefs.normBasis=false; const m10off=raw.map(v=>A.mOfPeak(v,'magnitude')); A.Prefs.normBasis=true;
    return {maxDiff:Math.max(...m11.map((v,i)=>Math.abs(v-m10[i]))), m11:m11.map(v=>+v.toFixed(2)), m10off:m10off.map(v=>+v.toFixed(2))}; });
  c('V1.0 raw peaks land on the same M as V1.1 deviation peaks (normalised)',r.maxDiff<1e-9,r); c('with normalisation off, V1.0 reads higher (matches Review)',r.m10off.some((v,i)=>v>r.m11[i]),r); await ctx.browser().close(); }
process.exit(await finish(c));
