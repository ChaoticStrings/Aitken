import { serve, launch } from './harness.mjs'; import { LOG, checker, Z, finish } from './helpers.mjs';
const log=LOG(), c=checker(log); await serve(8765); setTimeout(()=>{log('HARD TIMEOUT');process.exit(2)},90000);
const ctx=await launch(); const a=await ctx.newPage(); const errs=[]; a.on('pageerror',e=>errs.push(e.message));
await a.goto('http://localhost:8765/workbench.html',{waitUntil:'domcontentloaded'}); await a.waitForSelector('html[data-atlas-ready="1"]'); await a.click('#modeSwitch [data-mode=atlas]'); await a.waitForTimeout(500);
c('single tab: no warning',await a.evaluate(()=>!AitkenAtlas.Store.status.otherTab));
const b=await ctx.newPage(); await b.goto('http://localhost:8765/workbench.html',{waitUntil:'domcontentloaded'}); await b.waitForSelector('html[data-atlas-ready="1"]'); await b.waitForTimeout(1200); await a.waitForTimeout(500);
c('second tab is told about the first',await b.evaluate(()=>AitkenAtlas.Store.status.otherTab===true));
c('first tab is told about the second',await a.evaluate(()=>AitkenAtlas.Store.status.otherTab===true));
c('toolbar chip warns',await a.evaluate(()=>/another tab/.test(document.querySelector('#atDbChip').textContent)),await a.evaluate(()=>document.querySelector('#atDbChip').textContent));
// javascript: links from a data-source pack are never clickable
const pack=JSON.stringify({format:'aitken-datasource',version:1,source:{name:'evil',url:'javascript:alert(1)',license:'x'},roads:[{id:'1',name:'<img src=x onerror=alert(2)>',contractor:'<b>Bad</b> & Co',geometry:{type:'LineString',coordinates:[[73.8,18.5],[73.81,18.51]]}}]});
await a.setInputFiles('#atSrcInput',{name:'evil.json',mimeType:'application/json',buffer:Buffer.from(pack)}); await a.waitForSelector('#atModal.on'); await a.click('#atmGo'); await a.waitForTimeout(900);
await a.evaluate(()=>{ const id=[...AitkenAtlas.Model.sources.keys()][0]; const r=AitkenAtlas.ensureRoads(id).roads[0]; AitkenAtlas.selectRoad(r,id,{pan:false}); }); await a.waitForTimeout(500);
const dom=await a.evaluate(()=>({links:[...document.querySelectorAll('#atCard a')].map(x=>x.getAttribute('href')),imgs:document.querySelectorAll('#atCard img, #atSrcList img, #atIdxList img, #atSearchPop img').length,title:document.querySelector('#atCard .atc-title').textContent,contr:document.querySelector('#atCard .atc-contr .nm').textContent}));
c('javascript: URL is dropped (no link rendered)',!dom.links.some(h=>/^javascript:/i.test(h||'')),dom.links);
c('HTML in road names / contractors is shown as text, never executed',dom.imgs===0&&dom.title.includes('<img')&&dom.contr.includes('<b>Bad</b>'),dom);
c('no page errors',errs.length===0,errs); await ctx.close(); process.exit(await finish(c));
