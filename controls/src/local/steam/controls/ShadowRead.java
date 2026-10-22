package local.steam.controls;

import java.util.UUID;

/** Device reports plus bounded status-only requests; isolated from equipment commands. */
public final class ShadowRead {
 public static final class ReadFailure extends Exception {
  public final int code;
  public ReadFailure(int code){super("Reader failed");this.code=code;}
 }
 public interface Message {void received(String payload);}
 public interface Wire {
  void connect(String clientId,Runnable disconnected)throws Exception;
  void subscribe(String topic,Message receive)throws Exception;
  void requestStatus(String thing)throws Exception;
  void close();
 }
 public interface Observer {void changed(String stage);}
 private final Wire wire;private final ControlEngine.Guard guard;private final Observer observer;
 private final String base,thing;
 private long lastRefresh=-1;
 private final LightFeed feed=new LightFeed();
 private final ReadMetrics metrics=new ReadMetrics();
 private volatile boolean open,closed;
 private volatile String stage="Not checked";
 public ShadowRead(String thing,Wire wire,ControlEngine.Guard guard,Observer observer){
  if(!LightPayload.ownThing(thing))throw new IllegalArgumentException("Unknown panel");
  this.thing=thing;base="$aws/things/"+thing+"/shadow";this.wire=wire;this.guard=guard;this.observer=observer;
 }
 private void mark(String value){if(!value.equals(stage)){stage=value;observer.changed(value);}}
 private void allowed()throws Exception{if(closed||!guard.allowed())throw new Exception("Read canceled");}
 public String stage(){return stage;}
 public ReadMetrics.Snapshot metrics(){return metrics.snapshot();}
 public LightReport report(){return open&&!closed&&guard.allowed()?feed.current():null;}
 public boolean available(){return open&&!closed&&guard.allowed();}
 /** Broker receipt does not refresh the state. Only a subsequent device report can. */
 public synchronized void refresh(long wallTime,long elapsed){
  if(!available()||elapsed<0||(lastRefresh>=0&&elapsed-lastRefresh<30000))return;
  LightReport current=report();
  if(current!=null&&current.usable(wallTime)&&current.freshUntil-wallTime>60000)return;
  lastRefresh=elapsed;
  try{allowed();wire.requestStatus(thing);}catch(Exception unavailable){if(available())mark("Status refresh request failed");}
 }
 public void start(){
  String operation="Connecting status reader";
  try{
   allowed();mark("Connecting status reader");
   wire.connect("app-bearread"+UUID.randomUUID().toString().replace("-","").substring(0,10)+"-dev",()->{
    open=false;feed.clear();if(!closed)mark("Status reader disconnected");
   });
   allowed();operation="Checking live-update subscription";mark(operation);
   wire.subscribe(base+"/update/accepted",this::receive);
   allowed();open=true;
   LightReport initial=feed.current();
   stage=initial==null?"Live subscription accepted; waiting for reported lights":initial.usable(System.currentTimeMillis())?"Live light report received":"Device light report is old";
   // A report can arrive before subscribe() returns. Notify again with open=true
   // so the owner sees that report rather than a false waiting state.
   observer.changed(stage);
  }catch(Exception e){
   close();mark(operation+" failed"+(e instanceof ReadFailure?" (MQTT "+((ReadFailure)e).code+")":""));
  }
 }
 private void receive(String payload){
  if(closed||!guard.allowed())return;
  metrics.observe(payload);
  if(feed.accept(payload,System.currentTimeMillis())){
   metrics.accepted();
   LightReport r=feed.current();
   // A new On/Off report must notify even when the descriptive connection stage is unchanged.
   stage=r==null?"Device reports Chroma disconnected":r.usable(System.currentTimeMillis())?"Live light report received":"Device light report is old";
  }
  // Counts distinguish no delivery from desired-only traffic or rejected telemetry.
  observer.changed(stage);
 }
 public void close(){closed=true;open=false;feed.clear();wire.close();}
}
