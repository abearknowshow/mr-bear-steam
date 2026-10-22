import os,pathlib;os.environ.setdefault('BEAR_HOUSEHOLD',str(pathlib.Path(__file__).resolve().parents[2]/'household.example.json'))  # tests use made-up values
import unittest,threading,urllib.request,urllib.error,json,base64,os,tempfile,io,contextlib
from pathlib import Path
from http.server import ThreadingHTTPServer
from relay import SupportStore,handler,validate_action
JPEG=b'\xff\xd8\xff\xe0'+b'\0'*64+b'\xff\xd9'
def frame(hash='a'*64,captured=1000,sent=1200,blank=None,jpeg=JPEG,**kw):
 f={'hash':hash,'capturedAt':1760000000000,'capturedMono':captured,'sentMono':sent,'width':960,'height':563,'screenWidth':1024,'screenHeight':600,'blank':blank,'jpeg':None if jpeg is None else base64.b64encode(jpeg).decode()}
 return f|kw
def screen(hash='a'*64,windows=None,nodes=None):
 return {'hash':hash,'windows':windows if windows is not None else [{'id':1,'package':'com.yizhong','bounds':[0,0,1024,600],'allowed':True}],'nodes':nodes or []}
class Frames(unittest.TestCase):
 def setUp(self):
  self.now=0.;self.s=SupportStore(lambda:self.now);self.p=self.s.begin();self.auth={k:self.p[k] for k in ('id','key')}
 def exchange(self,sc=None,result=None):return self.s.exchange(dict(self.auth,screen=sc,result=result))
 def upload(self,f):return self.s.frame(dict(self.auth,frame=f))
 def ready(self,**kw):
  self.s.approve(self.p['code']);self.exchange(screen(**kw));self.upload(frame(**({'hash':kw['hash']} if 'hash' in kw else {})))
 def test_two_hour_cap(self):
  self.now=7199;self.assertEqual(self.s.status()['secondsLeft'],1);self.now=7200
  self.assertEqual(self.s.status(),{'state':'ended'})
 def test_no_frames_before_approval_or_with_wrong_key(self):
  with self.assertRaises(ValueError):self.upload(frame())
  self.s.approve(self.p['code'])
  with self.assertRaises(ValueError):self.s.frame(dict(self.auth,key='b'*64,frame=frame()))
  with self.assertRaises(ValueError):self.s.frame(dict(self.auth,frame=frame(),extra=1))
 def test_frame_validation(self):
  self.s.approve(self.p['code'])
  for bad in (frame(jpeg=b'GIF89a'),frame(jpeg=b'\xff\xd8'+b'\0'*700001),frame(hash='x'),frame(sent=999),frame(width=0),frame(blank='password field visible'),frame(blank='',jpeg=None),{**frame(),'extra':1}):
   with self.assertRaises(ValueError):self.upload(bad)
  bad=frame();bad['jpeg']='not base64!'
  with self.assertRaises(ValueError):self.upload(bad)
 def test_frame_age_counts_panel_delay_and_status_has_no_pixels(self):
  self.s.approve(self.p['code']);self.upload(frame(captured=1000,sent=1500));self.now=2
  info=self.s.status()['frame'];self.assertEqual(info['age'],2.5);self.assertNotIn('jpeg',info);self.assertEqual(info['bytes'],len(JPEG))
  self.assertEqual(base64.b64decode(self.s.latest_frame()['jpeg']),JPEG)
 def test_blocked_screen_frame_is_blank(self):
  self.s.approve(self.p['code']);self.upload(frame(blank='Settings: Accessibility',jpeg=None))
  latest=self.s.latest_frame();self.assertIsNone(latest['jpeg']);self.assertEqual(latest['blank'],'Settings: Accessibility');self.assertEqual(latest['bytes'],0)
 def test_coordinate_tap_needs_fresh_matching_frame(self):
  self.ready();self.s.queue({'op':'tap','hash':'a'*64,'x':10,'y':10})
 def test_stale_frame(self):
  self.ready();self.now=3.9;self.exchange(screen())
  with self.assertRaises(ValueError):self.s.queue({'op':'tap','hash':'a'*64,'x':10,'y':10})
 def test_frame_hash_mismatch(self):
  self.s.approve(self.p['code']);self.exchange(screen());self.upload(frame(hash='b'*64))
  for a in ({'op':'tap','x':10,'y':10},{'op':'long_press','x':10,'y':10},{'op':'swipe','x1':10,'y1':10,'x2':200,'y2':10,'ms':300}):
   with self.assertRaises(ValueError):self.s.queue(a|{'hash':'a'*64})
 def test_blocked_window(self):
  self.ready(windows=[{'id':2,'package':'com.android.settings','bounds':[0,0,512,600],'allowed':False,'blocked':'Settings: Accessibility'},{'id':1,'package':'com.yizhong','bounds':[0,0,1024,600],'allowed':True}])
  with self.assertRaises(ValueError):self.s.queue({'op':'tap','hash':'a'*64,'x':100,'y':100})
  self.s.queue({'op':'tap','hash':'a'*64,'x':800,'y':100})
 def test_password_field(self):
  self.ready(nodes=[{'id':'1.0','text':'[password]','bounds':[100,100,300,150],'password':True}])
  with self.assertRaises(ValueError):self.s.queue({'op':'tap','hash':'a'*64,'x':150,'y':120})
  with self.assertRaises(ValueError):self.s.queue({'op':'swipe','hash':'a'*64,'x1':150,'y1':120,'x2':600,'y2':120,'ms':300})
 def test_point_outside_screen(self):
  self.ready()
  with self.assertRaises(ValueError):self.s.queue({'op':'tap','hash':'a'*64,'x':1024,'y':10})
 def test_tree_only_session_keeps_label_tap(self):
  # Owner declined screen images: no frame ever arrived, so 0.1's tree-derived tap still works; long press and swipe need a frame.
  self.s.approve(self.p['code']);self.exchange(screen());self.s.queue({'op':'tap','hash':'a'*64,'x':10,'y':10})
  s=SupportStore(lambda:self.now);p=s.begin();s.approve(p['code']);s.exchange({'id':p['id'],'key':p['key'],'screen':screen(),'result':None})
  with self.assertRaises(ValueError):s.queue({'op':'long_press','hash':'a'*64,'x':10,'y':10})
 def test_action_shapes(self):
  for a in ({'op':'swipe','x1':0,'y1':0,'x2':1,'y2':1,'ms':50},{'op':'swipe','x1':0,'y1':0,'x2':1,'y2':1,'ms':5000},{'op':'swipe','x1':0,'y1':0,'x2':1,'y2':1},{'op':'long_press','x':-1,'y':0},{'op':'tap','x':1.5,'y':0},{'op':'home','x':1}):
   with self.assertRaises(ValueError):validate_action(a|{'hash':'a'*64})
  validate_action({'op':'home','hash':'a'*64});validate_action({'op':'swipe','hash':'a'*64,'x1':0,'y1':0,'x2':1,'y2':1,'ms':300})
 def test_one_outstanding_expiry_no_replay_and_latency(self):
  self.ready();a=self.s.queue({'op':'tap','hash':'a'*64,'x':10,'y':10})
  with self.assertRaises(ValueError):self.s.queue({'op':'tap','hash':'a'*64,'x':10,'y':10})
  self.now=.5;cmd=self.exchange(screen())['command'];self.assertEqual(cmd['id'],a['id']);self.assertIsNone(self.exchange(screen())['command'])
  self.now=1.25;self.exchange(screen(),{'id':a['id'],'status':'dispatched','at':1760000000123,'mono':4567})
  self.assertEqual(self.s.status()['last'],{'id':a['id'],'status':'dispatched','op':'tap','deliveredAfter':.5,'reportedAfter':1.25,'dispatchedAt':1760000000123})
  self.upload(frame());b=self.s.queue({'op':'tap','hash':'a'*64,'x':10,'y':10});self.now=8;self.upload(frame())
  self.exchange(screen());self.assertEqual(self.s.status()['last']['status'],'expired')
  with self.assertRaises(ValueError):self.exchange(screen(),{'id':b['id'],'status':'done','at':'soon'})
class HTTP(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.store=SupportStore();cls.servers=[]
  for kind in ('relay','control'):
   server=ThreadingHTTPServer(('127.0.0.1',0),handler(kind,cls.store,'test-only',panel='127.0.0.1'));cls.servers.append(server);threading.Thread(target=server.serve_forever,daemon=True).start()
  cls.relay,cls.control=(s.server_address[1] for s in cls.servers)
 @classmethod
 def tearDownClass(cls):
  for s in cls.servers:s.shutdown();s.server_close()
 def post(self,port,path,body,auth=None):
  data=body if isinstance(body,bytes) else json.dumps(body).encode()
  r=urllib.request.Request(f'http://127.0.0.1:{port}'+path,data=data,headers={'Content-Type':'application/json','Authorization':auth or ''})
  try:
   with urllib.request.urlopen(r) as x:return x.status,json.load(x)
  except urllib.error.HTTPError as e:
   try:return e.code,json.load(e)
   finally:e.close()
 def get(self,path,auth='Bearer test-only'):
  r=urllib.request.Request(f'http://127.0.0.1:{self.control}'+path,headers={'Authorization':auth})
  try:
   with urllib.request.urlopen(r) as x:return x.status,json.load(x)
  except urllib.error.HTTPError as e:
   try:return e.code,json.load(e)
   finally:e.close()
 def test_frame_round_trip_and_cli_screenshot(self):
  p=self.store.begin();auth={k:p[k] for k in ('id','key')}
  self.assertEqual(self.post(self.relay,'/frame',auth|{'frame':frame()})[0],409)
  self.assertEqual(self.get('/frame')[0],409)
  self.post(self.control,'/approve',{'code':p['code']},'Bearer test-only')
  self.assertEqual(self.post(self.relay,'/frame',auth|{'frame':frame()}),(200,{'approved':True}))
  self.assertEqual(self.get('/frame',auth='')[0],403)
  status,body=self.get('/frame');self.assertEqual(status,200);self.assertEqual(base64.b64decode(body['jpeg']),JPEG)
  with tempfile.TemporaryDirectory() as d:
   token=Path(d)/'token';token.write_text('test-only')
   os.environ.update(BEAR_SUPPORT_CONTROL_PORT=str(self.control),BEAR_SUPPORT_TOKEN=str(token),BEAR_SUPPORT_FRAMES=d)
   try:
    import importlib,support_cli;importlib.reload(support_cli)
    out=io.StringIO()
    with contextlib.redirect_stdout(out):support_cli.main(['screenshot'])
    meta=json.loads(out.getvalue());saved=Path(meta['path'])
    self.assertEqual(saved.read_bytes(),JPEG);self.assertEqual(saved.stat().st_mode&0o777,0o600);self.assertEqual((Path(d)/'last-frame.json').stat().st_mode&0o777,0o600)
    self.assertEqual(json.loads((Path(d)/'last-frame.json').read_text())['hash'],'a'*64);self.assertIsNotNone(meta['age'])
   finally:
    for k in ('BEAR_SUPPORT_CONTROL_PORT','BEAR_SUPPORT_TOKEN','BEAR_SUPPORT_FRAMES'):os.environ.pop(k,None)
 def test_frame_size_limit(self):
  p=self.store.begin();self.store.approve(p['code'])
  body=json.dumps({'id':p['id'],'key':p['key'],'frame':frame()}).encode()+b' '*1000001
  try:status=self.post(self.relay,'/frame',body)[0]
  except (ConnectionError,urllib.error.URLError):status=409   # server may close before reading an oversize body
  self.assertEqual(status,409)
if __name__=='__main__':unittest.main()
