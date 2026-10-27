# Renders the background images and icon PNGs that the editable PowerPoint uses (same look as the video).
import asyncio,json,base64,subprocess,sys,time,urllib.request,os,re
import websockets
HERE=os.path.dirname(os.path.abspath(__file__))
PORT=9355
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
ICONS=re.search(r'const ICONS=\{(.*?)\};\nconst ic=',open(HERE+'/deck.html').read(),re.S).group(1)
os.makedirs(HERE+'/pptx-assets',exist_ok=True)
BG=f'''<!doctype html><html><head><meta charset="utf-8"><style>
:root{{--red:#D31145;--deep:#6E0A27;--wine:#3B0516}}*{{margin:0;padding:0}}html,body{{width:1920px;height:1080px;overflow:hidden}}
.bg{{position:absolute;inset:0}}
.light{{background:radial-gradient(1000px 700px at 100% -10%,rgba(211,17,69,.18),transparent 60%),radial-gradient(800px 600px at -5% 60%,rgba(211,17,69,.09),transparent 60%),#FFF8FA}}
.dark{{background:radial-gradient(900px 700px at 15% 110%,rgba(211,17,69,.6),transparent 62%),linear-gradient(150deg,var(--wine),var(--deep) 55%,#A30D36)}}
.hero{{background:url(hero.png) 0% 50%/cover}}.veil{{position:absolute;inset:0;background:linear-gradient(90deg,rgba(255,248,250,0) 20%,rgba(255,248,250,.97) 58%)}}
.impact{{position:absolute;left:0;top:0;width:574px;height:572px;border-radius:36px;background:radial-gradient(500px 400px at 100% 100%,rgba(255,120,150,.45),transparent 62%),linear-gradient(150deg,var(--wine),var(--deep) 50%,var(--red))}}
</style></head><body><div id="k"></div><script>
const k=new URLSearchParams(location.search).get('k');const d=document.getElementById('k');
if(k==='title')d.innerHTML='<div class="bg hero"></div><div class="veil"></div>';else if(k==='impact')d.innerHTML='<div class="impact"></div>';else d.innerHTML='<div class="bg '+k+'"></div>';
</script></body></html>'''
open(HERE+'/bg.html','w').write(BG)
IC=f'''<!doctype html><html><head><meta charset="utf-8"><style>*{{margin:0;padding:0}}body{{background:transparent}}svg{{width:128px;height:128px;stroke-width:2;fill:none;stroke-linecap:round;stroke-linejoin:round}}</style></head><body><script>
const ICONS={{{ICONS}}};
const q=new URLSearchParams(location.search);const n=q.get('n'),c=q.get('c');
document.body.innerHTML='<svg viewBox="0 0 24 24" style="stroke:'+c+'">'+ICONS[n]+'</svg>';
</script></body></html>'''
open(HERE+'/icon.html','w').write(IC)
NAMES=re.findall(r'^(\w+):',ICONS,re.M)
async def shot(call,url,out,w,h,clip=None,transparent=False):
    await call('Emulation.setDeviceMetricsOverride',{'width':w,'height':h,'deviceScaleFactor':1,'mobile':False})
    if transparent: await call('Emulation.setDefaultBackgroundColorOverride',{'color':{'r':0,'g':0,'b':0,'a':0}})
    await call('Page.navigate',{'url':url});await asyncio.sleep(0.5)
    r=await call('Page.captureScreenshot',{'format':'png','captureBeyondViewport':False,**({'clip':clip} if clip else {})})
    open(out,'wb').write(base64.b64decode(r['result']['data']))
async def main():
    proc=subprocess.Popen([CHROME,'--headless=new','--disable-gpu','--hide-scrollbars','--allow-file-access-from-files',f'--remote-debugging-port={PORT}','--window-size=1920,1080','--force-device-scale-factor=1','--user-data-dir='+HERE+'/chrome-prof2','about:blank'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    try:
        for _ in range(60):
            try: tabs=json.load(urllib.request.urlopen(f'http://127.0.0.1:{PORT}/json')); break
            except Exception: time.sleep(.2)
        ws_url=[t for t in tabs if t['type']=='page'][0]['webSocketDebuggerUrl']
        async with websockets.connect(ws_url,max_size=64*1024*1024) as ws:
            n=[0]
            async def call(m,p=None):
                n[0]+=1;i=n[0];await ws.send(json.dumps({'id':i,'method':m,'params':p or {}}))
                while True:
                    r=json.loads(await ws.recv())
                    if r.get('id')==i: return r
            await call('Page.enable')
            for k in ('light','dark','title'): await shot(call,'file://'+HERE+f'/bg.html?k={k}',f'{HERE}/pptx-assets/bg-{k}.png',1920,1080)
            await shot(call,'file://'+HERE+'/bg.html?k=impact',f'{HERE}/pptx-assets/impact.png',574,572,{'x':0,'y':0,'width':574,'height':572,'scale':1})
            for nm in NAMES:
                for col,tag in (('#D31145','red'),('#FFFFFF','white')):
                    await shot(call,'file://'+HERE+f'/icon.html?n={nm}&c=%23{col[1:]}',f'{HERE}/pptx-assets/{nm}-{tag}.png',128,128,{'x':0,'y':0,'width':128,'height':128,'scale':1},True)
    finally: proc.terminate()
asyncio.run(main()); print('assets ok',len(NAMES),'icons')
