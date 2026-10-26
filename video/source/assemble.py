import subprocess,os,sys
SP=os.path.dirname(os.path.abspath(__file__))
FF=SP+'/../venv/lib/python3.13/site-packages/imageio_ffmpeg/binaries/ffmpeg-macos-aarch64-v7.1'
OUT=sys.argv[1]
TEMPO=1.06
def dur(f):
    r=subprocess.run([FF,'-i',f],capture_output=True,text=True).stderr
    t=r.split('Duration: ')[1].split(',')[0]; h,m,s=t.split(':'); return int(h)*3600+int(m)*60+float(s)
sd={i:dur(f'{SP}/s{i}.mp3')/TEMPO for i in range(1,8)}
# stills per scene: (file, weight)
plan={1:[('s1k1',.45),('s1k2',.55)],2:[('s2k1',.5),('s2k2',.5)],3:[('s3k1',.3),('s3k2',.4),('s3k3',.3)],
4:[(f's4k{k}',.2) for k in range(1,6)],5:[(f's5k{k}',.2) for k in range(1,6)],
6:[('s6k0',.22),('s6k1',.26),('s6k2',.26),('s6k3',.26)],7:[('s7k1',.55),('s7k2',.45)]}
clips=[]
for sc in range(1,8):
    for f,w in plan[sc]: clips.append((f,sd[sc]*w))
tot=sum(d for _,d in clips); print('total',tot)
XF=0.35; FPS=25
inputs=[];fc=[]
for i,(f,d) in enumerate(clips):
    L=d+(XF if i<len(clips)-1 else 0)
    inputs+=['-loop','1','-t',f'{L:.3f}','-i',f'{SP}/fr/{f}.png']
    n=int(L*FPS)+1
    zdir = '1+0.045*on/%d'%n if i%2==0 else '1.045-0.045*on/%d'%n
    fc.append(f'[{i}:v]scale=3840:2160,zoompan=z=\'{zdir}\':x=\'iw/2-(iw/zoom/2)\':y=\'ih/2-(ih/zoom/2)\':d=1:s=1920x1080:fps={FPS},format=yuv420p[v{i}]')
# crossfades
cur='v0';t=0
for i in range(1,len(clips)):
    t+=clips[i-1][1]
    fc.append(f'[{cur}][v{i}]xfade=transition=fade:duration={XF}:offset={t:.3f}[x{i}]'); cur=f'x{i}'
# audio
n=len(clips)
for sc in range(1,8): inputs+=['-i',f'{SP}/s{sc}.mp3']
ai=[f'[{n+sc-1}:a]atempo={TEMPO},aresample=44100[a{sc}]' for sc in range(1,8)]
fc+=ai
fc.append(''.join(f'[a{sc}]' for sc in range(1,8))+'concat=n=7:v=0:a=1[aout]')
fc.append('[aout]asplit[ao][aw]')
fc.append(f'[aw]showwaves=s=430x70:mode=cline:colors=0xD31145:rate={FPS}:scale=sqrt,format=rgba,colorkey=black:0.25:0.15[wv]')
end7=tot-sd[7]
fc.append(f'[{cur}][wv]overlay=304:953:enable=\'lt(t,{end7:.2f})\'[vout]')
cmd=[FF,'-y']+inputs+['-filter_complex',';'.join(fc),'-map','[vout]','-map','[ao]','-r',str(FPS),'-c:v','libx264','-preset','medium','-crf','18','-pix_fmt','yuv420p','-c:a','aac','-b:a','192k','-movflags','+faststart',OUT]
r=subprocess.run(cmd,capture_output=True,text=True)
print(r.returncode); print(r.stderr[-800:] if r.returncode else 'ok')
