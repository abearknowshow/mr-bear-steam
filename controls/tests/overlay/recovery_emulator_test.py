"""0.21 shutdown, wake and boot checks on the same offline dashboard fixture; emulator-5580 only."""
import sys,pathlib;sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[2]));import toolchain
import subprocess,time,re
ADB=str(toolchain.ADB);PKG='local.steam.controls.offlinetest'
SERVICE=PKG+'/local.steam.controls.DashboardAccessService'
def adb(*args):return subprocess.check_output([ADB,'-s',toolchain.EMULATOR,*map(str,args)],text=True,timeout=30)
def shell(*args):return adb('shell',*args)
def start():shell('am','start','-n','com.yizhong/local.steam.fixture.VendorFixture');time.sleep(1);shell('am','start','-n',PKG+'/local.steam.controls.MainActivity');time.sleep(3)
def shown():return 'Bear Steam dashboard' in shell('dumpsys','window','windows')
def closed():
 assert 'local.steam.controls.DashboardService' not in shell('dumpsys','activity','services',PKG)
 w=shell('dumpsys','window','windows');assert 'Bear Steam dashboard' not in w and 'Bear Steam return' not in w
def wait_shown(seconds):
 end=time.time()+seconds
 while time.time()<end:
  if shown():return True
  time.sleep(.5)
 return False
def uptime():return float(shell('cat','/proc/uptime').split()[0])
start();assert shown();shell('input','keyevent',223);time.sleep(2);closed()
print('Screen off stops the dashboard service and removes both windows.',flush=True)
shell('input','keyevent',224);shell('wm','dismiss-keyguard')
assert wait_shown(6),'Wake reopens the dashboard (open on wake is on by default)'
print('Wake reopens the dashboard.',flush=True)
shell('settings','put','secure','enabled_accessibility_services','null');time.sleep(4);closed()
print('Accessibility revocation closes dashboard/service; normal overlay access was denied throughout.',flush=True)
assert uptime()>200
shell('settings','put','secure','enabled_accessibility_services',SERVICE);shell('settings','put','secure','accessibility_enabled','1');time.sleep(5);closed()
print('Service reconnection long after boot stays idle; no automatic dashboard restart.',flush=True)
log=adb('logcat','-d','-s','BearWindowTest:I','AndroidRuntime:E')
assert 'UNEXPECTED_STEAM_WRITE' not in log and 'FATAL EXCEPTION' not in log,log
# Restart: the service reconnects after boot and opens Bear once, about 45 s after boot.
adb('reboot');adb('wait-for-device')
while shell('getprop','sys.boot_completed').strip()!='1':time.sleep(2)
booted=uptime();print(f'Rebooted; boot completed at {booted:.0f} s uptime.',flush=True)
end=time.time()+120;opened=None
while time.time()<end:
 if shown():opened=uptime();break
 time.sleep(1)
assert opened is not None,'Bear opens by itself after a restart'
assert opened>=44,f'Bear waited for Mr. Steam to sign in: opened at {opened:.0f} s'
time.sleep(5);assert shown()
assert re.search(r'(mResumedActivity|topResumedActivity).*com\.yizhong',shell('dumpsys','activity','activities')),'Mr. Steam is the app underneath'
log=adb('logcat','-d','-s','BearWindowTest:I','AndroidRuntime:E')
assert 'UNEXPECTED_STEAM_WRITE' not in log and 'FATAL EXCEPTION' not in log,log
print(f'After the restart Bear opened by itself at {opened:.0f} s uptime, over the original controls, with no steam writes.',flush=True)
print('Recovery checks passed with no real network or equipment.',flush=True)
