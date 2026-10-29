# Narration + soft transition whooshes + a short closing chime (very quiet, so the voice stays clear).
import json,subprocess,sys,os
HERE=os.path.dirname(os.path.abspath(__file__));FF=sys.argv[1]
TL=json.loads(open(HERE+'/timeline.js').read().split('=',1)[1].split(';\n')[0])
total=TL['total']
subprocess.run([FF,'-v','error','-y','-f','lavfi','-i','anoisesrc=d=1.0:c=pink:r=44100:a=0.7','-af','highpass=f=350,lowpass=f=7000,afade=t=in:d=0.5,afade=t=out:st=0.5:d=0.5,volume=0.22',HERE+'/whoosh.wav'])
starts=[s['start']-0.45 for s in TL['slides'][1:]]
close=TL['slides'][-1]['start']
cmd=[FF,'-v','error','-y','-i',HERE+'/master.wav','-i',HERE+'/whoosh.wav']
for f in (880,1318.5,1760): cmd+=['-f','lavfi','-t','1.4','-i',f'sine=f={f}:r=44100']
n=len(starts);fc=f'[1:a]asplit={n}'+''.join(f'[w{i}]' for i in range(n))+';'
for i,st in enumerate(starts):
    ms=int(max(0,st)*1000);fc+=f'[w{i}]adelay={ms}|{ms}[x{i}];'
for k in range(3):
    ms=int((close+0.15+k*0.16)*1000);fc+=f'[{2+k}:a]afade=t=in:d=0.03,afade=t=out:st=0.2:d=1.1,volume=0.05,adelay={ms}|{ms}[c{k}];'
ins='[0:a]'+''.join(f'[x{i}]' for i in range(n))+''.join(f'[c{k}]' for k in range(3))
fc+=f'{ins}amix=inputs={1+n+3}:normalize=0,alimiter=limit=0.95,atrim=0:{total:.2f}[a]'
cmd+=['-filter_complex',fc,'-map','[a]','-ar','44100',HERE+'/mix.wav']
r=subprocess.run(cmd,capture_output=True,text=True);print(r.stderr[-400:] or 'mix ok')
