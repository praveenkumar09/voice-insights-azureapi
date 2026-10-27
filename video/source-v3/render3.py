import asyncio,json,base64,subprocess,sys,time,urllib.request,os
import websockets
HERE=os.path.dirname(os.path.abspath(__file__))
PORT=9344
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
async def session(url,fn):
    proc=subprocess.Popen([CHROME,'--headless=new','--disable-gpu','--hide-scrollbars','--allow-file-access-from-files',f'--remote-debugging-port={PORT}','--window-size=1920,1080','--force-device-scale-factor=1','--user-data-dir='+HERE+'/chrome-prof','about:blank'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    try:
        for _ in range(60):
            try: tabs=json.load(urllib.request.urlopen(f'http://127.0.0.1:{PORT}/json')); break
            except Exception: time.sleep(.2)
        ws_url=[t for t in tabs if t['type']=='page'][0]['webSocketDebuggerUrl']
        async with websockets.connect(ws_url,max_size=256*1024*1024) as ws:
            n=[0]
            async def call(m,p=None):
                n[0]+=1;i=n[0];await ws.send(json.dumps({'id':i,'method':m,'params':p or {}}))
                while True:
                    r=json.loads(await ws.recv())
                    if r.get('id')==i: return r
            await call('Page.enable');await call('Emulation.setDeviceMetricsOverride',{'width':1920,'height':1080,'deviceScaleFactor':1,'mobile':False})
            await call('Page.navigate',{'url':url});await asyncio.sleep(2.5)
            await fn(call)
    finally: proc.terminate()
async def frames(times,outdir):
    os.makedirs(outdir,exist_ok=True)
    async def fn(call):
        t0=time.time()
        for idx,t in enumerate(times):
            await call('Runtime.evaluate',{'expression':f'R({t})'})
            r=await call('Page.captureScreenshot',{'format':'jpeg','quality':92})
            open(f'{outdir}/f{idx:05d}.jpg','wb').write(base64.b64decode(r['result']['data']))
            if idx%100==0: print(idx,len(times),round(time.time()-t0),'s',flush=True)
    await session('file://'+HERE+'/deck.html',fn)
async def pdf(out):
    async def fn(call):
        r=await call('Page.printToPDF',{'paperWidth':20,'paperHeight':11.25,'printBackground':True,'preferCSSPageSize':True,'marginTop':0,'marginBottom':0,'marginLeft':0,'marginRight':0})
        open(out,'wb').write(base64.b64decode(r['result']['data']))
    await session('file://'+HERE+'/deck.html?static=1',fn)
if __name__=='__main__':
    m=sys.argv[1]
    if m=='test': asyncio.run(frames([float(x) for x in sys.argv[2].split(',')],HERE+'/test'))
    elif m=='video':
        total=float(sys.argv[2]);fps=25;asyncio.run(frames([i/fps for i in range(int(total*fps))],HERE+'/frames'))
    elif m=='pdf': asyncio.run(pdf(sys.argv[2]))
