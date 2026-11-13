// Real touch input for the mobile suite. Uses the DevTools protocol so Chrome sees genuine touch events
// (and generates the pointer events, compositor scrolling and fling a finger would) -- not synthetic JS events.
export class Touch{
  constructor(page,cdp){ this.page=page; this.cdp=cdp; this.active=[]; }
  static async on(page){ return new Touch(page, await page.context().newCDPSession(page)); }
  async _send(type,pts){ await this.cdp.send('Input.dispatchTouchEvent',{type,touchPoints:pts.map(p=>({x:p.x,y:p.y,id:p.id||0,radiusX:6,radiusY:6,force:1}))}); }
  sleep(ms){ return this.page.waitForTimeout(ms); }
  async down(...pts){ this.active=pts.map((p,i)=>({...p,id:p.id??i})); await this._send('touchStart',this.active); }
  async move(...pts){ this.active=pts.map((p,i)=>({...p,id:p.id??i})); await this._send('touchMove',this.active); }
  async up(...remaining){ this.active=remaining.map((p,i)=>({...p,id:p.id??i})); await this._send('touchEnd',this.active); }
  async tap(x,y,gap=40){ await this.down({x,y}); await this.sleep(gap); await this.up(); }
  async hold(x,y,ms){ await this.down({x,y}); await this.sleep(ms); await this.up(); }
  /** straight swipe, linear, `ms` total; returns after release. */
  async swipe(x0,y0,x1,y1,{ms=160,steps=10,release=true}={}){
    await this.down({x:x0,y:y0});
    for(let i=1;i<=steps;i++){ await this.sleep(ms/steps); await this.move({x:x0+(x1-x0)*i/steps,y:y0+(y1-y0)*i/steps}); }
    if(release) await this.up();
  }
  /** two fingers centred on (cx,cy), horizontal, going from r0 to r1 apart (half-distance). */
  async pinch(cx,cy,r0,r1,{ms=260,steps=10}={}){
    await this.down({x:cx-r0,y:cy,id:0},{x:cx+r0,y:cy,id:1});
    for(let i=1;i<=steps;i++){ const r=r0+(r1-r0)*i/steps; await this.sleep(ms/steps); await this.move({x:cx-r,y:cy,id:0},{x:cx+r,y:cy,id:1}); }
    await this.up();
  }
}
/** page-space centre/edges of a Review segment, from the live view mapping */
export const segPoint=(page,i=0,frac=.5)=>page.evaluate(([i,frac])=>{
  const D=AitkenWB.debug, S=D.S, r=document.getElementById('waveCanvasWrap').getBoundingClientRect();
  const seg=S.segments.slice().sort((a,b)=>a.startSec-b.startSec)[i]; D.drawWave();
  const x=S._mapX(seg.startSec+seg.durSec*frac), y=S._plotH/2+14;
  return {x:r.left+x, y:r.top+y, id:seg.id, x0:r.left+S._mapX(seg.startSec), x1:r.left+S._mapX(seg.startSec+seg.durSec), w:S._mapX(seg.startSec+seg.durSec)-S._mapX(seg.startSec)};
},[i,frac]);
