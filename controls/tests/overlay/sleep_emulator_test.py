"""0.23: with steam confirmed off, an idle Bear turns the screen off after five minutes without a touch; emulator-5580 only.
Needs the offline fixture installed with the accessibility service on (run_emulator_test.py leaves it so).
The panel's own screen timeout never fired, so the test sets Android's timeout to 30 minutes: only Bear can turn it off."""
import sys,pathlib;sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[2]));import toolchain
import subprocess,time
ADB=str(toolchain.ADB);PKG='local.steam.controls.offlinetest'
LIGHTS_TILE=(600,480);BACK=(56,80)
def adb(*args):return subprocess.check_output([ADB,'-s',toolchain.EMULATOR,*map(str,args)],timeout=30).decode()
def shell(*args):return adb('shell',*args)
def shown():return 'Bear Steam dashboard' in shell('dumpsys','window','windows')
def awake():return 'mWakefulness=Awake' in shell('dumpsys','power')
def wait(check,seconds):
 end=time.time()+seconds
 while time.time()<end:
  if check():return True
  time.sleep(.5)
 return False
def uptime():return float(shell('cat','/proc/uptime').split()[0])
def tap(xy,wait=1.2):shell('input','tap',*xy);time.sleep(wait)
def log():return adb('logcat','-d','-s','BearWindowTest:I','AndroidRuntime:E')
def sleep_until(t):time.sleep(max(0,t-time.time()))

while uptime()<200:time.sleep(5)
timeout=shell('settings','get','system','screen_off_timeout').strip()
try:
 shell('settings','put','system','screen_off_timeout',1_800_000);shell('svc','power','stayon','false')
 shell('input','keyevent',224);time.sleep(1);adb('logcat','-c')
 shell('am','start','-n','com.yizhong/local.steam.fixture.VendorFixture');time.sleep(1)
 shell('am','start','-n',PKG+'/local.steam.controls.MainActivity');assert wait(shown,8),'Bear opens'
 # Bear may already be open (an earlier test's restart opens it), and opening an open Bear keeps its timer: start it with a touch.
 tap(LIGHTS_TILE);tap(BACK);opened=time.time()
 # A touch three minutes in restarts the five minutes: still awake at 5.5 minutes after opening.
 sleep_until(opened+180);tap(LIGHTS_TILE);tap(BACK);touched=time.time()
 sleep_until(opened+330);assert awake() and shown(),'A touch three minutes in keeps the screen on past five minutes'
 print('Awake 5.5 minutes after opening, because of the touch at 3 minutes.',flush=True)
 sleep_until(touched+290);assert awake() and shown(),'Still awake just under five minutes after the last touch'
 assert wait(lambda:not awake(),30),'Bear turns the screen off about five minutes after the last touch'
 idle=time.time()-touched;assert 295<=idle<=325,idle
 assert wait(lambda:not shown(),5),'Bear closes when the screen goes off'
 print(f'Screen off {idle:.0f} s after the last touch, steam off; Bear closed.',flush=True)
 # Wake: Bear opens again, as with the power button.
 shell('input','keyevent',224);assert wait(shown,8),'Wake reopens Bear'
 print('Wake reopens Bear.',flush=True)
 l=log();assert 'UNEXPECTED_STEAM_WRITE' not in l and 'FAKE_LIGHT_COMMAND' not in l and 'FATAL EXCEPTION' not in l,l
 print('Idle sleep checks passed on Android 10 with fake equipment only; no steam or light command.',flush=True)
finally:
 shell('settings','put','system','screen_off_timeout',timeout if timeout.isdigit() else 60_000)
