package local.steam.controls;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Owner build: controls are live on every visit (0.21). Stop always works while Bear is visible. */
public final class DashboardService extends Service {
 private DashboardAccessService overlayHost;private Context overlayContext;private boolean receiverRegistered;
 private OverlayWindows windows;private LinearLayout root;private volatile boolean destroyed;
 private final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
 private final BroadcastReceiver screenOff=new BroadcastReceiver(){public void onReceive(Context c,Intent i){Timeline.note("screen.off");closeDashboard();}};
 private WebView web;private Button account;private TextView accountIssue,notice;private LinearLayout nav;private ControlEngine engine;
 // 0.21: the guard after an automatic open, the last touch on Bear (keep-screen-on), and why the page cannot help.
 private volatile long guardUntil,lastTouch;private long sleptForTouch;
 // 0.25: when steam last went from running to off (elapsedRealtime), for the three awake minutes after a session.
 private volatile long steamEnded;private volatile boolean steamWasActive;private volatile String problem="";private PanelSettings settings;private volatile boolean pageReady;
 private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
 private volatile boolean active,armed,busy,stopRequested;private volatile long epoch,engineEpoch,lastSuccess;
 private volatile String cached="{\"connected\":false,\"armed\":false}";
 private volatile String operation="",error="",errorAction="";private volatile boolean connected;
 private int token;
 private CloudLights cloud;
 private final ExecutorService cloudWorker=Executors.newSingleThreadExecutor();
 private final ScheduledExecutorService readWorker=Executors.newSingleThreadScheduledExecutor();
 private final ReadWindow readWindow=new ReadWindow();
 private final java.util.concurrent.atomic.AtomicBoolean cloudBusy=new java.util.concurrent.atomic.AtomicBoolean(false);
 private final ReaderRetry readerRetry=new ReaderRetry();
 private final java.util.concurrent.atomic.AtomicBoolean syncResultQueued=new java.util.concurrent.atomic.AtomicBoolean(false);
 private volatile String lightError="",accountError="";private boolean accountAttempted;
 private volatile boolean lightDelivered,lightPending;
 private volatile int lightAttempt;private volatile String lightCommand="idle";
 // 0.18 timeline (telemetry only). Uploads run on their own thread, never the steam worker.
 private final ScheduledExecutorService timelineWorker=Executors.newSingleThreadScheduledExecutor();
 private long timelineSent;private boolean timelineFailing;private RemoteSignIn timelineLink;
 private boolean shownConnected,quietCovering;private String lastStateKey="",lastLightKey="";
 private final Object uiTraceLock=new Object();private long uiTraceWindow;private int uiTraceCount;
 @Override public void onCreate(){
  super.onCreate();
  Timeline.note("svc.create","build",Timeline.BUILD,"screenOn",getSystemService(android.os.PowerManager.class).isInteractive());
  NotificationManager notifications=getSystemService(NotificationManager.class);
  notifications.createNotificationChannel(new NotificationChannel("dashboard","Bear Steam dashboard",NotificationManager.IMPORTANCE_LOW));
  PendingIntent close=PendingIntent.getService(this,1,new Intent(this,DashboardService.class).setAction("close"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  PendingIntent open=PendingIntent.getService(this,2,new Intent(this,DashboardService.class).setAction("show"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  startForeground(11,new Notification.Builder(this,"dashboard").setContentTitle("Bear Steam")
   .setContentText("Dashboard available over Mr. Steam").setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true)
   .setContentIntent(open).addAction(new Notification.Action.Builder(null,"Close dashboard",close).build()).build());
  overlayHost=DashboardAccessService.host();
  if(overlayHost==null){stopSelf();return;}
  overlayContext=new android.view.ContextThemeWrapper(overlayHost,android.R.style.Theme_Material_Dialog_Alert);
  overlayHost.onDisconnect(()->{Timeline.note("access.disconnect");closeDashboard();});
  registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF));receiverRegistered=true;
  readWindow.resume();
  settings=new PanelSettings(this);
  root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.BLACK);
  int navHeight=Math.round(48*getResources().getDisplayMetrics().density);
  // 0.21: no top bar in normal use. This row appears only when the page can't help (sign in again,
  // display or controller unavailable), and it always offers Mr. Steam.
  nav=new LinearLayout(this);nav.setVisibility(View.GONE);
  notice=new TextView(this);notice.setTextColor(Color.WHITE);notice.setTextSize(16);notice.setPadding(24,0,16,0);notice.setGravity(android.view.Gravity.CENTER_VERTICAL);
  nav.addView(notice,new LinearLayout.LayoutParams(0,navHeight,2));
  account=new Button(this);account.setAllCaps(false);account.setText("Sign in");account.setVisibility(View.GONE);account.setOnClickListener(v->connectAccount());nav.addView(account,new LinearLayout.LayoutParams(0,navHeight,1));
  Button exit=new Button(this);exit.setText("Mr. Steam ↗");exit.setAllCaps(false);exit.setOnClickListener(v->original());nav.addView(exit,new LinearLayout.LayoutParams(0,navHeight,1));root.addView(nav);
  accountIssue=new TextView(this);accountIssue.setTextColor(Color.rgb(255,195,172));accountIssue.setPadding(16,6,16,6);accountIssue.setVisibility(View.GONE);accountIssue.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);root.addView(accountIssue);windows=new OverlayWindows(overlayHost,root,()->showDashboard(false),this::closeDashboard);
  try{
   cloud=new CloudLights(this);
   final BridgeTransport udp=new BridgeTransport(true);
   engine=new ControlEngine(new ControlEngine.Transport(){
    public Protocol.Status read()throws Exception{Protocol.Status s=udp.read();lastSuccess=System.currentTimeMillis();setConnected(true,"read-ok");return s;}
    public boolean write(String action,int value)throws Exception{return udp.write(action,value);}
    public void abandonReadsWhen(java.util.function.BooleanSupplier a){udp.abandonReadsWhen(a);}
    public void refreshMapWhen(java.util.function.BooleanSupplier i){udp.refreshMapWhen(i);}
   },()->active&&armed&&!stopRequested&&engineEpoch==epoch,new ControlEngine.Clock(){public long now(){return android.os.SystemClock.elapsedRealtime();}public void pause(long ms)throws Exception{Thread.sleep(ms);}});
   web=new WebView(this);WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(false);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setGeolocationEnabled(false);s.setJavaScriptCanOpenWindowsAutomatically(false);s.setSupportMultipleWindows(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
   web.addJavascriptInterface(new Bridge(),"SteamNative");
   web.setWebViewClient(new WebViewClient(){
    @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
    @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
     Uri u=r.getUrl();String file=u.getPath();boolean local="https".equals(u.getScheme())&&"appassets.androidplatform.net".equals(u.getHost())&&u.getPort()==-1&&u.getQuery()==null;
     if(local&&r.getMethod().equals("GET")&&(file.equals("/index.html")||file.equals("/live.js"))){try{return new WebResourceResponse(file.endsWith(".js")?"application/javascript":"text/html","UTF-8",getAssets().open(file.substring(1)));}catch(IOException ignored){}}
     return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
    }
    @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame()){problem="Display unavailable · use Mr. Steam";publish();}}
    @Override public boolean onRenderProcessGone(WebView v,RenderProcessGoneDetail detail){armed=false;epoch++;v.destroy();web=null;problem="Display unavailable · use Mr. Steam";publish();return true;}
    // A rendered page clears the failed automatic open count (two unrendered opens turn "open on wake" off).
    @Override public void onPageFinished(WebView v,String url){if(url!=null&&url.endsWith("/index.html")&&problem.isEmpty()){pageReady=true;settings.pageLoaded();Timeline.note("page.loaded");}}
   });
   // Any touch on Bear keeps the screen awake for five more minutes (PanelPolicy.keepScreenOn).
   web.setOnTouchListener((v,e)->{if(e.getActionMasked()==android.view.MotionEvent.ACTION_DOWN)lastTouch=android.os.SystemClock.elapsedRealtime();return false;});
   root.addView(web,new LinearLayout.LayoutParams(-1,0,1));web.loadUrl("https://appassets.androidplatform.net/index.html");
   worker.execute(this::poll);
   readWorker.scheduleWithFixedDelay(()->{
    // An uncaught throw would cancel every later tick, including reader recovery.
    try{if(!destroyed&&cloud!=null){cloud.refreshLiveReport();reopenLostReader();}queueSyncResult(readWindow.generation());}
    catch(RuntimeException e){}
   },10,10,TimeUnit.SECONDS);
  }catch(Exception e){problem="Controller unavailable · use Mr. Steam";Timeline.note("svc.unavailable","error",Timeline.text(e));publish();}
  timelineWorker.scheduleWithFixedDelay(this::uploadTimeline,2,2,TimeUnit.SECONDS);
 }
 /** Diagnostics for the owner's Mac while the dashboard is visible. Never the bridge or the cloud. */
 private void uploadTimeline(){
  try{
   if(destroyed||!active)return;
   Timeline.Batch batch=Timeline.global().batch(timelineSent,Timeline.BATCH,Timeline.BATCH_BYTES);
   if(batch.count==0)return;
   if(timelineLink==null)timelineLink=new RemoteSignIn(this);
   int code=timelineLink.timeline(batch.json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
   // The Mac refused this batch (malformed or too large): skip it rather than resend it forever.
   if(code==200||code==400||code==413)timelineSent=batch.last;
   boolean failing=code!=200;
   if(failing!=timelineFailing){timelineFailing=failing;Timeline.note("timeline.upload","ok",!failing,"status",code);}
  }catch(Exception e){if(!timelineFailing){timelineFailing=true;Timeline.note("timeline.upload","ok",false,"error",Timeline.text(e));}}
 }
 /** One status poll, then the next: POLL_MS after a good read, RETRY_MS after a failed one (0.24, issue #8). */
 private void poll(){
  long next=StatusGrace.POLL_MS;
  try{
   if(!DashboardAccessService.available()){Timeline.note("access.unavailable");runOnUiThread(this::closeDashboard);return;}
   if(active)updateScreenOn();if(!active||busy)return;
   try{engine.refresh();setConnected(true,"poll-ok");}catch(Exception e){setConnected(false,"poll-fail");next=StatusGrace.RETRY_MS;}
   publish();
  }finally{
   if(!destroyed)try{worker.schedule(this::poll,next,TimeUnit.MILLISECONDS);}catch(RejectedExecutionException shutDown){}
  }
 }
 /** The only writer of {@code connected}; records each flip and what caused it. */
 private void setConnected(boolean value,String cause){
  boolean was=connected;connected=value;
  if(was!=value)Timeline.note("conn","to",value,"cause",cause,"lastOkAgoMs",lastSuccess==0?-1:System.currentTimeMillis()-lastSuccess);
 }
 /** Steam running, or a Start or Stop under way, keeps the screen on; otherwise five minutes after the last touch
  *  or three minutes after steam goes off, whichever is later. */
 private void updateScreenOn(){
  Protocol.Status s=engine==null?null:engine.latest;
  final boolean steamActive=(s!=null&&s.possiblyOn())||busy||(engine!=null&&engine.startUncertain);
  final long now=android.os.SystemClock.elapsedRealtime(),touch=lastTouch;
  if(steamWasActive&&!steamActive){steamEnded=now;Timeline.note("screen.after_steam","awakeMs",PanelPolicy.AFTER_STEAM_MS);}
  steamWasActive=steamActive;
  final long ended=steamEnded,idleSince=Math.max(touch,ended);
  final boolean on=PanelPolicy.keepScreenOn(steamActive,now,touch,ended);
  final boolean knownOff=s!=null&&!s.possiblyOn()&&!s.sessionActive();
  final boolean sleep=PanelPolicy.shouldSleep(steamActive,knownOff,lastSuccess==0?-1:System.currentTimeMillis()-lastSuccess,now,touch,ended);
  runOnUiThread(()->{
   if(windows==null||!active)return;
   if(windows.keepScreenOn(on))Timeline.note("screen.keep_on","on",on,"steam",steamActive);
   // 0.23: one sleep request per idle stretch; a new touch (or, since 0.25, a session ending) allows the next one.
   if(sleep&&sleptForTouch!=idleSince){sleptForTouch=idleSince;DashboardAccessService host=DashboardAccessService.host();
    Timeline.note("screen.sleep","ok",host!=null&&host.sleepScreen(),"idleMs",now-touch);}
  });
 }
 private void original(){
  armed=false;active=false;epoch++;Timeline.note("dash.hide","to","original");publish();
  if(!resumeOriginal())return;
  try{windows.hide();queueSyncResult(readWindow.generation());}catch(RuntimeException e){closeDashboard();}
 }
 private boolean resumeOriginal(){
  try{
   Intent original=getPackageManager().getLaunchIntentForPackage("com.yizhong");
   if(original==null)throw new IllegalStateException("Original controller unavailable");
   // Settings or the support app can be underneath the overlay. Bring the existing
   // singleTask controller forward so its status loop runs; never clear its task.
   original.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
   startActivity(original);return true;
  }catch(RuntimeException e){
   Toast.makeText(this,"Mr. Steam could not be opened. Open its normal controls, then return to Bear Steam.",Toast.LENGTH_LONG).show();
   return false;
  }
 }
 public final class Bridge {
  @JavascriptInterface public String state(){return cached;}
  @JavascriptInterface public void originalApp(){runOnUiThread(()->original());}
  /** Owner settings: accent and open on wake (0.21), the last light look (0.22). Never equipment. */
  @JavascriptInterface public String settings(){try{
   JSONObject j=new JSONObject().put("accent",settings.accent()).put("openOnWake",settings.openOnWake()).put("version",Timeline.BUILD);
   int[] l=settings.look();if(l!=null)j.put("look",new JSONObject().put("hue",l[0]).put("saturation",l[1]).put("brightness",l[2]).put("mode",l[3]==1?"disco":"solid"));
   return j.toString();}catch(Exception e){return "{}";}}
  /** Remembers the look only; nothing is sent to the light. */
  @JavascriptInterface public boolean setLook(int hue,int saturation,int brightness,boolean disco){boolean ok=settings.setLook(hue,saturation,brightness,disco);Timeline.note("settings.look","hue",hue,"saturation",saturation,"brightness",brightness,"disco",disco,"ok",ok);return ok;}
  @JavascriptInterface public boolean setAccent(String value){boolean ok=settings.setAccent(value);Timeline.note("settings.accent","ok",ok);return ok;}
  @JavascriptInterface public void setOpenOnWake(boolean on){settings.setOpenOnWake(on);Timeline.note("settings.openOnWake","on",on);}
  @JavascriptInterface public boolean light(boolean on,String color,int brightness,boolean disco){return sendLight(on,color,brightness,disco);}
  @JavascriptInterface public void command(String action,int value,int minutes){submit(action,value,minutes);}
  /** 0.18: page events (taps, settle, send, what the status line shows) for the timeline only. */
  @JavascriptInterface public void trace(String type,String fields){traceUi(type,fields);}
 }
 private void traceUi(String type,String fields){
  try{
   if(type==null||fields==null||fields.length()>1024||!type.matches("[a-z][a-z0-9_.]{0,23}"))return;
   long now=android.os.SystemClock.elapsedRealtime();
   synchronized(uiTraceLock){if(now-uiTraceWindow>=1000){uiTraceWindow=now;uiTraceCount=0;}if(++uiTraceCount>30)return;}
   JSONObject f=new JSONObject(fields);Object[]kv=new Object[2*Math.min(f.length(),Timeline.MAX_FIELDS)];int i=0;
   for(Iterator<String> keys=f.keys();keys.hasNext()&&i<kv.length;){String key=keys.next();Object v=f.opt(key);kv[i++]=key;kv[i++]=v==JSONObject.NULL?null:v;}
   Timeline.note("ui."+type,kv);
  }catch(Exception ignored){}
 }
 private void connectAccount(){
  if(cloud==null)return;
  if(cloud.requiresSignIn){signIn();return;}
  if(!cloudBusy.compareAndSet(false,true))return;
  final long visit=epoch;accountError="";publish();Timeline.note("account.connect","restore",!cloud.ready);
  cloudWorker.execute(()->{
   try{ControlEngine.Guard guard=()->active&&epoch==visit;if(!cloud.ready)cloud.restore(guard);cloud.refreshReport(guard);Timeline.note("account.result","ok",true,"ready",cloud.ready);}
   catch(Exception e){if(active&&epoch==visit)accountError=e.getMessage()==null?"Could not reconnect to Mr. Steam. Try again.":e.getMessage();Timeline.note("account.result","ok",false,"error",Timeline.text(e));}
   finally{cloudBusy.set(false);publish();beginLightRead(visit);}
  });
 }
 /** Visible Bear, ready account, lost reader: bounded reopen. Status-only; never a light command. */
 private void reopenLostReader(){
  final long visit=epoch;
  if(cloudBusy.get()||!readerRetry.due(visit,active,cloud.ready,cloud.readerAvailable(),android.os.SystemClock.elapsedRealtime()))return;
  Timeline.note("light.reader.reopen","exhausted",readerRetry.exhausted());
  beginLightRead(visit);
 }
 private void beginLightRead(long visit){
  if(destroyed||epoch!=visit||cloud==null||!cloud.ready)return;
  final long generation=readWindow.generation();
  readWorker.execute(()->{
   ControlEngine.Guard guard=()->readWindow.allows(generation,android.os.SystemClock.elapsedRealtime());
   Timeline.note("light.reader.start");
   try{cloud.startReader(guard,()->{publish();queueSyncResult(generation);});}
   catch(Exception e){Timeline.note("light.reader.fail","error",Timeline.text(e),"current",guard.allowed());if(guard.allowed())cloud.reportIssue="Status reader unavailable";}
   if(guard.allowed()){queueSyncResult(generation);publish();}
  });
 }
 private void queueSyncResult(long generation){
  if(cloud==null||!cloud.ready||!readWindow.allows(generation,android.os.SystemClock.elapsedRealtime())||!syncResultQueued.compareAndSet(false,true))return;
  try{readWorker.execute(()->{
   try{if(readWindow.allows(generation,android.os.SystemClock.elapsedRealtime()))new RemoteSignIn(this).syncResult(cloud.reportIssue,cloud.report,cloud.readMetrics(),active?"bear-overlay":"original-visible",lightAttempt,lightCommand);}
   catch(Exception ignored){}finally{syncResultQueued.set(false);}
  });}catch(RejectedExecutionException e){syncResultQueued.set(false);}
 }
 private void signIn(){
  if(cloud==null||!cloudBusy.compareAndSet(false,true))return;
  final long visit=epoch;final java.util.concurrent.atomic.AtomicBoolean canceled=new java.util.concurrent.atomic.AtomicBoolean(false);
  ControlEngine.Guard guard=()->active&&epoch==visit&&!canceled.get();
  accountError="";publish();Timeline.note("account.signin.start");
  AlertDialog dialog=new AlertDialog.Builder(overlayContext).setTitle("Sign in to Mr. Steam")
   .setMessage("Connecting to the Mac…")
   .setNegativeButton("Cancel",(d,w)->canceled.set(true)).create();
  dialog.setOnCancelListener(d->canceled.set(true));windows.dialog(dialog);
  cloudWorker.execute(()->{
   RemoteSignIn remote=null;String identity=null;String outcome="failed";
   try{
    remote=new RemoteSignIn(this);JSONObject pair=remote.begin();identity=pair.getString("id");String code=pair.getString("code");
    runOnUiThread(()->{if(guard.allowed())dialog.setMessage("Open this page on your Mac:\nhttp://127.0.0.1:8776/\n\nCode: "+code+"\n\nUse your existing Mr. Steam account. Nothing to type here.");});
    JSONObject login=remote.await(identity,guard);
    runOnUiThread(()->{if(guard.allowed())dialog.setMessage("Signing into your existing Mr. Steam account…");});
    try{cloud.signIn(login.getString("email"),login.getString("password"),guard);cloud.refreshReport(guard);}finally{login.remove("email");login.remove("password");}
    outcome="connected";
   }catch(Exception e){if(guard.allowed())accountError=e.getMessage()==null?"Sign-in did not finish. Try again.":e.getMessage();else outcome="canceled";}
   finally{Timeline.note("account.signin","outcome",outcome);if(remote!=null&&identity!=null)remote.finish(identity,outcome);cloudBusy.set(false);publish();beginLightRead(visit);runOnUiThread(()->{if(!destroyed)dialog.dismiss();});}
  });
 }
 private synchronized boolean sendLight(boolean on,String color,int brightness,boolean disco){
  if(!active||!armed||cloud==null){Timeline.note("light.cmd","on",on,"accepted",false,"why",cloud==null?"no cloud":!active?"hidden":"locked");return false;}
  if(!PanelPolicy.commandAllowed("lights",android.os.SystemClock.elapsedRealtime(),guardUntil)){Timeline.note("light.cmd","on",on,"accepted",false,"why","guard");return false;}
  if(!cloud.ready){accountError=cloud.requiresSignIn?"Sign in to your Mr. Steam account to finish connecting.":"Cloud connection unavailable. Tap Reconnect.";Timeline.note("light.cmd","on",on,"accepted",false,"why","cloud not ready");publish();return false;}
  try{LightPayload.build(Household.LIGHT_THING,on,color,brightness,disco);}catch(Exception e){Timeline.note("light.cmd","on",on,"accepted",false,"why","invalid payload");return false;}
  if(!cloudBusy.compareAndSet(false,true)){Timeline.note("light.cmd","on",on,"accepted",false,"why","cloud busy");return false;}
  final long visit=epoch;lightError="";lightDelivered=false;lightPending=true;lightAttempt=Math.min(1000000,lightAttempt+1);lightCommand="sending";
  Timeline.note("light.cmd","on",on,"color",color,"brightness",brightness,"disco",disco,"accepted",true,"attempt",lightAttempt);publish();
  final long lightStarted=android.os.SystemClock.elapsedRealtime();
  cloudWorker.execute(()->{
   try{
    ControlEngine.Guard guard=()->active&&armed&&epoch==visit;
    long sentAt=System.currentTimeMillis();
    // Chroma publishes only on a power change, so a same-state command (Start or a color edit
    // while On) can never produce a confirming report. Remember what was reported beforehand.
    LightReport before=cloud.report;final boolean alreadyReported=before!=null&&before.usable(sentAt)&&before.on==on;
    cloud.send(on,color,brightness,disco,guard);
    lightDelivered=true;lightCommand="cloud-received";
    // Steamlinx's light-output bit does not track this panel's Chroma fixture.
    // Only the normal steam poller uses UDP; light verification cannot compete with it.
    LightReport r=cloud.report;
    for(int i=0;guard.allowed()&&i<5&&(r==null||!r.confirms(on,sentAt,System.currentTimeMillis()));i++){
     Thread.sleep(1000);r=cloud.report;
    }
    long now=System.currentTimeMillis();
    // "unchanged": power was and still is reported as requested. Not proof the color/brightness applied.
    lightCommand=guard.allowed()&&r!=null&&r.confirms(on,sentAt,now)?"confirmed":alreadyReported&&r!=null&&r.usable(now)&&r.on==on?"unchanged":"unconfirmed";
    if(guard.allowed()&&lightCommand.equals("unconfirmed"))lightError="Light change not confirmed.";
   }catch(Exception e){lightCommand="failed";if(cloud.requiresSignIn){accountError=e.getMessage()==null?"Sign in again to reconnect.":e.getMessage();}else{lightError=e.getMessage()==null?"Light change unconfirmed. Use Mr. Steam.":e.getMessage();}}
   finally{Timeline.note("light.result","command",lightCommand,"delivered",lightDelivered,"ms",android.os.SystemClock.elapsedRealtime()-lightStarted,"error",lightError);
    lightPending=false;cloudBusy.set(false);publish();queueSyncResult(readWindow.generation());}
  });
  return true;
 }
 private synchronized void submit(String action,int value,int minutes){
  // Stop needs only a visible dashboard: it is never locked or guarded (owner decision 3).
  boolean stopping="stop".equals(action);
  if(engine==null||!active||(!armed&&!stopping)||!Arrays.asList("start","stop","time","temperature").contains(action)){
   Timeline.note("cmd.submit","action",action,"value",value,"minutes",minutes,"accepted",false,"why",engine==null?"no engine":!active?"hidden":!armed?"locked":"unsupported");return;}
  if(!PanelPolicy.commandAllowed(action,android.os.SystemClock.elapsedRealtime(),guardUntil)){Timeline.note("cmd.submit","action",action,"value",value,"accepted",false,"why","guard");return;}
  if(busy&&(!stopping||operation.equals("stop"))){Timeline.note("cmd.submit","action",action,"value",value,"accepted",false,"why","busy","busyWith",operation);return;}
  // Stop cuts in: the change already in flight sees stopPending and gives up at its next check.
  if(stopping){stopRequested=true;engine.stopPending=true;Timeline.note("stop.pending","set",true,"inFlight",operation);}
  busy=true;operation=action;error="";errorAction="";final int task=++token;final long visit=epoch;
  Timeline.note("cmd.submit","action",action,"value",value,"minutes",minutes,"accepted",true,"task",task);publish();
  final long queued=android.os.SystemClock.elapsedRealtime();
  worker.execute(()->{
   if(visit!=epoch&&!stopping){Timeline.note("cmd.skip","action",action,"task",task,"why","dashboard left before it ran");finishTask(task);return;}
   engineEpoch=visit;final long began=android.os.SystemClock.elapsedRealtime();
   Timeline.note("cmd.start","action",action,"value",value,"task",task,"queuedMs",began-queued);
   try{engine.execute(action,value,minutes);setConnected(true,"cmd-ok");
    Timeline.note("cmd.end","action",action,"value",value,"task",task,"ok",true,"ms",android.os.SystemClock.elapsedRealtime()-began);}
   catch(Exception e){if(task==token){error=e.getMessage()==null?"Change unconfirmed. Use Mr. Steam.":e.getMessage();errorAction=action;}setConnected(System.currentTimeMillis()-lastSuccess<10000,"cmd-fail");
    Timeline.note("cmd.end","action",action,"value",value,"task",task,"ok",false,"ms",android.os.SystemClock.elapsedRealtime()-began,"error",Timeline.text(e),"cause",Timeline.text(e.getCause()),"shown",task==token,
     "startUncertain",engine.startUncertain,"runtimeBlocked",engine.runtimeBlocked);}
   finally{if(stopping){engine.stopPending=false;Timeline.note("stop.pending","set",false);}finishTask(task);}
  });
 }
 private synchronized void finishTask(int task){if(task==token){busy=false;operation="";stopRequested=false;}publish();}
 private synchronized void publish(){
  try{
   // Driven by the age of the last good read, not the last poll, so one lost read does not flash "Reconnecting…".
   long nowMs=System.currentTimeMillis();Protocol.Status last=engine==null?null:engine.latest;
   // 0.26: with steam confirmed off and nothing waiting, a silent bridge stays quiet for up to a minute (StatusGrace.QUIET_MS).
   boolean quiet=StatusGrace.quiet(nowMs,lastSuccess,last!=null&&!last.possiblyOn()&&!last.sessionActive(),!busy&&!stopRequested&&(engine==null||!engine.startUncertain));
   boolean graced=StatusGrace.shown(nowMs,lastSuccess),shown=graced||quiet;
   JSONObject j=new JSONObject();j.put("quiet",quiet);j.put("cloudReady",cloud!=null&&cloud.ready);j.put("cloudBusy",cloudBusy.get());j.put("lightError",lightError);j.put("lightDelivered",lightDelivered);j.put("connected",shown);j.put("at",lastSuccess);j.put("armed",armed&&active);j.put("busy",busy);j.put("operation",operation);j.put("error",error);j.put("errorAction",errorAction);
   j.put("lightPending",lightPending);j.put("guarded",PanelPolicy.guarded(android.os.SystemClock.elapsedRealtime(),guardUntil));
   LightReport r=cloud==null?null:cloud.report;
   if(r!=null)j.put("lightReport",new JSONObject().put("on",r.on).put("at",r.at).put("freshUntil",r.freshUntil));
   if(engine!=null){j.put("unsafeStart",engine.startUncertain);j.put("runtimeBlocked",engine.runtimeBlocked);Protocol.Status s=engine.latest;if(s!=null){JSONObject d=new JSONObject();d.put("on",s.on);d.put("possiblyOn",s.possiblyOn());d.put("heater",s.heater);d.put("paused",s.paused);d.put("diagnostic",s.diagnostic);d.put("requestedMode",s.requestedMode);d.put("targetF",s.targetF);d.put("roomF",s.roomF);d.put("minutes",s.requestedMinutes);d.put("remaining",s.remaining);d.put("maxMinutes",s.maxMinutes);d.put("minF",s.minF);d.put("maxF",s.maxF);d.put("lightOn",s.lightOn);d.put("fault",s.error);d.put("program",s.program);j.put("snapshot",d);}}
   cached=j.toString();
   traceState(shown,quiet&&!graced);
  }catch(Exception ignored){}
  runOnUiThread(()->{
   boolean needsAccount=cloud!=null&&!cloud.ready&&!cloudBusy.get();
   notice.setText(problem.isEmpty()?"Lights need your Mr. Steam account":problem);
   nav.setVisibility(needsAccount||!problem.isEmpty()?View.VISIBLE:View.GONE);
   account.setVisibility(needsAccount?View.VISIBLE:View.GONE);account.setEnabled(!cloudBusy.get());
   account.setText(cloudBusy.get()?"Connecting…":cloud!=null&&!cloud.requiresSignIn?"Reconnect":accountError.isEmpty()?"Sign in":"Retry sign-in");
   accountIssue.setText(accountError);accountIssue.setVisibility(accountError.isEmpty()?View.GONE:View.VISIBLE);
  });
 }
 /** Called from publish() (synchronized): what the page will be told, recorded only when it changes. */
 private void traceState(boolean shown,boolean covering){
  if(shown!=shownConnected){
   shownConnected=shown;long age=lastSuccess==0?-1:System.currentTimeMillis()-lastSuccess;
   // The page shows "Reconnecting…" while connected is false and the last good read is under 20 s old.
   Timeline.note("ui.connected","to",shown,"field",connected,"lastOkAgoMs",age,"why",shown?"read ok within grace":"no good read for "+StatusGrace.GRACE_MS/1000+" s");
  }
  // 0.26: when the quiet window starts and stops hiding a silent bridge (steam off, nothing waiting).
  if(covering!=quietCovering){quietCovering=covering;Timeline.note("ui.quiet","on",covering,"lastOkAgoMs",lastSuccess==0?-1:System.currentTimeMillis()-lastSuccess);}
  Protocol.Status s=engine==null?null:engine.latest;
  String key=s==null?"none":s.on+"|"+s.possiblyOn()+"|"+s.sessionActive()+"|"+s.requestedMode+"|"+s.remaining+"|"+s.requestedMinutes+"|"+s.targetF+"|"+s.roomF+"|"+s.lightOn+"|"+s.error+"|"+s.heater+"|"+error+"|"+errorAction+"|"+lightError;
  if(!key.equals(lastStateKey)){
   lastStateKey=key;
   if(s==null)Timeline.note("state","known",false,"error",error);
   else Timeline.note("state","on",s.on,"possiblyOn",s.possiblyOn(),"session",s.sessionActive(),"mode",s.requestedMode,"remaining",s.remaining,"minutes",s.requestedMinutes,
    "targetF",s.targetF,"roomF",s.roomF,"lightBit",s.lightOn,"heater",s.heater,"fault",s.error,"error",error,"errorAction",errorAction,"lightError",lightError);
  }
  LightReport r=cloud==null?null:cloud.report;long now=System.currentTimeMillis();
  String light=cloud==null?"none":cloud.ready+"|"+cloud.reportIssue+"|"+cloud.readerAvailable()+"|"+(r==null?"-":r.on+"|"+r.usable(now));
  if(!light.equals(lastLightKey)){
   lastLightKey=light;
   if(cloud!=null)Timeline.note("light.reader","ready",cloud.ready,"stage",cloud.reportIssue,"available",cloud.readerAvailable(),"reportOn",r==null?null:r.on,"fresh",r!=null&&r.usable(now));
  }
 }
 private void runOnUiThread(Runnable work){if(android.os.Looper.myLooper()==main.getLooper()){if(!destroyed)work.run();}else main.post(()->{if(!destroyed)work.run();});}
 private void closeDashboard(){Timeline.note("dash.close","wasActive",active);active=false;armed=false;epoch++;readWindow.close();if(windows!=null)windows.close();stopSelf();}
 private void showDashboard(boolean automatic){
  if(destroyed||windows==null||!DashboardAccessService.available()){closeDashboard();return;}
  if(active){if(pageReady)settings.pageLoaded();return;}
  if(!resumeOriginal()){Timeline.note("dash.show.fail","why","original app unavailable");closeDashboard();return;}
  try{windows.show();}catch(RuntimeException e){Timeline.note("dash.show.fail","why","window");closeDashboard();return;}
  // 0.21: live on every visit (owner decision 2). An automatic open ignores all but Stop for 1.5 s (decision 4b).
  long now=android.os.SystemClock.elapsedRealtime();
  active=true;armed=true;epoch++;guardUntil=PanelPolicy.guardUntil(automatic,now);lastTouch=now;windows.keepScreenOn(true);
  Timeline.note("dash.show","automatic",automatic,"guardMs",automatic?PanelPolicy.GUARD_MS:0);publish();
  if(automatic)main.postDelayed(this::publish,PanelPolicy.GUARD_MS+50);
  if(cloud!=null&&(!accountAttempted||!cloud.readerAvailable())){accountAttempted=true;connectAccount();}
  queueSyncResult(readWindow.generation());
 }
 @Override public int onStartCommand(Intent intent,int flags,int id){
  String action=intent==null?null:intent.getAction();
  Timeline.note("svc.command","action",action==null?"none":"close".equals(action)?"close":"auto".equals(action)?"auto":"show");
  if(intent==null||"close".equals(action)){closeDashboard();return START_NOT_STICKY;}
  showDashboard("auto".equals(action));return START_NOT_STICKY;
 }
 @Override public android.os.IBinder onBind(Intent intent){return null;}
 @Override public void onDestroy(){
  Timeline.note("svc.destroy");
  destroyed=true;active=false;armed=false;epoch++;readWindow.close();
  if(cloud!=null)cloud.stopReader();worker.shutdownNow();cloudWorker.shutdownNow();readWorker.shutdownNow();timelineWorker.shutdownNow();
  if(receiverRegistered)unregisterReceiver(screenOff);main.removeCallbacksAndMessages(null);
  if(overlayHost!=null)overlayHost.onDisconnect(null);
  if(windows!=null)windows.close();if(web!=null){web.removeJavascriptInterface("SteamNative");web.destroy();}
  stopForeground(true);super.onDestroy();
 }
}
