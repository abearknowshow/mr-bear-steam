package local.steam.controls;

import android.content.Context;
import android.content.SharedPreferences;

/** Owner settings kept on the panel: accent, open on wake, the failed automatic open count (0.21) and the last light look (0.22). No account data. */
final class PanelSettings {
 private static final String FILE="bear-panel",ACCENT="accent",OPEN_ON_WAKE="openOnWake",FAILED_OPENS="failedOpens",
  LOOK_HUE="lookHue",LOOK_SATURATION="lookSaturation",LOOK_BRIGHTNESS="lookBrightness",LOOK_DISCO="lookDisco";
 private final SharedPreferences prefs;
 PanelSettings(Context context){prefs=context.getApplicationContext().getSharedPreferences(FILE,Context.MODE_PRIVATE);}
 String accent(){String a=PanelPolicy.accent(prefs.getString(ACCENT,null));return a==null?PanelPolicy.DEFAULT_ACCENT:a;}
 /** Stores only one of the offered accents; anything else is refused. */
 boolean setAccent(String value){String a=PanelPolicy.accent(value);if(a==null)return false;prefs.edit().putString(ACCENT,a).apply();return true;}
 boolean openOnWake(){return prefs.getBoolean(OPEN_ON_WAKE,true);}
 /** Switching it either way also clears the failure count, so a deliberate "on" gets a fresh start. */
 void setOpenOnWake(boolean on){prefs.edit().putBoolean(OPEN_ON_WAKE,on).putInt(FAILED_OPENS,0).apply();}
 int failedOpens(){return prefs.getInt(FAILED_OPENS,0);}
 /** Counted before an automatic open; cleared when the page finishes loading. commit(): a crash must not lose it. */
 void countAutomaticOpen(){prefs.edit().putInt(FAILED_OPENS,failedOpens()+1).commit();}
 /** The last look chosen on Bear as {hue, saturation, brightness, disco 0/1}, or null before the first choice.
  *  The light reports only on or off, so this is what the page shows after a wake or a restart. */
 int[] look(){
  if(!prefs.contains(LOOK_HUE))return null;
  int h=prefs.getInt(LOOK_HUE,-1),s=prefs.getInt(LOOK_SATURATION,-1),b=prefs.getInt(LOOK_BRIGHTNESS,-1);
  return PanelPolicy.validLook(h,s,b)?new int[]{h,s,b,prefs.getBoolean(LOOK_DISCO,false)?1:0}:null;
 }
 boolean setLook(int hue,int saturation,int brightness,boolean disco){
  if(!PanelPolicy.validLook(hue,saturation,brightness))return false;
  prefs.edit().putInt(LOOK_HUE,hue).putInt(LOOK_SATURATION,saturation).putInt(LOOK_BRIGHTNESS,brightness).putBoolean(LOOK_DISCO,disco).apply();return true;
 }
 void pageLoaded(){if(failedOpens()!=0)prefs.edit().putInt(FAILED_OPENS,0).apply();}
}
