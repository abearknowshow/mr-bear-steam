package local.steam.support;
import android.app.*;import android.os.*;import android.content.*;import android.graphics.*;import android.view.accessibility.AccessibilityNodeInfo;import org.json.*;import java.util.concurrent.*;import java.util.function.Predicate;
public final class SupportHarness extends Instrumentation {
 private UiAutomation automation;private boolean bearWebView=true;
 private <T>T ui(Callable<T> f)throws Exception{FutureTask<T> t=new FutureTask<>(f);runOnMainSync(t);return t.get();}
 private void require(boolean ok,String why)throws Exception{if(!ok)throw new Exception(why);}
 private JSONObject node(JSONObject tree,String label)throws Exception{JSONArray a=tree.getJSONArray("nodes");for(int i=0;i<a.length();i++){JSONObject n=a.getJSONObject(i);if(label.equals(n.optString("text")))return n;}throw new Exception("Missing "+label+": "+tree);}
 private JSONObject nodePrefix(JSONObject tree,String prefix)throws Exception{JSONArray a=tree.getJSONArray("nodes");for(int i=0;i<a.length();i++){JSONObject n=a.getJSONObject(i);if(n.optString("text").toLowerCase(java.util.Locale.ROOT).startsWith(prefix.toLowerCase(java.util.Locale.ROOT)))return n;}throw new Exception("Missing "+prefix+": "+tree);}
 private JSONObject action(JSONObject t,String op)throws Exception{return new JSONObject().put("hash",t.getString("hash")).put("op",op);}
 private int cx(JSONObject n)throws Exception{JSONArray b=n.getJSONArray("bounds");return (b.getInt(0)+b.getInt(2))/2;}
 private int cy(JSONObject n)throws Exception{JSONArray b=n.getJSONArray("bounds");return (b.getInt(1)+b.getInt(3))/2;}
 private JSONObject tap(JSONObject t,String op,int x,int y)throws Exception{return action(t,op).put("x",x).put("y",y);}
 private void clickLatest(SupportService service,String label)throws Exception{for(int i=0;i<6;i++){JSONObject t=ui(service::capture);JSONObject n=node(t,label);String status=ui(()->service.execute(tap(t,"tap",cx(n),cy(n))));if(status.equals("dispatched"))return;require(status.equals("rejected"),"Click failed: "+status);Thread.sleep(200);}throw new Exception("Screen never stable for "+label);}
 // Retries only until the whole scene is stable (same hash at capture and execute); returns the final status.
 private String stable(SupportService s,String label,String op)throws Exception{String status="";for(int i=0;i<6;i++){JSONObject t=ui(s::capture);JSONObject n=node(t,label);status=ui(()->s.execute(op.equals("click")?action(t,"click").put("node",n.getString("id")):tap(t,op,cx(n),cy(n))));JSONObject again=ui(s::capture);if(again.getString("hash").equals(t.getString("hash")))return status;Thread.sleep(300);}return status;}
 /** Owner's taps on our own screens and Android's capture prompt, done through UiAutomation inside the emulator. */
 private boolean tryPress(String label){AccessibilityNodeInfo root=automation.getRootInActiveWindow();if(root!=null)for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText(label))if(label.equalsIgnoreCase(String.valueOf(n.getText()))){AccessibilityNodeInfo c=n;while(c!=null&&!c.isClickable())c=c.getParent();if(c!=null&&c.isEnabled()&&c.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;}return false;}
 private void press(String label)throws Exception{for(int i=0;i<40;i++){if(tryPress(label))return;Thread.sleep(250);}throw new Exception("Never found "+label);}
 // Android 10 (the panel) asks "Start now"; Android 14+ defaults to one app, so the emulator picks the entire screen.
 private void consent()throws Exception{for(int i=0;i<40;i++){if(tryPress("Start now"))return;if(tryPress("Share one app")){Thread.sleep(800);press("Share entire screen");Thread.sleep(500);press("Share screen");return;}Thread.sleep(250);}throw new Exception("No capture prompt");}
 private void waitFor(Callable<Boolean> c,String why)throws Exception{for(int i=0;i<40;i++){if(c.call())return;Thread.sleep(250);}throw new Exception(why);}
 private JSONObject frameAfter(int count,Predicate<JSONObject> want,String why)throws Exception{JSONObject last=null;for(int i=0;i<60;i++){JSONObject f=Transport.frame;if(Transport.frames>count&&f!=null){last=f;if(want.test(f))return f;}Thread.sleep(250);}throw new Exception(why+": "+(last==null?"no frame":last.optString("blank")));}
 // Shell launches (inside the emulator) are not subject to background-activity-start limits on newer Android.
 private void open(String shell)throws Exception{try(ParcelFileDescriptor fd=automation.executeShellCommand(shell)){new java.io.FileInputStream(fd.getFileDescriptor()).read(new byte[4096]);Thread.sleep(1500);}}
 private void start(Intent i)throws Exception{getTargetContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(1500);}
 private void blocked(SupportService s,String why,String expect)throws Exception{
  int count=Transport.frames;JSONObject t=ui(s::capture);JSONObject top=t.getJSONArray("windows").getJSONObject(0);require(!top.getBoolean("allowed"),why+" window blocked: "+t);
  JSONArray nodes=t.getJSONArray("nodes");for(int i=0;i<nodes.length();i++)require(!nodes.getJSONObject(i).getString("id").startsWith(top.getInt("id")+"."),why+" text excluded");
  require("rejected".equals(ui(()->s.execute(tap(t,"tap",500,300)))),why+" taps refused");require("rejected".equals(ui(()->s.execute(action(t,"back")))),why+" back refused");
  JSONObject f=frameAfter(count,x->!x.isNull("blank")&&x.optString("blank").matches("(?s).*("+expect+").*"),why+" frame blank");require(f.isNull("jpeg"),why+" frame has no pixels");
 }
 public void onCreate(Bundle b){super.onCreate(b);bearWebView=!"false".equals(b==null?null:b.getString("bearWebView"));start();}
 public void onStart(){Bundle out=new Bundle();try{
  automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);Context ctx=getTargetContext();
  for(int wait=0;SupportService.host==null&&wait<60;wait++)Thread.sleep(250);SupportService s=SupportService.host;require(s!=null,"Support service bound");
  require("rejected".equals(ui(()->s.execute(new JSONObject()))),"Idle service refuses commands");
  // Owner start: our screen, then Android's capture prompt. Cancel starts nothing.
  start(new Intent(ctx,MainActivity.class));press("Start support · 2 hours");press("Cancel");Thread.sleep(800);require(s.remainingMs()==0&&!s.framesActive(),"Declined capture starts no session");
  press("Start support · 2 hours");consent();waitFor(s::framesActive,"Screen capture attached after Start now");
  long left=s.remainingMs();require(left>7190000&&left<=7200000,"Two-hour session: "+left);Thread.sleep(1500);
  Intent vendor=ctx.getPackageManager().getLaunchIntentForPackage("com.yizhong");start(vendor);
  JSONObject secretFrame=frameAfter(0,f->"password field visible".equals(f.optString("blank")),"Visible password blanks the frame");require(secretFrame.isNull("jpeg"),"Blank frame carries no pixels");
  JSONObject t=ui(s::capture);require(!t.toString().contains("test-secret"),"Password must be redacted");JSONObject on=node(t,"LIGHT ON");
  JSONObject click=action(t,"click").put("node",on.getString("id"));require("done".equals(ui(()->s.execute(click))),"Click executes");Thread.sleep(400);
  JSONObject changed=ui(s::capture);node(changed,"Physical fixture: ON");require("rejected".equals(ui(()->s.execute(click))),"Old scene rejected");
  JSONObject input=node(changed,"Editable note");JSONObject text=action(changed,"text").put("node",input.getString("id")).put("text","Remote text");require("done".equals(ui(()->s.execute(text))),"Text executes");Thread.sleep(300);JSONObject typed=ui(s::capture);node(typed,"Remote text");
  JSONObject password=node(typed,"[password]");JSONObject secret=action(typed,"text").put("node",password.getString("id")).put("text","do not set");require("rejected".equals(ui(()->s.execute(secret))),"Password writes refused");
  require("rejected".equals(ui(()->s.execute(tap(typed,"tap",cx(password),cy(password))))),"Password field taps refused");
  require("rejected".equals(ui(()->s.execute(tap(typed,"tap",5000,cy(password))))),"Off-screen point refused");
  JSONObject off=node(typed,"LIGHT OFF");require("dispatched".equals(ui(()->s.execute(tap(typed,"tap",cx(off),cy(off))))),"Gesture dispatch");Thread.sleep(500);node(ui(s::capture),"Physical fixture: OFF");
  require("dispatched".equals(stable(s,"LIGHT ON","long_press")),"Long press dispatch");Thread.sleep(1200);node(ui(s::capture),"Physical fixture: HELD");
  JSONObject padTree=ui(s::capture);JSONObject pad=node(padTree,"Swipe pad");JSONArray pb=pad.getJSONArray("bounds");
  require("dispatched".equals(ui(()->s.execute(action(padTree,"swipe").put("x1",pb.getInt(0)+20).put("y1",cy(pad)).put("x2",pb.getInt(2)-20).put("y2",cy(pad)).put("ms",300)))),"Swipe dispatch");Thread.sleep(800);node(ui(s::capture),"Physical fixture: SWIPED");
  // With the password field gone the frame carries pixels; the swipe pad's blue appears where the tree says it is.
  int before=Transport.frames;require("done".equals(stable(s,"HIDE SECRET","click")),"Hide secret");
  JSONObject image=frameAfter(before,f->f.isNull("blank")&&!f.isNull("jpeg"),"Frame with pixels after the password field hides");
  byte[] jpeg=android.util.Base64.decode(image.getString("jpeg"),0);Bitmap bmp=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);require(bmp!=null&&bmp.getWidth()==image.getInt("width")&&bmp.getWidth()<=960,"Frame is a downscaled JPEG");
  JSONObject now=ui(s::capture);JSONObject pad2=node(now,"Swipe pad");int px=bmp.getPixel(cx(pad2)*bmp.getWidth()/image.getInt("screenWidth"),cy(pad2)*bmp.getHeight()/image.getInt("screenHeight"));
  require(Color.blue(px)>150&&Color.red(px)<110,"Frame pixels match the screen: "+Integer.toHexString(px));
  require(image.getLong("capturedAt")>0&&image.getLong("sentMono")>=image.getLong("capturedMono")&&image.getString("hash").matches("[0-9a-f]{64}"),"Frame carries capture times and UI hash");
  // Settings is allowed; blocked pages and app-removing controls are not.
  open("am start -W -a android.settings.SETTINGS");JSONObject settings=ui(s::capture);require(settings.getJSONArray("windows").getJSONObject(0).getBoolean("allowed")&&settings.getJSONArray("nodes").length()>0,"Settings readable: "+settings);
  before=Transport.frames;frameAfter(before,f->f.isNull("blank"),"Settings frame has pixels");
  open("am start -W -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.yizhong");
  for(String control:new String[]{"Force stop","Uninstall","Permissions"}){JSONObject info=ui(s::capture);JSONObject n=nodePrefix(info,control);require("rejected".equals(ui(()->s.execute(tap(info,"tap",cx(n),cy(n))))),control+" tap refused");require("rejected".equals(ui(()->s.execute(action(info,"click").put("node",n.getString("id"))))),control+" click refused");}
  open("am start -W -a android.settings.ACCESSIBILITY_SETTINGS");blocked(s,"Accessibility settings","Accessibility");
  open("am start -W -a android.settings.SECURITY_SETTINGS");blocked(s,"Security settings","Security|another app is in front");  // Android 13+ opens Safety Center in the permission controller
  open("am start -W -a android.settings.MANAGE_UNKNOWN_APP_SOURCES");blocked(s,"Install unknown apps","nstall unknown apps");
  open("am start -a android.intent.action.MANAGE_PERMISSIONS");blocked(s,"Permission manager","another app is in front");
  start(vendor);JSONObject back=ui(s::capture);require("done".equals(ui(()->s.execute(action(back,"home")))),"Home dispatch");Thread.sleep(1500);
  JSONObject home=ui(s::capture);require(!home.getJSONArray("windows").getJSONObject(0).getBoolean("allowed"),"Launcher is outside the allowlist");frameAfter(Transport.frames,f->"another app is in front".equals(f.optString("blank")),"Launcher frame blank");
  start(vendor);
  Intent bear=ctx.getPackageManager().getLaunchIntentForPackage("local.steam.controls.offlinetest");boolean bearTested=bear!=null;if(bear!=null){bear.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);ctx.startActivity(bear);Thread.sleep(2500);JSONObject dashboard=ui(s::capture);nodePrefix(dashboard,"Test controls · locked");node(dashboard,"Adjust time");require(!dashboard.toString().contains("Physical fixture"),"Occluded original text excluded");
   before=Transport.frames;frameAfter(before,f->!"another app is in front".equals(f.optString("blank")),"Bear overlay frame allowed");
   if(bearWebView){clickLatest(s,"Bluetooth");Thread.sleep(2000);JSONObject instructions=ui(s::capture);require(instructions.toString().contains("5555"),"Bluetooth instructions opened: "+instructions);JSONObject exit=node(instructions,"Mr. Steam ↗");require("done".equals(ui(()->s.execute(action(instructions,"click").put("node",exit.getString("id"))))),"Original return click");Thread.sleep(500);node(ui(s::capture),"LIGHT ON");
   clickLatest(s,"Bear Steam");Thread.sleep(2000);
   JSONObject returned=ui(s::capture);nodePrefix(returned,"Test controls · locked");node(returned,"Back to main controls");
   clickLatest(s,"Back to main controls");Thread.sleep(1000);node(ui(s::capture),"Adjust time");}
  }
  ui(()->{s.end();return null;});require("rejected".equals(ui(()->s.execute(click))),"Stop immediately rejects commands");require(!s.framesActive(),"Stop ends screen capture");
  int stopped=Transport.frames;Thread.sleep(3500);require(Transport.frames==stopped,"No frames after Stop");
  for(ActivityManager.RunningServiceInfo r:((ActivityManager)ctx.getSystemService(Context.ACTIVITY_SERVICE)).getRunningServices(50))require(!r.service.getClassName().endsWith("CaptureService"),"Capture service stopped");
  ui(()->{s.begin();return null;});Thread.sleep(1500);
  try(ParcelFileDescriptor fd=automation.executeShellCommand("input keyevent 223")){Thread.sleep(500);}
  require("rejected".equals(ui(()->s.execute(click))),"Screen-off rejects commands");
  try(ParcelFileDescriptor fd=automation.executeShellCommand("input keyevent 224")){Thread.sleep(300);}
  ui(()->{s.disableSelf();return null;});Thread.sleep(500);require(SupportService.host==null,"Disable service unbinds");
  out.putString("result","PASS: locked idle, declined capture, Start now consent, 2 h cap, password blank frame, redaction, native click, stale rejection, text, password tap refused, coordinate tap, long press, swipe, frame pixels, Settings allowed, app-removing controls refused, Accessibility/Security/unknown-apps/permission screens blanked and refused, home, launcher blank"+(!bearTested?", Bear fixture not installed":bearWebView?", Bear overlay read, Bear WebView and original return":", Bear overlay read (WebView navigation skipped: system WebView below 80)")+", explicit stop ends frames, screen-off, permission revocation");finish(-1,out);
 }catch(Exception e){out.putString("error",e.toString());finish(0,out);}}
}
