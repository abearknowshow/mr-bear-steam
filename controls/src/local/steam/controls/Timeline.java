package local.steam.controls;

import java.util.regex.Pattern;

/** 0.18 diagnostic timeline: a bounded ring of recent events for the owner's Mac. Telemetry only.
 *  Recording never throws, blocks on I/O or changes control flow. Values are numbers, booleans or
 *  short scrubbed strings: no credentials, tokens, account ids, thing names, IPs or MACs. */
public final class Timeline {
 public static final String BUILD="0.25";
 public static final int CAPACITY=2000,BATCH=250,BATCH_BYTES=96*1024,MAX_TEXT=120,MAX_FIELDS=16;
 public interface Clock{long mono();long wall();}
 private static final Clock SYSTEM=new Clock(){public long mono(){return System.nanoTime()/1000000;}public long wall(){return System.currentTimeMillis();}};
 private static final Timeline GLOBAL=new Timeline(CAPACITY,SYSTEM);
 public static Timeline global(){return GLOBAL;}
 /** Record into the process timeline. Fields alternate key, value. */
 public static void note(String type,Object... fields){GLOBAL.record(type,fields);}

 private final Clock clock;private final String[] ring;private final long[] seqs;
 /** Identifies one process run; seq restarts at 1 in each run. */
 public final long run;
 private long next=1;private int head,size;private long overwritten;
 public Timeline(int capacity,Clock clock){
  this.clock=clock;ring=new String[Math.max(1,capacity)];seqs=new long[ring.length];run=clock.wall();
 }

 public void record(String type,Object... fields){
  try{
   long mono=clock.mono(),wall=clock.wall();
   StringBuilder b=new StringBuilder(96);
   synchronized(this){
    long seq=next++;
    b.append("{\"s\":").append(seq).append(",\"m\":").append(mono).append(",\"w\":").append(wall)
     .append(",\"t\":");string(b,type!=null&&TYPE.matcher(type).matches()?type:"invalid");
    int written=0;
    for(int i=0;fields!=null&&i+1<fields.length&&written<MAX_FIELDS;i+=2){
     Object key=fields[i];
     if(!(key instanceof String)||!KEY.matcher((String)key).matches())continue;
     int mark=b.length();b.append(',');string(b,(String)key);b.append(':');
     if(value(b,fields[i+1]))written++;else b.setLength(mark);
    }
    b.append('}');
    int slot=(head+size)%ring.length;
    if(size==ring.length){head=(head+1)%ring.length;overwritten++;}else size++;
    ring[slot]=b.toString();seqs[slot]=seq;
   }
  }catch(RuntimeException ignored){/* Telemetry must never affect the caller. */}
 }

 public static final class Batch {
  public final String json;public final long last;public final int count;public final long lost;
  Batch(String j,long l,int c,long x){json=j;last=l;count=c;lost=x;}
 }
 /** Events after {@code after}, oldest first, at most {@code max}. {@code lost} counts events the ring
  *  overwrote before they could be taken. The batch stays under {@code maxBytes} (always at least one
  *  event). The cursor only moves when the caller accepts the batch. */
 public synchronized Batch batch(long after,int max,int maxBytes){
  StringBuilder b=new StringBuilder(256);int count=0;long last=after,lost=0;
  if(size>0){long oldest=seqs[head];if(after<oldest-1)lost=oldest-1-after;}
  b.append("{\"v\":1,\"build\":\"").append(BUILD).append("\",\"run\":").append(run).append(",\"lost\":").append(lost).append(",\"events\":[");
  for(int i=0;i<size&&count<Math.max(0,max);i++){
   int slot=(head+i)%ring.length;if(seqs[slot]<=after)continue;
   if(count>0&&b.length()+ring[slot].length()+3>maxBytes)break;
   if(count>0)b.append(',');b.append(ring[slot]);count++;last=seqs[slot];
  }
  b.append("]}");
  return new Batch(b.toString(),last,count,lost);
 }
 public synchronized int size(){return size;}
 public synchronized long overwritten(){return overwritten;}

 /** Short, stable failure class for a read, for summaries; the scrubbed message travels separately. */
 public static String readFailure(Throwable e,int fragments){
  String m=e==null||e.getMessage()==null?"":e.getMessage();
  if(m.startsWith("Read abandoned for Stop"))return "abandoned";
  if(m.contains("radio link"))return "radio";
  if(m.startsWith("No compatible Steamlinx map")||m.startsWith("Unknown Steamlinx map"))return "format";
  if(m.startsWith("Incomplete live status")||m.startsWith("Incomplete status map")||m.startsWith("Steamlinx answered a different range"))return "wrong-size";
  if(e instanceof java.net.SocketTimeoutException)return fragments>0?"incomplete":"timeout";
  if(e instanceof IllegalArgumentException)return m.contains("segment")||m.contains("fragments")||m.startsWith("Oversized")?"bad-fragment":"invalid";
  if(e instanceof java.io.IOException)return "socket";
  return e==null?"unknown":"other";
 }
 /** Scrubbed exception message, or its class name when it has none. */
 public static String text(Throwable e){
  if(e==null)return "";return e.getMessage()==null?e.getClass().getSimpleName():scrub(e.getMessage());
 }

 static final Pattern TYPE=Pattern.compile("[a-z][a-z0-9_.]{0,31}"),KEY=Pattern.compile("[a-zA-Z][a-zA-Z0-9]{0,23}");
 // Order matters: the wider shapes run first so a MAC inside a thing name cannot survive as an IPv6.
 private static final Pattern[] SECRETS={
  Pattern.compile("[^\\s@\"]+@[^\\s@\"]+"),                                     // email
  Pattern.compile("(?i)\\bsu-[^\\s\"]+"),                                          // thing name
  Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), // uuid
  Pattern.compile("(?i)[0-9a-f]{2}(?:[:-][0-9a-f]{2})+"),                         // MAC or part of one
  Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}"),                                    // IPv4
  Pattern.compile("(?i)(?:[0-9a-f]{1,4})?(?::(?:[0-9a-f]{1,4})?){2,7}"),           // IPv6
  Pattern.compile("(?i)\\b(?:[a-z0-9-]+\\.)+(?:com|net|org|io|aws|local|lan|home|internal)\\b"), // host names
  Pattern.compile("[A-Za-z0-9+/_=-]{24,}"),                                         // tokens, keys, ids
  Pattern.compile("(?i)[0-9a-f]{12,}"),                                             // long hex
  Pattern.compile("\\d{7,}")                                                        // long numbers (account ids)
 };
 private static final String[] MASKS={"[email]","[thing]","[id]","[mac]","[ip]","[ip]","[host]","[redacted]","[redacted]","[n]"};
 public static String scrub(String s){
  if(s==null)return null;
  String out=s.length()>4*MAX_TEXT?s.substring(0,4*MAX_TEXT):s;
  for(int i=0;i<SECRETS.length;i++)out=SECRETS[i].matcher(out).replaceAll(MASKS[i]);
  return out.length()>MAX_TEXT?out.substring(0,MAX_TEXT-1)+"…":out;
 }
 private static boolean value(StringBuilder b,Object v){
  if(v==null){b.append("null");return true;}
  if(v instanceof Boolean){b.append(((Boolean)v).booleanValue());return true;}
  if(v instanceof Integer||v instanceof Long||v instanceof Short||v instanceof Byte){b.append(((Number)v).longValue());return true;}
  if(v instanceof Double||v instanceof Float){double d=((Number)v).doubleValue();if(Double.isNaN(d)||Double.isInfinite(d)){b.append("null");return true;}
   b.append(Math.round(d*100)/100.0);return true;}
  if(v instanceof CharSequence){string(b,scrub(v.toString()));return true;}
  if(v instanceof Enum){string(b,((Enum<?>)v).name());return true;}
  return false; // Anything else could carry arbitrary toString() content.
 }
 private static void string(StringBuilder b,String s){
  b.append('"');
  for(int i=0;i<s.length();i++){
   char c=s.charAt(i);
   if(c=='"'||c=='\\')b.append('\\').append(c);
   else if(c<0x20||c>0x7e)b.append(String.format("\\u%04x",(int)c));
   else b.append(c);
  }
  b.append('"');
 }
}
