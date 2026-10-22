"""0.21: exercise the actual dashboard/window classes against offline stubs on emulator-5580 only.
Android 10 at 1024x600 (avd bear-support-02-api29). Taps inside the page use screen coordinates measured
import sys,pathlib;sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[2]));import toolchain
from screenshots of this fixture (WebView nodes are not exposed to the dump harness)."""
from pathlib import Path
import subprocess,time,re,json,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'build/overlay-test'
ADB=str(toolchain.ADB);TARGET=toolchain.EMULATOR;PKG='local.steam.controls.offlinetest'
# Page coordinates on this fixture (the status bar shifts the page down 24 px).
LIGHT_ON=(672,370);LIGHT_OFF=(736,370);SETTINGS=(910,450);BACK=(56,80);RETURN_ROW=(180,510);WAKE_SWITCH=(614,300)
def adb(*args):return subprocess.check_output([ADB,'-s',TARGET,*map(str,args)],text=True,timeout=25)
def shell(*args):return adb('shell',*args)
def page_tap(xy,wait=1.0):shell('input','tap',*xy);time.sleep(wait)
def shot(name):(OUT/(name+'.png')).write_bytes(subprocess.check_output([ADB,'-s',TARGET,'exec-out','screencap','-p'],timeout=25))
def tree():
 output=shell('am','instrument','-w','local.steam.window.harness/local.steam.fixture.DumpWindows')
 found=re.search(r'INSTRUMENTATION_RESULT: tree=(<hierarchy>.*?</hierarchy>)',output,re.S)
 assert found,output
 return ET.fromstring(found.group(1))
def tap(label,hold=False):
 nodes=[n for n in tree().iter('node') if label.casefold()==n.get('text','').casefold() or label==n.get('content-desc')]
 assert nodes,'Missing UI: '+label
 nums=list(map(int,re.findall(r'\d+',nodes[0].get('bounds'))));x=(nums[0]+nums[2])//2;y=(nums[1]+nums[3])//2
 if hold:shell('input','swipe',x,y,x,y,1000)
 else:shell('input','tap',x,y)
 time.sleep(.8)
def resumed():
 tasks=shell('dumpsys','activity','activities')
 assert re.search(r'(mResumedActivity|topResumedActivity).*com\.yizhong',tasks),tasks[-3000:]
def logs():return adb('logcat','-d','-s','BearWindowTest:I','AndroidRuntime:E')
def stable(log,allow_resume=False):
 new=logs()[len(log):]
 # A launcher intent may resume the existing singleTask controller (onNewIntent: a brief pause/resume).
 # Never allow recreation, an unmatched pause or a steam write.
 for word in ('VENDOR_CREATE','VENDOR_DESTROY','UNEXPECTED_STEAM_WRITE','FATAL EXCEPTION'):
  assert word not in new,new
 if allow_resume:
  assert new.count('VENDOR_PAUSE')==new.count('VENDOR_RESUME'),new
  assert new.count('VENDOR_PAUSE')<=new.count('VENDOR_NEW_INTENT'),new
 else:assert 'VENDOR_PAUSE' not in new,new
 resumed()
def same_original(log):
 # Screen off and on pause and resume Mr. Steam by themselves; what must hold is the same instance,
 # no steam write, no crash, and Mr. Steam still the resumed activity.
 new=logs()[len(log):]
 for word in ('VENDOR_CREATE','VENDOR_DESTROY','UNEXPECTED_STEAM_WRITE','FATAL EXCEPTION'):
  assert word not in new,new
 resumed()
def report():return json.loads(shell('run-as',PKG,'cat','files/test-report.json'))
def windows():return shell('dumpsys','window','windows')
def dashboard_shown():return 'Bear Steam dashboard' in windows()
def wait_dashboard(seconds):
 end=time.time()+seconds
 while time.time()<end:
  if dashboard_shown():return True
  time.sleep(.15)
 return False
def light_commands():return logs().count('FAKE_LIGHT_COMMAND')
def keep_screen_on():
 w=windows();i=w.find('Bear Steam dashboard');assert i>=0
 return 'KEEP_SCREEN_ON' in w[i:i+1500].split('Window #')[0]
def uptime():return float(shell('cat','/proc/uptime').split()[0])

for name in ('vendor','dashboard','harness'):print(adb('install','-r',OUT/(name+'.apk')).strip(),flush=True)
# A service connect within 3 minutes of boot is treated as a restart (one automatic open). Test from a settled emulator.
while uptime()<200:time.sleep(5)
shell('settings','put','secure','enabled_accessibility_services','null');time.sleep(1)
shell('am','force-stop',PKG);shell('am','force-stop','com.yizhong')
shell('appops','set',PKG,'SYSTEM_ALERT_WINDOW','deny')
shell('am','start','-n','com.yizhong/local.steam.fixture.VendorFixture');time.sleep(1.5)
shell('am','start','-f','0x10008000','-n',PKG+'/local.steam.controls.MainActivity');time.sleep(1.5)
assert any(n.get('text')=='Open Accessibility settings' for n in tree().iter('node')),'Setup must offer the supported permission path when ordinary overlays are denied'
print('Ordinary overlays denied: supported accessibility setup shown.',flush=True)
# Enable from Mr. Steam's screen, as the owner does in Settings: the setup screen itself opens Bear once the service connects.
shell('am','start','-n','com.yizhong/local.steam.fixture.VendorFixture');time.sleep(1.5)
shell('settings','put','secure','enabled_accessibility_services',PKG+'/local.steam.controls.DashboardAccessService');shell('settings','put','secure','accessibility_enabled','1');time.sleep(3)
assert 'local.steam.controls.DashboardService' not in shell('dumpsys','activity','services',PKG)
state=shell('dumpsys','accessibility');(OUT/'accessibility-capabilities.txt').write_text(state)
assert 'local.steam.controls.DashboardAccessService' in state,state
assert 'capabilities=0, eventTypes=,' in state,state
print('Service enabled long after boot: no automatic open; still no events or capabilities.',flush=True)
# Clearing the log sometimes fails on the emulator; every check compares against this snapshot instead.
try:adb('logcat','-c')
except subprocess.CalledProcessError:pass
shell('am','start','-n',PKG+'/local.steam.controls.MainActivity');time.sleep(5)
resumed();baseline=logs();assert 'FATAL EXCEPTION' not in baseline,baseline
assert 'ORDINARY_OVERLAY_ALLOWED false' in baseline,baseline
assert dashboard_shown() and keep_screen_on(),'Dashboard shown and keeping the screen on after a touch-driven open'
shot('0.21-home')
# Unlocked on arrival: no test-controls bar, the light On button works at once.
assert not any('Test controls' in n.get('text','') for n in tree().iter('node')),'No test-controls bar'
page_tap(LIGHT_ON,2);assert report()['on'] is True,report();stable(baseline)
assert 'FAKE_LIGHT_COMMAND true' in logs()
print('Dashboard opens unlocked: light On reaches the fake boundary with no enable step.',flush=True)
page_tap(SETTINGS);shot('0.21-settings');page_tap(RETURN_ROW,1.5);stable(baseline,allow_resume=True)
assert not dashboard_shown() and any(n.get('text')=='FIXTURE LIGHT ON' for n in tree().iter('node')),'Return to Mr. Steam shows the original controls'
tap('FIXTURE LIGHT OFF');time.sleep(1);assert report()['on'] is False,report()
tap('Return to Bear Steam');stable(baseline,allow_resume=True);time.sleep(1.5)
assert dashboard_shown() and report()['on'] is False,report()
before=light_commands();page_tap(LIGHT_ON,2);assert light_commands()==before+1 and report()['on'] is True,'Still unlocked after returning'
print('Return to Mr. Steam and back: same original instance, external Off read back, no relock.',flush=True)
# Screen off closes Bear; wake reopens it over the same Mr. Steam, guarded for 1.5 s except Stop.
shell('input','keyevent',223);time.sleep(2);assert not dashboard_shown()
before=light_commands();woke=time.time();shell('input','keyevent',224)
assert wait_dashboard(6),'Wake reopens the dashboard'
shown_after=time.time()-woke;shell('input','tap',*LIGHT_OFF);tapped_after=time.time()-woke
# A second tap once the page has normally loaded, still inside the 1.5 s guard (counted from the open).
while time.time()-woke<1.1:time.sleep(.05)
shell('input','tap',*LIGHT_OFF);late_tap=time.time()-woke
time.sleep(1.5);assert light_commands()==before,'Taps right after an automatic open send nothing'
time.sleep(1.5);page_tap(LIGHT_OFF,2)
assert light_commands()==before+1 and report()['on'] is False,'After the guard the same tap works'
same_original(baseline);assert keep_screen_on()
print(f'Wake reopens Bear in {shown_after:.1f} s; taps {tapped_after:.1f} s and {late_tap:.1f} s after wake were ignored; 3 s later the same tap worked. No steam writes.',flush=True)
# The setting off stops reopening; back on restores it.
page_tap(SETTINGS);page_tap(WAKE_SWITCH);shot('0.21-wake-off');page_tap(BACK)
shell('input','keyevent',223);time.sleep(2);shell('input','keyevent',224)
assert not wait_dashboard(5),'Open on wake off: waking leaves Mr. Steam alone'
resumed();print('Open on wake off: wake leaves Mr. Steam in front.',flush=True)
shell('am','start','-n',PKG+'/local.steam.controls.MainActivity');time.sleep(4);assert dashboard_shown()
page_tap(SETTINGS);page_tap(WAKE_SWITCH);page_tap(BACK)
shell('input','keyevent',223);time.sleep(2);shell('input','keyevent',224)
assert wait_dashboard(6),'Back on: wake opens again';print('Open on wake back on: wake opens again.',flush=True)
time.sleep(2)
page_tap(SETTINGS);page_tap(RETURN_ROW,1.5);tap('Return to Bear Steam',hold=True);same_original(baseline)
w=windows();assert 'Bear Steam dashboard' not in w and 'Bear Steam return' not in w
assert 'FATAL EXCEPTION' not in logs() and 'UNEXPECTED_STEAM_WRITE' not in logs(),logs()
print('Long-press closes dashboard and tab; original remains resumed.',flush=True)
(OUT/'lifecycle.log').write_text(logs())
print('Overlay emulator checks passed on Android 10. Both APKs lack INTERNET; all equipment is fake.',flush=True)
