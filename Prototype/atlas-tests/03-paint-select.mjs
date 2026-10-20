import { serve } from './harness.mjs'; import { LOG, checker, fresh, finish } from './helpers.mjs';
const log=LOG(), c=checker(log); await serve(8765); setTimeout(()=>{log('HARD TIMEOUT');process.exit(2)},150000);
const {ctx,page,errs}=await fresh(); await page.click('#modeSwitch [data-mode=atlas]');
// 70 tiny fictional sessions, generated in the page
await page.evaluate(async()=>{ const files=[]; for(let i=0;i<70;i++){ const z=new JSZip(); const d='session_2026'+String(8+Math.floor(i/28)).padStart(2,'0')+String(1+i%28).padStart(2,'0')+'_'+String(8+i%12).padStart(2,'0')+'0000';
  let g='schema_version,timestamp_sensor_ns,latitude,longitude,speed_mps,accuracy_m\n'; for(let k=0;k<30;k++) g+=`1,${(1000+i*50+k)*1e9},${(18.40+i*0.003+k*0.0004).toFixed(6)},${(73.70+i*0.002).toFixed(6)},8,6\n`;
  let s='schema_version,start_ns,duration_ns,peak_m,rms_m,speed_mps,epoch_ms\n'; for(let k=0;k<3;k++) s+=`1,${(1000+i*50+5+k*8)*1e9},300000000,${(12+k*9).toFixed(3)},3,8,${1790000000000+i*1e6}\n`;
  z.file(d+'/gps.csv',g); z.file(d+'/segments.csv',s); z.file(d+'/config.json','{"schemaVersion":1}'); files.push(new File([await z.generateAsync({type:'blob'})],d+'.zip')); }
  await AitkenAtlas.importZips(files); });
const n=await page.evaluate(()=>AitkenAtlas.Model.sessions.size); c('70 sessions imported',n===70,n);
const list=page.locator('#atSessList'); 
const info=()=>page.evaluate(()=>{ const l=document.querySelector('#atSessList'); const rows=[...l.querySelectorAll('.at-row[data-idx]')]; return {top:l.scrollTop,max:l.scrollHeight-l.clientHeight,sel:rows.map((r,i)=>r.classList.contains('sel')?i:-1).filter(i=>i>=0),prefs:AitkenAtlas.Prefs.selectedSessions.length,rowsN:rows.length}; });
const rect=async()=>await list.boundingBox();
const rowY=async(i)=>page.evaluate((i)=>{ const l=document.querySelector('#atSessList'),r=l.querySelectorAll('.at-row[data-idx]')[i].getBoundingClientRect(); return [r.left+16,r.top+r.height/2]; },i);
const contiguous=(a)=>a.length>0 && a[a.length-1]-a[0]===a.length-1;

await page.click('#atSessNone'); await page.waitForTimeout(300); let s=await info();
c('None clears everything', s.sel.length===0 && s.prefs===0, s);
const R=await rect(); const visible=Math.floor(R.height/42);
// paint ON, drag down into the bottom scroll zone and hold
let [x,y]=await rowY(2); await page.mouse.move(x,y); await page.mouse.down(); await page.mouse.move(x,y+60,{steps:6}); await page.mouse.move(x,R.y+R.height-14,{steps:8});
for(let k=0;k<22;k++){ await page.mouse.move(x+(k%2),R.y+R.height-14+(k%2)); await page.waitForTimeout(70); }
s=await info(); await page.mouse.up(); 
c('list auto-scrolled while hovering the bottom zone', s.top>150, s.top);
c('painted a contiguous run beyond what was on screen', contiguous(s.sel) && s.sel[0]===2 && s.sel.length>visible+3, {first:s.sel[0],last:s.sel[s.sel.length-1],n:s.sel.length,visible});
c('no rows skipped even though scrolling was fast', contiguous(s.sel), s.sel.length);
await page.waitForTimeout(350); const after=await info();
c('map selection model matches the painted rows', after.prefs===after.sel.length, {prefs:after.prefs,sel:after.sel.length});
c('routes on the map follow the selection', await page.evaluate(()=>AitkenAtlas.Active.routes.size===AitkenAtlas.Prefs.selectedSessions.length), await page.evaluate(()=>[AitkenAtlas.Active.routes.size,AitkenAtlas.Prefs.selectedSessions.length]));
const painted=after.sel.length;
// speed: further past the edge scrolls faster
await page.evaluate(()=>{ document.querySelector('#atSessList').scrollTop=0; }); await page.click('#atSessNone');
[x,y]=await rowY(0); await page.mouse.move(x,y); await page.mouse.down(); await page.mouse.move(x,R.y+R.height-8,{steps:6}); const t0=Date.now(); let a=await info(); const z0=a.top; for(let k=0;k<10;k++){ await page.mouse.move(x+(k%2),R.y+R.height-8); await page.waitForTimeout(50);} const zoneTop=(await info()).top-z0; await page.mouse.up();
await page.evaluate(()=>{ document.querySelector('#atSessList').scrollTop=0; }); await page.click('#atSessNone');
[x,y]=await rowY(0); await page.mouse.move(x,y); await page.mouse.down(); await page.mouse.move(x,R.y+R.height+90,{steps:6}); const o0=(await info()).top; for(let k=0;k<10;k++){ await page.mouse.move(x+(k%2),R.y+R.height+90); await page.waitForTimeout(50);} const outTop=(await info()).top-o0; await page.mouse.up();
c('dragging beyond the edge scrolls faster than inside the zone', outTop>zoneTop*1.2 && zoneTop>0, {zoneTop,outTop});
// paint OFF going down, with the pointer outside the list (pointer capture keeps painting)
await page.click('#atSessAll'); await page.evaluate(()=>{ document.querySelector('#atSessList').scrollTop=0; });
[x,y]=await rowY(1); await page.mouse.move(x,y); await page.mouse.down(); await page.mouse.move(x,R.y+R.height+60,{steps:6}); for(let k=0;k<22;k++){ await page.mouse.move(x+(k%2),R.y+R.height+60); await page.waitForTimeout(60);} s=await info(); await page.mouse.up();
const off=[...Array(s.rowsN).keys()].filter(i=>!s.sel.includes(i));
c('pressing a selected row paints OFF (deselects the run)', contiguous(off) && off[0]===1 && off.length>visible, {first:off[0],n:off.length});
c('rows below the cursor stay selected', s.sel.includes(s.rowsN-1), s.sel.length);
// drag upward out of the top zone
await page.click('#atSessNone'); await page.evaluate(()=>{ const l=document.querySelector('#atSessList'); l.scrollTop=l.scrollHeight; });
await page.waitForTimeout(100); const vis=await page.evaluate(()=>{ const l=document.querySelector('#atSessList'),lr=l.getBoundingClientRect(); const rs=[...l.querySelectorAll('.at-row[data-idx]')]; const i=rs.findLastIndex(r=>r.getBoundingClientRect().bottom<=lr.bottom+1); return i; });
[x,y]=await rowY(vis); await page.mouse.move(x,y); await page.mouse.down(); await page.mouse.move(x,R.y+12,{steps:8}); const u0=(await info()).top; for(let k=0;k<22;k++){ await page.mouse.move(x+(k%2),R.y+12); await page.waitForTimeout(60);} s=await info(); await page.mouse.up();
c('drag upward scrolls the list up', s.top<u0-100, {from:u0,to:s.top});
c('upward paint is contiguous and ends at the press row', contiguous(s.sel) && s.sel[s.sel.length-1]===vis && s.sel.length>visible+4, {first:s.sel[0],last:s.sel[s.sel.length-1],vis});
// shift-click range + keyboard
await page.click('#atSessNone'); await page.evaluate(()=>{ document.querySelector('#atSessList').scrollTop=0; }); await page.waitForTimeout(100);
let p=await rowY(2); await page.mouse.click(p[0],p[1]); p=await rowY(6); await page.keyboard.down('Shift'); await page.mouse.click(p[0],p[1]); await page.keyboard.up('Shift'); s=await info();
c('shift-click selects the whole range', JSON.stringify(s.sel)===JSON.stringify([2,3,4,5,6]), s.sel);
await page.focus('#atSessList'); await page.keyboard.press('ArrowDown'); await page.keyboard.press('ArrowDown'); await page.keyboard.press('Space'); s=await info();
c('keyboard: arrows move focus, Space toggles', s.sel.length===6 || s.sel.length===4, s.sel);
await page.keyboard.press('Control+a'); s=await info(); c('Ctrl+A selects all', s.sel.length===70, s.sel.length);
// buttons on a row must NOT toggle it
await page.click('#atSessNone'); const rb=await page.evaluate(()=>{ const b=document.querySelector('#atSessList .at-row[data-idx="0"] [data-act=info]'); const r=b.closest('.at-row'); r.dispatchEvent(new MouseEvent('mouseover',{bubbles:true})); const q=b.getBoundingClientRect(); return [q.left+q.width/2,q.top+q.height/2]; });
await page.mouse.move(rb[0]-30,rb[1]); await page.mouse.move(rb[0],rb[1]); await page.mouse.click(rb[0],rb[1]); await page.waitForTimeout(300); s=await info();
c('row action buttons select the ride (info) but are not painted by the drag logic', s.sel.length===1 && s.sel[0]===0, s.sel);
c('no console errors', errs.length===0, errs.slice(0,4));
process.exit(await finish(c));
