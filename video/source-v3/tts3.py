import json,sys,urllib.request,os
HERE=os.path.dirname(os.path.abspath(__file__))
D=json.load(open(HERE+'/deck.json'))
env={}
for l in open(HERE+'/../../.env'):
    if '=' in l and not l.startswith('#'):
        k,v=l.rstrip('\n').split('=',1); env[k]=v.strip().strip('"')
base=env['AZURE_OPENAI_ENDPOINT'].rstrip('/'); dep=env['AZURE_OPENAI_TTS_DEPLOYMENT']; key=env['AZURE_OPENAI_API_KEY']
INS="You are Juno, a warm, bright, confident AI colleague presenting a product to senior business leaders. Friendly, with a gentle smile in your voice, lively and natural, slightly brisk, with short pauses at full stops. Keep exactly the same voice and energy throughout."
for sid,text in D['beats'].items():
    out=f"{HERE}/a/{sid}.mp3"
    if os.path.exists(out): continue
    body=json.dumps({"model":dep,"input":text,"voice":"coral","instructions":INS,"response_format":"mp3"}).encode()
    req=urllib.request.Request(f"{base}/openai/deployments/{dep}/audio/speech?api-version=2025-03-01-preview",data=body,headers={"api-key":key,"Content-Type":"application/json"})
    try: data=urllib.request.urlopen(req,timeout=90).read()
    except Exception as e: print(sid,'FAILED',getattr(e,'code',e)); sys.exit(1)
    open(out,"wb").write(data); print(sid,'ok',flush=True)
print('done')
