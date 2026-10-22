package local.steam.support;

import android.accessibilityservice.*;import android.app.KeyguardManager;import android.content.*;import android.graphics.*;import android.media.projection.MediaProjection;import android.os.*;import android.view.*;import android.view.accessibility.*;import android.widget.Button;
import org.json.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicBoolean;import java.security.MessageDigest;

/** Explicit, time-limited UI support. No access to app storage, shell or equipment protocols. */
public final class SupportService extends AccessibilityService {
 static volatile SupportService host;
 static final long SESSION_MS=7200000;
 private static final String SETTINGS="com.android.settings";
 private static final List<String> PACKAGES=Arrays.asList("com.yizhong","com.android.chrome","local.steam.controls",SETTINGS);
 // Settings pages that stay owner-operated, matched on the visible page title (best effort; see README).
 private static final Set<String> BLOCKED_PAGES=new HashSet<>(Arrays.asList("accessibility","app permissions","permission manager","permissions","install unknown apps","unknown sources","special app access","device admin apps","device administrators","display over other apps","modify system settings","notification access","usage access","security","security & location","security & privacy","device unlock","screen lock","lock screen","choose screen lock","lock screen preferences","smart lock","encryption & credentials","trusted credentials","user credentials","reset options","erase all data (factory reset)"));
 // Settings controls that stop, clear or remove an app (Mr. Steam included) or reset the panel. Refused as tap or click targets.
 private static final Set<String> BLOCKED_CONTROLS=new HashSet<>(Arrays.asList("force stop","uninstall","uninstall updates","disable","clear storage","clear data","clear cache","reset app preferences","erase all data","reset phone","reset tablet"));
 // When a page sets no title, Android 10 reports the activity name, e.g. "com.android.settings/.Settings$AccessibilitySettingsActivity".
 private static final String[] BLOCKED_CLASSES={"accessibility","unknownsources","externalsources","permission","security","screenlock","chooselock","deviceadmin","encryption","credential","masterclear"};
 private final Handler main=new Handler(Looper.getMainLooper());
 private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
 private final ExecutorService uploads=Executors.newSingleThreadExecutor();private final AtomicBoolean uploading=new AtomicBoolean();
 private volatile boolean active,approved;private volatile long epoch,deadline,lastNetwork;
 private volatile String identity,key,uiHash;private volatile Transport transport;private volatile ScreenFrames frames;private Button chip;private WindowManager wm;
 private final Map<String,AccessibilityNodeInfo> nodes=new HashMap<>();private List<AccessibilityWindowInfo> windows=new ArrayList<>();private final Map<Integer,Boolean> allowedWindows=new HashMap<>();
 private final Map<Integer,String> pageTitles=new HashMap<>();private Set<String> serviceLabels;private String blank;private final List<Rect> masks=new ArrayList<>();
 private ScheduledFuture<?> polling;private String lastAction="";private JSONObject result;private long dispatchAt,dispatchMono;
 private final BroadcastReceiver locked=new BroadcastReceiver(){public void onReceive(Context c,Intent i){end();}};
 @Override public void onCreate(){super.onCreate();registerReceiver(locked,new IntentFilter(Intent.ACTION_SCREEN_OFF));}
 @Override protected void onServiceConnected(){host=this;}
 // Remembers each Settings window's announced page title; used only to block pages.
 @Override public void onAccessibilityEvent(AccessibilityEvent e){
  if(e.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED&&SETTINGS.equals(String.valueOf(e.getPackageName()))&&!e.getText().isEmpty()){if(pageTitles.size()>32)pageTitles.clear();pageTitles.put(e.getWindowId(),String.valueOf(e.getText().get(0)));}
 }
 @Override public void onInterrupt(){end();}
 private boolean alive(long visit){return active&&epoch==visit&&SystemClock.elapsedRealtime()<deadline&&((PowerManager)getSystemService(POWER_SERVICE)).isInteractive()&&!((KeyguardManager)getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked();}
 public void begin(){
  end();active=true;approved=false;long visit=++epoch;deadline=SystemClock.elapsedRealtime()+SESSION_MS;lastNetwork=SystemClock.elapsedRealtime();lastAction="";result=null;serviceLabels=null;
  chip=new Button(this);chip.setAllCaps(false);chip.setText("Connecting support · Stop");chip.setTextSize(12);chip.setContentDescription("Stop remote support");chip.setOnClickListener(v->end());wm=(WindowManager)getSystemService(WINDOW_SERVICE);
  WindowManager.LayoutParams p=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);p.gravity=Gravity.BOTTOM|Gravity.LEFT;p.setTitle("Bear remote support stop");
  try{wm.addView(chip,p);}catch(RuntimeException e){end();return;}
  worker.execute(()->{try{
   Transport t=new Transport(this);JSONObject pair=t.send("/begin",new JSONObject());String id=pair.getString("id"),secret=pair.getString("key"),code=pair.getString("code");
   if(!id.matches("[0-9a-f]{64}")||!secret.matches("[0-9a-f]{64}")||!code.matches("[0-9]{6}"))throw new Exception();
   main.post(()->{if(!alive(visit))return;identity=id;key=secret;transport=t;chip.setText("Support "+code+" · Stop");polling=worker.scheduleWithFixedDelay(()->exchange(visit),0,1000,TimeUnit.MILLISECONDS);});
  }catch(Exception e){main.post(()->{if(epoch==visit)end();});}});
 }
 /** Called by CaptureService on the main thread after the owner taps Start now. The projection lives only as long as this session. */
 boolean attachProjection(MediaProjection p){if(!active)return false;ScreenFrames old=frames;if(old!=null)old.close();frames=new ScreenFrames(this,p);frames.start();return true;}
 void framesStopped(ScreenFrames f){main.post(()->{if(frames!=f)return;frames=null;f.close();stopService(new Intent(this,CaptureService.class));});}
 boolean framesWanted(ScreenFrames f){return active&&approved&&frames==f&&transport!=null;}
 String latestHash(){return uiHash;}
 boolean uploading(){return uploading.get();}
 ScreenFrames.Plan framePlan(){long visit=epoch;try{return onMain(()->{if(!alive(visit)||!approved)return null;JSONObject s=capture();return new ScreenFrames.Plan(s.getString("hash"),blank,new ArrayList<>(masks));});}catch(Exception e){return null;}}
 void upload(ScreenFrames f,JSONObject frame){
  long visit=epoch;String id=identity,k=key;Transport t=transport;if(t==null||id==null||k==null||!uploading.compareAndSet(false,true))return;
  try{uploads.execute(()->{try{if(framesWanted(f)&&epoch==visit)t.send("/frame",new JSONObject().put("id",id).put("key",k).put("frame",frame.put("sentMono",SystemClock.elapsedRealtime())),5000);}catch(Exception ignored){}finally{uploading.set(false);}});}catch(RejectedExecutionException e){uploading.set(false);}
 }
 long remainingMs(){return active?deadline-SystemClock.elapsedRealtime():0;}
 boolean framesActive(){return frames!=null;}
 String frameBlank(){return blank;}
 private <T>T onMain(Callable<T> call)throws Exception{FutureTask<T> task=new FutureTask<>(call);main.post(task);try{return task.get(2500,TimeUnit.MILLISECONDS);}catch(TimeoutException e){task.cancel(false);throw e;}}
 private void exchange(long visit){
  try{
   if(!alive(visit)){main.post(()->{if(epoch==visit)end();});return;}
   JSONObject screen=approved?onMain(()->alive(visit)?capture():null):null;
   JSONObject payload=new JSONObject().put("id",identity).put("key",key).put("screen",screen==null?JSONObject.NULL:screen).put("result",result==null?JSONObject.NULL:result);
   JSONObject response=transport.send("/exchange",payload);if(!alive(visit))return;lastNetwork=SystemClock.elapsedRealtime();result=null;
   approved=response.getBoolean("approved");JSONObject command=response.optJSONObject("command");
   if(command!=null&&approved){String id=command.optString("id");if(id.matches("[0-9a-f]{32}")&&!id.equals(lastAction)){
    lastAction=id;String status=onMain(()->alive(visit)&&approved?execute(command):"rejected");result=new JSONObject().put("id",id).put("status",status);
    if(status.equals("done")||status.equals("dispatched"))result.put("at",dispatchAt).put("mono",dispatchMono);
   }}
  }catch(Exception e){if(SystemClock.elapsedRealtime()-lastNetwork>15000)main.post(()->{if(epoch==visit)end();});}
 }
 private void clearNodes(){for(AccessibilityNodeInfo n:nodes.values())n.recycle();nodes.clear();for(AccessibilityWindowInfo w:windows)w.recycle();windows.clear();allowedWindows.clear();}
 private Point screenSize(){Point s=new Point();((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealSize(s);return s;}
 private static long area(Rect r){return (long)r.width()*r.height();}
 private static long area(Region r){long a=0;RegionIterator it=new RegionIterator(r);Rect part=new Rect();while(it.next(part))a+=area(part);return a;}
 JSONObject capture()throws Exception{
  clearNodes();masks.clear();String reason=null;boolean appSeen=false;Point size=screenSize();long screenArea=(long)size.x*size.y;Region covered=new Region();
  JSONArray output=new JSONArray(),win=new JSONArray();List<AccessibilityWindowInfo> all=getWindows();all.sort((a,b)->Integer.compare(b.getLayer(),a.getLayer()));
  for(AccessibilityWindowInfo w:all){
   int type=w.getType();Rect bounds=new Rect();w.getBoundsInScreen(bounds);
   // Status and navigation bars may appear in frames; a pulled-down shade or recents screen may not.
   if(type==AccessibilityWindowInfo.TYPE_SYSTEM){if(reason==null&&area(bounds)*4>screenArea)reason="system panel open";w.recycle();continue;}
   if(type!=AccessibilityWindowInfo.TYPE_APPLICATION&&type!=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY&&type!=AccessibilityWindowInfo.TYPE_INPUT_METHOD){w.recycle();continue;}
   AccessibilityNodeInfo r=w.getRoot();String pkg=r==null?"":String.valueOf(r.getPackageName());
   if(pkg.equals(getPackageName())){if(r!=null)r.recycle();w.recycle();continue;}
   if(windows.size()>=12){if(reason==null)reason="too many windows";if(r!=null)r.recycle();w.recycle();continue;}
   String blocked=blockedReason(w,r,pkg);boolean allowed=blocked==null;
   windows.add(w);allowedWindows.put(w.getId(),allowed);
   JSONObject item=new JSONObject().put("id",w.getId()).put("package",PACKAGES.contains(pkg)?pkg:"unavailable").put("bounds",rect(bounds)).put("allowed",allowed);if(!allowed&&PACKAGES.contains(pkg))item.put("blocked",blocked);win.put(item);
   if(!allowed){
    // Pixels outside the allowlist are never sent: blank when the window is the app in front or most of the screen, otherwise black out its visible part.
    Region visible=new Region(bounds);visible.op(covered,Region.Op.DIFFERENCE);
    if(reason==null&&(type==AccessibilityWindowInfo.TYPE_APPLICATION&&!appSeen||area(visible)*2>screenArea))reason=PACKAGES.contains(pkg)?blocked:"another app is in front";
    RegionIterator it=new RegionIterator(visible);Rect part=new Rect();while(it.next(part))masks.add(new Rect(part));
   }
   covered.op(bounds,Region.Op.UNION);if(type==AccessibilityWindowInfo.TYPE_APPLICATION)appSeen=true;
   if(r!=null){if(allowed){walk(r,Integer.toString(w.getId()),output,0);if(reason==null&&frames!=null)reason=secret(r,w.getId(),new int[1],0);}r.recycle();}
  }
  blank=reason;
  JSONObject screen=new JSONObject().put("windows",win).put("nodes",output);MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[]bytes=digest.digest(screen.toString().getBytes("UTF-8"));StringBuilder hex=new StringBuilder();for(byte b:bytes)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));uiHash=hex.toString();return screen.put("hash",uiHash);
 }
 private String blockedReason(AccessibilityWindowInfo w,AccessibilityNodeInfo r,String pkg){
  if(r==null||!PACKAGES.contains(pkg))return "unavailable";
  if(!SETTINGS.equals(pkg))return null;
  List<String> titles=new ArrayList<>();titles.add(w.getTitle()==null?null:w.getTitle().toString());titles.add(pageTitles.get(w.getId()));toolbarTitles(r,titles,0,new int[1]);
  for(String t:titles)if(t!=null&&blockedPage(t))return "Settings: "+t.trim();
  return null;
 }
 private boolean blockedPage(String title){
  String t=title.trim().toLowerCase(Locale.ROOT);if(BLOCKED_PAGES.contains(t)||labels().contains(t))return true;
  if(t.contains("/"))for(String c:BLOCKED_CLASSES)if(t.contains(c))return true;
  return false;
 }
 // Accessibility service pages are titled with the service's own name.
 private Set<String> labels(){
  if(serviceLabels==null){Set<String> s=new HashSet<>();try{for(AccessibilityServiceInfo i:getSystemService(AccessibilityManager.class).getInstalledAccessibilityServiceList())s.add(String.valueOf(i.getResolveInfo().loadLabel(getPackageManager())).trim().toLowerCase(Locale.ROOT));}catch(RuntimeException ignored){}serviceLabels=s;}
  return serviceLabels;
 }
 private void toolbarTitles(AccessibilityNodeInfo n,List<String> out,int depth,int[] seen){
  if(depth>8||++seen[0]>300)return;
  String cls=String.valueOf(n.getClassName()),id=String.valueOf(n.getViewIdResourceName());
  boolean bar=cls.endsWith("Toolbar")||id.endsWith(":id/action_bar")||id.endsWith(":id/collapsing_toolbar");
  for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c==null)continue;if(bar){if(c.getText()!=null)out.add(c.getText().toString());}else toolbarTitles(c,out,depth+1,seen);c.recycle();}
 }
 /** A blank reason if a password field may be visible, or if the tree is too large to check. */
 private String secret(AccessibilityNodeInfo n,int window,int[] seen,int depth){
  if(++seen[0]>1500||depth>60)return "screen too complex to check for passwords";
  if(!n.isVisibleToUser())return null;
  if(n.isPassword()){Rect b=new Rect();n.getBoundsInScreen(b);int[][] corners={{b.centerX(),b.centerY()},{b.left,b.top},{b.right-1,b.top},{b.left,b.bottom-1},{b.right-1,b.bottom-1}};for(int[] p:corners)if(pointVisible(p[0],p[1],window))return "password field visible";}
  for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){String s=secret(c,window,seen,depth+1);c.recycle();if(s!=null)return s;}}
  return null;
 }
 private JSONArray rect(Rect b){return new JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom);}
 private String text(CharSequence value){String s=value==null?"":value.toString();return s.length()>100?s.substring(0,100):s;}
 private void walk(AccessibilityNodeInfo n,String id,JSONArray out,int depth)throws Exception{
  if(depth>40||out.length()>=240||!n.isVisibleToUser())return;
  Rect b=new Rect();n.getBoundsInScreen(b);boolean password=n.isPassword();String label=password?"[password]":text(n.getText());String description=password?"":text(n.getContentDescription());
  // Keep only meaningful visible nodes, with IDs tied to their window and child path.
  if(pointVisible(b.centerX(),b.centerY(),n.getWindowId())&&(password||!label.isEmpty()||!description.isEmpty()||n.isClickable()||n.isScrollable()||n.isEditable())){
   nodes.put(id,AccessibilityNodeInfo.obtain(n));out.put(new JSONObject().put("id",id).put("text",label).put("description",description).put("bounds",rect(b)).put("clickable",n.isClickable()).put("scrollable",n.isScrollable()).put("editable",n.isEditable()&&!password).put("password",password).put("enabled",n.isEnabled()).put("selected",n.isSelected()).put("checked",n.isChecked()));
  }
  for(int i=0;i<n.getChildCount()&&out.length()<240;i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){walk(c,id+"."+i,out,depth+1);c.recycle();}}
 }
 private boolean pointVisible(int x,int y,int expectedWindow){
  for(AccessibilityWindowInfo w:windows){Rect b=new Rect();w.getBoundsInScreen(b);if(!b.contains(x,y))continue;return Boolean.TRUE.equals(allowedWindows.get(w.getId()))&&(expectedWindow<0||expectedWindow==w.getId());}
  return false;
 }
 private boolean pointAllowed(int x,int y,int expectedWindow){
  if(!pointVisible(x,y,expectedWindow))return false;
  for(AccessibilityNodeInfo n:nodes.values())if(n.isPassword()){Rect b=new Rect();n.getBoundsInScreen(b);if(b.contains(x,y)&&pointVisible(x,y,n.getWindowId()))return false;}
  return true;
 }
 private boolean onScreen(int x,int y){Point s=screenSize();return x>=0&&y>=0&&x<s.x&&y<s.y;}
 private boolean blockedLabel(AccessibilityNodeInfo n){
  for(CharSequence s:new CharSequence[]{n.getText(),n.getContentDescription()}){if(s==null)continue;String t=s.toString().trim().toLowerCase(Locale.ROOT);if(BLOCKED_PAGES.contains(t)||BLOCKED_CONTROLS.contains(t)||labels().contains(t))return true;}
  return false;
 }
 private boolean namesBlocked(AccessibilityNodeInfo n,int depth){
  if(blockedLabel(n))return true;if(depth>=4)return false;
  for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){boolean b=namesBlocked(c,depth+1);c.recycle();if(b)return true;}}
  return false;
 }
 /** In Settings, refuses a target whose row names a blocked page or an app-removing control. */
 private boolean blockedTarget(int x,int y){
  for(AccessibilityNodeInfo n:nodes.values()){
   if(!SETTINGS.equals(String.valueOf(n.getPackageName())))continue;Rect b=new Rect();n.getBoundsInScreen(b);
   if(b.contains(x,y)&&(n.isClickable()?namesBlocked(n,0):blockedLabel(n)))return true;
  }
  return false;
 }
 private boolean stamp(){dispatchAt=System.currentTimeMillis();dispatchMono=SystemClock.elapsedRealtime();return true;}
 String execute(JSONObject command){
  try{
   if(!active||!approved)return "rejected";JSONObject screen=capture();if(!screen.getString("hash").equals(command.getString("hash")))return "rejected";
   String op=command.getString("op");if(op.equals("stop")){end();return "done";}
   boolean allowedTop=false;JSONArray ws=screen.getJSONArray("windows");if(ws.length()>0)allowedTop=ws.getJSONObject(0).getBoolean("allowed");
   if(op.equals("back")||op.equals("home")){return allowedTop&&stamp()&&performGlobalAction(op.equals("back")?GLOBAL_ACTION_BACK:GLOBAL_ACTION_HOME)?"done":"rejected";}
   if(op.equals("launch_bear")){
    if(!allowedTop)return "rejected";Intent i=getPackageManager().getLaunchIntentForPackage("local.steam.controls");if(i==null)return "failed";i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);stamp();startActivity(i);return "dispatched";
   }
   if(op.equals("url")){
    if(!allowedTop)return "rejected";String url=command.getString("url");if(!Arrays.asList("http://"+Household.MAC_HOST+":8768/","http://"+Household.MAC_HOST+":8775/","http://"+Household.MAC_HOST+":8780/").contains(url))return "rejected";
    Intent i=new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url));i.setPackage("com.android.chrome");i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);stamp();startActivity(i);return "dispatched";
   }
   if(op.equals("tap")||op.equals("long_press")||op.equals("swipe")){
    // Full-resolution screen pixels. Both ends must land on an allowed, unoccluded window and off any password field.
    boolean swipe=op.equals("swipe");int x=command.getInt(swipe?"x1":"x"),y=command.getInt(swipe?"y1":"y"),x2=swipe?command.getInt("x2"):x,y2=swipe?command.getInt("y2"):y;long ms=swipe?command.getLong("ms"):op.equals("tap")?80:800;
    if(nodes.size()>=240||windows.size()>=12||ms<80||ms>3000)return "rejected";
    if(!onScreen(x,y)||!onScreen(x2,y2)||!pointAllowed(x,y,-1)||!pointAllowed(x2,y2,-1))return "rejected";
    if(Math.hypot(x2-x,y2-y)<24&&blockedTarget(x,y))return "rejected";
    Path p=new Path();p.moveTo(x,y);if(swipe)p.lineTo(x2,y2);
    return stamp()&&dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,ms)).build(),null,null)?"dispatched":"failed";
   }
   AccessibilityNodeInfo n=nodes.get(command.optString("node"));if(n==null||!n.refresh()||!n.isVisibleToUser()||!n.isEnabled()||n.isPassword())return "rejected";Rect b=new Rect();n.getBoundsInScreen(b);if(!pointAllowed(b.centerX(),b.centerY(),n.getWindowId()))return "rejected";
   if(op.equals("click")){if(blockedTarget(b.centerX(),b.centerY())||SETTINGS.equals(String.valueOf(n.getPackageName()))&&namesBlocked(n,0))return "rejected";return stamp()&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK)?"done":"failed";}
   if(op.equals("scroll_forward")||op.equals("scroll_backward"))return stamp()&&n.performAction(op.equals("scroll_forward")?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)?"done":"failed";
   if(op.equals("text")){String value=command.getString("text");if(!n.isEditable()||value.length()>256)return "rejected";Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);return stamp()&&n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)?"done":"failed";}
  }catch(Exception ignored){}
  return "rejected";
 }
 public void end(){
  String endedId=identity,endedKey=key;Transport endedTransport=transport;
  if(endedId!=null&&endedKey!=null&&endedTransport!=null&&!worker.isShutdown())worker.execute(()->{try{endedTransport.send("/end",new JSONObject().put("id",endedId).put("key",endedKey));}catch(Exception ignored){}});
  active=false;approved=false;epoch++;identity=null;key=null;transport=null;uiHash=null;if(polling!=null){polling.cancel(false);polling=null;}if(chip!=null){try{wm.removeView(chip);}catch(RuntimeException ignored){}chip=null;}clearNodes();
  ScreenFrames f=frames;frames=null;if(f!=null)f.close();stopService(new Intent(this,CaptureService.class));
 }
 @Override public boolean onUnbind(Intent i){end();if(host==this)host=null;return super.onUnbind(i);}
 @Override public void onDestroy(){end();if(host==this)host=null;unregisterReceiver(locked);worker.shutdownNow();uploads.shutdownNow();super.onDestroy();}
}
