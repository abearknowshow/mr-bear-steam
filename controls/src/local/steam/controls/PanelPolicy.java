package local.steam.controls;

/** 0.21 decisions for opening on wake, the guard after an automatic open, keep-screen-on and the accent.
 *  Pure: no Android types, so verify.py runs it on the Mac. Owner decisions: STARTUP-DESIGN.md. */
public final class PanelPolicy {
 private PanelPolicy(){}
 /** After an automatic open, every command except Stop is refused for this long (the tap that woke the screen). */
 public static final long GUARD_MS=1500;
 /** With steam off, the screen may sleep once Bear has gone this long without a touch (Mr. Steam's own idle timeout). */
 public static final long IDLE_AWAKE_MS=5*60_000L;
 /** The accessibility service connecting within this long of boot means a restart, not a settings toggle. */
 public static final long BOOT_WINDOW_MS=180_000L;
 /** The one open after boot waits until this long after boot, so Mr. Steam has signed itself in. */
 public static final long BOOT_OPEN_MS=45_000L;
 /** Automatic opens in a row that never loaded the page before the setting turns itself off. */
 public static final int MAX_FAILED_OPENS=2;
 /** Repeated screen-on broadcasts within this window open once. */
 public static final long DEBOUNCE_MS=3000;
 public static final String DEFAULT_ACCENT="#FFFFFF";
 /** White, lime, cyan, amber, orange: the only values the page may store. */
 public static final String[] ACCENTS={"#FFFFFF","#C6FF3D","#3DE0FF","#F2B56B","#FF9900"};

 /** Whether an automatic open may run now. A failure count at the limit turns the setting off instead. */
 public static boolean mayAutoOpen(boolean enabled,int failedOpens){return enabled&&failedOpens<MAX_FAILED_OPENS;}
 public static boolean shouldDisable(boolean enabled,int failedOpens){return enabled&&failedOpens>=MAX_FAILED_OPENS;}
 /** Delay until the boot open, or -1 when the service connected long after boot. */
 public static long bootDelay(long uptimeMs){return uptimeMs<0||uptimeMs>BOOT_WINDOW_MS?-1:Math.max(0,BOOT_OPEN_MS-uptimeMs);}
 public static boolean debounced(long now,long lastOpen){return lastOpen<=0||now-lastOpen>=DEBOUNCE_MS;}
 public static long guardUntil(boolean automatic,long now){return automatic?now+GUARD_MS:0;}
 public static boolean guarded(long now,long guardUntil){return now<guardUntil;}
 /** Stop is never guarded; everything else waits out the guard. */
 public static boolean commandAllowed(String action,long now,long guardUntil){return "stop".equals(action)||!guarded(now,guardUntil);}
 /** Steam running (or a Start/Stop in progress) keeps the screen on; otherwise only a touch in the last five minutes. */
 public static boolean keepScreenOn(boolean steamActive,long now,long lastTouch){return steamActive||(lastTouch>0&&now>=lastTouch&&now-lastTouch<IDLE_AWAKE_MS);}
 /** The steam-off reading that allows an idle sleep must be at least this fresh. */
 public static final long SLEEP_STATUS_MS=10_000L;
 /** 0.23: the panel's own screen timeout never fired (the screen stayed on 33 minutes with keep-screen-on released),
  *  so Bear turns the screen off itself once it has been idle as long as keepScreenOn allows. Only with steam confirmed
  *  off by a fresh reading: Mr. Steam stops steam when the screen goes off, so unknown or stale state never sleeps. */
 public static boolean shouldSleep(boolean steamActive,boolean steamKnownOff,long statusAgeMs,long now,long lastTouch){
  return !steamActive&&steamKnownOff&&statusAgeMs>=0&&statusAgeMs<=SLEEP_STATUS_MS&&lastTouch>0&&now>=lastTouch&&now-lastTouch>=IDLE_AWAKE_MS;
 }
 /** 0.22: a remembered light look must be in the page's own ranges: hue 0-359, saturation 0-100, brightness 1-100. */
 public static boolean validLook(int hue,int saturation,int brightness){return hue>=0&&hue<360&&saturation>=0&&saturation<=100&&brightness>=1&&brightness<=100;}
 /** Canonical form of an allowed accent, or null for anything else. */
 public static String accent(String value){
  if(value==null)return null;
  for(String allowed:ACCENTS)if(allowed.equalsIgnoreCase(value.trim()))return allowed;
  return null;
 }
}
