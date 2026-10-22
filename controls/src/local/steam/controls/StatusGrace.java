package local.steam.controls;

/** Whether the page is told the bridge is connected. Pure timing: it never reads, sends or fakes state. */
public final class StatusGrace {
 // A poll every ~2.3 s, and a lost read takes 2 s, so one lost read leaves the last good read about 4.3 s old.
 // During heating the bridge drops about one read a minute (attended session, 2026-10-21); that alone must not
 // flash "Reconnecting…". Two lost reads in a row (about 8.6 s, then rediscovery) still show it.
 static final long GRACE_MS=6000;
 private StatusGrace(){}
 public static boolean shown(long now,long lastSuccess){return lastSuccess!=0&&now-lastSuccess<GRACE_MS;}
}
