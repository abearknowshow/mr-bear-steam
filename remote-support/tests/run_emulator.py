"""Offline harness on an isolated task emulator only (default emulator-5576; never the panel)."""
import sys,pathlib;sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[2]/'controls'));import toolchain
from pathlib import Path
import subprocess,time,os,re
root=Path(__file__).resolve().parents[1];serial=os.environ.get('BEAR_SUPPORT_EMULATOR','emulator-5576')
assert serial.startswith('emulator-'),'Emulator only'
adb=[str(toolchain.ADB),'-s',serial]
def run(*a,timeout=60):return subprocess.check_output(adb+list(map(str,a)),text=True,timeout=timeout)
bear=root.parent/'controls/build/overlay-test/dashboard.apk'
# A service still listed while am instrument force-stops the reinstalled package can stay stuck in 'Binding'; start from none.
run('shell','settings','put','secure','enabled_accessibility_services','null');time.sleep(1)
subprocess.run(adb+['uninstall','local.steam.support'],capture_output=True)
for apk in [root/'build/offline/vendor/signed.apk',root/'build/offline/support/signed.apk']+([bear] if bear.exists() else []):print(run('install','-r',apk).strip())
# Emulator only: start from the home screen with no Settings or permission screens left over from an earlier run.
for pkg in ('com.android.settings','com.android.permissioncontroller','com.google.android.permissioncontroller'):run('shell','am','force-stop',pkg)
run('shell','input','keyevent','3')
run('shell','am','force-stop','local.steam.controls.offlinetest');run('shell','am','force-stop','com.yizhong');run('shell','input','keyevent','224');run('shell','wm','dismiss-keyguard')
# Bear's live.js needs Chrome 80+ syntax; on an older system WebView the dashboard renders but its buttons are inert, so only its navigation steps are skipped.
web=re.search(r'versionName=(\d+)',run('shell','dumpsys','package','com.android.webview')+run('shell','dumpsys','package','com.google.android.webview'));webview=int(web.group(1)) if web else 0
print('System WebView major version:',webview)
p=subprocess.Popen(adb+['shell','am','instrument','-w','-e','bearWebView','true' if webview>=80 else 'false','local.steam.support/local.steam.support.SupportHarness'],stdout=subprocess.PIPE,text=True)
time.sleep(1);run('shell','settings','put','secure','enabled_accessibility_services','local.steam.controls.offlinetest/local.steam.controls.DashboardAccessService');time.sleep(1);run('shell','settings','put','secure','enabled_accessibility_services','local.steam.support/.SupportService:local.steam.controls.offlinetest/local.steam.controls.DashboardAccessService')
output=p.communicate(timeout=300)[0];(root/'build/emulator-test.log').write_text(output);print(output);assert 'INSTRUMENTATION_RESULT: result=PASS:' in output, 'Offline emulator test failed'
