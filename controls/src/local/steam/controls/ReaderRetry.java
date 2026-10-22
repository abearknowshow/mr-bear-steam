package local.steam.controls;

/** Bounded reopen schedule for the status-only reader. Pure timing: it never sends, reads or fakes state. */
public final class ReaderRetry {
 static final long[] DELAYS_MS={10000,20000,40000,80000,160000,300000};
 private long visit=Long.MIN_VALUE,since=-1;private int attempts;
 /** True when the caller should reopen the reader now. Hidden Bear or no account session never retries. */
 public synchronized boolean due(long visit,boolean visible,boolean ready,boolean available,long now){
  if(visit!=this.visit){this.visit=visit;attempts=0;since=-1;}
  if(!visible||!ready){since=-1;return false;}
  if(available){attempts=0;since=-1;return false;}
  if(since<0){since=now;return false;}
  if(attempts>=DELAYS_MS.length||now-since<DELAYS_MS[attempts])return false;
  attempts++;since=now;return true;
 }
 public synchronized boolean exhausted(){return attempts>=DELAYS_MS.length;}
}
