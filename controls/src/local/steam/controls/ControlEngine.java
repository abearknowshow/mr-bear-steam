package local.steam.controls;

/** No scheduling, automatic Start retries, root, sensor writes, or valve commands. */
public final class ControlEngine {
 public interface Transport {Protocol.Status read()throws Exception;boolean write(String action,int value)throws Exception;
  /** A read in progress may give up early while this returns true (Stop is waiting behind it).
   *  Deliberately not a default method: a wrapper that forgot to forward it left Stop slow (emulator, 2026-10-21). */
  void abandonReadsWhen(java.util.function.BooleanSupplier abandon);
  /** The slow full map refresh may run only while this returns true (no command in flight). Not a default, as above. */
  void refreshMapWhen(java.util.function.BooleanSupplier idle);}
 public interface Guard {boolean allowed();}
 public interface Clock {long now();void pause(long ms)throws Exception;}
 private final Transport transport;private final Guard guard;private final Clock clock;
 public volatile Protocol.Status latest;public volatile boolean startUncertain=false;public volatile boolean runtimeBlocked=false;
 // Set when Stop is tapped. A queued or in-flight change gives up (unconfirmed) so Stop runs next.
 public volatile boolean stopPending=false;private volatile boolean stopRunning=false;private volatile boolean commandRunning=false;
 // Stop runs on the same worker after the in-flight change returns, so that change's read must not hold it up.
 // A 4 s full map read during a command delayed one Stop's confirmation to 11 s (attended session, 2026-10-21).
 public ControlEngine(Transport t,Guard g,Clock c){transport=t;guard=g;clock=c;t.abandonReadsWhen(()->stopPending&&!stopRunning);t.refreshMapWhen(()->!commandRunning);}
 public Protocol.Status refresh()throws Exception{return latest=transport.read();}
 // The bridge drops replies while another client reads it. One lost reply must not drop the change,
 // so the read before a change gets two more tries. This repeats a read only, never a command.
 private Protocol.Status freshRead()throws Exception{
  Exception last=null;
  for(int attempt=0;attempt<3;attempt++){
   if(attempt>0)clock.pause(300);
   if(stopPending)throw new Exception("Stop requested.");
   try{return refresh();}catch(Exception e){last=e;}
  }
  throw new Exception("Steamlinx didn't answer. Try again.",last);
 }
 private void allowed()throws Exception{if(!guard.allowed())throw new Exception("Controls locked. Enable a supervised test.");}
 private void normal(Protocol.Status s)throws Exception{if(s.error!=0||s.paused||s.diagnostic||s.requestedMode==2||s.program!=0)throw new Exception("Mr. Steam needs attention. Use original controls.");}
 private void safeOff(Protocol.Status s)throws Exception{normal(s);if(s.possiblyOn())throw new Exception("Steam state changed. Review the panel before starting.");}
 private void temperature(Protocol.Status s,int value)throws Exception{if(value<Math.max(80,s.minF)||value>Math.min(120,s.maxF))throw new Exception("Temperature exceeds the device limit.");}
 private void runtime(Protocol.Status s,int value)throws Exception{if(value<1||value>Math.min(60,s.maxMinutes))throw new Exception("Time exceeds the device limit.");}
 interface Match{boolean matches(Protocol.Status s);}
 private Protocol.Status change(String action,int value,Match match)throws Exception{
  boolean stopping=action.equals("stop");
  if(!stopping){allowed();if(stopPending)throw new Exception("Stop requested.");}
  // One transaction. Transport may repeat only OFF when its receipt is missing.
  // A PACKS receipt is not proof of the resulting physical state.
  transport.write(action,value);
  return confirm(stopping,match,stopping?"Stop unconfirmed. Use Mr. Steam now.":"Change unconfirmed. Check Mr. Steam.");
 }
 /** Reads until the device reports the match, for up to 8 s. A pending Stop ends a non-stop wait early. */
 private Protocol.Status confirm(boolean stopping,Match match,String failure)throws Exception{
  long deadline=clock.now()+8000;Exception last=null;
  do{
   if(!stopping&&stopPending)break;
   try{Protocol.Status s=refresh();if(match.matches(s))return s;}catch(Exception e){last=e;}clock.pause(300);
  }while(clock.now()<deadline);
  throw new Exception(failure,last);
 }
 public Protocol.Status execute(String action,int value,int minutes)throws Exception{
  commandRunning=true;
  try{return run(action,value,minutes);}finally{commandRunning=false;}
 }
 private Protocol.Status run(String action,int value,int minutes)throws Exception{
  if(action.equals("stop")){
   // After one map-validated read, Stop remains available even if fresh reads fail.
   if(latest==null)throw new Exception("No verified device yet. Use Mr. Steam to stop.");
   startUncertain=true;stopRunning=true;
   try{Protocol.Status s=change("stop",0,x->!x.possiblyOn());startUncertain=false;return s;}finally{stopRunning=false;}
  }
  allowed();Protocol.Status s=freshRead();normal(s);
  switch(action){
   case "start":
    if(startUncertain)throw new Exception("Previous Start unconfirmed. Stop or use Mr. Steam first.");
    safeOff(s);temperature(s,value);runtime(s,minutes);if(minutes<5)throw new Exception("Choose at least five minutes.");
    if(s.targetF!=value||s.requestedMinutes!=minutes){
     // Both settings go out back to back and one read confirms them, instead of a confirmation per setting:
     // the read after a write is often lost, so each separate confirmation cost about 2 s (2026-10-21).
     allowed();if(stopPending)throw new Exception("Stop requested.");
     if(s.targetF!=value)transport.write("temperature",value);
     // Re-checked between the writes: leaving Bear or tapping Stop must cancel before the next one goes out.
     if(s.requestedMinutes!=minutes){allowed();if(stopPending)throw new Exception("Stop requested.");transport.write("time",minutes);}
     s=confirm(false,x->x.targetF==value&&x.requestedMinutes==minutes,"Settings unconfirmed. Check Mr. Steam.");
    }
    // The state Start relies on was read just now: by the confirmation above, or by the read before this switch.
    // Cancel if backgrounded or another controller changed it.
    safeOff(s);allowed();if(stopPending)throw new Exception("Stop requested.");
    if(s.targetF!=value||s.requestedMinutes!=minutes)throw new Exception("Settings changed. Review and try again.");
    startUncertain=true;s=change("start",1,x->x.sessionActive()&&!x.paused&&!x.diagnostic&&x.error==0);startUncertain=false;
    // The time lock covers the session whose time change went unconfirmed; a confirmed new session starts unlocked.
    runtimeBlocked=false;return s;
   case "temperature":
    temperature(s,value);if(s.targetF==value)return s;return change("temperature",value,x->x.targetF==value);
   case "time":
    if(runtimeBlocked)throw new Exception("Adjust time in Mr. Steam until the timer behavior is verified.");
    // An absolute remaining-time target in whole minutes (the page settles taps before sending).
    if(!s.sessionActive())throw new Exception("Session changed. Review the displayed time.");
    final int desired=value;runtime(s,desired);
    if(s.remaining==desired&&s.requestedMinutes==desired)return s;
    // Exact match only: with one-minute steps, allowing desired-1 would accept a +1 the device ignored.
    try{return change("time",desired,x->x.sessionActive()&&x.requestedMinutes==desired&&x.remaining==desired);}
    // A change abandoned for Stop is moot (Stop ends the session); any other miss locks time controls.
    catch(Exception e){if(stopPending)throw new Exception("Stop requested.",e);runtimeBlocked=true;throw new Exception("Time change unconfirmed. Use Mr. Steam.",e);}
   case "lights":
    if(value!=0&&value!=1)throw new Exception("Unsupported light setting.");return change("lights",value,x->x.lightRequested==value&&x.lightOn==(value==1));
   default:throw new Exception("Unsupported control.");
  }
 }
}
