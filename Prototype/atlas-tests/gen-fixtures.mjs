// Generates test fixtures for Atlas. Everything "city" below is FICTIONAL test data.
import fs from 'fs'; import path from 'path'; import JSZip from 'jszip'; import { fileURLToPath } from 'url';
const HERE=path.dirname(fileURLToPath(import.meta.url));
const REPO=path.join(HERE,'../../workbench/test/fixtures');      // the real trimmed V1.1 ride that is already in the repo
const OUT=path.join(HERE,'zips'); fs.rmSync(OUT,{recursive:true,force:true}); fs.mkdirSync(OUT,{recursive:true});
const real=(n)=>fs.readFileSync(path.join(REPO,'session_trimmed_90s',n),'utf8');
const G=9.81;

async function zipOf(files, dir){ const z=new JSZip(); for(const [n,c] of Object.entries(files)) z.file(dir?dir+'/'+n:n, c); return z.generateAsync({type:'nodebuffer',compression:'DEFLATE'}); }
const save=async(name,files,dir)=>{ fs.writeFileSync(path.join(OUT,name), await zipOf(files,dir)); };

// ---------- 1. derived from the REAL session ----------
const sensor=real('sensor.csv'), gps=real('gps.csv'), seg=real('segments.csv'), lab=real('labels.csv'), cfg=real('config.json');
// raw magnitude per segment, as V1.0 recorded it
const sl=sensor.trim().split('\n'); const T=[],V=[]; for(let i=1;i<sl.length;i++){ const r=sl[i].split(','); T.push(+r[1]); V.push(+r[8]); }
const segRows=seg.trim().split('\n'); const v10=[segRows[0]];
for(let i=1;i<segRows.length;i++){ const r=segRows[i].split(','); const t=+r[1], d=+r[2]; let mx=0; for(let k=0;k<T.length;k++) if(T[k]>=t&&T[k]<=t+d) mx=Math.max(mx,Math.abs(V[k])); r[3]=mx.toFixed(3); v10.push(r.join(',')); }
const seg10=v10.join('\n')+'\n';
console.log('real V1.1 peaks :', segRows.slice(1).map(r=>r.split(',')[3]).join(' '));
console.log('as-V1.0 raw peak:', v10.slice(1).map(r=>r.split(',')[3]).join(' '));

// V1.1 = the real bundle, flat
await save('session_20260913_095700_v11.zip',{'sensor.csv':sensor,'gps.csv':gps,'segments.csv':seg,'labels.csv':lab,'config.json':cfg});
// V1.0 = no config, raw-magnitude peaks, inside a session_ folder
await save('session_20260905_163505_v10.zip',{'sensor.csv':sensor,'gps.csv':gps,'segments.csv':seg10,'labels.csv':lab},'session_20260905_163505');
// V1.0 without sensor.csv (cannot be probed)
await save('session_20260905_171000_v10_nosensor.zip',{'gps.csv':gps.replace(/\n(\d+,)(\d+)/g,(m,a,b)=>m),'segments.csv':seg10,'labels.csv':lab},'session_20260905_171000');
// V2: config w/ detectorVersion 2 + patches.csv
const cfg2=JSON.stringify({schemaVersion:1,detectorVersion:2,calibrationDurationMs:10000,stdFactor:3.0,floorStd:0.05,rollingWindowMs:500,roughSensitivity:1.0,roughPatchDistanceM:40,calibrationSource:'fresh',calibrationPresetName:null,calibratedMThreshold:6.2,calibratedDThreshold:2.1,accelMaxRangeMs2:78.4,accelName:'Test Accelerometer'},null,2);
const patches='schema_version,start_ns,end_ns,length_m,segment_count,peak_m,max_d,trigger,speed_mps,epoch_ms\n1,95740000000000,95746000000000,48.5,3,11.2,3.4,D,7.1,1788597460000\n1,95770000000000,95776000000000,36.0,2,9.4,2.9,M,6.0,1788597490000\n';
await save('session_20261016_081500_v2.zip',{'sensor.csv':sensor,'gps.csv':gps,'segments.csv':seg,'labels.csv':lab,'config.json':cfg2,'patches.csv':patches},'session_20261016_081500');
// Workbench clean bundle of the V1.0 ride: no config, same GPS => should REPLACE the plain V1.0 import
await save('session_20260905_163505_clean.zip',{'sensor.csv':sensor,'gps.csv':gps,'segments.csv':seg10,'labels.csv':lab},'session_20260905_163505_clean');
await save('session_20260913_095700_clean_of_v11.zip',{'sensor.csv':sensor,'gps.csv':gps,'segments.csv':seg,'labels.csv':lab},'session_20260913_095700_clean');
// two session folders in one zip
const z2=new JSZip(); for(const [d,s] of [['session_20260920_100000',seg],['session_20260921_100000',seg]]){ z2.file(d+'/gps.csv',gps.replace(/18\.59/g,d.endsWith('0000')&&d.includes('0920')?'18.60':'18.64').replace(/73\.7/g,'73.8')); z2.file(d+'/segments.csv',s); z2.file(d+'/labels.csv',lab); z2.file(d+'/config.json',cfg); }
fs.writeFileSync(path.join(OUT,'two_sessions.zip'), await z2.generateAsync({type:'nodebuffer'}));
// BOM + CRLF, future schema, no gps
const bom=(s)=>'\uFEFF'+s.replace(/\n/g,'\r\n');
await save('session_20260922_090000_bomcrlf.zip',{'gps.csv':bom(gps.replace(/18\.59/g,'18.61')),'segments.csv':bom(seg),'labels.csv':bom(lab),'config.json':'\uFEFF'+cfg},'session_20260922_090000');
await save('session_20260923_090000_future.zip',{'gps.csv':gps.replace(/^1,/gm,'3,').replace(/^3,timestamp/,'schema_version,timestamp').replace(/18\.59/g,'18.62').replace('schema_version,timestamp_sensor_ns,latitude,longitude,speed_mps,accuracy_m','schema_version,timestamp_sensor_ns,latitude,longitude,speed_mps,accuracy_m,hdop').replace(/\n3,/g,'\n3,').replace(/(\n3,[^\n]*)/g,'$1,1.2'),'segments.csv':seg.replace(/^1,/gm,'3,').replace('3,start_ns','schema_version,start_ns'),'config.json':JSON.stringify({schemaVersion:3,detectorVersion:5})},'session_20260923_090000');
await save('session_20260924_090000_nogps.zip',{'segments.csv':seg,'labels.csv':lab,'config.json':cfg},'session_20260924_090000');
await save('not_a_session.zip',{'readme.txt':'hello'});

// ---------- 2. fictional city rides ----------
const WAY=[[18.5590,73.7868],[18.5575,73.8085],[18.5535,73.8247],[18.5308,73.8475],[18.5167,73.8409]]; // Baner -> Aundh -> Univ. Circle -> Shivajinagar -> Deccan (approximate, straight lines)
const WAY2=[[18.5122,73.7845],[18.5230,73.7990],[18.5330,73.8160],[18.5308,73.8475]];                  // Pashan-ish -> Shivajinagar
function rng(seed){ let s=seed>>>0; return ()=>((s=Math.imul(s^(s>>>15),2246822507)>>>0, s=Math.imul(s^(s>>>13),3266489909)>>>0, ((s^(s>>>16))>>>0)/4294967296)); }
function pathAt(route,d){ let acc=0; for(let i=1;i<route.length;i++){ const L=Math.hypot((route[i][0]-route[i-1][0])*110574,(route[i][1]-route[i-1][1])*111320*Math.cos(route[i][0]*Math.PI/180)); if(acc+L>=d||i===route.length-1){ const f=Math.min(1,(d-acc)/L); return [route[i-1][0]+(route[i][0]-route[i-1][0])*f,route[i-1][1]+(route[i][1]-route[i-1][1])*f]; } acc+=L; } }
function routeLen(route){ let a=0; for(let i=1;i<route.length;i++) a+=Math.hypot((route[i][0]-route[i-1][0])*110574,(route[i][1]-route[i-1][1])*111320*Math.cos(route[i][0]*Math.PI/180)); return a; }
async function ride(name,{route,reverse,gen,seed,startMs,speed=8.5,eventsPer100=0.9}){
  const R=rng(seed), r=reverse?route.slice().reverse():route, L=routeLen(r), n=Math.floor(L/speed), t0=Math.round(40e9+seed*1e12);
  let g='schema_version,timestamp_sensor_ns,latitude,longitude,speed_mps,accuracy_m\n';
  for(let i=0;i<n;i++){ const p=pathAt(r,i*speed); g+=`1,${t0+i*1e9},${(p[0]+(R()-.5)*2.5e-5).toFixed(6)},${(p[1]+(R()-.5)*2.5e-5).toFixed(6)},${(speed+(R()-.5)*2).toFixed(3)},${(6+R()*8).toFixed(3)}\n`; }
  const nEv=Math.round(L/100*eventsPer100); const segs=[]; for(let k=0;k<nEv;k++){ const tt=t0+Math.round((5+R()*(n-10))*1e9); const dev=Math.exp(Math.log(9)+R()*Math.log(8)); segs.push({t:tt,dur:Math.round((120+R()*500)*1e6),dev}); }
  segs.sort((a,b)=>a.t-b.t);
  const raw=gen==='v1.0'; let s='schema_version,start_ns,duration_ns,peak_m,rms_m,speed_mps,epoch_ms\n'; segs.forEach(e=>{ s+=`1,${e.t},${e.dur},${(raw?e.dev+G:e.dev).toFixed(3)},${(e.dev*.35).toFixed(3)},${speed.toFixed(3)},${startMs+Math.round((e.t-t0)/1e6)}\n`; });
  let lb='schema_version,timestamp_sensor_ns,kind,segment_start_ns,label,tap_offset_ms,epoch_ms\n'; const labs=['Pothole','Pothole','Pothole','Bump','Speedbreaker'];
  segs.forEach((e,i)=>{ if(R()<.38){ const off=Math.round(300+R()*1200); lb+=`1,${e.t+e.dur+off*1e6},POINT,${e.t},${labs[Math.floor(R()*labs.length)]},${off},${startMs+Math.round((e.t-t0)/1e6)}\n`; } });
  lb+=`1,${t0+Math.round(n*.3e9)},POINT,,Pothole,0,${startMs}\n`;     // unmatched tap
  const files={'gps.csv':g,'segments.csv':s,'labels.csv':lb};
  if(gen!=='v1.0') files['config.json']=gen==='v2'?cfg2:cfg;
  if(gen==='v2'){ const a=t0+Math.round(n*.4e9); files['patches.csv']=`schema_version,start_ns,end_ns,length_m,segment_count,peak_m,max_d,trigger,speed_mps,epoch_ms\n1,${a},${a+9e9},${(9*speed).toFixed(1)},4,${(14+R()*20).toFixed(2)},3.1,D,${speed},${startMs}\n`; }
  else { const a=t0+Math.round(n*.55e9); lb2(files,a,speed,startMs); }
  const d=new Date(startMs); const p2=(x)=>String(x).padStart(2,'0'); const folder=`session_${d.getFullYear()}${p2(d.getMonth()+1)}${p2(d.getDate())}_${p2(d.getHours())}${p2(d.getMinutes())}${p2(d.getSeconds())}`;
  await save(`city_${name}.zip`,files,folder);
}
function lb2(files,a,speed,startMs){ files['labels.csv']+=`1,${a},RANGE_START,,Rough stretch,0,${startMs}\n1,${a+8e9},RANGE_END,,Rough stretch,0,${startMs}\n`; }
const D=(m,d,h,mi)=>new Date(2026,m-1,d,h,mi,0).getTime();
await ride('01',{route:WAY,reverse:false,gen:'v1.0',seed:11,startMs:D(9,5,16,35)});
await ride('02',{route:WAY,reverse:true, gen:'v1.0',seed:12,startMs:D(9,5,18,10)});
await ride('03',{route:WAY,reverse:false,gen:'v1.1',seed:13,startMs:D(9,14,9,2)});
await ride('04',{route:WAY,reverse:true, gen:'v1.1',seed:14,startMs:D(9,14,18,40)});
await ride('05',{route:WAY2,reverse:false,gen:'v1.1',seed:15,startMs:D(9,29,13,15)});
await ride('06',{route:WAY2,reverse:true, gen:'v2',seed:16,startMs:D(10,16,8,15)});
await ride('07',{route:WAY,reverse:false,gen:'v2',seed:17,startMs:D(10,17,9,30)});
await ride('08',{route:WAY,reverse:true, gen:'v2',seed:18,startMs:D(10,18,19,5)});

// ---------- 3. contractor datasets (FICTIONAL names; geometry follows the fictional route) ----------
const seg_=(a,b,n=12)=>Array.from({length:n+1},(_,i)=>[a[0]+(b[0]-a[0])*i/n,a[1]+(b[1]-a[1])*i/n]);
const roads=[
  {name:'Test Baner Road',ref:'TR-1',contractor:'Fictional Infra Pvt Ltd',pts:seg_(WAY[0],WAY[1])},
  {name:'Test Aundh Link',ref:'TR-2',contractor:'Fictional Infra Pvt Ltd',pts:seg_(WAY[1],WAY[2])},
  {name:'Test University Road',ref:'TR-3',contractor:'Example Roadworks LLP',pts:seg_(WAY[2],WAY[3])},
  {name:'Test Shivajinagar Lane',ref:'',contractor:'',pts:seg_(WAY[3],WAY[4])},
  {name:'Test Pashan Road',ref:'TR-5',contractor:'Example Roadworks LLP',pts:seg_(WAY2[0],WAY2[1])},
  {name:'Test Ambiguous Street',ref:'',contractor:'Mystery Builders',pts:seg_(WAY2[1],WAY2[2])}
];
fs.writeFileSync(path.join(OUT,'contractors_geo.geojson'),JSON.stringify({type:'FeatureCollection',features:roads.map(r=>({type:'Feature',properties:{road_name:r.name,ref:r.ref,contractor_name:r.contractor,work:'Resurfacing',year:'2025'},geometry:{type:'LineString',coordinates:r.pts.map(p=>[p[1],p[0]])}}))}));
fs.writeFileSync(path.join(OUT,'contractors_wkt.csv'),'Road Name,Contractor,WKT\n'+roads.map(r=>`"${r.name}","${r.contractor}","LINESTRING(${r.pts.map(p=>p[1]+' '+p[0]).join(', ')})"`).join('\n')+'\n');
fs.writeFileSync(path.join(OUT,'contractors_nogeo.csv'),'Name of Road,Agency,Cost\n'+roads.map(r=>`${r.name.toUpperCase()},${r.contractor||'N/A'},${100000+r.name.length*1000}`).join('\n')+'\n');
fs.writeFileSync(path.join(OUT,'contractors_pack.json'),JSON.stringify({format:'aitken-datasource',version:1,source:{id:'sample',name:'Sample pack (FICTIONAL)',license:'test data',sample:true},roads:roads.map((r,i)=>({id:'p'+i,name:r.name,ref:r.ref,contractor:r.contractor,geometry:{type:'LineString',coordinates:r.pts.map(p=>[p[1],p[0]])},props:{work:'Resurfacing'}}))}));
// MoRTH-style point records (chainage located) to prove point geometry works
fs.writeFileSync(path.join(OUT,'points.geojson'),JSON.stringify({type:'FeatureCollection',features:[{type:'Feature',properties:{name:'NH-48 km 12',contractor:'Example Highways Ltd'},geometry:{type:'Point',coordinates:[73.80,18.56]}}]}));
console.log(fs.readdirSync(OUT).join('\n'));
