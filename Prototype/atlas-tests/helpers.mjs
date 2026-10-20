import fs from 'fs'; import { serve, launch, ROOT, shutdown } from './harness.mjs';
export const Z=ROOT+'/zips/';
export const LOG=()=>(...a)=>console.log(a.join(' '));   // the suites just print; run-all.mjs collects exit codes
export function checker(log){ let pass=0, fail=0; const c=(name,cond,detail)=>{ if(cond){ pass++; log('  ok   '+name); } else { fail++; log('  FAIL '+name+(detail!==undefined?'  -> '+(typeof detail==='string'?detail:JSON.stringify(detail)):'')); } }; c.done=()=>{ log(`\n${pass} passed, ${fail} failed`); return fail; }; return c; }
export async function fresh(opts={}){
  const ctx=await launch(opts); const page=await ctx.newPage(); const errs=[];
  page.on('console',m=>{ if(m.type()==='error') errs.push(m.text()); }); page.on('pageerror',e=>errs.push('PAGEERROR '+e.message));
  await page.goto('http://localhost:'+(opts.port||8765)+'/workbench.html',{waitUntil:'domcontentloaded'});
  await page.waitForSelector('html[data-atlas-ready="1"]',{timeout:20000});
  return {ctx,page,errs};
}
export async function loadZips(page, names, expect){
  await page.click('#modeSwitch [data-mode=atlas]');
  await page.setInputFiles('#atZipInput', names.map(n=>Z+n));
  await page.waitForFunction((n)=>AitkenAtlas.Model.sessions.size>=n && !document.querySelector('#atSessList .at-row.build'),expect,{timeout:30000});
  await page.waitForTimeout(300);
}
export const sess=(page)=>page.evaluate(()=>[...AitkenAtlas.Model.sessions.values()].map(s=>({name:s.meta.name,gen:s.meta.generation,basis:s.meta.peakBasis,note:s.meta.basisNote,nGps:s.meta.nGps,nSeg:s.meta.nSeg,nTag:s.meta.nTag,nPatch:s.meta.nPatch,clean:s.meta.isClean,warn:s.meta.warnings.map(w=>w.level+':'+w.text),id:s.meta.id})));

/** print the summary, close all browsers, return the process exit code */
export async function finish(c){ const bad=c.done(); await shutdown(); return bad?1:0; }
