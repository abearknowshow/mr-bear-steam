package local.steam.controls;

/** One lost read stays hidden; two in a row, or no read yet, show the bridge as not connected. */
public final class StatusGraceTest {
 static int checks;static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
 public static void main(String[]args){
  long t=1_000_000;
  check(!StatusGrace.shown(t,0),"no good read yet is not connected");
  check(StatusGrace.shown(t,t),"a read just now is connected");
  check(StatusGrace.shown(t+2300,t),"between polls stays connected");
  // Timeline, 2026-10-21: last ok, a 2.0 s lost read, the next good read 4.3 s after the last ok.
  check(StatusGrace.shown(t+4300,t),"one lost read does not flash Reconnecting");
  check(StatusGrace.shown(t+5999,t),"just inside the grace");
  check(!StatusGrace.shown(t+6000,t),"grace ends at 6 s");
  check(!StatusGrace.shown(t+8600,t),"two lost reads in a row show Reconnecting");
  check(StatusGrace.GRACE_MS<20000,"grace stays well inside the 20 s controls lock");
  System.out.println("Status grace checks passed: "+checks);
 }
}
