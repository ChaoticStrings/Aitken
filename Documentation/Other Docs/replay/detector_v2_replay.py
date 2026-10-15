"""Detector v2 forensic replay (tickets 28-29). Independent Python re-implementation of the pipeline (calibrator w/ motion gating, amplitude detector,
distance-based rough-patch tracker). Written from the design, not transliterated from the Kotlin."""
import csv, math, sys
from collections import deque
import os
REPO=os.path.abspath(os.path.join(os.path.dirname(__file__),'..','..','..'))
FX=os.path.join(REPO,'workbench','test','fixtures','session_trimmed_90s')+'/'
G=9.81
def run(suppress=True, peak_factor=3.0, rough_factor=1.5, floor=0.5, window_ms=1000, patch_m=2.5, cal_ms=10000, end_quiet=500, min_seg=30):
    rows=list(csv.DictReader(open(FX+'sensor.csv')))
    ns=[int(r['timestamp_sensor_ns']) for r in rows]
    acc=[(float(r['accel_x_ms2']),float(r['accel_y_ms2']),float(r['accel_z_ms2'])) for r in rows]
    gz=[(float(r['gyro_z_rads']) if r['gyro_z_rads'] else None) for r in rows]
    gps=[(int(r['timestamp_sensor_ns']), float(r['speed_mps']) if r['speed_mps'] else None) for r in csv.DictReader(open(FX+'gps.csv'))]
    # vertical via the same gravity filter (alpha .9)
    g=None; vert=[]
    for (ax,ay,az) in acc:
        g=[ax,ay,az] if g is None else [0.9*g[i]+0.1*v for i,v in enumerate((ax,ay,az))]
        n=math.sqrt(sum(x*x for x in g)); vert.append((ax*g[0]+ay*g[1]+az*g[2])/n if n>1e-6 else 0.0)
    dev=[abs(v-G) for v in vert]
    turning=[(x is not None and abs(x)>=1.0) for x in gz]
    # ---- pass 1: gps arrival -> sensor-timeline stamps, motion intervals, odometer speed events
    events=[]  # (sample_index_after_which_fix_arrives, speed)
    gi=0; fixes=[]  # (arrival_ns = last sensor ts at arrival, speed)
    last_ts=None
    for i,t in enumerate(ns):
        while gi<len(gps) and gps[gi][0]<=t:
            if last_ts is not None: fixes.append((i,last_ts,gps[gi][1]))   # arrives before sample i
            gi+=1
        last_ts=t
    # motion intervals (|dv|/dt>=1, dt<=1.5s)
    mot=[]; prev=None
    for (i,at,sp) in fixes:
        if sp is not None and prev is not None and prev[1] is not None:
            dt=(at-prev[0])/1e6
            if 0<dt<=1500 and abs(sp-prev[1])/(dt/1000)>=1.0: mot.append((prev[0],at,i))
        prev=(at,sp)
    # ---- calibration (buffer + commit lag 2000ms, settle 500ms)
    SET=500e6; LAG=2000e6
    dirty=[]  # list of (from,to,known_from_sample_index)
    def is_dirty(t,upto):
        for (a,b,k) in dirty:
            if k<=upto and a<=t<=b: return True
        return False
    for i,t in enumerate(ns):
        if turning[i]: dirty.append((t-SET,t+SET,i))
    for (f,to,i) in mot: dirty.append((f-SET,to+SET,i))
    committed=0; clean_ns=0; prev_clean=None; pp=p=None; peaks=[]; sl=deque(); ssum=0.0; maxr=0.0; filled=False; first=ns[0]
    done_at=None
    for i,t in enumerate(ns):
        # judge samples older than LAG using knowledge available at sample i
        while committed<=i and t-ns[committed]>=LAG:
            c=committed; committed+=1; tc=ns[c]
            if is_dirty(tc,i):
                pp=p=None; prev_clean=None; sl.clear(); ssum=0.0; sl_first=None; continue
            if prev_clean is not None: clean_ns+=tc-prev_clean
            prev_clean=tc
            d=dev[c]
            if pp is not None and p is not None and p>pp and p>=d: peaks.append(p)
            pp,p=p,d
            if not sl: sl_first=tc
            sl.append((tc,d)); ssum+=d
            while sl and sl[0][0]<=tc-window_ms*1e6: ssum-=sl.popleft()[1]
            if tc-sl_first>=window_ms*1e6:
                filled=True; maxr=max(maxr,ssum/len(sl))
        if clean_ns/1e6>=cal_ms or (t-first)/1e6>=cal_ms*3:
            done_at=i; break
    mean_peak=sum(peaks)/len(peaks) if peaks else 0.0
    M=max(mean_peak*peak_factor,floor); D=max((maxr if filled else 0)*rough_factor,floor)
    cal=dict(M=M,D=D,meanPeak=mean_peak,peaks=len(peaks),maxRough=maxr,cleanMs=clean_ns//1_000_000,elapsedMs=(ns[done_at]-first)//1_000_000)
    # ---- detection from sample done_at+1
    # odometer: speed fixes stamped at arrival ts; held ≤10 s
    fx=sorted([(at,sp) for (_,at,sp) in fixes]); fxi=0; cur=None; odo=0.0; last=None; odo_arr=[]
    for i,t in enumerate(ns):
        while fxi<len(fx) and fx[fxi][0]<=last if last is not None else False: pass
        odo_arr.append(0.0)
    # simpler: sequential
    cur=None; fxi=0; odo=0.0; last=None; odo_arr=[]
    arrivals={}
    for (i,at,sp) in fixes: arrivals.setdefault(i,[]).append((at,sp))
    for i,t in enumerate(ns):
        # fixes arriving before sample i were registered after sample i-1 was processed
        for (at,sp) in arrivals.get(i,[]): cur=(at,sp)
        if last is not None and cur is not None and cur[1] is not None and t>last:
            end=min(t,cur[0]+10_000_000_000)
            if end>last: odo+=cur[1]*(end-last)/1e9
        last=t; odo_arr.append(odo)
    # sliding mean over ALL samples (pipeline-level), 1s window
    sl2=deque(); s2=0.0; rough=[]; full=[]; f0=ns[0]
    for i,t in enumerate(ns):
        sl2.append((t,dev[i])); s2+=dev[i]
        while sl2 and sl2[0][0]<=t-window_ms*1e6: s2-=sl2.popleft()[1]
        rough.append(s2/len(sl2)); full.append((t-f0)/1e6>=window_ms)
    # detector + tracker
    segs=[]; patches=[]
    phase=False; st=ls=0; pk=0.0; ssq=0.0; n=0; ssq_sig=0.0; n_sig=0
    active=False; reg=False
    def fin():
        nonlocal active,reg
        if active and reg:
            patches.append(dict(start=cs,end=ce_ns,len=cend-cstart,segs=ccount,peak=cpeak,maxD=cmaxd,trig=ctrig))
        active=False; reg=False
    lastb=None
    cs=ce_ns=0; cstart=cend=0.0; ccount=0; clast=None; cpeak=cmaxd=0.0; ctrig=None
    for i in range(done_at+1,len(ns)):
        t=ns[i]; d=dev[i]; breach=False; closed=None
        if not phase:
            if d>=M and not turning[i]:
                phase=True; st=ls=t; pk=d; ssq=d*d; n=1; ssq_sig=ssq; n_sig=1; breach=True
        else:
            ssq+=d*d; n+=1
            if d>=M:
                ls=t; pk=max(pk,d); ssq_sig=ssq; n_sig=n; breach=True
            elif (t-ls)/1e6>=end_quiet:
                if (ls-st)/1e6>=min_seg: closed=(st,ls-st,pk,math.sqrt(ssq_sig/n_sig))
                phase=False
        if closed: segs.append(closed)
        openstart=st if phase else None
        r = rough[i] if full[i] else None
        dact = r is not None and r>=D
        if suppress:
            if breach: lastb=t
            if lastb is not None and (t-lastb)/1e6<=window_ms: dact=False
        isrough=breach or dact
        held = openstart is not None and openstart==clast
        if active and not held and odo_arr[i]-cend>patch_m: fin()
        if isrough:
            if not active:
                active=True; reg=False; cs=t; cstart=odo_arr[i]; ccount=0; clast=None; cpeak=0.0; cmaxd=0.0
            ce_ns=t; cend=odo_arr[i]; cpeak=max(cpeak,d)
            if r is not None: cmaxd=max(cmaxd,r)
            if breach and openstart is not None and openstart!=clast: ccount+=1; clast=openstart
            if not reg:
                length=cend-cstart
                if ccount>=2: reg=True; ctrig='CLUSTER'
                elif length>=patch_m: reg=True; ctrig='ROUGHNESS' if ccount==0 else 'LENGTH'
    if phase and (ls-st)/1e6>=min_seg: segs.append((st,ls-st,pk,math.sqrt(ssq_sig/n_sig)))
    fin()
    return cal,[(round((s[0]-ns[0])/1e9,2),round(s[1]/1e6),round(s[2],2),round(s[3],2)) for s in segs],[(round((p['start']-ns[0])/1e9,2),round((p['end']-ns[0])/1e9,2),round(p['len'],1),p['segs'],round(p['peak'],1),round(p['maxD'],2),p['trig']) for p in patches]
if __name__=='__main__':
    pf=float(sys.argv[1]) if len(sys.argv)>1 else 3.0   # Detection sensitivity (stdFactor)
    cal,segs,pat=run(peak_factor=pf)
    print('CAL M=%.4f D=%.4f meanPeak=%.4f peaks=%d maxRough=%.4f clean=%dms elapsed=%dms'%(cal['M'],cal['D'],cal['meanPeak'],cal['peaks'],cal['maxRough'],cal['cleanMs'],cal['elapsedMs']))
    print('SEGMENTS',len(segs))
    for s in segs: print('  t=%.2fs dur=%dms peak=%.2f rms=%.2f'%s)
    print('PATCHES',len(pat))
    for p in pat: print('  t=%.2f..%.2fs len=%.1fm segs=%d peak=%.1f maxD=%.2f %s'%p)
