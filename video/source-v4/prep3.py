import subprocess,json,os,struct,math,sys
HERE=os.path.dirname(os.path.abspath(__file__))
FF=sys.argv[1]
D=json.load(open(HERE+'/deck.json'))
GAP=0.28; LEAD=0.6; TAIL=1.0; TARGET=float(sys.argv[2]) if len(sys.argv)>2 else 121
def run(a): return subprocess.run([FF,'-v','error','-y']+a,capture_output=True,text=True)
def dur(f):
    r=subprocess.run([FF,'-i',f],capture_output=True,text=True).stderr
    h,m,s=r.split('Duration: ')[1].split(',')[0].split(':'); return int(h)*3600+int(m)*60+float(s)
ids=list(D['beats'])
raw={i:dur(f'{HERE}/a/{i}.mp3') for i in ids}
rawsum=sum(raw.values()); fixed=LEAD+GAP*(len(ids)-1)+TAIL
TEMPO=max(1.0,min(1.19,rawsum/(TARGET-fixed)))
print('raw speech',round(rawsum,1),'tempo',round(TEMPO,3))
beats={}; t=LEAD
for i in ids:
    run(['-i',f'{HERE}/a/{i}.mp3','-af',f'atempo={TEMPO:.4f}',f'{HERE}/t2/{i}.wav'])
    d=dur(f'{HERE}/t2/{i}.wav'); beats[i]={'id':i,'text':D['beats'][i],'t0':round(t,3),'t1':round(t+d,3)}; t+=d+GAP
total=t-GAP+TAIL
cmd=[]
for i in ids: cmd+=['-i',f'{HERE}/t2/{i}.wav']
fc=''.join(f'[{k}:a]adelay={int(beats[i]["t0"]*1000)}|{int(beats[i]["t0"]*1000)},apad=whole_dur={total:.2f}[d{k}];' for k,i in enumerate(ids))
fc+=''.join(f'[d{k}]' for k in range(len(ids)))+f'amix=inputs={len(ids)}:normalize=0,alimiter=limit=0.95,atrim=0:{total:.2f}[a]'
run(cmd+['-filter_complex',fc,'-map','[a]','-ar','44100',HERE+'/master.wav'])
slides=[]
for k,s in enumerate(D['slides']):
    b=beats[s['beat']]; start=0 if k==0 else b['t0']-0.35
    slides.append({'id':s['id'],'start':round(max(0,start),3),'beat':s['beat']})
open(HERE+'/timeline.js','w').write('window.TL='+json.dumps({'beats':beats,'slides':slides,'total':round(total,2)})+';\nwindow.DECK='+json.dumps(D)+';')
print('total',round(total,1),'s')
