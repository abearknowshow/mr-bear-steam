"""Local-only support client; never prints the authentication secret.

Coordinate input (tap, long_press, swipe) and back/home act on the frame you last saved with `screenshot`:
the relay refuses them unless its newest frame is fresh and shows that same UI hash."""
from pathlib import Path
import json,os,sys,time,base64,urllib.request,urllib.error
ROOT=Path(__file__).resolve().parent
PORT=int(os.environ.get('BEAR_SUPPORT_CONTROL_PORT','8779'))
TOKEN=Path(os.environ.get('BEAR_SUPPORT_TOKEN',ROOT/'private/control-token'))
FRAMES=Path(os.environ['BEAR_SUPPORT_FRAMES']) if 'BEAR_SUPPORT_FRAMES' in os.environ else ROOT/'private/frames'
SEEN=TOKEN.parent/'last-frame.json'
USAGE='status | screen | screenshot [PATH] | tap X Y | long_press X Y | swipe X1 Y1 X2 Y2 MS | back | home | last | approve CODE | action JSON | stop'
def request(path,body=None):
 r=urllib.request.Request(f'http://127.0.0.1:{PORT}'+path,data=None if body is None else json.dumps(body).encode(),headers={'Authorization':'Bearer '+TOKEN.read_text(),'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(r,timeout=5) as response:return json.load(response)
 except urllib.error.HTTPError as e:
  try:raise SystemExit(e.read().decode())
  finally:e.close()
def private_write(path,data):
 fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
 with os.fdopen(fd,'wb') as f:f.write(data)
 os.chmod(path,0o600)
def screenshot(target=None):
 """Save the newest frame (0600) and remember its hash as the one the operator has seen. Blank frames save no file."""
 f=request('/frame');meta={k:f[k] for k in ('age','hash','capturedAt','width','height','screenWidth','screenHeight','blank','bytes')}
 private_write(SEEN,json.dumps({k:f[k] for k in ('hash','capturedAt','width','height','screenWidth','screenHeight')}).encode())
 if f['jpeg'] is None:return meta|{'path':None}
 path=Path(target) if target else FRAMES/f"frame-{f['capturedAt']}.jpg"
 path.parent.mkdir(parents=True,exist_ok=True,mode=0o700);private_write(path,base64.b64decode(f['jpeg']))
 return meta|{'path':str(path)}
def seen():
 if not SEEN.exists():raise SystemExit('Take a screenshot first')
 return json.loads(SEEN.read_text())
def act(action,wait=8):
 """Queue one action, then wait for the panel's report. Never retries."""
 queued=request('/action',action);end=time.monotonic()+wait
 while time.monotonic()<end:
  last=request('/session').get('last')
  if last and last.get('id')==queued['id']:return last
  time.sleep(.25)
 return {'id':queued['id'],'status':'no report yet'}
def ints(args,n):
 if len(args)!=n:raise SystemExit(USAGE)
 try:return [int(a) for a in args]
 except ValueError:raise SystemExit(USAGE)
def main(argv):
 op=argv[0] if argv else 'status';args=argv[1:]
 if op in ('status','screen'):
  value=request('/session')
  if op=='status':value.pop('screen',None)
  else:value={k:value.get(k) for k in ('screenAge','frame','screen','last')}
 elif op=='screenshot':value=screenshot(args[0] if args else None)
 elif op=='last':value={k:request('/session').get(k) for k in ('last','pending','frame')}
 elif op=='approve':value=request('/approve',{'code':args[0]})
 elif op=='stop':value=request('/stop',{})
 elif op=='action':value=request('/action',json.loads(args[0]))
 elif op in ('tap','long_press'):x,y=ints(args,2);value=act({'op':op,'hash':seen()['hash'],'x':x,'y':y})
 elif op=='swipe':x1,y1,x2,y2,ms=ints(args,5);value=act({'op':'swipe','hash':seen()['hash'],'x1':x1,'y1':y1,'x2':x2,'y2':y2,'ms':ms})
 elif op in ('back','home'):value=act({'op':op,'hash':seen()['hash']})
 else:raise SystemExit(USAGE)
 print(json.dumps(value,indent=2,ensure_ascii=False))
if __name__=='__main__':main(sys.argv[1:])
