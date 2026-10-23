package local.steam.controls;
import java.nio.file.*;import java.util.*;
public final class BridgeTransportTest {
 static int checks;static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}interface Work{void run()throws Exception;}static void fails(Work w)throws Exception{try{w.run();throw new AssertionError("Expected rejection");}catch(AssertionError e){throw e;}catch(Exception expected){checks++;}}
 static final class Fake implements ControlEngine.Transport{Protocol.Status status;boolean fail;List<String>writes=new ArrayList<>();Fake(Protocol.Status s){status=s;}java.util.function.BooleanSupplier abandon,idle;public void abandonReadsWhen(java.util.function.BooleanSupplier a){abandon=a;}public void refreshMapWhen(java.util.function.BooleanSupplier i){idle=i;}public Protocol.Status read()throws Exception{if(fail)throw new Exception("unreachable/map invalid");return status;}public boolean write(String a,int v){writes.add(a+":"+v);return true;}}
 public static void main(String[]args)throws Exception{
  Protocol.Status status=Protocol.decode(Files.readAllBytes(Paths.get(args[0])));Fake old=new Fake(status),next=new Fake(status);String[]address={"old"};int[]locates={0};
  BridgeTransport t=new BridgeTransport(()->{locates[0]++;return address[0];},a->a.equals("old")?old:next);
  fails(()->t.write("start",1));fails(()->t.write("stop",0));check(locates[0]==0,"Writes never discover");
  t.read();t.read();check(locates[0]==1,"Healthy reads reuse discovered address");check(old.writes.isEmpty(),"Discovery/read no commands");
  old.fail=true;fails(t::read);fails(()->t.write("start",1));check(old.writes.isEmpty(),"Lost read blocks non-stop writes");
  t.write("stop",0);check(old.writes.equals(Arrays.asList("stop:0")),"Explicit Stop retains last verified destination");check(locates[0]==1,"Stop does not discover or retry elsewhere");
  address[0]="new";fails(t::read);check(locates[0]==1,"A single lost reply keeps the verified bridge");
  t.read();check(locates[0]==2,"Repeated failure triggers rediscovery");
  old.fail=false;next.fail=true;fails(t::read);fails(()->t.write("temperature",114));check(next.writes.isEmpty(),"Kept bridge still refuses commands after a lost read");
  next.fail=false;t.read();check(locates[0]==2,"Recovered read reuses the kept bridge");t.write("temperature",115);check(next.writes.equals(Arrays.asList("temperature:115")),"Write uses newly validated destination");check(old.writes.size()==1,"No stale-address write");
  int[]created={0};Fake same=new Fake(status);BridgeTransport r=new BridgeTransport(()->"same",a->{created[0]++;return same;});
  r.read();same.fail=true;fails(r::read);fails(r::read);same.fail=false;r.read();
  check(created[0]==1,"Rediscovering the same bridge reuses its transport and cached map");
  // A waiting Stop: reads give way without discovery, and the cut-short read is not a bridge failure.
  boolean[]stopWaiting={false};int[]made={0};Fake quiet=new Fake(status);BridgeTransport q=new BridgeTransport(()->{made[0]++;return "quiet";},a->quiet);
  q.abandonReadsWhen(()->stopWaiting[0]);q.read();check(quiet.abandon!=null&&!quiet.abandon.getAsBoolean(),"Stop check reaches the bridge transport");
  stopWaiting[0]=true;fails(q::read);fails(q::read);quiet.fail=true;fails(q::read);check(made[0]==1,"Abandoned reads never rediscover");
  stopWaiting[0]=false;quiet.fail=false;q.read();check(made[0]==1,"Abandoned reads are not counted as bridge failures");q.write("temperature",113);check(quiet.writes.equals(Arrays.asList("temperature:113")),"Writes resume after a good read");
  // 0.24 quick relink (issue #8): after two lost reads, one HELLO to the last bridge comes before any network-wide search.
  int[]searches={0},hellos={0},made2={0};boolean[]answers={true};String[]where={"home"};Fake kept=new Fake(status),moved=new Fake(status);
  BridgeTransport k=new BridgeTransport(()->{searches[0]++;return where[0];},a->{made2[0]++;return a.equals("home")?kept:moved;},a->{hellos[0]++;return answers[0]&&a.equals("home");});
  k.read();check(searches[0]==1&&hellos[0]==0,"The first read searches; there is no last bridge to greet yet");
  kept.fail=true;fails(k::read);fails(k::read);kept.fail=false;k.read();
  check(hellos[0]==1&&searches[0]==1&&made2[0]==1,"After a reset the last bridge is greeted and reused, with no search and no new transport");
  k.write("start",1);check(kept.writes.equals(Arrays.asList("start:1")),"The good read after a relink verifies the bridge for commands");
  kept.fail=true;fails(k::read);fails(()->k.write("time",15));check(kept.writes.size()==1,"A lost read after a relink blocks commands again");kept.fail=false;k.read();
  kept.fail=true;fails(k::read);fails(k::read);answers[0]=false;where[0]="away";k.read();
  check(hellos[0]==2&&searches[0]==2&&made2[0]==2,"A bridge that no longer answers falls back to the full search");
  moved.writes.clear();k.write("temperature",110);check(moved.writes.equals(Arrays.asList("temperature:110"))&&kept.writes.size()==1,"Writes follow the bridge the search found");
  kept.fail=false;moved.fail=true;fails(k::read);fails(k::read);answers[0]=true;moved.fail=false;k.read();
  check(hellos[0]==3&&searches[0]==3,"The greeting goes to the bridge last used, not an earlier one");
  Fake invalid=new Fake(status);invalid.fail=true;BridgeTransport bad=new BridgeTransport(()->"bad",a->invalid);fails(bad::read);fails(()->bad.write("stop",0));check(invalid.writes.isEmpty(),"Unvalidated candidate never becomes stop target");
  check(BridgeLocator.matches(("<HELLO>"+Protocol.SPA+"|My Steamer</HELLO>").getBytes("UTF-8"),"192.168.1.9",10022),"Exact owner accepted");
  check(!BridgeLocator.matches("<HELLO>SPA00:11:22:33:44:55|Other</HELLO>".getBytes("UTF-8"),"192.168.1.9",10022),"Foreign identity refused");
  check(!BridgeLocator.matches(("<HELLO>"+Protocol.SPA+"x|Other</HELLO>").getBytes("UTF-8"),"192.168.1.9",10022),"Prefix spoof refused");
  check(!BridgeLocator.matches(("<HELLO>"+Protocol.SPA+"|Name</HELLO>").getBytes("UTF-8"),"203.0.113.3",10022),"Non-owner LAN refused");
  check(!BridgeLocator.matches(("<HELLO>"+Protocol.SPA+"|Name</HELLO>").getBytes("UTF-8"),"192.168.1.9",1234),"Wrong response port refused");
  check(!BridgeLocator.matches(("<HELLO>"+Protocol.SPA+"|Name</HELLO>garbage").getBytes("UTF-8"),"192.168.1.9",10022),"Trailing malformed bytes refused");
  check(!BridgeLocator.matches(new byte[513],"192.168.1.9",10022),"Oversize refused");
  System.out.println("Bridge transport checks: "+checks);
 }
}
