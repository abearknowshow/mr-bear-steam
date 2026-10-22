package local.steam.controls;
import com.google.gson.*;
import java.util.*;
public final class StatusRefreshTest {
 static int checks;static void check(boolean yes){checks++;if(!yes)throw new AssertionError("Check "+checks);}
 static class Wire implements ShadowRead.Wire {
  int requests;boolean fail;ShadowRead.Message message;Runnable lost;
  public void connect(String id,Runnable r){lost=r;}
  public void subscribe(String topic,ShadowRead.Message m){message=m;}
  public void requestStatus(String thing)throws Exception{check(thing.equals(ShadowReadTest.THING));requests++;if(fail)throw new Exception("unavailable");}
  public void close(){}
 }
 public static void main(String[]args){
  String payload=StatusRequest.build(ShadowReadTest.THING);
  JsonObject root=JsonParser.parseString(payload).getAsJsonObject(),state=root.getAsJsonObject("state"),desired=state.getAsJsonObject("desired");
  check(root.size()==2&&state.size()==1&&desired.size()==1);check(desired.get("online").getAsBoolean());
  check(!payload.contains("light")&&!payload.contains("steam")&&!payload.contains("shower")&&!payload.contains("aroma"));
  try{StatusRequest.build("foreign");throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
  Wire wire=new Wire();boolean[]allowed={true};ShadowRead read=new ShadowRead(ShadowReadTest.THING,wire,()->allowed[0],s->{});
  long now=System.currentTimeMillis();read.refresh(now,0);check(wire.requests==0);read.start();read.refresh(now,0);check(wire.requests==1&&read.report()==null);
  read.refresh(now+1000,1000);check(wire.requests==1);
  wire.message.received("{\"version\":1,\"state\":{\"desired\":{\"online\":true}}}");check(read.report()==null);
  wire.message.received(ShadowReadTest.doc(false,2));LightReport report=read.report();check(report!=null&&!report.on);
  long received=report.at;read.refresh(received+59000,59000);check(wire.requests==1);
  read.refresh(received+61000,61000);check(wire.requests==2&&read.report()==report);
  read.refresh(received+62000,62000);check(wire.requests==2);
  read.refresh(received+121000,121000);check(wire.requests==3&&!read.report().usable(received+121000));
  wire.lost.run();read.refresh(received+200000,200000);check(wire.requests==3&&read.report()==null);
  wire=new Wire();wire.fail=true;read=new ShadowRead(ShadowReadTest.THING,wire,()->allowed[0],s->{});read.start();read.refresh(now,0);
  check(wire.requests==1&&read.available()&&read.report()==null);check(read.stage().equals("Status refresh request failed"));
  read.refresh(now+1000,1000);check(wire.requests==1);read.refresh(now+30000,30000);check(wire.requests==2);
  allowed[0]=false;read.refresh(now+60000,60000);check(wire.requests==2);read.close();read.refresh(now+90000,90000);check(wire.requests==2);
  System.out.println("Status refresh checks passed: "+checks);
 }
}
