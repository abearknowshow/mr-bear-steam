"""Build emulator-only APKs with the REAL dashboard classes and fake network boundaries.
Neither fixture requests INTERNET. No production config, credentials or vendor APK is used.
"""
from pathlib import Path
import os, subprocess, zipfile, base64, shutil, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
import household
from toolchain import JDK,TOOLS,ANDROID
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'build/overlay-test';OUT.mkdir(exist_ok=True)
ENV=dict(os.environ,JAVA_HOME=str(JDK));ENV['PATH']=str(JDK/'bin')+':'+ENV['PATH']
def run(*args):subprocess.run(list(map(str,args)),check=True,env=ENV,stdout=subprocess.DEVNULL)
key=OUT/'test-only.p12'
if not key.exists():run(JDK/'bin/keytool','-genkeypair','-keystore',key,'-storepass','fixture-only','-keypass','fixture-only','-alias','test','-dname','CN=Offline fixture','-keyalg','RSA','-validity','10')
stubs=OUT/'stubs';stubs.mkdir(exist_ok=True)
(stubs/'CloudLights.java').write_text('''package local.steam.controls;
import android.content.*;import org.json.*;
public final class CloudLights {
 public volatile boolean ready=true,requiresSignIn=false;public volatile LightReport report;public String reportIssue="Not checked";
 private Context context;private boolean available,registered;private Runnable changed;private ControlEngine.Guard guard;private final ReadMetrics metrics=new ReadMetrics();
 private final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){update(i.getBooleanExtra("on",false));}};
 public CloudLights(Context c){context=c;android.util.Log.i("BearWindowTest","ORDINARY_OVERLAY_ALLOWED "+android.provider.Settings.canDrawOverlays(c));}
 private void update(boolean on){if(guard==null||!guard.allowed())return;long now=System.currentTimeMillis()/1000;
  String doc="{\\"version\\":1,\\"timestamp\\":"+now+",\\"state\\":{\\"reported\\":{\\"devices\\":{\\"deviceLightStatus\\":"+on+",\\"deviceChromaConnected\\":true}}},\\"metadata\\":{\\"reported\\":{\\"devices\\":{\\"deviceLightStatus\\":{\\"timestamp\\":"+now+"},\\"deviceChromaConnected\\":{\\"timestamp\\":"+now+"}}}}}";
  report=LightReport.parse(doc,System.currentTimeMillis());metrics.observe(doc);metrics.accepted();if(changed!=null)changed.run();
 }
 public void restore(ControlEngine.Guard g){}public void refreshReport(ControlEngine.Guard g){}
 public void signIn(String a,String b,ControlEngine.Guard g){}
 public void startReader(ControlEngine.Guard g,Runnable r){guard=g;changed=r;available=true;reportIssue="Live light report received";
  if(!registered){context.registerReceiver(receiver,new IntentFilter("bear.test.report"),Context.RECEIVER_EXPORTED);registered=true;}update(false);
 }
 public void stopReader(){available=false;guard=null;if(registered){context.unregisterReceiver(receiver);registered=false;}}
 public void refreshLiveReport(){}
 public boolean readerAvailable(){return available;}public ReadMetrics.Snapshot readMetrics(){return metrics.snapshot();}
 public void send(boolean on,String color,int brightness,boolean disco,ControlEngine.Guard g){if(!g.allowed())throw new IllegalStateException();android.util.Log.i("BearWindowTest","FAKE_LIGHT_COMMAND "+on);update(on);}
}''')
(stubs/'BridgeTransport.java').write_text('package local.steam.controls;public final class BridgeTransport implements ControlEngine.Transport{private final UdpTransport fake;public BridgeTransport(boolean writes){fake=new UdpTransport("127.0.0.1",0,false);}public Protocol.Status read(){return fake.read();}public boolean write(String action,int value){return fake.write(action,value);}public void abandonReadsWhen(java.util.function.BooleanSupplier a){}public void refreshMapWhen(java.util.function.BooleanSupplier i){}}')
encoded=base64.b64encode((ROOT/'tests/status-fixture.bin').read_bytes()).decode()
(stubs/'UdpTransport.java').write_text('''package local.steam.controls;
public final class UdpTransport {public UdpTransport(String h,int p,boolean b){}public Protocol.Status read(){return Protocol.decode(android.util.Base64.decode("'''+encoded+'''",0));}
public boolean write(String action,int value){android.util.Log.e("BearWindowTest","UNEXPECTED_STEAM_WRITE "+action);throw new IllegalStateException("No hardware in test");}}''')
(stubs/'RemoteSignIn.java').write_text('''package local.steam.controls;
import android.content.Context;import org.json.*;
public final class RemoteSignIn {private Context context;public RemoteSignIn(Context c){context=c;}public JSONObject begin()throws Exception{throw new Exception("No login in offline test");}public JSONObject await(String i,ControlEngine.Guard g)throws Exception{throw new Exception();}public void finish(String i,String s){}
// 0.18: the timeline batch lands in a private file (no INTERNET in this fixture).
public int timeline(byte[] batch)throws Exception{try(java.io.OutputStream out=context.openFileOutput("timeline-batch.json",0)){out.write(batch);}return 200;}
public void syncResult(String stage,LightReport r,ReadMetrics.Snapshot m,String view,int attempt,String command)throws Exception{
 JSONObject j=new JSONObject().put("on",r==null?JSONObject.NULL:r.on).put("view",view).put("attempt",attempt).put("command",command);
 try(java.io.OutputStream out=context.openFileOutput("test-report.json",0)){out.write(j.toString().getBytes("UTF-8"));}
}}
''')
def build(name,manifest,sources,assets=None):
 out=OUT/name;out.mkdir(exist_ok=True)
 for folder in ('classes','dex'):
  p=out/folder
  if p.exists():shutil.rmtree(p)
  p.mkdir()
 (out/'AndroidManifest.xml').write_text(manifest)
 args=[TOOLS/'aapt','package','-f','-M',out/'AndroidManifest.xml','-I',ANDROID,'-F',out/'resources.apk']
 if assets:args+=['-A',assets,'-S',ROOT/'res']
 run(*args)
 gson=ROOT/'dependencies/gson-2.8.9.jar'
 run(JDK/'bin/javac','--release','8','-Xlint:-options','-cp',str(ANDROID)+':'+str(gson),'-d',out/'classes',*sources)
 run(TOOLS/'d8','--min-api','29','--lib',ANDROID,'--output',out/'dex',*sorted((out/'classes').rglob('*.class')),gson)
 shutil.copyfile(out/'resources.apk',out/'unsigned.apk')
 with zipfile.ZipFile(out/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
  for p in (out/'dex').glob('*.dex'):z.write(p,p.name)
 run(TOOLS/'zipalign','-f','4',out/'unsigned.apk',out/'aligned.apk')
 run(TOOLS/'apksigner','sign','--ks',key,'--ks-pass','pass:fixture-only','--key-pass','pass:fixture-only','--out',OUT/(name+'.apk'),out/'aligned.apk')
vendor='''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.yizhong"><uses-sdk android:minSdkVersion="29" android:targetSdkVersion="29"/><application android:theme="@android:style/Theme.Material.NoActionBar"><activity android:name="local.steam.fixture.VendorFixture" android:exported="true" android:launchMode="singleTask" android:screenOrientation="landscape"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>'''
build('vendor',vendor,[ROOT/'tests/overlay/VendorFixture.java'])
manifest=(ROOT/'AndroidManifest.xml').read_text().replace('package="local.steam.controls"','package="local.steam.controls.offlinetest"').replace('android:name=".','android:name="local.steam.controls.').replace('<uses-permission android:name="android.permission.INTERNET"/>','').replace('<application ','<application android:debuggable="true" ').replace(' android:debuggable="false"','')
names=('MainActivity','DashboardService','OverlayWindows','DashboardAccessService','ControlEngine','Protocol','LightPayload','LightReport','ReadWindow','ReadMetrics','StatusGrace','ReaderRetry','Timeline','PanelPolicy','PanelSettings')
example=household.java(household.load(example=True),'local.steam.controls',OUT/'generated')
build('dashboard',manifest,[example]+[ROOT/f'src/local/steam/controls/{n}.java' for n in names]+list(stubs.glob('*.java')),ROOT/'assets')
print('Offline dashboard and original-app fixtures built without INTERNET permission.')

harness='<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="local.steam.window.harness"><uses-sdk android:minSdkVersion="29" android:targetSdkVersion="29"/><application android:debuggable="true"/><instrumentation android:name="local.steam.fixture.DumpWindows" android:targetPackage="local.steam.window.harness"/></manifest>'
build('harness',harness,[ROOT/'tests/overlay/DumpWindows.java'])
