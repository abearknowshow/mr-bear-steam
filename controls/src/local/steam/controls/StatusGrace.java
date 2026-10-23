package local.steam.controls;

/** Whether the page is told the bridge is connected. Pure timing: it never reads, sends or fakes state. */
public final class StatusGrace {
 // A poll every ~2.3 s, and a lost read takes 2 s, so one lost read leaves the last good read about 4.3 s old.
 // 0.24 (issue #8): one night showed the bridge going silent in short bursts, usually two lost reads in a row, and
 // "Reconnecting…" about 7% of the time. A failed read is now retried after RETRY_MS instead of POLL_MS, and the
 // grace is 10 s, so two lost reads plus the quick relink (about 7.5 s) stay hidden. Commands never rely on this:
 // each needs its own fresh read, and Stop always works.
 static final long GRACE_MS=10_000;
 /** Time between polls after a good read, and after a failed one. */
 public static final long POLL_MS=2000,RETRY_MS=250;
 // 0.26 (issue #8): the bridge sometimes stops answering status for 10-40 s at a time, even with steam off and no
 // command near (0.25 run: about 2 minutes after a session ended). With steam confirmed off and nothing waiting there is
 // nothing to watch, so the page stays quiet this long. Start still needs its own fresh read and refuses without one.
 static final long QUIET_MS=60_000;
 private StatusGrace(){}
 public static boolean shown(long now,long lastSuccess){return lastSuccess!=0&&now-lastSuccess<GRACE_MS;}
 /** Steam confirmed off by the last good read, no command or uncertain Start, and that read under QUIET_MS old. */
 public static boolean quiet(long now,long lastSuccess,boolean steamKnownOff,boolean idle){return steamKnownOff&&idle&&lastSuccess!=0&&now>=lastSuccess&&now-lastSuccess<QUIET_MS;}
}
