"""End to end on an isolated task emulator: real Transport and TLS pinning -> loopback test relay -> support_cli.

Never touches the panel or the running production relay: the test relay listens on 127.0.0.1:18778/18779 with a throwaway
certificate and its own token under build/loopback, and the emulator reaches it through adb reverse (removed at the end)."""
import sys,pathlib;sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[2]/'controls'));import toolchain
from pathlib import Path
import subprocess,time,os,sys,json,re,secrets,io,contextlib,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1];LOOP=ROOT/'build/loopback';sys.path.insert(0,str(ROOT))
serial=os.environ.get('BEAR_SUPPORT_EMULATOR','emulator-5576');assert serial.startswith('emulator-'),'Emulator only'
ADB=[str(toolchain.ADB),'-s',serial]
def adb(*a,timeout=60):return subprocess.check_output(ADB+list(map(str,a)),text=True,timeout=timeout)
token=LOOP/'control-token';secret=secrets.token_hex(32);token.write_text(secret);token.chmod(0o600)
os.environ.update(BEAR_SUPPORT_CONTROL_PORT='18779',BEAR_SUPPORT_TOKEN=str(token),BEAR_SUPPORT_FRAMES=str(LOOP/'frames'))
import relay,support_cli
def cli(*args):
 out=io.StringIO()
 with contextlib.redirect_stdout(out):support_cli.main(list(map(str,args)))
 return json.loads(out.getvalue())
def jpeg_size(data):
 i=2
 while i<len(data):
  marker,length=data[i+1],int.from_bytes(data[i+2:i+4],'big')
  if 0xc0<=marker<=0xc3:return int.from_bytes(data[i+7:i+9],'big'),int.from_bytes(data[i+5:i+7],'big')
  i+=2+length
def ui_tap(label):
 for _ in range(20):
  adb('shell','uiautomator','dump','/sdcard/ui.xml');tree=ET.fromstring(adb('shell','cat','/sdcard/ui.xml'))
  hit=[n for n in tree.iter('node') if n.get('text','').lower()==label.lower()]
  if hit:x1,y1,x2,y2=map(int,re.findall(r'\d+',hit[0].get('bounds')));adb('shell','input','tap',(x1+x2)//2,(y1+y2)//2);return
  time.sleep(.5)
 raise AssertionError('Emulator UI never showed '+label)
def node(label):
 s=support_cli.request('/session');return next((n for n in (s.get('screen') or {}).get('nodes',[]) if n['text']==label),None)
def wait(check,why,limit=12):
 end=time.monotonic()+limit
 while time.monotonic()<end:
  v=check()
  if v:return v
  time.sleep(.3)
 raise AssertionError(why)
def operate(op,label,extra=lambda b:[]):
 """Operator loop: screenshot, read the target from the tree, act on it. A relay refusal queues nothing, so look again."""
 for _ in range(6):
  shot=cli('screenshot');n=node(label)
  if not n:time.sleep(.5);continue
  b=n['bounds'];args=[(b[0]+b[2])//2,(b[1]+b[3])//2]+extra(b)
  try:return shot,cli(op,*args)
  except SystemExit as e:print('  relay refused, looking again:',str(e)[:80]);time.sleep(.6)
 raise AssertionError(f'{op} {label} never accepted')
store=relay.SupportStore();servers=relay.serve(store,secret,[('relay','127.0.0.1',18778),('control','127.0.0.1',18779)],LOOP/'tls/relay.crt',LOOP/'tls/relay.key',panel='127.0.0.1')
results=[]
try:
 adb('reverse','tcp:18778','tcp:18778')
 adb('shell','settings','put','secure','enabled_accessibility_services','null');time.sleep(1)
 # Fresh install: upgrading in place from the no-INTERNET harness build leaves the process without network access.
 subprocess.run(ADB+['uninstall','local.steam.support'],capture_output=True)
 for apk in (ROOT/'build/offline/vendor/signed.apk',LOOP/'support/signed.apk'):print(adb('install','-r',apk).strip().splitlines()[-1])
 adb('shell','am','force-stop','com.yizhong');adb('shell','input','keyevent','224');adb('shell','wm','dismiss-keyguard')
 adb('shell','settings','put','secure','enabled_accessibility_services','local.steam.support/.SupportService');time.sleep(3)
 adb('shell','monkey','-p','com.yizhong','-c','android.intent.category.LAUNCHER','1');time.sleep(1.5)
 # Owner steps, simulated with adb inside the emulator only: Start support, then Android's Start now.
 adb('shell','am','start','-n','local.steam.support/.MainActivity');ui_tap('Start support · 2 hours');ui_tap('Start now')  # Android 10 prompt, as on the panel
 code=wait(lambda:store.status().get('code'),'Panel never reached the loopback relay');print('Pairing reached the loopback relay over TLS; approving the test relay\'s own code')
 cli('approve',code)
 blank=wait(lambda:store.status().get('frame') and store.status()['frame']['blank'],'No frame arrived')
 assert blank=='password field visible',blank;shot=cli('screenshot');assert shot['path'] is None and shot['bytes']==0;results.append('blank frame with reason while a password field is visible: no pixels uploaded or saved')
 operate('tap','HIDE SECRET');wait(lambda:node('[password]') is None,'Password field never hid')
 shot=wait(lambda:(lambda s:s if s['path'] else None)(cli('screenshot')),'No frame with pixels')
 data=Path(shot['path']).read_bytes();size=jpeg_size(data);assert data[:2]==b'\xff\xd8' and size==(shot['width'],shot['height']) and shot['width']<=960,(size,shot)
 assert Path(shot['path']).stat().st_mode&0o777==0o600;results.append(f"frame {shot['width']}x{shot['height']} JPEG ({shot['bytes']} bytes) of a {shot['screenWidth']}x{shot['screenHeight']} screen, age {shot['age']} s, saved 0600")
 evidence=LOOP/'evidence';evidence.mkdir(exist_ok=True);(evidence/'frame-before-tap.jpg').write_bytes(data)
 shot,last=operate('tap','LIGHT ON');assert last['status']=='dispatched',last
 on=wait(lambda:node('Physical fixture: ON'),'Coordinate tap did not land on LIGHT ON')
 after=wait(lambda:(lambda s:s if s['path'] and s['capturedAt']>last['dispatchedAt'] and s['hash']==support_cli.request('/session')['screen']['hash'] else None)(cli('screenshot')),'No frame after the tap')
 (evidence/'frame-after-tap.jpg').write_bytes(Path(after['path']).read_bytes())
 results.append(f"coordinate tap landed on LIGHT ON: queued->delivered {last['deliveredAfter']} s, queued->reported {last['reportedAfter']} s, panel dispatch->first frame showing ON {after['capturedAt']-last['dispatchedAt']} ms (same panel clock)")
 shot,last=operate('long_press','LIGHT ON');assert last['status']=='dispatched',last;wait(lambda:node('Physical fixture: HELD'),'Long press did not land');results.append(f"long press landed (reported after {last['reportedAfter']} s)")
 shot,last=operate('swipe','Swipe pad',lambda b:[b[2]-20,(b[1]+b[3])//2,300]);assert last['status']=='dispatched',last;wait(lambda:node('Physical fixture: SWIPED'),'Swipe did not land');results.append(f"swipe landed (reported after {last['reportedAfter']} s)")
 # Stale frame: the relay refuses a tap when the latest frame is older than 4 s (simulated by freezing the relay's view of the frame).
 cli('screenshot');n=node('LIGHT OFF');b=n['bounds']
 with store.lock:store.item['frameAt']-=10
 try:cli('tap',(b[0]+b[2])//2,(b[1]+b[3])//2);raise AssertionError('Stale frame accepted')
 except SystemExit:results.append('stale frame refused at the relay')
 time.sleep(3.5)
 queued=support_cli.request('/action',{'op':'stop','hash':cli('screenshot')['hash']})
 wait(lambda:store.status()=={'state':'ended'},'Panel did not end the session on stop')
 wait(lambda:'CaptureService' not in adb('shell','dumpsys','activity','services','local.steam.support'),'Capture service still running after stop')
 results.append('remote stop: panel ended the session, relay cleared, capture foreground service stopped')
 print('PASS loopback:\n- '+'\n- '.join(results))
finally:
 subprocess.run(ADB+['reverse','--remove','tcp:18778'],capture_output=True)
 for s in servers:s.shutdown();s.server_close()
 token.unlink(missing_ok=True)
