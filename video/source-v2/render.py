import asyncio,json,base64,subprocess,sys,time,urllib.request,os
import websockets
HERE=os.path.dirname(os.path.abspath(__file__))
PORT=9333
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
async def main(times,outdir,fps=None):
    os.makedirs(outdir,exist_ok=True)
    proc=subprocess.Popen([CHROME,'--headless=new','--disable-gpu','--hide-scrollbars','--allow-file-access-from-files',f'--remote-debugging-port={PORT}','--window-size=1920,1080','--force-device-scale-factor=1','--user-data-dir='+HERE+'/chrome-prof','about:blank'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    try:
        for _ in range(50):
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
            await call('Page.enable');await call('Emulation.setDeviceMetricsOverride',{'width':1920,'height':1080,'deviceScaleFactor':1,'mobile':False})
            await call('Page.navigate',{'url':'file://'+HERE+'/v2.html'})
            await asyncio.sleep(2.0)
            r=await call('Runtime.evaluate',{'expression':'typeof R'}); print(r['result']['result'])
            t0=time.time()
            for idx,t in enumerate(times):
                await call('Runtime.evaluate',{'expression':f'R({t})'})
                r=await call('Page.captureScreenshot',{'format':'jpeg','quality':92})
                open(f'{outdir}/f{idx:05d}.jpg','wb').write(base64.b64decode(r['result']['data']))
                if idx%100==0: print(idx,len(times),round(time.time()-t0),'s',flush=True)
    finally: proc.terminate()
if __name__=='__main__':
    mode=sys.argv[1]
    if mode=='test':
        ts=[float(x) for x in sys.argv[2].split(',')]; asyncio.run(main(ts,HERE+'/test'))
    else:
        total=float(sys.argv[2]); fps=25; ts=[i/fps for i in range(int(total*fps))]; asyncio.run(main(ts,HERE+'/frames'))
