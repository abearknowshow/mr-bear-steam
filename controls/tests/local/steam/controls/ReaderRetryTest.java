package local.steam.controls;
import java.util.*;
/** Reader lost while Bear stays visible must reopen, bounded, with no light command and no synthetic state. */
public final class ReaderRetryTest {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static final List<ShadowReadTest.Fake> wires=new ArrayList<>();static boolean failConnect;
 static ShadowRead open(){
  ShadowReadTest.Fake wire=new ShadowReadTest.Fake(){public void connect(String id,Runnable lost){super.connect(id,lost);if(failConnect)throw new RuntimeException("offline");}};
  wire.denyGet=false;wires.add(wire);ShadowRead r=new ShadowRead(ShadowReadTest.THING,wire,()->true,s->{});r.start();return r;
 }
 /** Mirrors the DashboardService readWorker tick: every 10 s, reopen only when the schedule says so. */
 static ShadowRead tick(ReaderRetry retry,ShadowRead current,long visit,boolean visible,long now){
  return retry.due(visit,visible,true,current!=null&&current.available(),now)?open():current;
 }
 public static void main(String[]a){
  ReaderRetry retry=new ReaderRetry();ShadowRead read=open();long t=0;
  read=tick(retry,read,1,true,t);read=tick(retry,read,1,true,t+=10000);check(wires.size()==1,"healthy reader is never replaced");
  wires.get(0).message.received(ShadowReadTest.doc(true,65));check(read.report().on,"live On before loss");
  wires.get(0).disconnected.run();
  check(read.stage().equals("Status reader disconnected")&&read.report()==null,"incident stage reproduced");
  for(int i=0;i<3&&wires.size()==1;i++)read=tick(retry,read,1,true,t+=10000);
  check(wires.size()==2&&read.available(),"reader reopened within 30 s while Bear stays visible");
  ShadowReadTest.Fake again=wires.get(1);check(!again.client.equals(wires.get(0).client),"fresh client id");
  check(read.report()==null,"reconnect alone shows no state");
  read.refresh(System.currentTimeMillis(),t);check(again.reads.equals(Arrays.asList(ShadowReadTest.THING)),"only the fixed status request is sent");
  again.message.received(ShadowReadTest.doc(true,66));check(read.report().on,"state returns only from a device report");
  read=tick(retry,read,1,true,t+=10000);check(wires.size()==2,"healthy reopened reader resets the budget, not replaced");
  for(ShadowReadTest.Fake w:wires)check(w.topics.stream().allMatch(s->s.endsWith("/shadow/update/accepted")),"subscribe-only topics");
  // Hidden Bear: no retry however long it waits.
  again.disconnected.run();int before=wires.size();
  for(int i=0;i<100;i++)read=tick(retry,read,1,false,t+=10000);
  check(wires.size()==before,"no reopen while Bear is hidden");
  // Bounded backoff when the cloud stays unreachable.
  failConnect=true;List<Long> at=new ArrayList<>();
  for(int i=0;i<2000;i++){int n=wires.size();read=tick(retry,read,1,true,t+=10000);if(wires.size()>n)at.add(t);}
  check(at.size()==ReaderRetry.DELAYS_MS.length&&retry.exhausted(),"bounded attempts: "+at.size());
  for(int i=2;i<at.size();i++)check(at.get(i)-at.get(i-1)>=at.get(i-1)-at.get(i-2),"non-decreasing backoff");
  // A new visit (return from Mr. Steam) resets the budget; no session means no retry.
  failConnect=false;before=wires.size();
  for(int i=0;i<3;i++)read=tick(retry,read,2,true,t+=10000);check(wires.size()==before+1&&read.available(),"new visit retries again");
  ReaderRetry signedOut=new ReaderRetry();for(int i=0;i<100;i++)check(!signedOut.due(1,true,false,false,i*10000L),"no session, no retry");
  System.out.println("Reader reconnect checks passed: "+checks);
 }
}
