package local.steam.controls;

/** A passive reader may outlive the UI only for an explicit, bounded original-app comparison. */
public final class ReadWindow {
 public static final long WATCH_MS=90000;
 private long generation,deadline;private boolean visible;
 public synchronized long resume(){generation++;visible=true;deadline=0;return generation;}
 public synchronized void watchOriginal(long now){if(visible)deadline=now+WATCH_MS;}
 public synchronized void cancelWatch(){deadline=0;}
 public synchronized void pause(){visible=false;if(deadline==0)generation++;}
 public synchronized void close(){visible=false;deadline=0;generation++;}
 public synchronized boolean allows(long visit,long now){return generation==visit&&(visible||now<deadline);}
 public synchronized boolean observing(long now){return !visible&&now<deadline;}
 public synchronized long generation(){return generation;}
}
