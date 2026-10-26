import subprocess,json,os,struct,math,sys
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from beats import BEATS
HERE=os.path.dirname(os.path.abspath(__file__))
FF=HERE+'/../venv/lib/python3.13/site-packages/imageio_ffmpeg/binaries/ffmpeg-macos-aarch64-v7.1'
TEMPO=1.1; GAP=0.2; LEAD=0.5
def run(a): return subprocess.run([FF,'-v','error','-y']+a,capture_output=True,text=True)
def dur(f):
    r=subprocess.run([FF,'-i',f],capture_output=True,text=True).stderr
    h,m,s=r.split('Duration: ')[1].split(',')[0].split(':'); return int(h)*3600+int(m)*60+float(s)
os.makedirs(HERE+'/t2',exist_ok=True)
ins=[];fl=[];labels=[]
t=LEAD;beats=[]
for i,(bid,text) in enumerate(BEATS):
    run(['-i',f'{HERE}/t/{bid}.wav','-af',f'atempo={TEMPO}',f'{HERE}/t2/{bid}.wav'])
    d=dur(f'{HERE}/t2/{bid}.wav'); beats.append({'id':bid,'text':text,'t0':round(t,3),'t1':round(t+d,3)}); t+=d+GAP
total=t-GAP+0.9
# master audio: place each wav with adelay
cmd=[];
for b in beats: cmd+=['-i',f'{HERE}/t2/{b["id"]}.wav']
fc=''.join(f'[{i}:a]adelay={int(b["t0"]*1000)}|{int(b["t0"]*1000)},apad=whole_dur={total:.2f}[d{i}];' for i,b in enumerate(beats))
fc+=''.join(f'[d{i}]' for i in range(len(beats)))+f'amix=inputs={len(beats)}:normalize=0,alimiter=limit=0.95,atrim=0:{total:.2f}[a]'
run(cmd+['-filter_complex',fc,'-map','[a]','-ar','44100',HERE+'/master.wav'])
# envelope 50fps
r=subprocess.run([FF,'-v','error','-i',HERE+'/master.wav','-f','s16le','-ac','1','-ar','16000','-'],capture_output=True)
pcm=r.stdout; n=len(pcm)//2; sm=struct.unpack('<%dh'%n,pcm)
W=320 # 20ms
env=[]
for i in range(0,n-W,W):
    seg=sm[i:i+W]; env.append(math.sqrt(sum(x*x for x in seg)/W))
mx=sorted(env)[int(len(env)*.97)] or 1
env=[min(1,e/mx) for e in env]
# smooth
sm2=[];a=0
for e in env: a=a+(e-a)*(0.5 if e>a else 0.25); sm2.append(round(a,3))
open(HERE+'/timeline.js','w').write('window.TL='+json.dumps({'beats':beats,'total':round(total,2),'env':sm2,'envFps':50})+';')
print('total',total,[ (b['id'],b['t0'],b['t1']) for b in beats])
