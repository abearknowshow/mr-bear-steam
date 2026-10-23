package local.steam.controls;
import com.google.gson.*;
import java.nio.file.*;import java.util.*;import java.util.regex.Pattern;
/** 0.18 timeline: bounded ring, batch format, cursor, scrubbing, and no addresses from the transports. */
public final class TimelineTest {
 static int checks;static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
 static final class Clock implements Timeline.Clock{long mono=1000,wall=1729500000000L;public long mono(){return mono;}public long wall(){return wall;}}
 // Shapes the Mac refuses (setup_server.TIMELINE_SECRETS) plus the bare identities in this codebase.
 static final Pattern FORBIDDEN=Pattern.compile("(?i)\\d{1,3}(\\.\\d{1,3}){3}|[0-9a-f]{2}([:-][0-9a-f]{2}){2,}|[^\\s@\"]+@[^\\s@\"]+|\\bsu-[^\\s\"\\[]+|[A-Za-z0-9+/_=-]{24,}|[0-9a-f]{12,}|\\d{7,}|[0-9a-f]{0,4}:[0-9a-f]{0,4}:[0-9a-f]{0,4}:");
 static JsonObject parse(String json){return JsonParser.parseString(json).getAsJsonObject();}
 static List<String> strings(JsonElement e){List<String>out=new ArrayList<>();
  if(e.isJsonObject())for(Map.Entry<String,JsonElement> x:e.getAsJsonObject().entrySet()){if(!Arrays.asList("s","m","w","t").contains(x.getKey()))out.addAll(strings(x.getValue()));}
  else if(e.isJsonArray())for(JsonElement x:e.getAsJsonArray())out.addAll(strings(x));
  else if(e.isJsonPrimitive()&&e.getAsJsonPrimitive().isString())out.add(e.getAsString());
  return out;}
 static void clean(String batch,String why){
  JsonArray events=parse(batch).getAsJsonArray("events");
  for(JsonElement e:events)for(String s:strings(e))check(!FORBIDDEN.matcher(s).find(),why+": "+s);
 }
 public static void main(String[]args)throws Exception{
  Clock clock=new Clock();Timeline t=new Timeline(2000,clock);
  for(int i=0;i<5000;i++){clock.mono++;t.record("bridge.read","ok",i%3!=0,"ms",i,"kind","live");}
  check(t.size()==2000,"The ring holds at most its capacity");check(t.overwritten()==3000,"Older events are overwritten, and counted");
  Timeline.Batch b=t.batch(0,Timeline.BATCH,Timeline.BATCH_BYTES);
  check(b.count==250&&b.lost==3000,"A lagging cursor learns how many events it lost");
  JsonObject j=parse(b.json);
  check(j.get("v").getAsInt()==1&&j.get("build").getAsString().equals("0.23")&&j.get("run").getAsLong()==clock.wall&&j.get("lost").getAsLong()==3000,"Batch envelope: v, build, run, lost");
  check(new TreeSet<>(j.keySet()).equals(new TreeSet<>(Arrays.asList("v","build","run","lost","events"))),"Batch carries exactly the envelope keys");
  JsonArray events=j.getAsJsonArray("events");check(events.size()==250,"Batch holds max events");
  JsonObject first=events.get(0).getAsJsonObject();
  check(first.get("s").getAsLong()==3001&&first.get("t").getAsString().equals("bridge.read")&&first.has("m")&&first.has("w"),"Events carry seq, mono, wall and type");
  long previous=0;boolean ordered=true;for(JsonElement e:events){long s=e.getAsJsonObject().get("s").getAsLong();ordered&=s==previous+1||previous==0;previous=s;}
  check(ordered&&b.last==3250,"Batches are contiguous, oldest first");
  Timeline.Batch next=t.batch(b.last,Timeline.BATCH,Timeline.BATCH_BYTES);check(next.lost==0&&parse(next.json).getAsJsonArray("events").get(0).getAsJsonObject().get("s").getAsLong()==3251,"An accepted cursor continues without loss");
  check(t.batch(b.last,Timeline.BATCH,Timeline.BATCH_BYTES).json.equals(next.json),"A refused upload is resent unchanged");
  Timeline.Batch small=t.batch(b.last,Timeline.BATCH,2000);check(small.count>=1&&small.json.length()<=2000+400,"Batches stay under the byte cap");
  check(t.batch(5000,10,1000).count==0&&t.batch(5000,10,1000).last==5000,"Nothing new: empty batch, cursor unchanged");
  check(new Timeline(10,clock).batch(0,10,1000).count==0,"Empty ring: empty batch");
  // Robust recording: never throws, drops anything that could carry arbitrary text.
  Timeline r=new Timeline(50,clock);
  r.record(null);r.record("Bad Type!","ok",true);r.record("ok.type","bad key!",1,"fine",2,"obj",new Object(),"list",Arrays.asList(1,2),"dbl",Double.NaN,"f",1.234f,"n",null,"odd");
  Object[]many=new Object[60];for(int i=0;i<30;i++){many[2*i]="k"+i;many[2*i+1]=i;}r.record("many",many);
  JsonArray rec=parse(r.batch(0,50,100000).json).getAsJsonArray("events");
  check(rec.size()==4&&rec.get(0).getAsJsonObject().get("t").getAsString().equals("invalid")&&rec.get(1).getAsJsonObject().get("t").getAsString().equals("invalid"),"Invalid types are recorded as invalid");
  JsonObject odd=rec.get(2).getAsJsonObject();
  check(odd.has("fine")&&!odd.has("obj")&&!odd.has("list")&&!odd.has("bad key!")&&odd.get("dbl").isJsonNull()&&odd.get("n").isJsonNull()&&Math.abs(odd.get("f").getAsDouble()-1.23)<1e-9,"Unsafe values dropped; numbers kept");
  check(rec.get(3).getAsJsonObject().size()==4+Timeline.MAX_FIELDS,"At most MAX_FIELDS fields per event");
  // Scrubbing. Every one of these must leave the event without an identifier.
  String[]secrets={"failed to connect to /192.168.1.104 (port 10022) from /192.168.1.106 (port 40312)",
   "Rx SPA02:00:00:00:00:01 timed out","su-02:00:00:00:00:02-dev rejected","panel ending 00:02",
   "owner@example.com signed in","token eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload","id us-east-2:00000000-1111-2222-3333-444444444444",
   "Unable to resolve host a1b2c3d4e5f6g7-ats.iot.us-east-2.amazonaws.com","fe80::1%wlan0 unreachable","account 123456789012","key 1example2client3id4abcdefg",
   "pair 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"};
  Timeline s=new Timeline(100,clock);
  for(String x:secrets){s.record("account.result","ok",false,"error",x);s.record("bridge.read","error",Timeline.text(new java.net.SocketException(x)));}
  String sb=s.batch(0,100,100000).json;clean(sb,"Scrubbed text leaks an identifier");
  for(String x:Arrays.asList("192.168.1","00:01","00:02","example.com","amazonaws","c7dc2adc","3j8tuk3v","123456789012"))check(!sb.contains(x),"No fragment survives: "+x);
  check(Timeline.scrub("Steamlinx didn't answer. Try again.").equals("Steamlinx didn't answer. Try again."),"Ordinary messages are kept");
  check(Timeline.scrub("Live subscription accepted; waiting for reported lights").equals("Live subscription accepted; waiting for reported lights"),"Reader stages are kept");
  check(Timeline.scrub("#ffaa00").equals("#ffaa00"),"Light colours are kept");
  String longText=new String(new char[500]).replace('\0','x').replace("xxxxx","word ");check(Timeline.scrub(longText).length()<=Timeline.MAX_TEXT,"Text is bounded");
  // JSON escaping survives quotes, backslashes, controls and non-ASCII.
  Timeline q=new Timeline(5,clock);q.record("ui.status","text","Reconnecting…","q","a\"b\\c\n\u0001°");
  JsonObject qe=parse(q.batch(0,5,10000).json).getAsJsonArray("events").get(0).getAsJsonObject();
  check(qe.get("text").getAsString().equals("Reconnecting…")&&qe.get("q").getAsString().equals("a\"b\\c\n\u0001°"),"Escaped strings round-trip");
  check(q.batch(0,5,10000).json.chars().allMatch(c->c>=0x20&&c<0x7f),"Batch JSON is plain ASCII");
  // Read failure classes.
  check(Timeline.readFailure(new java.net.SocketTimeoutException("Read abandoned for Stop."),0).equals("abandoned"),"abandoned");
  check(Timeline.readFailure(new java.net.SocketTimeoutException("No complete Steamlinx status."),0).equals("timeout"),"timeout");
  check(Timeline.readFailure(new java.net.SocketTimeoutException("No complete Steamlinx status."),1).equals("incomplete"),"incomplete");
  check(Timeline.readFailure(new java.net.SocketTimeoutException("Receive timed out"),0).equals("timeout"),"socket timeout");
  check(Timeline.readFailure(new IllegalArgumentException("Incomplete live status"),2).equals("wrong-size"),"wrong size");
  check(Timeline.readFailure(new IllegalArgumentException("Incomplete status map"),2).equals("wrong-size"),"wrong size map");
  check(Timeline.readFailure(new IllegalArgumentException("Conflicting status segment"),2).equals("bad-fragment"),"bad fragment");
  check(Timeline.readFailure(new IllegalArgumentException("Unknown mode"),2).equals("invalid"),"invalid map");
  check(Timeline.readFailure(new Exception("Steamlinx radio link error."),0).equals("radio"),"radio");
  check(Timeline.readFailure(new java.net.SocketTimeoutException("No compatible Steamlinx map."),0).equals("format"),"format");
  check(Timeline.readFailure(new java.net.PortUnreachableException("ICMP"),0).equals("socket"),"socket");
  // BridgeTransport records locate and failure transitions, never the host it found.
  Protocol.Status status=Protocol.decode(Files.readAllBytes(Paths.get(args[0])));
  boolean[]fail={false};boolean[]stop={false};
  ControlEngine.Transport fake=new ControlEngine.Transport(){public void abandonReadsWhen(java.util.function.BooleanSupplier a){}public void refreshMapWhen(java.util.function.BooleanSupplier i){}
   public Protocol.Status read()throws Exception{if(fail[0])throw new java.net.SocketException("connect failed: /192.168.1.104:10022");return status;}public boolean write(String a,int v){return true;}};
  Timeline g=Timeline.global();long before=lastSeq(g);
  BridgeTransport bt=new BridgeTransport(()->"192.168.1.104",host->fake);bt.abandonReadsWhen(()->stop[0]);
  bt.read();fail[0]=true;try{bt.read();}catch(Exception e){}try{bt.read();}catch(Exception e){}fail[0]=false;bt.read();stop[0]=true;try{bt.read();}catch(Exception e){}
  BridgeTransport lost=new BridgeTransport(()->{throw new java.net.SocketTimeoutException("Owner Steamlinx not found on the home network.");},host->fake);try{lost.read();}catch(Exception e){}
  String gb=g.batch(before,Timeline.BATCH,Timeline.BATCH_BYTES).json;List<String>types=new ArrayList<>();
  for(JsonElement e:parse(gb).getAsJsonArray("events"))types.add(e.getAsJsonObject().get("t").getAsString());
  check(types.equals(Arrays.asList("bridge.locate.start","bridge.locate.end","bridge.fail","bridge.reset","bridge.locate.start","bridge.locate.end","bridge.read","bridge.locate.start","bridge.locate.end","bridge.fail")),"Transport events in order: "+types);
  clean(gb,"Transport events leak the bridge address");check(!gb.contains("192.168"),"No bridge address in any event");
  JsonObject relocated=parse(gb).getAsJsonArray("events").get(5).getAsJsonObject();
  check(relocated.get("sameHost").getAsBoolean()&&relocated.get("reused").getAsBoolean(),"Rediscovery says it kept the same bridge and transport");
  // The sample batch for the Mac-side contract test (tests/test_timeline.py).
  Timeline sample=new Timeline(20,clock);
  sample.record("ui.tap","control","temperature","delta",1,"target",116,"session",true);
  sample.record("bridge.read","ok",false,"kind","live","ms",2004,"asks",2,"frags",0,"reason","timeout","error",Timeline.text(new java.net.SocketTimeoutException("No complete Steamlinx status.")));
  sample.record("conn","to",false,"cause","poll-fail","lastOkAgoMs",4300);sample.record("ui.status","text","Reconnecting…","online",true);
  sample.record("light.cmd","on",true,"color","#ffaa00","brightness",100,"disco",false,"accepted",true,"attempt",3);
  for(String x:secrets)sample.record("account.result","ok",false,"error",x);
  Files.createDirectories(Paths.get("build"));Files.write(Paths.get("build/timeline-sample.json"),sample.batch(0,50,Timeline.BATCH_BYTES).json.getBytes("US-ASCII"));
  System.out.println("Timeline checks passed: "+checks);
 }
 static long lastSeq(Timeline t){Timeline.Batch b=t.batch(0,Integer.MAX_VALUE,Integer.MAX_VALUE);return b.last;}
}
