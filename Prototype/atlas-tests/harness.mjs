// Shared Playwright harness: local http server + CDN/tiles interception, real Chrome.
import http from 'http'; import fs from 'fs'; import path from 'path'; import zlib from 'zlib'; import { fileURLToPath } from 'url'; import { chromium } from 'playwright-core';
export const ROOT=path.dirname(fileURLToPath(import.meta.url)), NM=ROOT+'/node_modules', PAGE_ROOT=path.join(ROOT,'..');
/** CHROME_PATH=/path/to/chrome, otherwise the system Chrome ("channel: chrome") is used. */
export const CHROME=process.env.CHROME_PATH||'';
fs.mkdirSync('/tmp/atlas-shots',{recursive:true});

// ---- stub OSM-ish tiles generated in memory (no image library needed) ----
const crcT=(()=>{ const t=[]; for(let n=0;n<256;n++){ let c=n; for(let k=0;k<8;k++) c=c&1?0xEDB88320^(c>>>1):c>>>1; t[n]=c>>>0; } return t; })();
const crc=(b)=>{ let c=0xFFFFFFFF; for(const x of b) c=crcT[(c^x)&255]^(c>>>8); return (c^0xFFFFFFFF)>>>0; };
const chunk=(type,data)=>{ const l=Buffer.alloc(4); l.writeUInt32BE(data.length); const td=Buffer.concat([Buffer.from(type),data]); const c=Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l,td,c]); };
function makeTile(seed){ const W=256, px=Buffer.alloc(W*W*3); let s=seed*2654435761>>>0; const rnd=()=>((s=Math.imul(s^(s>>>15),2246822507)>>>0,s=Math.imul(s^(s>>>13),3266489909)>>>0,(s^(s>>>16))>>>0)/4294967296);
  const set=(x,y,c)=>{ if(x<0||y<0||x>=W||y>=W) return; const i=(y*W+x)*3; px[i]=c[0]; px[i+1]=c[1]; px[i+2]=c[2]; };
  for(let i=0;i<W*W;i++){ px[i*3]=242; px[i*3+1]=239; px[i*3+2]=233; }
  const rect=(x,y,w,h,c)=>{ for(let j=0;j<h;j++) for(let i=0;i<w;i++) set(x+i,y+j,c); };
  for(let k=0;k<3;k++) rect(rnd()*200|0,rnd()*200|0,30+rnd()*60|0,20+rnd()*50|0,[200,222,176]); if(seed%4===0) rect(rnd()*150|0,rnd()*150|0,256,256,[170,211,223]);
  for(let k=0;k<6;k++){ const horiz=k%2, p=rnd()*256|0, w=[3,5,8][rnd()*3|0], col=w===8?[252,214,164]:[255,255,255]; for(let t=0;t<256;t++){ for(let d=-1;d<=w+1;d++){ const c=(d<0||d>w)?[190,190,190]:col; horiz?set(t,p+d,c):set(p+d,t,c); } } }
  const raw=Buffer.alloc((W*3+1)*W); for(let y=0;y<W;y++){ raw[y*(W*3+1)]=0; px.copy(raw,y*(W*3+1)+1,y*W*3,(y+1)*W*3); }
  const ihdr=Buffer.alloc(13); ihdr.writeUInt32BE(W,0); ihdr.writeUInt32BE(W,4); ihdr[8]=8; ihdr[9]=2;
  return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ihdr),chunk('IDAT',zlib.deflateSync(raw)),chunk('IEND',Buffer.alloc(0))]); }
const TILES=Array.from({length:16},(_,i)=>makeTile(i+1));
const LIVE=[];
/** close every browser this process launched (otherwise orphaned Chrome processes outlive the test and hold its stdout open) */
export async function shutdown(){ for(const c of LIVE.splice(0)){ try{ const b=c.browser(); await c.close(); if(b) await b.close(); }catch(e){} } }
export function serve(port=8765){ const srv=http.createServer((q,r)=>{ const f=PAGE_ROOT+decodeURIComponent(q.url.split('?')[0]); if(fs.existsSync(f)&&fs.statSync(f).isFile()){ r.writeHead(200,{'Content-Type':f.endsWith('.html')?'text/html':'application/octet-stream'}); fs.createReadStream(f).pipe(r);} else { r.writeHead(404); r.end(); } }); return new Promise(res=>srv.listen(port,()=>res(srv))); }
export async function launch({tilesLog,overpass,userData}={}){
  const lo={headless:true,args:['--no-sandbox'],...(CHROME?{executablePath:CHROME}:{channel:'chrome'})};
  const ctx = userData ? await chromium.launchPersistentContext(userData,{...lo,viewport:{width:1500,height:900},acceptDownloads:true})
                       : await (await chromium.launch(lo)).newContext({viewport:{width:1500,height:900},acceptDownloads:true});
  await ctx.route('**/*',async(route)=>{
    const u=route.request().url();
    const ok=(f,type)=>route.fulfill({status:200,contentType:type,body:fs.readFileSync(f)});
    if(u.includes('leaflet.min.js')) return ok(NM+'/leaflet/dist/leaflet.js','application/javascript');
    if(u.includes('leaflet.min.css')) return ok(NM+'/leaflet/dist/leaflet.css','text/css');
    if(u.includes('jszip.min.js')) return ok(NM+'/jszip/dist/jszip.min.js','application/javascript');
    if(u.includes('sql.js@1.12.0/dist/sql-wasm.js')) return ok(NM+'/sql.js/dist/sql-wasm.js','application/javascript');
    if(u.includes('sql.js@1.12.0/dist/sql-wasm.wasm')) return ok(NM+'/sql.js/dist/sql-wasm.wasm','application/wasm');
    if(/leaflet.*\/images\//.test(u)) return route.fulfill({status:200,contentType:'image/png',body:Buffer.alloc(0)});
    if(u.includes('fonts.googleapis.com')||u.includes('fonts.gstatic.com')) return route.fulfill({status:200,contentType:'text/css',body:''});
    const m=/tile\.openstreetmap\.org\/(\d+)\/(\d+)\/(\d+)\.png|basemaps\.cartocdn\.com\/.*\/(\d+)\/(\d+)\/(\d+)/.exec(u);
    if(m){ if(tilesLog) tilesLog.push(u); const z=+(m[1]||m[4]),x=+(m[2]||m[5]),y=+(m[3]||m[6]); return route.fulfill({status:200,contentType:'image/png',body:TILES[(x*31+y*17+z)%16]}); }
    if(u.includes('overpass')&&overpass) return overpass(route);
    if(u.startsWith('http://localhost')) return route.continue();
    return route.abort();
  });
  LIVE.push(ctx);
  return ctx;
}
