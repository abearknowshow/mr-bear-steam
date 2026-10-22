"""Regression for Settings remaining beneath Bear. Offline emulator-5580 only."""
import sys,pathlib;sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[2]));import toolchain
from pathlib import Path
import subprocess,time,re,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'build/overlay-test'
ADB=str(toolchain.ADB);PKG='local.steam.controls.offlinetest'
def adb(*args):return subprocess.check_output([ADB,'-s',toolchain.EMULATOR,*map(str,args)],text=True,timeout=30)
def shell(*args):return adb('shell',*args)
def tree():
 output=shell('am','instrument','-w','local.steam.window.harness/local.steam.fixture.DumpWindows')
 found=re.search(r'INSTRUMENTATION_RESULT: tree=(<hierarchy>.*?</hierarchy>)',output,re.S)
 assert found,output
 return ET.fromstring(found.group(1))
def tap(label):
 nodes=[n for n in tree().iter('node') if label==n.get('text') or label==n.get('content-desc')]
 assert len(nodes)==1,('Missing or ambiguous control',label)
 b=list(map(int,re.findall(r'\d+',nodes[0].get('bounds'))))
 shell('input','tap',(b[0]+b[2])//2,(b[1]+b[3])//2);time.sleep(1)
def resumed(package):
 state=shell('dumpsys','activity','activities')
 top=[l for l in state.splitlines() if 'topResumedActivity' in l or 'mResumedActivity' in l]
 assert any(package in l for l in top), 'Expected '+package+' resumed; found '+str(top)
for boot in range(45):
 if shell('getprop','sys.boot_completed').strip()=='1':break
 time.sleep(1)
else:raise AssertionError('Task emulator did not finish booting')
for name in ('vendor','dashboard','harness'):
 print(adb('install','--no-incremental','-r',OUT/(name+'.apk')).strip(),flush=True)
shell('input','keyevent',224);shell('wm','dismiss-keyguard');shell('svc','power','stayon','true')
shell('am','force-stop',PKG);shell('am','force-stop','com.yizhong')
shell('settings','put','secure','enabled_accessibility_services',PKG+'/local.steam.controls.DashboardAccessService')
shell('settings','put','secure','accessibility_enabled','1');time.sleep(2)
adb('logcat','-c')
shell('am','start','-n',PKG+'/local.steam.controls.MainActivity');time.sleep(3);resumed('com.yizhong')
# This reproduces starting/finishing support via the owner's Android settings screen.
shell('am','start','-a','android.settings.SETTINGS');time.sleep(1);resumed('com.android.settings')
tap('Mr. Steam ↗');resumed('com.yizhong')
print('Native original return resumes Mr. Steam even when Settings was underneath.',flush=True)
shell('am','start','-a','android.settings.SETTINGS');time.sleep(1);resumed('com.android.settings')
tap('Return to Bear Steam');resumed('com.yizhong')
assert any(n.get('text','').startswith('Test controls · locked ·') for n in tree().iter('node'))
print('Return tab restores original foreground before showing Bear locked.',flush=True)
log=adb('logcat','-d','-s','BearWindowTest:I','AndroidRuntime:E')
assert log.count('VENDOR_CREATE')==1,log
assert 'VENDOR_DESTROY' not in log and 'UNEXPECTED_STEAM_WRITE' not in log and 'FAKE_LIGHT_COMMAND' not in log and 'FATAL EXCEPTION' not in log,log
(OUT/'navigation-lifecycle.log').write_text(log)
print('PASS: original instance preserved, controls locked, zero equipment commands; no INTERNET in fixtures.',flush=True)
