package local.steam.controls;
/** Resolves the owner bridge on reads. A command never triggers discovery or moves itself. */
public final class BridgeTransport implements ControlEngine.Transport {
 interface Locator{String locate()throws Exception;}interface Factory{ControlEngine.Transport create(String host)throws Exception;}
 /** Whether the bridge Bear last used still answers as the owner SPA (one quick HELLO, no network-wide search). */
 interface Confirmer{boolean confirm(String host);}
 private final Locator locator;private final Factory factory;private final Confirmer confirmer;
 private ControlEngine.Transport current,stopTarget,lastTransport;private String lastHost;private int failures;private boolean verified;
 public BridgeTransport(boolean writes){this(()->new BridgeLocator().locate(),host->new UdpTransport(host,10022,writes),host->new BridgeLocator().confirm(host));}
 BridgeTransport(Locator l,Factory f){this(l,f,host->false);}
 BridgeTransport(Locator l,Factory f,Confirmer c){locator=l;factory=f;confirmer=c;}
 private volatile java.util.function.BooleanSupplier abandon=()->false;
 public synchronized void abandonReadsWhen(java.util.function.BooleanSupplier a){abandon=a;if(lastTransport!=null)lastTransport.abandonReadsWhen(a);}
 private volatile java.util.function.BooleanSupplier idle=()->true;
 public synchronized void refreshMapWhen(java.util.function.BooleanSupplier i){idle=i;if(lastTransport!=null)lastTransport.refreshMapWhen(i);}
 public synchronized Protocol.Status read()throws Exception{
  // Checked before the failure count: giving way to Stop says nothing about the bridge.
  if(abandon.getAsBoolean()){Timeline.note("bridge.read","ok",false,"kind","none","ms",0,"reason","abandoned");throw new java.net.SocketTimeoutException("Read abandoned for Stop.");}
  try{
   ControlEngine.Transport candidate=current;
   // 0.24 (issue #8): every network-wide search found the same bridge, and each cost 1.8 s. After a reset, one
   // HELLO to the last bridge comes first; the full search runs only when that address no longer answers as the owner SPA.
   if(candidate==null&&lastHost!=null&&lastTransport!=null){
    long started=System.nanoTime();boolean ok=confirmer.confirm(lastHost);
    Timeline.note("bridge.relink","ok",ok,"ms",(System.nanoTime()-started)/1000000);
    if(ok)candidate=lastTransport;
   }
   if(candidate==null){
    // Rediscovery usually finds the same bridge; reusing its transport keeps the cached full map,
    // so recovery is one live read instead of a 4 s full read that times out under contention.
    // Timeline events carry whether the host and transport were kept, never the address itself.
    long started=System.nanoTime();Timeline.note("bridge.locate.start");String host;
    try{host=locator.locate();}
    catch(Exception e){Timeline.note("bridge.locate.end","ok",false,"ms",(System.nanoTime()-started)/1000000,"error",Timeline.text(e));throw e;}
    boolean sameHost=host.equals(lastHost),reused=sameHost&&lastTransport!=null;
    candidate=host.equals(lastHost)&&lastTransport!=null?lastTransport:factory.create(host);
    Timeline.note("bridge.locate.end","ok",true,"ms",(System.nanoTime()-started)/1000000,"sameHost",sameHost,"reused",reused);
    lastHost=host;lastTransport=candidate;candidate.abandonReadsWhen(abandon);candidate.refreshMapWhen(idle);
   }
   // UdpTransport verifies the known SPA envelope, register map and complete status.
   Protocol.Status status=candidate.read();current=candidate;stopTarget=candidate;failures=0;verified=true;return status;
  }catch(Exception failure){
   verified=false;
   // The bridge drops replies when another client is reading. Rediscovery (about 1.8 s plus a
   // full map read) is kept for repeated failures, so one lost reply costs one retry, not a reset.
   // A read cut short for Stop is not counted, so Stop's own confirmation reads skip rediscovery.
   if(!abandon.getAsBoolean()&&++failures>=2){current=null;failures=0;Timeline.note("bridge.reset","why","two failed reads; next read greets the last bridge, then searches if needed");}
   else Timeline.note("bridge.fail","failures",failures,"abandon",abandon.getAsBoolean());
   throw failure;
  }
 }
 public synchronized boolean write(String action,int value)throws Exception{
  // Only Stop may follow a lost read; every other command needs the latest read to have succeeded.
  ControlEngine.Transport destination=action.equals("stop")?stopTarget:verified?current:null;
  if(destination==null)throw new Exception("No verified bridge connection. Use Mr. Steam.");
  return destination.write(action,value);
 }
}
