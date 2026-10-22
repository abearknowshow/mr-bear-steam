package local.steam.controls;
import java.util.*;
public final class ShadowReadTest {
 static final String THING="su-02:00:00:00:00:02-dev";
 static int checks;
 static void check(boolean v){checks++;if(!v)throw new AssertionError();}
 static class Fake implements ShadowRead.Wire {
  String client;List<String> reads=new ArrayList<>(),topics=new ArrayList<>();ShadowRead.Message message;
  Runnable disconnected;boolean closed,deny,denyGet=true;String initial;
  public void connect(String id,Runnable lost){client=id;disconnected=lost;}
  public void subscribe(String topic,ShadowRead.Message callback)throws Exception{topics.add(topic);if(deny||denyGet&&topic.endsWith("/get/accepted"))throw new ShadowRead.ReadFailure(32109);message=callback;if(initial!=null)message.received(initial);}
  public void get(String topic,String data){reads.add(topic);check(!data.contains("desired")&&!data.contains("reported"));}
  public void requestStatus(String thing){reads.add(thing);}
  public void close(){closed=true;}
 }
 static String doc(boolean on,int version){
  long now=System.currentTimeMillis()/1000;
  return LightReportTest.doc(""+on,"true",now).replace("\"timestamp\":1000","\"timestamp\":"+now).replace("{\"timestamp\"","{\"version\":"+version+",\"timestamp\"");
 }
 public static void main(String[]args){
  Fake wire=new Fake();boolean[] allowed={true};
  List<String> notifications=new ArrayList<>();
  ShadowRead read=new ShadowRead(THING,wire,()->allowed[0],notifications::add);
  read.start();if(!read.available())throw new AssertionError("A denied snapshot subscription must not prevent live-update subscription");check(read.available());check(!wire.client.equals(THING));check(wire.client.matches("app-bearread[0-9a-f]{10}-dev"));
  check(wire.topics.equals(Arrays.asList("$aws/things/"+THING+"/shadow/update/accepted")));
  check(wire.reads.isEmpty());check(read.report()==null);
  wire.message.received(doc(false,4));check(!read.report().on);
  wire.message.received("{\"version\":5,\"state\":{\"desired\":{\"light\":{\"type\":1}}}}");check(!read.report().on);
  check(read.metrics().messages==2);check(read.metrics().reported==1);check(read.metrics().accepted==1);
  check(read.metrics().kind.equals("desired-only"));
  int before=notifications.size();
  wire.message.received(doc(true,6));check(read.report().on);check(notifications.size()==before+1);
  wire.message.received(doc(false,5));check(read.report().on);
  before=notifications.size();
  wire.message.received(doc(false,7));check(!read.report().on);check(notifications.size()==before+1);
  wire.message.received(doc(true,8).replace("\"deviceChromaConnected\":true","\"deviceChromaConnected\":false"));check(read.report()==null);
  wire.message.received(doc(true,9));check(read.report().on);
  wire.disconnected.run();check(read.report()==null);check(!read.available());
  read.close();wire.message.received(doc(true,10));check(read.report()==null);
  wire=new Fake();wire.deny=true;read=new ShadowRead(THING,wire,()->true,s->{});read.start();
  check(!read.available());check(wire.closed);check(wire.reads.isEmpty());check(read.stage().contains("subscription"));
  wire=new Fake();read=new ShadowRead(THING,wire,()->false,s->{});read.start();check(wire.client==null);check(wire.reads.isEmpty());
  try{new ShadowRead("another-device",wire,()->true,s->{});throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
  wire=new Fake();wire.initial=doc(false,20);read=new ShadowRead(THING,wire,()->true,s->{});read.start();
  check(read.report()!=null);if(!read.stage().equals("Live light report received"))throw new AssertionError("Early valid report must not be relabeled as waiting");
  // The reader can observe a foreground original app, but stops accepting at the deadline.
  ReadWindow window=new ReadWindow();long generation=window.resume();long[] time={100};
  wire=new Fake();read=new ShadowRead(THING,wire,()->window.allows(generation,time[0]),s->{});read.start();
  window.watchOriginal(time[0]);window.pause();wire.message.received(doc(true,21));check(read.report().on);
  time[0]=90100;wire.message.received(doc(false,22));check(read.report()==null);check(read.metrics().messages==1);
  read.close();check(wire.closed);
  System.out.println("Shadow read protocol checks passed: "+checks);
 }
}
