import json,sys,urllib.request,os
sys.path.insert(0,os.path.dirname(__file__)); from beats import BEATS
env={}
for l in open('/Users/praveenkumarthangaraj/Documents/CobaltIngestion/voice-insights-azureapi/.env'):
    if '=' in l and not l.startswith('#'):
        k,v=l.rstrip('\n').split('=',1); env[k]=v.strip().strip('"')
base=env['AZURE_OPENAI_ENDPOINT'].rstrip('/'); dep=env['AZURE_OPENAI_TTS_DEPLOYMENT']; key=env['AZURE_OPENAI_API_KEY']
INS="You are Juno, a warm, bright, confident AI colleague explaining an idea to senior executives. Friendly, with a gentle smile in your voice, lively and natural, slightly brisk, with short pauses at full stops. Keep exactly the same voice and energy throughout."
out=os.path.join(os.path.dirname(__file__),'a')
for sid,text in BEATS:
    body=json.dumps({"model":dep,"input":text,"voice":"coral","instructions":INS,"response_format":"mp3"}).encode()
    req=urllib.request.Request(f"{base}/openai/deployments/{dep}/audio/speech?api-version=2025-03-01-preview",data=body,headers={"api-key":key,"Content-Type":"application/json"})
    try: data=urllib.request.urlopen(req,timeout=90).read()
    except Exception as e: print(sid,'FAILED',getattr(e,'code',e)); sys.exit(1)
    open(f"{out}/{sid}.mp3","wb").write(data)
print('done')
