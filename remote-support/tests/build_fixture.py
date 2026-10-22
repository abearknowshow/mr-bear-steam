from pathlib import Path
import sys,subprocess
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from build import build,run,JDK
import household
OUT=ROOT/'build/offline';OUT.mkdir(parents=True,exist_ok=True);key=ROOT.parent/'controls/build/overlay-test/test-only.p12'
SRC=ROOT/'src/local/steam/support';LOOPBACK_PORT=18778
base=(ROOT/'AndroidManifest.xml').read_text().replace('android:debuggable="false"','android:debuggable="true"')
manifest=base.replace('<uses-permission android:name="android.permission.INTERNET"/>','').replace('</manifest>','<instrumentation android:name="local.steam.support.SupportHarness" android:targetPackage="local.steam.support"/></manifest>')
# Offline stub: approves at once, never contacts a server, and keeps the last uploaded frame for the harness.
stub=OUT/'Transport.java';stub.write_text('''package local.steam.support;import android.content.Context;import org.json.*;final class Transport{static volatile JSONObject frame;static volatile int frames;Transport(Context c){}JSONObject send(String p,JSONObject b,int t)throws Exception{return send(p,b);}JSONObject send(String p,JSONObject b)throws Exception{if(p.equals("/begin"))return new JSONObject().put("id","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").put("key","bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb").put("code","123456");if(p.equals("/frame")){frame=b.getJSONObject("frame");frames++;return new JSONObject().put("approved",true);}return new JSONObject().put("approved",true).put("command",JSONObject.NULL);}}''')
service=OUT/'SupportService.java';service.write_text((SRC/'SupportService.java').read_text().replace('"local.steam.controls"','"local.steam.controls.offlinetest"'))
shared=[SRC/'MainActivity.java',SRC/'CaptureService.java',SRC/'ScreenFrames.java']
# Made-up household values; the loopback build points them at the test relay.
example=household.java(household.load(example=True),'local.steam.support',OUT/'generated')
build(OUT/'support',manifest,[example,service,stub,*shared,ROOT/'tests/SupportHarness.java'],key,'fixture-only','test')
vendor='<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.yizhong"><uses-sdk android:minSdkVersion="29" android:targetSdkVersion="29"/><application android:theme="@android:style/Theme.Material.NoActionBar"><activity android:name="local.steam.fixture.SupportVendor" android:exported="true" android:screenOrientation="landscape"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>'
build(OUT/'vendor',vendor,[ROOT/'tests/SupportVendor.java'],key,'fixture-only','test')
# Loopback build: the real Transport and TLS pinning, pointed at a test relay reached through adb reverse, with a throwaway certificate.
LOOP=ROOT/'build/loopback';tls=LOOP/'tls';assets=LOOP/'assets';tls.mkdir(parents=True,exist_ok=True);assets.mkdir(exist_ok=True);tls.chmod(0o700)
if not (tls/'relay.key').exists():subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','3','-subj','/CN=127.0.0.1','-addext','subjectAltName=IP:127.0.0.1','-keyout',tls/'relay.key','-out',tls/'relay.crt'],check=True,capture_output=True)
(assets/'setup-server.crt').write_bytes((tls/'relay.crt').read_bytes())
# Test build only: log transport failures (production swallows them by design).
loop_household=household.java(household.load(example=True),'local.steam.support',LOOP/'generated')
loop_household.write_text(loop_household.read_text().replace(household.load(example=True)['mac_host'],'127.0.0.1').replace('RELAY_PORT=8778',f'RELAY_PORT={LOOPBACK_PORT}'))
assert f'RELAY_PORT={LOOPBACK_PORT}' in loop_household.read_text()
transport=LOOP/'Transport.java';transport.write_text((SRC/'Transport.java').read_text()
 .replace('JSONObject send(String path,JSONObject data,int timeout)throws Exception{','JSONObject send(String path,JSONObject data,int timeout)throws Exception{try{return send0(path,data,timeout);}catch(Exception e){android.util.Log.w("BearSupportLoopback",path+" failed",e);throw e;}}\n JSONObject send0(String path,JSONObject data,int timeout)throws Exception{'))
build(LOOP/'support',base,[loop_household,service,transport,*shared],key,'fixture-only','test',assets=assets)
print('Offline fixtures built. Harness build: INTERNET omitted, transport replaced. Loopback build: real transport to 127.0.0.1:%d with a throwaway certificate.'%LOOPBACK_PORT)
