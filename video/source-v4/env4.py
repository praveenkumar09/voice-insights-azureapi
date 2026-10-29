# Adds the narration loudness envelope (50 per second) to timeline.js so animations can move with Juno's voice.
import subprocess,sys,os,array,math,json
HERE=os.path.dirname(os.path.abspath(__file__)); FF=sys.argv[1]
raw=subprocess.run([FF,'-v','error','-i',HERE+'/master.wav','-ac','1','-ar','8000','-f','s16le','-'],capture_output=True).stdout
a=array.array('h');a.frombytes(raw)
step=160; env=[]
for i in range(0,len(a)-step,step):
    seg=a[i:i+step]; env.append(math.sqrt(sum(x*x for x in seg)/step))
s=sorted(env); ref=s[int(len(s)*.95)] or 1
env=[min(1,e/ref) for e in env]
sm=[]; y=0
for e in env:
    y=y+(e-y)*(0.5 if e>y else 0.2); sm.append(round(y,2))
t=open(HERE+'/timeline.js').read()
t=t.split('\nwindow.ENV=')[0]+'\nwindow.ENV='+json.dumps(sm)+';'
open(HERE+'/timeline.js','w').write(t); print('env',len(sm))
