// Atlas on a phone: the sidebar as a bottom sheet, the card as a second sheet, touch gestures on the list and the map,
// the kebab action sheet, sheet-aware fitting, rotation, and the round trip back to the laptop layout.
// Real touch (CDP Input.dispatchTouchEvent), not synthetic JS events.  Chrome swallows a tap that lands within ~1s of a fast flick
// (it treats it as "stop the fling"), so the script spaces gestures with settle() the way a person would.
import fs from 'fs'; import { serve } from './harness.mjs'; import { LOG, checker, fresh, Z, finish } from './helpers.mjs'; import { Touch } from './touch.mjs';
const log=LOG(), c=checker(log); const srv=await serve(8765); setTimeout(()=>{log('HARD TIMEOUT');process.exit(2)},240000);
const {ctx,page,errs}=await fresh({ctxOpts:{viewport:{width:390,height:844},hasTouch:true,isMobile:true,deviceScaleFactor:2}});
const city=fs.readdirSync(Z).filter(f=>/^city_/.test(f));
await page.tap('#modeSwitch [data-mode=atlas]');
await page.setInputFiles('#atZipInput',city.map(n=>Z+n));
await page.waitForFunction(()=>AitkenAtlas.Model.sessions.size>=8&&!document.querySelector('#atSessList .at-row.build'),null,{timeout:30000});
await page.waitForTimeout(1500);
const T=await Touch.on(page), ev=(fn,a)=>page.evaluate(fn,a), settle=(ms=1500)=>page.waitForTimeout(ms);
const R=(id)=>ev((id)=>{ const r=document.getElementById(id).getBoundingClientRect(); return {t:r.top,b:r.bottom,l:r.left,r:r.right,w:r.width,h:r.height}; },id);
const S=()=>ev(()=>({side:atSide.dataset.detent,card:atCard.dataset.detent,on:atCard.classList.contains('on'),covered:atSide.classList.contains('covered'),
  sv:+getComputedStyle(atlas).getPropertyValue('--sv'),sel:AitkenAtlas.sel?AitkenAtlas.sel.kind:null,nSel:AitkenAtlas.Prefs.selectedSessions.length,
  menu:document.getElementById('atAct').classList.contains('on'),legend:document.getElementById('atCtl').classList.contains('legend-open')}));
const atlas$=()=>R('atlas');
const setSide=async(d)=>{ await ev((d)=>AitkenShell.Sheets.get('atSide').set(d),d); await settle(600); };
// is a lat/lon inside the part of the map that is not covered by the pills or the sheet?
const visibleCheck=(pts,slack=6)=>ev(([pts,slack])=>{ const m=AitkenAtlas.map, H=m.getSize().y, W=m.getSize().x;
  const sheet=document.getElementById('atCard').classList.contains('on')?document.getElementById('atCard'):document.getElementById('atSide');
  const mapTop=m.getContainer().getBoundingClientRect().top, sTop=sheet.getBoundingClientRect().top-mapTop;
  return pts.map(p=>{ const q=m.latLngToContainerPoint(p); return {x:Math.round(q.x),y:Math.round(q.y),ok:q.x>=-slack&&q.x<=W+slack&&q.y>=100-slack&&q.y<=sTop+slack}; }); },[pts,slack]);

log('# structure on a phone (390x844, touch)');
const attrs=await ev(()=>['layout','orient','sheet','input'].map(k=>document.documentElement.dataset[k]).join(' '));
c('classified compact / portrait / bottom / touch',attrs==='compact portrait bottom touch',attrs);
const par=await ev(()=>({search:document.querySelector('.at-search').parentElement.id,hud:document.getElementById('atHud').parentElement.id,legend:document.getElementById('atLegend').parentElement.id}));
c('search floats over the map; stats live in the sheet; legend sits behind the key button',par.search==='atlas'&&par.hud==='atSide'&&par.legend==='atCtl',par);
c('no horizontal overflow',await ev(()=>document.documentElement.scrollWidth<=innerWidth));
let s=await S(); const ar=await atlas$(), sr=await R('atSide');
c('sheet starts at its peek and --sv equals what it covers',s.side==='peek'&&Math.abs(s.sv-(ar.b-sr.t))<=2,{s,cover:ar.b-sr.t});
c('no backdrop-filter anywhere in Atlas on touch (re-blurring under every panel each pan frame)',await ev(()=>[...document.querySelectorAll('#atlas *')].every(e=>{ const b=getComputedStyle(e).backdropFilter; return !b||b==='none'; })));
c('zoom +/- hidden (pinch is the gesture)',await ev(()=>getComputedStyle(document.querySelector('#atMap .leaflet-control-zoom')).display==='none'));
c('rows are at least 48px tall',await ev(()=>[...document.querySelectorAll('#atSessList .at-row')].every(r=>r.getBoundingClientRect().height>=48)));
c('text fields are 16px (iOS zooms the page into anything smaller)',await ev(()=>['#atSearch','#atSessFilter'].every(q=>{ const e=document.querySelector(q); return !e||parseFloat(getComputedStyle(e).fontSize)>=16; })));

log('\n# sheet gestures');
let top=sr.t; await T.swipe(195,top+12,195,top-150,{ms:200}); await settle(700); s=await S();
c('dragging the grab handle up settles on half',s.side==='half',s);
top=(await R('atSide')).t; await T.swipe(195,top+12,195,100,{ms:200}); await settle(); s=await S();
c('a long drag settles on full',s.side==='full'&&s.card==='closed',s);
// Off-screen rows use content-visibility:auto: measure touch targets only after the sheet reveals them.
const rowActions=await ev(()=>{ const r=document.querySelector('#atSessList .at-row'), k=r.querySelector('.at-kebab').getBoundingClientRect(); return {actions:getComputedStyle(r.querySelector('.at-racts')).display,width:k.width,height:k.height}; });
c('hover-only row buttons are gone; the visible kebab is finger-sized',rowActions.actions==='none'&&rowActions.width>=40&&rowActions.height>=40,rowActions);
const lr=await ev(()=>{ const l=document.getElementById('atSessList'),r=l.getBoundingClientRect(); return {x:r.left+r.width/2,y:r.top+r.height/2,sh:l.scrollHeight,ch:l.clientHeight}; });
c('the session list is a real scroller',lr.sh>lr.ch+20,lr);
const before=s.nSel; await T.swipe(lr.x,lr.y+50,lr.x,lr.y-70,{ms:140}); await settle(900);
const afterFlick=await ev(()=>({st:document.getElementById('atSessList').scrollTop,n:AitkenAtlas.Prefs.selectedSessions.length,d:atSide.dataset.detent}));
c('flicking the list scrolls it and ticks nothing (the old code toggled on pointerdown)',afterFlick.st>0&&afterFlick.n===before&&afterFlick.d==='full',{before,afterFlick});
const row0=await ev(()=>{ const r=[...document.querySelectorAll('#atSessList .at-row')].find(x=>{ const b=x.getBoundingClientRect(),l=document.getElementById('atSessList').getBoundingClientRect(); return b.top>=l.top+4&&b.bottom<=l.bottom-4; }); const b=r.getBoundingClientRect(); return {x:b.left+90,y:b.top+b.height/2,id:r.dataset.id}; });
await T.tap(row0.x,row0.y); await settle(500);
c('a tap toggles exactly that row',(await S()).nSel===before-1||(await S()).nSel===before+1,{before,after:(await S()).nSel});
await T.tap(row0.x,row0.y); await settle(500);
c('and tapping again toggles it back',(await S()).nSel===before);
// pull down on content that is scrolled: the content scrolls, the sheet stays
await T.swipe(lr.x,lr.y-40,lr.x,lr.y+80,{ms:160}); await settle(900);
c('pulling down on a scrolled list scrolls it back, not the sheet',(await S()).side==='full',await S());
await ev(()=>{ document.getElementById('atSessList').scrollTop=0; document.querySelector('#atSide .at-page.on').scrollTop=0; });
await T.swipe(195,450,195,760,{ms:160}); await settle(); s=await S();
c('pulling down from the very top of a full sheet hands the gesture to the sheet',s.side!=='full',s);
await setSide('full');

log('\n# long-press paint-select on a row list');
const rows=await ev(()=>{ document.getElementById('atSessList').scrollTop=0; const l=document.getElementById('atSessList').getBoundingClientRect(); return [...document.querySelectorAll('#atSessList .at-row')].slice(0,4).map(r=>{ const b=r.getBoundingClientRect(); return {id:r.dataset.id,x:b.left+90,y:b.top+b.height/2,vis:b.top>=l.top&&b.bottom<=l.bottom}; }); });
const ison=(ids)=>ev((ids)=>ids.map(id=>AitkenAtlas.Prefs.selectedSessions.includes(id)),ids);
const was=await ison(rows.map(r=>r.id)); const scBefore=await ev(()=>document.getElementById('atSessList').scrollTop);
await T.down({x:rows[0].x,y:rows[0].y}); await T.sleep(560);
c('holding enters paint mode (haptic tick, list marked)',await ev(()=>document.getElementById('atSessList').dataset.painting==='1'));
for(let i=1;i<=8;i++){ await T.sleep(30); await T.move({x:rows[0].x,y:rows[0].y+(rows[2].y-rows[0].y)*i/8}); }
await T.sleep(120); await T.up(); await settle(600);
const now=await ison(rows.map(r=>r.id)); const sc2=await ev(()=>({st:document.getElementById('atSessList').scrollTop,d:atSide.dataset.detent,p:document.getElementById('atSessList').dataset.painting}));
c('dragging after the hold paints the rows it crosses to the same state',now[0]===!was[0]&&now[1]===!was[0]&&now[2]===!was[0]&&now[3]===was[3],{was,now});
c('...without scrolling the list or moving the sheet, and painting ends on release',sc2.st===scBefore&&sc2.d==='full'&&!sc2.p,{scBefore,sc2});
await ev(()=>AitkenAtlas.Prefs.selectedSessions=[]);   // (reset below by All)
await page.tap('#atSessAll'); await settle(500);

log('\n# kebab action sheet');
await ev(()=>{ document.getElementById('atSessList').scrollTop=0; }); await settle(300);
const kb=await ev(()=>{ const b=document.querySelector('#atSessList .at-kebab').getBoundingClientRect(); return {x:b.left+b.width/2,y:b.top+b.height/2}; });
await T.tap(kb.x,kb.y); await settle(500); s=await S();
const items=await ev(()=>[...document.querySelectorAll('#atAct .at-act-box button')].map(b=>b.textContent.replace(/\s+/g,' ').trim()));
c('the kebab opens an action sheet with the row\'s own actions + Cancel',s.menu&&items.length===4&&/Zoom/.test(items[0])&&/Cancel/.test(items[3]),items);
await ev(()=>document.querySelector('#atAct .at-act-scrim').click()); await settle(300);
c('tapping the scrim closes it',!(await S()).menu);
await T.tap(kb.x,kb.y); await settle(500);
const zoomTarget=await ev(()=>{ const id=document.querySelector('#atSessList .at-row').dataset.id; AitkenAtlas.map.setView([0,0],3,{animate:false}); const m=AitkenAtlas.Model.sessions.get(id).meta; return m.bbox; });
await ev(()=>[...document.querySelectorAll('#atAct .at-act-box button')][0].click()); await settle(2500); s=await S();
const vis=await visibleCheck([[zoomTarget[0],zoomTarget[1]],[zoomTarget[2],zoomTarget[3]]]);
c('"Zoom to this ride" closes the menu, drops the sheet to its peek and frames the ride in the part of the map that is visible',!s.menu&&s.side==='peek'&&vis.every(v=>v.ok),{s,vis});

log('\n# card as a second sheet');
await ev(()=>{ let e=null; AitkenAtlas.Model.sessions.forEach((x,id)=>{ if(e) return; AitkenAtlas.hydrate(id); const l=AitkenAtlas.eventsOf(id); if(l&&l.length) e=l[Math.floor(l.length/2)]; }); AitkenAtlas.map.setView([e.lat,e.lon],16.5,{animate:false}); });
await settle(1200);
const mk=await ev(()=>{ const m=AitkenAtlas.map, box=m.getContainer().getBoundingClientRect(), cc=m.getSize().divideBy(2); let best=null,bd=1e9; AitkenAtlas.Active.events.forEach(l=>l.forEach(e=>{ if(!e.shown||!e.marker) return; const p=m.latLngToContainerPoint(e.marker.getLatLng()), d=Math.hypot(p.x-cc.x,p.y-cc.y); if(d<bd){ bd=d; const ll=e.marker.getLatLng(); best={x:box.left+p.x,y:box.top+p.y,ll:[ll.lat,ll.lng]}; } })); return best; });
await T.tap(mk.x,mk.y); await settle(1300); s=await S();
c('tapping a marker raises the card at its peek and tucks the sidebar sheet away',s.on&&s.card==='peek'&&s.covered&&s.sel==='event',s);
const cr=await R('atCard'); c('--sv follows the card',Math.abs(s.sv-((await atlas$()).b-cr.t))<=2,{sv:s.sv,cover:(await atlas$()).b-cr.t});
const vm=await visibleCheck([mk.ll]);
c('the selected event is centred in what is visible, not hidden under the card',vm[0].ok,vm);
await T.tap(...await ev(()=>{ const b=document.querySelector('#atCard [data-card=close]').getBoundingClientRect(); return [b.left+b.width/2,b.top+b.height/2]; })); await settle(900); s=await S();
c('the ✕ closes the card, clears the selection and brings the sidebar sheet back',!s.on&&s.card==='closed'&&!s.covered&&s.sel===null&&s.side==='peek',s);
await T.tap(mk.x,mk.y); await settle(1300);
let ctop=(await R('atCard')).t; await T.swipe(195,ctop+40,195,ctop+300,{ms:140}); await settle(); s=await S();
c('swiping the card down is the same as the ✕',!s.on&&s.card==='closed'&&s.sel===null,s);
await T.tap(mk.x,mk.y); await settle(1300); ctop=(await R('atCard')).t; await T.swipe(195,ctop+40,195,ctop-200,{ms:200}); await settle(); s=await S();
c('dragging the card header up expands it',s.card==='half'||s.card==='full',s);
c('the card body scrolls inside the sheet',await ev(()=>{ const b=document.querySelector('#atCard .atc-body'); return getComputedStyle(b).overflowY==='auto'&&b.scrollHeight>=b.clientHeight; }));
await ev(()=>document.querySelector('#atCard [data-card=close]').click()); await settle(800);

log('\n# fit and the controls');
await setSide('peek'); await ev(()=>AitkenAtlas.map.setView([0,0],3,{animate:false})); await ev(()=>AitkenAtlas.goRegion('__all')); await settle(2500);
const bb=await ev(()=>{ const pts=[]; AitkenAtlas.Model.sessions.forEach(s=>{ const b=s.meta.bbox; pts.push([b[0],b[1]],[b[2],b[3]]); }); return pts; });
const fit=await visibleCheck(bb); c('"fit to my rides" frames every ride inside the uncovered map',fit.every(v=>v.ok),fit.filter(v=>!v.ok));
const fb=await ev(()=>{ const b=document.getElementById('atFit').getBoundingClientRect(); return {x:b.left+b.width/2,y:b.top+b.height/2}; });
await ev(()=>AitkenAtlas.map.setView([0,0],3,{animate:false})); await T.tap(fb.x,fb.y); await settle(2500);
c('the fit button does the same',(await visibleCheck(bb)).every(v=>v.ok));
const kk=await ev(()=>{ const b=document.getElementById('atKey').getBoundingClientRect(); return {x:b.left+b.width/2,y:b.top+b.height/2}; });
await T.tap(kk.x,kk.y); await settle(500); s=await S();
c('the key button opens the legend popover on screen',s.legend&&await ev(()=>{ const r=document.getElementById('atLegend').getBoundingClientRect(); return r.width>150&&r.left>=0&&r.right<=innerWidth&&r.top>=0; }),s);
await T.tap(100,380); await settle(600);
c('tapping the map closes it',!(await S()).legend);
await T.tap(kk.x,kk.y); await settle(300);   // legend open again: must not survive a rotation (checked below)

log('\n# real map gestures');
const mapBefore=await ev(()=>({zoom:AitkenAtlas.map.getZoom(),lat:AitkenAtlas.map.getCenter().lat,lng:AitkenAtlas.map.getCenter().lng}));
await T.pinch(195,300,40,90); await settle();
c('two-finger spreading zooms the map without moving the sheet',await ev(z=>AitkenAtlas.map.getZoom()>z,mapBefore.zoom)&&(await S()).side==='peek');
const panBefore=await ev(()=>({lat:AitkenAtlas.map.getCenter().lat,lng:AitkenAtlas.map.getCenter().lng}));
await T.swipe(100,300,260,330,{ms:300}); await settle();
c('one-finger map pan changes the centre without selecting a row',await ev(p=>{const q=AitkenAtlas.map.getCenter();return Math.hypot(q.lat-p.lat,q.lng-p.lng)>1e-6;},panBefore)&&(await S()).side==='peek');

log('\n# search');
await ev(()=>{ document.getElementById('atCtl').classList.remove('legend-open'); });
const sp=await ev(()=>{ const b=document.getElementById('atSearch').getBoundingClientRect(); return {x:b.left+80,y:b.top+b.height/2}; });
await setSide('half'); await T.tap(sp.x,sp.y); await settle(500);
c('focusing search drops the sheet to its peek (map + results stay visible)',(await S()).side==='peek');
// Only rides are imported here. "Test" matches no local records and offers only external place search.
await page.keyboard.type('2026'); await settle(900);
const pop=await ev(()=>{ const p=document.getElementById('atSearchPop'),r=p.getBoundingClientRect(),rows=[...p.querySelectorAll('.at-sr')]; return {open:p.classList.contains('open'),n:rows.length,top:r.top,bottom:r.bottom,h:rows[0]&&rows[0].getBoundingClientRect().height,inView:r.bottom<=innerHeight}; });
c('results open under the pill, tall enough for a finger, inside the screen',pop.open&&pop.n>0&&pop.h>=48&&pop.inView,pop);
const searchScroll=await ev(()=>{ const p=document.getElementById('atSearchPop'),r=p.getBoundingClientRect(); return {x:r.left+r.width/2,y:r.top+110}; });
await T.swipe(searchScroll.x,searchScroll.y,searchScroll.x,searchScroll.y-70,{ms:220}); await settle();
c('scrolling search results does not select a result',!(await S()).on&&(await S()).sel===null,await S());
await ev(()=>{ document.getElementById('atSearchPop').scrollTop=0; }); await settle(300);
const r1=await ev(()=>{ const b=document.querySelector('#atSearchPop .at-sr[data-n]').getBoundingClientRect(); return {x:b.left+b.width/2,y:b.top+b.height/2}; });
await T.tap(r1.x,r1.y); await settle(1500); s=await S();
c('tapping a result selects it (card up) and closes the list',s.sel!==null&&s.on&&!(await ev(()=>document.getElementById('atSearchPop').classList.contains('open'))),s);
await ev(()=>document.querySelector('#atCard [data-card=close]').click()); await settle(700);

log('\n# rotate: phone sideways');
await page.setViewportSize({width:844,height:390}); await settle(1500);
const a2=await ev(()=>['layout','orient','sheet'].map(k=>document.documentElement.dataset[k]).join(' '));
c('classified compact / landscape / side',a2==='compact landscape side',a2);
const p2=await ev(()=>({search:document.querySelector('.at-search').parentElement.id,hud:document.getElementById('atHud').parentElement.id,legend:document.getElementById('atLegend').parentElement.id,
  ctl:getComputedStyle(document.getElementById('atCtl')).display,sideW:document.getElementById('atSide').getBoundingClientRect().width,legendOpen:document.getElementById('atCtl').classList.contains('legend-open'),
  over:document.documentElement.scrollWidth>innerWidth,tr:document.getElementById('atSide').style.transform}));
c('everything went back where the laptop layout keeps it; no horizontal overflow',p2.search==='atSide'&&p2.hud==='atlas'&&p2.legend==='atlas'&&p2.ctl==='none'&&!p2.over,p2);
c('the sidebar is narrower than half the screen and free of sheet transforms',p2.sideW<=844*.5+1&&p2.tr==='',p2);
await ev(()=>{ AitkenAtlas.hydrate([...AitkenAtlas.Model.sessions.keys()][0]); const e=AitkenAtlas.eventsOf([...AitkenAtlas.Model.sessions.keys()][0])[3]; AitkenAtlas.selectEvent?AitkenAtlas.selectEvent(e):0; });
await settle(900);
const sc=await ev(()=>({on:atCard.classList.contains('on'),collapsed:atlas.classList.contains('side-collapsed'),pref:AitkenAtlas.Prefs.sideCollapsed,cardH:atCard.getBoundingClientRect().height}));
c('a card opening on a narrow screen steps the sidebar aside without touching the saved preference',sc.on&&sc.collapsed&&!sc.pref,sc);
await ev(()=>document.querySelector('#atCard [data-card=close]').click()); await settle(700);
const sc3=await ev(()=>({collapsed:atlas.classList.contains('side-collapsed'),hasContent:atCard.innerHTML.length>0}));
c('closing it brings the sidebar back (and the card is emptied: only a bottom sheet keeps its text while sliding)',!sc3.collapsed&&!sc3.hasContent,sc3);

log('\n# back to a laptop');
await page.setViewportSize({width:1500,height:900}); await page.mouse.click(700,500); await settle(1500);
const a3=await ev(()=>['layout','sheet','input'].map(k=>document.documentElement.dataset[k]).join(' '));
c('classified desktop / mouse',/^desktop\s*mouse$/.test(a3.trim().replace(/\s+/g,' '))||a3==='desktop undefined mouse'||(a3.startsWith('desktop')&&a3.endsWith('mouse')),a3);
const p3=await ev(()=>({search:document.querySelector('.at-search').parentElement.id,hud:document.getElementById('atHud').parentElement.id,legend:document.getElementById('atLegend').parentElement.id,
  tr:atSide.style.transform,w:atSide.getBoundingClientRect().width,covered:atSide.classList.contains('covered'),ctl:getComputedStyle(document.getElementById('atCtl')).display,
  kebab:getComputedStyle(document.querySelector('#atSessList .at-kebab')).display,racts:getComputedStyle(document.querySelector('#atSessList .at-racts')).display}));
c('the laptop layout is exactly as before: nodes home, no sheet transform, 372px sidebar, hover actions not the kebab',p3.search==='atSide'&&p3.hud==='atlas'&&p3.legend==='atlas'&&p3.tr===''&&Math.abs(p3.w-372)<2&&!p3.covered&&p3.ctl==='none'&&p3.kebab==='none'&&p3.racts!=='none',p3);
log('\n# A-Z navigation uses the page scroller (desktop and touch)');
const pack={format:'aitken-datasource',version:1,source:{name:'Alphabet roads (FICTIONAL)',sample:true},roads:Array.from({length:104},(_,i)=>({id:'az'+i,name:String.fromCharCode(65+Math.floor(i/4))+' test road '+i,geometry:{type:'LineString',coordinates:[[73.8,18.55],[73.801,18.551]]}}))};
await page.setInputFiles('#atSrcInput',{name:'alphabet.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(pack))});
await page.waitForSelector('#atModal.on'); await page.locator('#atmGo').click({noWaitAfter:true});
await page.waitForFunction(()=>[...AitkenAtlas.Model.sources.values()].some(s=>s.meta.nRoads===104));
await page.waitForSelector('#atModal.on',{state:'hidden'}); await settle(700);
for(const [width,height,touch] of [[1500,900,false],[390,844,true]]){
  await page.setViewportSize({width,height}); await settle(800);
  if(touch){ await T.tap(5,60); await setSide('full'); }
  await page.locator('#atTabs [data-tab=index]').click(); await settle(500);
  await ev(()=>{ document.querySelector('.at-page[data-page=index]').scrollTop=0; });
  await page.locator('#atAZ [data-l=Z]').click();
  // smooth scrolling takes longer on a slow machine: wait until the scroller stops moving instead of a fixed delay
  await page.waitForFunction(()=>{ const p=document.querySelector('.at-page[data-page=index]'); const t=p.scrollTop; const prev=window.__azPrev; window.__azPrev=t; return prev===t&&t>0; },null,{polling:250,timeout:15000}); await ev(()=>{ window.__azPrev=undefined; });
  const az=await ev(()=>{ const p=document.querySelector('.at-page[data-page=index]'),r=p.getBoundingClientRect(),h=document.getElementById('idx-Z').getBoundingClientRect(); return {scroll:p.scrollTop,top:h.top,bottom:h.bottom,pageTop:r.top,pageBottom:r.bottom}; });
  c(`${width}x${height}: Z scrolls the Index page to a visible letter heading`,az.scroll>0&&az.top>=az.pageTop-2&&az.bottom<=az.pageBottom+2,az);
}
await page.setViewportSize({width:820,height:1180}); await settle(800); await T.tap(600,100);
await page.locator('#atTheme').tap(); await settle(500);
c('tablet portrait uses side panels, light theme and no horizontal overflow',await ev(()=>document.documentElement.dataset.sheet==='side'&&atlas.dataset.theme==='light'&&document.documentElement.scrollWidth<=innerWidth));
c('tablet touch keeps opaque panels and finger-sized fields',await ev(()=>getComputedStyle(atSide).backdropFilter==='none'&&parseFloat(getComputedStyle(atSearch).fontSize)>=16));
c('no console errors during any of it',errs.length===0,errs.slice(0,5));
await ctx.close(); process.exit(await finish(c));
