package local.steam.controls;
import java.nio.file.*;
import java.util.*;
public final class EngineTest {
 static int checks;static byte[]fixture;
 static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
 interface Work{void run()throws Exception;}
 static void fails(Work work,String message)throws Exception{try{work.run();throw new AssertionError(message);}catch(AssertionError e){throw e;}catch(Exception expected){checks++;}}
 static final class Fake implements ControlEngine.Transport,ControlEngine.Guard,ControlEngine.Clock{
  byte[]b=fixture.clone();List<String>writes=new ArrayList<>();boolean armed=true,apply=true,failRead=false,cancelAfterWrite=false;long time;Runnable onPause;
  int failReads;java.util.function.BooleanSupplier abandon;Boolean abandonDuringStop;
  List<String>log=new ArrayList<>();Set<String>ignore=new HashSet<>();List<Boolean>idleAtRead=new ArrayList<>();int failAfterTime;
  ControlEngine engine=new ControlEngine(this,this,this);
  public boolean allowed(){return armed;}public long now(){return time;}public void pause(long ms){time+=ms;if(onPause!=null)onPause.run();}
  void word(int p,int v){b[p]=(byte)(v>>8);b[p+1]=(byte)v;}
  public void abandonReadsWhen(java.util.function.BooleanSupplier a){abandon=a;}
  java.util.function.BooleanSupplier idle;public void refreshMapWhen(java.util.function.BooleanSupplier i){idle=i;}
  public Protocol.Status read()throws Exception{log.add("read");if(idle!=null)idleAtRead.add(idle.getAsBoolean());if(failAfterTime>0&&log.contains("time")){failAfterTime--;throw new Exception("lost reply");}if(failReads>0){failReads--;throw new Exception("lost reply");}if(failRead)throw new Exception("offline");return Protocol.decode(b);}
  public boolean write(String action,int value)throws Exception{
   if(action.equals("stop"))abandonDuringStop=abandon.getAsBoolean();
   writes.add(action+":"+value);log.add(action);if(cancelAfterWrite)armed=false;if(!apply||ignore.contains(action))return true;
   // Modeled on the 2026-10-21 heated session: Start sets mode, heater and countdown but never bit 269.0.
   switch(action){case "start":b[256]=1;b[295]|=4;word(293,Protocol.word(b,261));break;
    case "stop":b[256]=0;b[269]&=~1;b[295]&=~4;word(293,0);break;
    case "temperature":word(264,value*10-320);break;
    case "time":word(261,value);if(b[256]==1&&Protocol.word(b,293)>0)word(293,value);break;
    case "lights":b[259]=(byte)value;if(value==1)b[295]|=16;else b[295]&=~16;break;}
   return false; // Receipt loss: real readback still confirms success.
  }
 }
 public static void main(String[]args)throws Exception{
  fixture=Files.readAllBytes(Paths.get(args[0]));
  Fake f=new Fake();f.engine.refresh();check(f.writes.isEmpty(),"read never writes");
  final Fake locked=new Fake();locked.armed=false;fails(()->locked.engine.execute("start",115,20),"lock");check(locked.writes.isEmpty(),"locked no writes");
  final Fake fault=new Fake();fault.b[272]=1;fails(()->fault.engine.execute("start",115,20),"fault start blocked");check(fault.writes.isEmpty(),"fault no writes");
  final Fake bounds=new Fake();fails(()->bounds.engine.execute("start",121,20),"temp bounds");fails(()->bounds.engine.execute("start",115,61),"time bounds");check(bounds.writes.isEmpty(),"out of bounds no writes");
  final Fake recentButOffline=new Fake();recentButOffline.engine.refresh();recentButOffline.failRead=true;
  fails(()->recentButOffline.engine.execute("start",115,20),"cached off status cannot authorize Start without a fresh read");
  check(recentButOffline.writes.isEmpty(),"missed pre-Start read sends no configuration or Start commands");
  f=new Fake();Protocol.Status started=f.engine.execute("start",115,20);
  check(started.sessionActive()&&!started.on,"start confirms from the countdown although bit 269.0 stays clear");
  check(!f.engine.startUncertain,"confirmed start does not leave Bear stuck on STOP");check(f.writes.equals(Arrays.asList("start:1")),"single start");
  final Fake running=f;fails(()->running.engine.execute("start",115,20),"duplicate start");check(f.writes.size()==1,"no duplicate packet");
  f.engine.execute("time",21,0);check(f.engine.latest.remaining==21,"absolute target 21");f.engine.execute("time",18,0);check(f.engine.latest.remaining==18,"settled target 18");
  check(f.writes.subList(1,3).equals(Arrays.asList("time:21","time:18")),"time writes are absolute remaining minutes");
  final Fake zero=f;int before=f.writes.size();fails(()->zero.engine.execute("time",0,0),"zero minutes rejected");fails(()->zero.engine.execute("time",61,0),"over the device limit rejected");
  fails(()->zero.engine.execute("time",-1,0),"negative rejected");zero.engine.execute("time",18,0);check(f.writes.size()==before,"rejected and already-matching targets send nothing");
  f.engine.execute("temperature",114,0);check(f.engine.latest.targetF==114,"temp readback");
  f.engine.execute("lights",1,0);f.engine.execute("stop",0,0);check(!f.engine.latest.possiblyOn()&&f.engine.latest.lightOn,"stop leaves lights");
  // Exact readings from the 2026-10-21 session: mode 1, heater on, 4 minutes left, bit 269.0 clear.
  final Fake observed=new Fake();observed.b[256]=1;observed.b[295]|=4;observed.word(261,5);observed.word(293,4);observed.b[269]&=~1;
  observed.engine.refresh();check(observed.engine.latest.sessionActive()&&!observed.engine.latest.on,"observed session is active");
  observed.engine.execute("time",5,0);check(observed.writes.equals(Arrays.asList("time:5"))&&observed.engine.latest.remaining==5,"target 5 accepted in the observed session");
  final Fake idleTime=new Fake();fails(()->idleTime.engine.execute("time",5,0),"no time write without a session");check(idleTime.writes.isEmpty(),"idle time sends nothing");
  final Fake expired=new Fake();expired.b[256]=1;expired.word(293,0);expired.engine.refresh();check(!expired.engine.latest.sessionActive()&&expired.engine.latest.possiblyOn(),"requested mode without countdown is not a session but still shows STOP");
  f=new Fake();f.engine.execute("start",114,15);check(f.writes.equals(Arrays.asList("temperature:114","time:15","start:1")),"settings before start");
  final Fake cancelled=new Fake();cancelled.cancelAfterWrite=true;fails(()->cancelled.engine.execute("start",114,15),"cancel setup");check(cancelled.writes.equals(Arrays.asList("temperature:114")),"background cancels start");
  final Fake unconfirmed=new Fake();unconfirmed.apply=false;fails(()->unconfirmed.engine.execute("start",115,20),"receipt is not success");check(unconfirmed.engine.startUncertain,"ambiguous start sticky");check(unconfirmed.writes.size()==1,"no auto retries");fails(()->unconfirmed.engine.execute("start",115,20),"unsafe second start");check(unconfirmed.writes.size()==1,"blocked second write");
  unconfirmed.engine.execute("stop",0,0);check(!unconfirmed.engine.startUncertain,"verified off clears ambiguity");
  final Fake disconnected=new Fake();disconnected.engine.refresh();disconnected.failRead=true;fails(()->disconnected.engine.execute("stop",0,0),"offline stop unconfirmed");check(disconnected.writes.equals(Arrays.asList("stop:0")),"offline stop attempted once");
  final Fake unknown=new Fake();fails(()->unknown.engine.execute("stop",0,0),"unidentified stop blocked");check(unknown.writes.isEmpty(),"unknown no command");
  final Fake acknowledgedButOn=new Fake();acknowledgedButOn.b[256]=1;acknowledgedButOn.b[269]|=1;acknowledgedButOn.b[295]|=4;
  acknowledgedButOn.engine.refresh();acknowledgedButOn.apply=false;
  fails(()->acknowledgedButOn.engine.execute("stop",0,0),"receipt cannot confirm Stop while still on");
  check(acknowledgedButOn.engine.startUncertain,"unconfirmed Stop blocks a subsequent Start");
  final Fake heaterStillOn=new Fake();heaterStillOn.b[295]|=4;heaterStillOn.engine.refresh();heaterStillOn.apply=false;
  fails(()->heaterStillOn.engine.execute("stop",0,0),"off session bit cannot conceal active heater");
  final Fake modeStillOn=new Fake();modeStillOn.b[256]=1;modeStillOn.engine.refresh();modeStillOn.apply=false;
  fails(()->modeStillOn.engine.execute("stop",0,0),"off session bit cannot conceal requested ON");
  // The device ignoring +1 leaves remaining one below the target; exact matching must not accept it.
  final Fake badTime=new Fake();badTime.engine.execute("start",115,20);badTime.apply=false;fails(()->badTime.engine.execute("time",21,0),"ignored +1 is unconfirmed");check(badTime.engine.runtimeBlocked,"time block after mismatch");
  final Fake blocked=badTime;int sent=badTime.writes.size();fails(()->blocked.engine.execute("time",22,0),"blocked time stays blocked");check(badTime.writes.size()==sent,"blocked time sends nothing");
  // Stop cuts in: a pending Stop blocks new writes and ends an in-flight confirmation early.
  final Fake queued=new Fake();queued.engine.execute("start",115,20);int w=queued.writes.size();queued.engine.stopPending=true;
  fails(()->queued.engine.execute("temperature",114,0),"change after Stop tap is refused");check(queued.writes.size()==w,"no write after Stop tap");
  queued.engine.execute("stop",0,0);check(!queued.engine.latest.possiblyOn(),"Stop still runs while pending");
  final Fake slow=new Fake();slow.engine.execute("start",115,20);slow.apply=false;long t0=slow.time;
  slow.onPause=()->slow.engine.stopPending=true;
  fails(()->slow.engine.execute("time",25,0),"in-flight time change abandoned for Stop");
  check(slow.time-t0<1000,"abandoned within one poll, not the 8 s window");check(!slow.engine.runtimeBlocked,"Stop-abandoned time change does not lock time controls");
  slow.apply=true;slow.engine.execute("stop",0,0);slow.engine.stopPending=false;check(!slow.engine.latest.possiblyOn(),"Stop completes after abandoning");
  final Fake sameTemp=new Fake();sameTemp.engine.execute("start",115,20);int s0=sameTemp.writes.size();sameTemp.engine.execute("temperature",115,0);check(sameTemp.writes.size()==s0,"temperature already at target sends nothing");
  // A lost reply before a change is read again, never dropped (emulator run, 2026-10-21: 117°F was never sent).
  final Fake lost=new Fake();lost.engine.execute("start",115,20);int l0=lost.writes.size();lost.failReads=1;lost.engine.execute("temperature",114,0);
  check(lost.writes.subList(l0,lost.writes.size()).equals(Arrays.asList("temperature:114")),"one lost pre-read still sends the change once");
  lost.failReads=2;lost.engine.execute("time",17,0);check(lost.engine.latest.remaining==17,"two lost pre-reads still change time");
  final Fake gone=new Fake();gone.engine.execute("start",115,20);int g0=gone.writes.size();gone.failReads=3;
  try{gone.engine.execute("temperature",114,0);throw new AssertionError("three lost reads must fail");}catch(Exception e){check("Steamlinx didn't answer. Try again.".equals(e.getMessage()),"plain message after three lost reads");}
  check(gone.writes.size()==g0,"no write without a fresh read");check(!gone.engine.runtimeBlocked,"a lost read does not lock time controls");
  final Fake lostStart=new Fake();lostStart.failReads=1;check(lostStart.engine.execute("start",115,20).sessionActive(),"Start survives one lost pre-read");check(lostStart.writes.equals(Arrays.asList("start:1")),"still a single Start packet");
  final Fake stopDuringRetry=new Fake();stopDuringRetry.engine.execute("start",115,20);int r0=stopDuringRetry.writes.size();stopDuringRetry.failReads=5;
  stopDuringRetry.onPause=()->stopDuringRetry.engine.stopPending=true;
  fails(()->stopDuringRetry.engine.execute("temperature",114,0),"Stop during pre-read retry ends the change");check(stopDuringRetry.writes.size()==r0,"no write after Stop during retry");
  // Reads give way to a waiting Stop, but never Stop's own confirmation reads.
  check(stopDuringRetry.abandon.getAsBoolean(),"in-flight reads give way once Stop is tapped");
  stopDuringRetry.failReads=0;stopDuringRetry.engine.execute("stop",0,0);check(Boolean.FALSE.equals(stopDuringRetry.abandonDuringStop),"Stop's own reads are not abandoned");
  stopDuringRetry.engine.stopPending=false;check(!stopDuringRetry.abandon.getAsBoolean(),"no abandoning without a pending Stop");
  // The time lock belongs to the session whose change went unconfirmed.
  badTime.apply=true;badTime.engine.execute("stop",0,0);badTime.engine.execute("start",115,20);check(!badTime.engine.runtimeBlocked,"a confirmed new session clears the time lock");
  badTime.engine.execute("time",22,0);check(badTime.engine.latest.remaining==22,"time works again in the new session");
  // 0.20: settings before Start go out back to back and one read confirms both; that read also clears Start.
  final Fake paired=new Fake();paired.engine.execute("start",114,15);
  check(paired.log.equals(Arrays.asList("read","temperature","time","read","start","read")),"settings back to back, one confirming read, then Start");
  final Fake ready=new Fake();ready.engine.execute("start",115,20);
  check(ready.log.equals(Arrays.asList("read","start","read")),"settings already right: no extra read before Start");
  final Fake lostSetting=new Fake();lostSetting.ignore.add("time");
  try{lostSetting.engine.execute("start",114,15);throw new AssertionError("ignored time write must stop Start");}catch(Exception e){check("Settings unconfirmed. Check Mr. Steam.".equals(e.getMessage()),"plain message when a setting is not confirmed");}
  check(!lostSetting.writes.contains("start:1"),"no Start after an unconfirmed setting");check(!lostSetting.engine.startUncertain,"no Start was sent, so nothing is uncertain");
  // The read right after a write is often lost on the real bridge; the engine keeps reading inside its 8 s window.
  final Fake confirmLost=new Fake();confirmLost.failAfterTime=2;confirmLost.engine.execute("start",114,15);
  check(confirmLost.writes.equals(Arrays.asList("temperature:114","time:15","start:1")),"lost confirming reads are read again; one Start");
  final Fake stopDuringSettings=new Fake();stopDuringSettings.apply=false;stopDuringSettings.onPause=()->stopDuringSettings.engine.stopPending=true;
  fails(()->stopDuringSettings.engine.execute("start",114,15),"Stop during settings ends Start");check(!stopDuringSettings.writes.contains("start:1"),"no Start after Stop during settings");
  // The slow full map refresh waits for idle reads: never during a command.
  final Fake mapped=new Fake();mapped.engine.refresh();check(mapped.idleAtRead.get(0),"idle poll may refresh the map");
  mapped.idleAtRead.clear();mapped.engine.execute("start",114,15);check(!mapped.idleAtRead.isEmpty()&&!mapped.idleAtRead.contains(true),"no map refresh during a command");
  mapped.idleAtRead.clear();mapped.engine.refresh();check(mapped.idleAtRead.equals(Arrays.asList(true)),"idle again after the command");
  final Fake failing=new Fake();failing.failRead=true;try{failing.engine.execute("temperature",114,0);}catch(Exception expected){}
  failing.failRead=false;failing.idleAtRead.clear();failing.engine.refresh();check(failing.idleAtRead.equals(Arrays.asList(true)),"a failed command still returns to idle");
  System.out.println("Engine checks passed: "+checks);
 }
}
