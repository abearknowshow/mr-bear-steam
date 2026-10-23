package local.steam.controls;

/** Short bursts of lost reads stay hidden (10 s grace since 0.24); a longer outage, or no read yet, shows the bridge as not connected. */
public final class StatusGraceTest {
 static int checks;static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
 public static void main(String[]args){
  long t=1_000_000;
  check(!StatusGrace.shown(t,0),"no good read yet is not connected");
  check(StatusGrace.shown(t,t),"a read just now is connected");
  check(StatusGrace.shown(t+2300,t),"between polls stays connected");
  // Timeline, 2026-10-21: last ok, a 2.0 s lost read, the next good read 4.3 s after the last ok.
  check(StatusGrace.shown(t+4300,t),"one lost read does not flash Reconnecting");
  // 0.24 (issue #8): two lost reads (2.3 + 2 + 0.25 + 2 s) plus a quick relink and a good read, about 7.5 s, stay hidden.
  check(StatusGrace.shown(t+7500,t),"two lost reads and a quick relink do not flash Reconnecting");
  check(StatusGrace.shown(t+9999,t),"just inside the grace");
  check(!StatusGrace.shown(t+10_000,t),"grace ends at 10 s");
  check(!StatusGrace.shown(t+15_000,t),"a longer outage still shows Reconnecting");
  check(StatusGrace.GRACE_MS<20000,"grace stays well inside the 20 s controls lock");
  check(StatusGrace.RETRY_MS<StatusGrace.POLL_MS&&StatusGrace.RETRY_MS>=100,"a failed read is retried sooner, but not in a tight loop");
  System.out.println("Status grace checks passed: "+checks);
 }
}
