import json,os,sys,urllib.request
env={}
for l in open('/Users/praveenkumarthangaraj/Documents/CobaltIngestion/voice-insights-azureapi/.env'):
    if '=' in l and not l.startswith('#'):
        k,v=l.rstrip('\n').split('=',1); env[k]=v.strip().strip('"')
base=env['AZURE_OPENAI_ENDPOINT'].rstrip('/'); dep=env['AZURE_OPENAI_TTS_DEPLOYMENT']; key=env['AZURE_OPENAI_API_KEY']
SCENES=[
("s1","Hi, I'm Juno, your agent's debrief partner. Agents spend the meeting listening... and the evening typing."),
("s2","On paper or in i-PoS, it's all typed from memory. Let's give them the evening back."),
("s3","I listen to the conversation and, in about thirty seconds, draft the suggestion, fact-find, advice record, follow-up and CRM note. In four languages."),
("s4","For agents: time back, better conversations, fewer missed needs, safer advice, in every customer's language."),
("s5","For AIA Singapore: productivity at scale, consistent quality, compliance caught early, revenue upside, and leadership visibility."),
("s6","And the timing is right. Adviser premiums are up thirty-six percent, and seventy-four percent of critical illness cover is still missing. Six hours back per rep is about seventy-four thousand hours a month."),
("s7","Let's prove it with AIA's own numbers. Just say... Hey Juno."),
]
INS="You are Juno, a warm, bright, confident AI colleague presenting to senior executives in a one-minute film. Friendly, with a gentle smile in your voice. Natural pace, slightly brisk, with brief pauses at full stops. Keep exactly the same voice and energy throughout."
out=sys.argv[1]
for sid,text in SCENES:
    body=json.dumps({"model":dep,"input":text,"voice":"coral","instructions":INS,"response_format":"mp3"}).encode()
    req=urllib.request.Request(f"{base}/openai/deployments/{dep}/audio/speech?api-version=2025-03-01-preview",data=body,headers={"api-key":key,"Content-Type":"application/json"})
    try:
        data=urllib.request.urlopen(req,timeout=90).read()
    except Exception as e:
        print(sid,"FAILED",getattr(e,'code',e)); sys.exit(1)
    open(f"{out}/{sid}.mp3","wb").write(data); print(sid,len(data))
