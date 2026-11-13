// Review's mobile shell and waveform, driven by real CDP touch (not dispatched DOM events).
import { serve } from './harness.mjs';
import { LOG, checker, fresh, Z, finish } from './helpers.mjs';
import { Touch, segPoint } from './touch.mjs';
const log=LOG(), c=checker(log); await serve(8765);
setTimeout(()=>{log('HARD TIMEOUT');process.exit(2)},180000);
const {ctx,page,errs}=await fresh({ctxOpts:{viewport:{width:390,height:844},hasTouch:true,isMobile:true,deviceScaleFactor:2}});
const T=await Touch.on(page), ev=(fn,a)=>page.evaluate(fn,a), settle=(ms=700)=>page.waitForTimeout(ms);
const detent=(name)=>ev(name=>AitkenShell.Sheets.get(name).detent,name);
const tap=async(selector)=>{ const b=await page.locator(selector).boundingBox(); if(!b) throw new Error('Hidden touch target: '+selector); await T.tap(b.x+b.width/2,b.y+b.height/2); await settle(); };
const state=()=>ev(()=>{const S=AitkenWB.debug.S;return {view:{...S.view},selection:{...S.selection},multi:[...S.multi.segIds],segments:S.segments.map(s=>[s.id,s.startSec,s.durSec]),labels:S.labels.length};});
const closeSheets=async()=>{ await ev(()=>{for(const n of ['tree','insp','signal','menu']) AitkenShell.Sheets.get(n).set('closed',{animate:false});}); await settle(); };
log('# compact Review structure');
c('portrait uses the compact bottom-sheet layout',await ev(()=>document.documentElement.dataset.layout==='compact'&&document.documentElement.dataset.sheet==='bottom'));
c('dock stays hidden until a session loads',await ev(()=>getComputedStyle(dock).display==='none'));
c('empty Review has no horizontal overflow',await ev(()=>document.documentElement.scrollWidth<=innerWidth));
await page.setInputFiles('#fileInput',Z+'session_20260913_095700_v11.zip');
await page.waitForFunction(()=>AitkenWB.debug.S.loaded&&AitkenWB.debug.S.segments.length===8); await settle(1000);
c('Review loads the real trimmed ride',await ev(()=>AitkenWB.debug.S.totalDur>80&&AitkenWB.debug.S.labels.length===3));

c('loaded Review shows the dock without overflow',await ev(()=>getComputedStyle(dock).display!=='none'&&document.documentElement.scrollWidth<=innerWidth));
log('\n# sheets, native scrolling, and modal scrims');
// On a short phone even the eight real segments exceed the full sheet's body height.
await page.setViewportSize({width:390,height:560}); await settle();
await tap('#dockItems'); c('Items opens at half',await detent('tree')==='half');
let box=await page.locator('#treePane').boundingBox();
await T.swipe(195,box.y+12,195,100,{ms:240}); await settle(1500);
c('dragging the Items grab handle expands to full',await detent('tree')==='full');
const body=await ev(()=>{const e=treeBody,r=e.getBoundingClientRect(); return {x:r.left+r.width/2,y:r.top+r.height*.65,sh:e.scrollHeight,ch:e.clientHeight};});
c('Items body has scrollable content',body.sh>body.ch,body);
const beforeScroll=await state(); await T.swipe(body.x,body.y,body.x,body.y-130,{ms:220}); await settle(1500);
c('scrolling Items scrolls the body and leaves the full sheet open',await detent('tree')==='full'&&await ev(()=>treeBody.scrollTop>0));
c('scrolling Items does not change the selection',JSON.stringify((await state()).selection)===JSON.stringify(beforeScroll.selection));
await ev(()=>{treeBody.scrollTop=0;});
await T.swipe(body.x,body.y-80,body.x,body.y+110,{ms:180}); await settle(1500);
c('pulling down at scrollTop zero hands the gesture to the sheet',await detent('tree')!=='full');
await closeSheets(); await tap('#dockMore');
c('More opens the session sheet and its scrim',await detent('menu')==='half'&&await ev(()=>scrim.classList.contains('on')));
await tap('#scrim'); c('tapping the scrim closes More',await detent('menu')==='closed');
await tap('#btnSigOpts');
c('Signal options opens with the existing controls reparented',await detent('signal')==='half'&&await ev(()=>document.querySelector('#signalBody #metricPanel')!==null));
await tap('#scrim'); c('scrim closes Signal options',await detent('signal')==='closed');
await tap('#dockAdd'); c('Add opens the existing add controls',await ev(()=>addPop.classList.contains('open')&&!!addPop.querySelector('#btnAddSegment')));
await tap('#dockAdd'); c('Add can be dismissed without editing',!(await ev(()=>addPop.classList.contains('open'))));

log('\n# waveform pinch, pan, tap, and long press');
await page.setViewportSize({width:390,height:844}); await settle();
await closeSheets();
await ev(()=>{const D=AitkenWB.debug;D.setView(10,50);D.drawWave();}); await settle();
const wr=await page.locator('#waveCanvasWrap').boundingBox(), cx=wr.x+wr.width*.55, cy=wr.y+wr.height*.48;
const start=await state();
await T.pinch(cx,cy,40,95); await settle(); const zoomed=await state();
c('spreading two fingers zooms the waveform',zoomed.view.end-zoomed.view.start<(start.view.end-start.view.start)*.6,{before:start.view,after:zoomed.view});
c('pinching edits neither segments nor labels',JSON.stringify(start.segments)===JSON.stringify(zoomed.segments)&&start.labels===zoomed.labels);
await T.swipe(cx+45,cy,cx-55,cy,{ms:250}); await settle(1500); const panned=await state();
c('a one-finger swipe pans the time window',panned.view.start>zoomed.view.start,{before:zoomed.view,after:panned.view});
c('panning preserves the window span',Math.abs((panned.view.end-panned.view.start)-(zoomed.view.end-zoomed.view.start))<.01);
c('panning does not select or edit a segment',panned.selection.id===zoomed.selection.id&&JSON.stringify(panned.segments)===JSON.stringify(start.segments));
await ev(()=>{const D=AitkenWB.debug,s=D.S.segments.slice().sort((a,b)=>a.startSec-b.startSec)[0];D.setView(Math.max(0,s.startSec-1),s.startSec+s.durSec+1);D.drawWave();}); await settle();
let p=await segPoint(page); await T.tap(p.x,p.y); await settle();
c('tapping a waveform segment selects it', (await state()).selection.id===p.id,await state());
c('selection brings the Inspector up at peek',await detent('insp')==='peek');
await closeSheets();
// Zoom in so the selected segment's resize handles do not occupy its entire touch hit area.
await ev(()=>{const D=AitkenWB.debug,s=D.S.segments.slice().sort((a,b)=>a.startSec-b.startSec)[0];D.setView(s.startSec-.1,s.startSec+s.durSec+.1);D.drawWave();}); await settle();
p=await segPoint(page); await T.hold(p.x,p.y,560); await settle();
c('holding a segment enters multi-selection', (await state()).multi.includes(p.id),await state());
c('long-press did not edit segment boundaries',JSON.stringify((await state()).segments)===JSON.stringify(start.segments));
await closeSheets();

log('\n# rotation, tablet, and desktop restoration');
for(const [width,height,sheet] of [[844,390,'side'],[820,1180,'side'],[390,844,'bottom']]){
 await page.setViewportSize({width,height}); await settle(1000);
 c(`${width}x${height}: expected sheet direction, no overflow`,await ev(sheet=>document.documentElement.dataset.sheet===sheet&&document.documentElement.scrollWidth<=innerWidth,sheet));
 await tap('#dockItems');
 c(`${width}x${height}: Items opens within the viewport`,await ev(()=>{const r=treePane.getBoundingClientRect();return r.width>100&&r.left>=-1&&r.right<=innerWidth+1&&r.top>=0&&r.top<innerHeight;}));
 await closeSheets();
}
c('rotation leaves the waveform sized and the session intact',await ev(()=>waveCanvas.width>100&&AitkenWB.debug.S.loaded));
await page.setViewportSize({width:1500,height:900}); await page.mouse.click(750,25); await settle(1000);
c('desktop classification and mouse input restored',await ev(()=>document.documentElement.dataset.layout==='desktop'&&document.documentElement.dataset.input==='mouse'));
c('desktop controls return home and dock is hidden',await ev(()=>getComputedStyle(dock).display==='none'&&!document.querySelector('#signalBody #metricPanel')&&!document.querySelector('#menuSession #grpFile')));
c('desktop panes have no leftover sheet transforms',await ev(()=>[treePane,inspPane].every(e=>e.style.transform==='')));
c('ride and long-press multi-selection survive rotation',await ev(id=>AitkenWB.debug.S.loaded&&AitkenWB.debug.S.multi.segIds.has(id),p.id));
c('no console errors in Review touch paths',errs.length===0,errs);
await ctx.close(); process.exit(await finish(c));
