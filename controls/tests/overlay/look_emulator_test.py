"""0.22: the light color chosen on Bear survives sleep/wake and a restart; emulator-5580 only.
Needs the offline fixture installed with the accessibility service on (run_emulator_test.py leaves it so).
The light reports only on or off, so the color shown after a wake comes from the panel's own settings."""
import sys,pathlib;sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[2]));import toolchain
from pathlib import Path
import subprocess,time,re,io
from PIL import Image
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'build/overlay-test'
ADB=str(toolchain.ADB);PKG='local.steam.controls.offlinetest'
# Screen coordinates on this fixture (1024x600), from screenshots.
LIGHTS_TILE=(600,480);HOME_DOT=(440,480);BLUE=(197,412);AMBER=(298,287);BACK=(56,80)
def adb(*args):return subprocess.check_output([ADB,'-s',toolchain.EMULATOR,*map(str,args)],timeout=30)
def shell(*args):return adb('shell',*args).decode()
def shown():return 'Bear Steam dashboard' in shell('dumpsys','window','windows')
def wait_shown(seconds):
 end=time.time()+seconds
 while time.time()<end:
  if shown():return True
  time.sleep(.3)
 return False
def frame(name):
 png=adb('exec-out','screencap','-p');(OUT/(name+'.png')).write_bytes(png);return Image.open(io.BytesIO(png)).convert('RGB')
def dot(name):
 r,g,b=frame(name).getpixel(HOME_DOT);return r,g,b
def blue(c):return c[2]>c[0]+60
def amber(c):return c[0]>c[2]+60
def prefs():return shell('run-as',PKG,'cat','shared_prefs/bear-panel.xml')
def light_commands():return adb('logcat','-d','-s','BearWindowTest:I').decode().count('FAKE_LIGHT_COMMAND')
def uptime():return float(shell('cat','/proc/uptime').split()[0])
def tap(xy,wait=1.2):shell('input','tap',*xy);time.sleep(wait)

while uptime()<200:time.sleep(5)
# Since 0.23 an idle Bear turns the screen off after 5 minutes; start every run awake.
shell('input','keyevent',224);time.sleep(1)
shell('am','start','-n','com.yizhong/local.steam.fixture.VendorFixture');time.sleep(1)
shell('am','start','-n',PKG+'/local.steam.controls.MainActivity');assert wait_shown(8),'Bear opens'
time.sleep(2)
# Start from Amber so the test proves a change, whatever an earlier run left behind.
tap(LIGHTS_TILE);tap(AMBER);tap(BACK)
assert amber(dot('0.22-look-amber')),'Amber chosen'
before=light_commands()
tap(LIGHTS_TILE);tap(BLUE);frame('0.22-look-blue-chosen');tap(BACK)
c=dot('0.22-look-blue-home');assert blue(c),c
p=prefs();assert re.search(r'name="lookHue" value="214"',p) and re.search(r'name="lookSaturation" value="70"',p),p
assert light_commands()==before,'Choosing a color with the light off sends nothing'
print(f'Blue chosen with the light off: stored on the panel, home dot {c}, no light command.',flush=True)
# Sleep and wake: a new page, the same color.
shell('input','keyevent',223);time.sleep(2);assert not shown()
shell('input','keyevent',224);assert wait_shown(6),'Wake reopens Bear';time.sleep(2)
c=dot('0.22-look-after-wake');assert blue(c),f'After a wake the home dot is Blue, not Amber: {c}'
assert light_commands()==before,'A wake sends no light command'
print(f'After sleep/wake Bear shows Blue (home dot {c}); no light command.',flush=True)
# Restart: Bear opens by itself about 45 s after boot, still Blue.
adb('reboot');adb('wait-for-device')
while shell('getprop','sys.boot_completed').strip()!='1':time.sleep(2)
assert wait_shown(120),'Bear opens by itself after a restart';time.sleep(3)
c=dot('0.22-look-after-restart');assert blue(c),f'After a restart the home dot is Blue: {c}'
log=adb('logcat','-d','-s','BearWindowTest:I','AndroidRuntime:E').decode()
assert 'FAKE_LIGHT_COMMAND' not in log and 'UNEXPECTED_STEAM_WRITE' not in log and 'FATAL EXCEPTION' not in log,log
print(f'After a restart Bear shows Blue (home dot {c}); no light or steam command since boot.',flush=True)
print('Remembered color checks passed on Android 10 with fake equipment only.',flush=True)
