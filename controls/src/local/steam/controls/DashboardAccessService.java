package local.steam.controls;

import android.accessibilityservice.AccessibilityService;
import android.content.*;
import android.os.*;
import android.view.accessibility.AccessibilityEvent;

/** Window-token host, and since 0.21 the automatic opener: on screen-on and once after boot.
 *  Since 0.23 it can also turn the screen off, like the power button, for Bear's idle sleep.
 *  Still no event subscription, screen reading or gesture control; screen-on is an ordinary broadcast. */
public final class DashboardAccessService extends AccessibilityService {
 private static volatile DashboardAccessService connected;
 private Runnable disconnected;private boolean receiverRegistered;private long lastAutoOpen;
 private final Handler main=new Handler(Looper.getMainLooper());
 private final BroadcastReceiver screenOn=new BroadcastReceiver(){public void onReceive(Context c,Intent i){autoOpen("wake");}};
 public static DashboardAccessService host(){return connected;}
 public static boolean available(){return connected!=null;}
 // All listener and window operations run on the main thread.
 public void onDisconnect(Runnable listener){disconnected=listener;}
 @Override protected void onServiceConnected(){
  connected=this;
  registerReceiver(screenOn,new IntentFilter(Intent.ACTION_SCREEN_ON));receiverRegistered=true;
  // A connect soon after boot is a restart: open once, after Mr. Steam has had time to sign itself in.
  long delay=PanelPolicy.bootDelay(SystemClock.elapsedRealtime());
  Timeline.note("access.connect","bootOpenInMs",delay,"screenTimeoutMs",android.provider.Settings.System.getInt(getContentResolver(),android.provider.Settings.System.SCREEN_OFF_TIMEOUT,-1));
  if(delay>=0)main.postDelayed(()->autoOpen("boot"),delay);
 }
 private void autoOpen(String why){
  if(connected!=this)return;
  PanelSettings settings=new PanelSettings(this);long now=SystemClock.elapsedRealtime();
  if(!getSystemService(PowerManager.class).isInteractive()){Timeline.note("auto.open","why",why,"opened",false,"skip","screen off");return;}
  if(!PanelPolicy.debounced(now,lastAutoOpen)){return;}
  if(PanelPolicy.shouldDisable(settings.openOnWake(),settings.failedOpens())){
   settings.setOpenOnWake(false);Timeline.note("auto.open","why",why,"opened",false,"skip","failed twice, turned off");return;}
  if(!PanelPolicy.mayAutoOpen(settings.openOnWake(),settings.failedOpens())){Timeline.note("auto.open","why",why,"opened",false,"skip","setting off");return;}
  lastAutoOpen=now;settings.countAutomaticOpen();
  Timeline.note("auto.open","why",why,"opened",true,"failedBefore",settings.failedOpens()-1);
  try{startForegroundService(new Intent(this,DashboardService.class).setAction("auto"));}
  catch(RuntimeException e){Timeline.note("auto.open.fail","error",Timeline.text(e));}
 }
 /** 0.23: the power-button sleep (Android 9 and later). Only DashboardService's idle sleep calls it, with steam confirmed off. */
 public boolean sleepScreen(){return Build.VERSION.SDK_INT>=28&&performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);}
 @Override public void onAccessibilityEvent(AccessibilityEvent event){}
 @Override public void onInterrupt(){}
 private void release(){
  main.removeCallbacksAndMessages(null);
  if(receiverRegistered){try{unregisterReceiver(screenOn);}catch(IllegalArgumentException ignored){}receiverRegistered=false;}
  if(connected==this)connected=null;
  Runnable listener=disconnected;disconnected=null;
  if(listener!=null)listener.run();
 }
 @Override public boolean onUnbind(Intent intent){release();return super.onUnbind(intent);}
 @Override public void onDestroy(){release();super.onDestroy();}
}
