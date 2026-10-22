package local.steam.controls;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;

/** Permission/setup entry only. The dashboard window never pauses the original controller. */
public final class MainActivity extends Activity {
 private boolean waiting=true,opening,resumed;private TextView message;
 private final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
 private final Runnable checkConnection=new Runnable(){public void run(){
  if(!resumed||opening)return;
  if(waiting&&DashboardAccessService.available()){open();return;}
  main.postDelayed(this,500);
 }};
 @Override public void onCreate(Bundle state){
  super.onCreate(state);
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(32,24,32,24);root.setBackgroundColor(Color.rgb(17,24,32));
  message=new TextView(this);message.setTextColor(Color.WHITE);message.setTextSize(22);
  message.setText("Bear Steam\n\nEnable Bear Steam dashboard in Android Accessibility, then return here. This lets the dashboard stay above Mr. Steam while its controller runs.\n\nIt does not read other apps or perform gestures. It also opens the dashboard when the screen wakes; turn that off in the dashboard Settings. Your saved sign-in stays in place.");root.addView(message);
  Button allow=new Button(this);allow.setAllCaps(false);allow.setText("Open Accessibility settings");
  allow.setOnClickListener(v->{
   if(DashboardAccessService.available()){open();return;}
   try{waiting=true;startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}
   catch(Exception e){waiting=false;message.setText("Open Android Settings → Accessibility → Bear Steam dashboard, enable it, then return here. If that setting is unavailable, use Mr. Steam and report what the panel shows.");}
  });root.addView(allow);
  Button back=new Button(this);back.setAllCaps(false);back.setText("Back to Mr. Steam");back.setOnClickListener(v->{try{Intent intent=getPackageManager().getLaunchIntentForPackage("com.yizhong");if(intent!=null)startActivity(intent);}catch(Exception ignored){}finish();});root.addView(back);
  setContentView(root);if(DashboardAccessService.available())open();
 }
 @Override protected void onResume(){super.onResume();resumed=true;main.post(checkConnection);}
 @Override protected void onPause(){resumed=false;main.removeCallbacks(checkConnection);super.onPause();}
 @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);super.onDestroy();}
 private void open(){
  if(opening||!DashboardAccessService.available())return;opening=true;waiting=false;
  try{
   Intent original=getPackageManager().getLaunchIntentForPackage("com.yizhong");
   if(original==null)throw new IllegalStateException();
   startActivity(original);
   startForegroundService(new Intent(this,DashboardService.class).setAction("show"));finish();
  }catch(Exception e){opening=false;message.setText("Mr. Steam could not be opened. Open its normal controls first, then reopen Bear Steam.");}
 }
}
