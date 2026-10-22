"""Temporary, local, memory-only UI support relay. No shell, account or equipment API."""
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
import json,secrets,ssl,time,threading,hmac,re,base64,binascii
ROOT=Path(__file__).resolve().parent
import sys;sys.path.insert(0,str(ROOT.parent/'controls'));import household
HOUSEHOLD=household.load();MAC=HOUSEHOLD['mac_host'];PANEL=HOUSEHOLD['panel_host']
URLS={f'http://{MAC}:{p}/' for p in (8768,8775,8780)}
SESSION=7200;FRESH=4;EXPIRY=6
APK='bear-remote-support-0.2.apk';PAGE='download-0.2.html'
# Coordinate input acts on what the operator saw, so it needs a fresh frame of the same UI.
POINTS={'tap':('x','y'),'long_press':('x','y'),'swipe':('x1','y1','x2','y2')}
FRAME_KEYS={'hash','capturedAt','capturedMono','sentMono','width','height','screenWidth','screenHeight','jpeg','blank'}
def validate_action(a):
 if not isinstance(a,dict) or not re.fullmatch('[a-f0-9]{64}',a.get('hash','')):raise ValueError('Invalid snapshot')
 keys={'op','hash'};op=a.get('op')
 if op in ('click','scroll_forward','scroll_backward'):keys|={'node'}
 elif op=='text':keys|={'node','text'}
 elif op in POINTS:keys|=set(POINTS[op])|({'ms'} if op=='swipe' else set())
 elif op=='url':keys|={'url'}
 elif op not in ('back','home','launch_bear','stop'):raise ValueError('Unsupported action')
 if set(a)!=keys:raise ValueError('Invalid action')
 if 'node' in a and (not isinstance(a['node'],str) or not re.fullmatch(r'\d+(?:\.\d+)*',a['node'])):raise ValueError('Invalid node')
 if 'text' in a and (not isinstance(a['text'],str) or len(a['text'])>256):raise ValueError('Invalid text')
 if op in POINTS and any(type(a[k]) is not int or not 0<=a[k]<4096 for k in POINTS[op]):raise ValueError('Invalid point')
 if op=='swipe' and (type(a['ms']) is not int or not 100<=a['ms']<=3000):raise ValueError('Invalid duration')
 if op=='url' and a['url'] not in URLS:raise ValueError('URL not allowed')
 return dict(a)
def inside(b,x,y):return isinstance(b,list) and len(b)==4 and b[0]<=x<b[2] and b[1]<=y<b[3]
def point_allowed(screen,x,y):
 """Relay-side copy of the panel's guard: the top window at the point must be allowed and no password field may cover it."""
 top=next((w for w in screen['windows'] if inside(w.get('bounds'),x,y)),None)
 if not top or top.get('allowed') is not True:return False
 return not any(n.get('password') and inside(n.get('bounds'),x,y) for n in screen['nodes'])
def validate_frame(f):
 if not isinstance(f,dict) or set(f)!=FRAME_KEYS or not isinstance(f['hash'],str) or not re.fullmatch('[a-f0-9]{64}',f['hash']):raise ValueError('Invalid frame')
 if any(type(f[k]) is not int or f[k]<0 for k in ('capturedAt','capturedMono','sentMono')) or f['sentMono']<f['capturedMono']:raise ValueError('Invalid frame time')
 if any(type(f[k]) is not int or not 0<f[k]<=4096 for k in ('width','height','screenWidth','screenHeight')):raise ValueError('Invalid frame size')
 if f['blank'] is not None:
  # A blank frame carries a reason and never pixels.
  if not isinstance(f['blank'],str) or not 0<len(f['blank'])<=120 or f['jpeg'] is not None:raise ValueError('Invalid blank frame')
  return None
 if not isinstance(f['jpeg'],str):raise ValueError('Invalid frame image')
 try:data=base64.b64decode(f['jpeg'],validate=True)
 except (binascii.Error,ValueError):raise ValueError('Invalid frame image')
 if not 0<len(data)<=700000 or data[:2]!=b'\xff\xd8':raise ValueError('Invalid frame image')
 return data
class SupportStore:
 def __init__(self,clock=time.monotonic):self.clock=clock;self.item=None;self.lock=threading.Lock()
 def active(self):
  if not self.item or self.item['expires']<=self.clock():self.item=None;raise ValueError('Support ended')
  return self.item
 def check(self,s,body,keys):
  if set(body)!=keys:raise ValueError('Invalid request')
  if not all(isinstance(body[k],str) and hmac.compare_digest(body[k],s[k]) for k in ('id','key')):raise ValueError('Wrong session')
 def begin(self):
  with self.lock:
   self.item=dict(id=secrets.token_hex(32),key=secrets.token_hex(32),code=str(secrets.randbelow(900000)+100000),expires=self.clock()+SESSION,approved=False,screen=None,screenAt=0,frame=None,image=None,frameAt=0,pending=None,last=None)
   return {k:self.item[k] for k in ('id','key','code')}
 def approve(self,code):
  with self.lock:
   s=self.active()
   if not isinstance(code,str) or not hmac.compare_digest(code,s['code']):raise ValueError('Pairing code mismatch')
   s['approved']=True
 def frame_age(self,s):
  # Relay time since receipt plus the panel's own capture-to-send delay.
  return None if not s['frame'] else round(self.clock()-s['frameAt']+(s['frame']['sentMono']-s['frame']['capturedMono'])/1000,1)
 def frame_info(self,s):
  if not s['frame']:return None
  return {k:v for k,v in s['frame'].items() if k!='jpeg'}|{'age':self.frame_age(s),'bytes':len(s['image']) if s['image'] else 0}
 def status(self):
  with self.lock:
   try:s=self.active()
   except ValueError:return {'state':'ended'}
   return {k:s[k] for k in ('id','code','approved','screen','last')}|{'secondsLeft':max(0,int(s['expires']-self.clock())),'screenAge':round(self.clock()-s['screenAt'],1) if s['screen'] else None,'frame':self.frame_info(s),'pending':None if not s['pending'] else {'id':s['pending']['id'],'delivered':s['pending']['delivered']}}
 def latest_frame(self):
  with self.lock:
   s=self.active()
   if not s['frame']:raise ValueError('No frame yet')
   return self.frame_info(s)|{'jpeg':base64.b64encode(s['image']).decode() if s['image'] else None}
 def end(self,auth):
  with self.lock:
   s=self.active();self.check(s,auth,{'id','key'});self.item=None
 def stop(self):
  with self.lock:self.item=None
 def queue(self,action):
  a=validate_action(action)
  with self.lock:
   s=self.active()
   if not s['approved'] or not s['screen'] or self.clock()-s['screenAt']>FRESH:raise ValueError('Fresh approved screen required')
   if a['hash']!=s['screen']['hash']:raise ValueError('Screen changed; read it again')
   op=a['op']
   # A session that never produced a frame (owner declined screen images) keeps 0.1's tree-derived tap.
   if op in POINTS and (op!='tap' or s['frame']):
    f=s['frame']
    if not f or self.frame_age(s)>FRESH:raise ValueError('Fresh frame required')
    if f['hash']!=a['hash']:raise ValueError('Frame changed; take a new screenshot')
    pts=[(a[POINTS[op][i]],a[POINTS[op][i+1]]) for i in range(0,len(POINTS[op]),2)]
    if any(not(x<f['screenWidth'] and y<f['screenHeight']) for x,y in pts):raise ValueError('Point outside the screen')
   if op in POINTS and not point_allowed(s['screen'],a[POINTS[op][0]],a[POINTS[op][1]]):raise ValueError('Point is on a blocked window or password field')
   if s['pending'] and self.clock()<s['pending']['deadline']:raise ValueError('One action already pending')
   a.update(id=secrets.token_hex(16));now=self.clock();s['pending']=dict(id=a['id'],action=a,queuedAt=now,deliveredAt=None,deadline=now+EXPIRY,delivered=False)
   return {'id':a['id']}
 def frame(self,body):
  with self.lock:
   s=self.active();self.check(s,body,{'id','key','frame'})
   if not s['approved']:raise ValueError('Not approved')
   f=body['frame'];data=validate_frame(f)
   s['frame']=dict(f);s['image']=data;s['frameAt']=self.clock()
   return {'approved':True}
 def exchange(self,body):
  with self.lock:
   s=self.active();self.check(s,body,{'id','key','screen','result'})
   screen=body['screen'];result=body['result']
   if screen is not None:
    if not s['approved'] or not isinstance(screen,dict) or set(screen)!={'hash','nodes','windows'} or not re.fullmatch('[a-f0-9]{64}',screen.get('hash','')) or not isinstance(screen['nodes'],list) or len(screen['nodes'])>240 or not isinstance(screen['windows'],list) or len(screen['windows'])>12:raise ValueError('Invalid screen')
    s['screen']=screen;s['screenAt']=self.clock()
   if result is not None:
    if not isinstance(result,dict) or not {'id','status'}<=set(result)<={'id','status','at','mono'} or result['status'] not in ('done','rejected','failed','dispatched') or any(type(result[k]) is not int for k in ('at','mono') if k in result):raise ValueError('Invalid result')
    p=s['pending']
    if p and result['id']==p['id']:
     # Latency: relay-measured delivery and report delays, plus the panel clock at dispatch (compare with a frame's capturedAt).
     now=self.clock();s['last']={'id':result['id'],'status':result['status'],'op':p['action']['op'],'deliveredAfter':round(p['deliveredAt']-p['queuedAt'],2) if p['deliveredAt'] is not None else None,'reportedAfter':round(now-p['queuedAt'],2),'dispatchedAt':result.get('at')};s['pending']=None
   pending=s['pending'];command=None
   if pending:
    if pending['deadline']<=self.clock():s['last']={'id':pending['id'],'status':'expired','op':pending['action']['op']};s['pending']=None
    elif s['approved'] and not pending['delivered']:command=pending['action'];pending['delivered']=True;pending['deliveredAt']=self.clock()
   return dict(approved=s['approved'],command=command)
def handler(kind,store,secret,panel=PANEL,mac=MAC):
 class Handler(BaseHTTPRequestHandler):
  def log_message(self,*_):pass
  def reply(self,status,value,mime='application/json'):
   data=value if isinstance(value,bytes) else json.dumps(value).encode();self.send_response(status);self.send_header('Content-Type',mime);self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store');self.send_header('X-Content-Type-Options','nosniff');self.send_header('Content-Security-Policy',"default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'");self.end_headers();self.wfile.write(data)
  def allowed(self):
   port=self.server.server_address[1];ip=self.client_address[0]
   hosts={f'127.0.0.1:{port}'} if kind=='control' else {f'{mac}:{port}',f'127.0.0.1:{port}'}
   ips={'127.0.0.1'} if kind=='control' else {panel,mac,'127.0.0.1'}
   if ip not in ips or self.headers.get('Host') not in hosts or self.headers.get('Origin') is not None:return False
   if kind=='control' and not hmac.compare_digest(self.headers.get('Authorization',''),'Bearer '+secret):return False
   if kind=='relay' and ip!=panel:return False
   return True
  def do_GET(self):
   if not self.allowed():return self.reply(403,{'error':'Unavailable'})
   try:
    if kind=='control' and self.path=='/session':return self.reply(200,store.status())
    if kind=='control' and self.path=='/frame':return self.reply(200,store.latest_frame())
   except ValueError:return self.reply(409,{'error':'No frame; check session state'})
   if kind=='download':
    if self.path=='/':return self.reply(200,household.page((ROOT/PAGE).read_text(),HOUSEHOLD).encode(),'text/html; charset=utf-8')
    if self.path=='/'+APK:return self.reply(200,(ROOT/APK).read_bytes(),'application/vnd.android.package-archive')
   self.reply(404,{'error':'Not found'})
  def do_POST(self):
   if not self.allowed():return self.reply(403,{'error':'Unavailable'})
   try:
    n=int(self.headers.get('Content-Length','0'));limit=1000000 if kind=='relay' and self.path=='/frame' else 131072
    if self.headers.get('Content-Type')!='application/json' or not 0<n<=limit:raise ValueError('Invalid body')
    b=json.loads(self.rfile.read(n))
    if not isinstance(b,dict):raise ValueError('Invalid body')
    if kind=='relay' and self.path=='/begin' and b=={}:return self.reply(200,store.begin())
    if kind=='relay' and self.path=='/exchange':return self.reply(200,store.exchange(b))
    if kind=='relay' and self.path=='/frame':return self.reply(200,store.frame(b))
    if kind=='relay' and self.path=='/end':store.end(b);return self.reply(200,{'ok':True})
    if kind=='control' and self.path=='/approve' and set(b)=={'code'}:store.approve(b['code']);return self.reply(200,{'ok':True})
    if kind=='control' and self.path=='/stop' and b=={}:store.stop();return self.reply(200,{'ok':True})
    if kind=='control' and self.path=='/action':return self.reply(200,store.queue(b))
    self.reply(404,{'error':'Not found'})
   except (ValueError,TypeError,KeyError):self.reply(409,{'error':'Support request rejected; refresh session state'})
 return Handler
def serve(store,secret,listeners,cert,key,panel=PANEL,mac=MAC):
 """Start (kind,ip,port) listeners; the relay listener uses TLS. Tests call this with loopback ports and a throwaway certificate."""
 servers=[]
 for kind,ip,port in listeners:
  server=ThreadingHTTPServer((ip,port),handler(kind,store,secret,panel,mac));server.daemon_threads=True
  if kind=='relay':
   tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.minimum_version=ssl.TLSVersion.TLSv1_2;tls.load_cert_chain(cert,key);server.socket=tls.wrap_socket(server.socket,server_side=True)
  servers.append(server);threading.Thread(target=server.serve_forever,daemon=True).start()
 return servers
def main():
 secret=secrets.token_hex(32);private=ROOT/'private';private.mkdir(exist_ok=True);private.chmod(0o700);key=private/'control-token';key.write_text(secret);key.chmod(0o600)
 store=SupportStore()
 servers=serve(store,secret,[('control','127.0.0.1',8779),('relay','0.0.0.0',8778),('download','0.0.0.0',8780)],ROOT/'assets/setup-server.crt',ROOT.parent/'controls/private/setup-server.key')
 print(f'Temporary support relay ready. Download: http://{MAC}:8780/',flush=True)
 try:
  while True:
   time.sleep(5)
   with store.lock:
    if store.item and store.item['expires']<=store.clock():store.item=None
 finally:
  store.stop();key.unlink(missing_ok=True)
  for s in servers:s.shutdown();s.server_close()
if __name__=='__main__':main()
