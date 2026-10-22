package local.steam.controls;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Narrow MrSt_C03/S03 codec. Wire format researched from gazoodle/geckolib; see NOTICE. */
public final class Protocol {
 public static final String SPA=Household.BRIDGE_ID;
 public static final String FORMAT="FILES,MrSt_C03.xml,MrSt_S03.xml";
 private static byte[] ascii(String s){return s.getBytes(StandardCharsets.US_ASCII);}
 public static byte[] envelope(String src,String dst,byte[] body){
  if(!src.matches("[A-Za-z0-9:-]{1,80}")||!dst.matches("[A-Za-z0-9:-]{1,80}"))throw new IllegalArgumentException("Invalid identity");
  ByteArrayOutputStream out=new ByteArrayOutputStream();
  byte[][]parts={ascii("<PACKT><SRCCN>"+src+"</SRCCN><DESCN>"+dst+"</DESCN><DATAS>"),body,ascii("</DATAS></PACKT>")};
  for(byte[]p:parts)out.write(p,0,p.length);return out.toByteArray();
 }
 public static byte[] unwrap(byte[]packet,String src,String dst){
  byte[]prefix=envelope(src,dst,new byte[0]);int suffixLength=16,prefixLength=prefix.length-suffixLength;
  if(packet.length<prefix.length||!Arrays.equals(Arrays.copyOf(packet,prefixLength),Arrays.copyOf(prefix,prefixLength))||!Arrays.equals(Arrays.copyOfRange(packet,packet.length-suffixLength,packet.length),ascii("</DATAS></PACKT>")))throw new IllegalArgumentException("Unexpected Steamlinx response identity");
  return Arrays.copyOfRange(packet,prefixLength,packet.length-suffixLength);
 }
 public static byte[] query(int seq,boolean format){
  require(seq>0&&seq<192,"Sequence");return format?ByteBuffer.allocate(6).put(ascii("SFILE")).put((byte)seq).array():status(seq,0,1024);
 }
 // Offsets 256..297 hold every value that changes during use: mode, light, set time, target,
 // status bits, error, max time, room, pack/map bytes, remaining and outputs. Measured on the
 // owner bridge 2026-10-21: about 300 ms in 2 fragments, versus about 3.7 s in 27 for 0..1024.
 public static final int LIVE_START=256,LIVE_LENGTH=42;
 public static byte[] status(int seq,int start,int length){
  require(seq>0&&seq<192&&start>=0&&length>0&&start+length<=1024,"Status range");
  return ByteBuffer.allocate(10).put(ascii("STATU")).put((byte)seq).putShort((short)start).putShort((short)length).array();
 }
 /** Live block over the last full read; decode() still validates the merged map. */
 public static byte[] overlay(byte[]full,byte[]live){
  require(full.length>=LIVE_START+LIVE_LENGTH&&live.length==LIVE_LENGTH,"Incomplete live status");
  byte[]merged=full.clone();System.arraycopy(live,0,merged,LIVE_START,LIVE_LENGTH);return merged;
 }
 public static byte[] command(int seq,String action,int value){
  // Command IDs occupy 192..255; 1..191 is reserved for protocol queries.
  require(seq>=192&&seq<=255,"Command sequence");int pos,length;
  switch(action){
   case "start":require(value==1,"Start value");pos=256;length=1;break;
   case "stop":require(value==0,"Stop value");pos=256;length=1;break;
   case "lights":require(value==0||value==1,"Light value");pos=259;length=1;break;
   case "time":require(value>=1&&value<=60,"Time limit");pos=261;length=2;break;
   case "temperature":require(value>=80&&value<=120,"Temperature limit");pos=264;length=2;value=value*10-320;break;
   default:throw new IllegalArgumentException("Unsupported control");
  }
  ByteBuffer b=ByteBuffer.allocate(13+length).put(ascii("SPACK")).put((byte)seq).put((byte)13).put((byte)(5+length)).put((byte)70).put((byte)3).put((byte)3).putShort((short)pos);
  if(length==1)b.put((byte)value);else b.putShort((short)value);return b.array();
 }
 static void require(boolean yes,String message){if(!yes)throw new IllegalArgumentException(message);}
 public static boolean verb(byte[]b,String v){byte[]p=ascii(v);return b.length>=p.length&&Arrays.equals(Arrays.copyOf(b,p.length),p);}
 public static final class Assembly {
  private final java.util.Map<Integer,byte[]> parts=new java.util.HashMap<>();private int size;public boolean complete;
  public void add(byte[]b){
   require(!complete&&verb(b,"STATV")&&b.length>=8,"Invalid status segment");int index=b[5]&255,after=b[6]&255,len=b[7]&255;
   require(len>0&&b.length==len+8&&(after==0||after>index),"Truncated or cyclic status segment");
   byte[]existing=parts.get(index);
   if(existing!=null){require(Arrays.equals(existing,b),"Conflicting status segment");return;}
   require(size+len<=1024&&parts.size()<256,"Oversized status");parts.put(index,b.clone());size+=len;
   // UDP ordering is not guaranteed. Follow the identity-matched response's index chain
   // only when every link exists; duplicate bytes never count toward completeness.
   int next=0,seen=0;
   while(parts.containsKey(next)){
    byte[]part=parts.get(next);seen++;next=part[6]&255;
    if(next==0){require(seen==parts.size(),"Disconnected status fragments");complete=true;return;}
   }
  }
  public byte[]data(){
   require(complete,"Incomplete status");ByteArrayOutputStream bytes=new ByteArrayOutputStream();int next=0;
   do{byte[]part=parts.get(next);bytes.write(part,8,part.length-8);next=part[6]&255;}while(next!=0);
   return bytes.toByteArray();
  }
 }
 public static final class Status {
  public final boolean on,heater,paused,diagnostic,lightOn;public final int requestedMode,requestedMinutes,remaining,maxMinutes,program,error,lightRequested;
  public final double targetF,roomF,minF,maxF;
  private Status(byte[]b){requestedMode=b[256]&255;on=(b[269]&1)!=0;paused=(b[269]&2)!=0;diagnostic=(b[269]&4)!=0;heater=(b[295]&4)!=0;lightOn=(b[295]&16)!=0;lightRequested=b[259]&255;
   requestedMinutes=word(b,261);remaining=word(b,293);maxMinutes=word(b,275);program=b[263]&255;error=word(b,272);targetF=temp(b,264);roomF=temp(b,278);minF=temp(b,11);maxF=temp(b,13);
  }
  public boolean possiblyOn(){return on||heater||requestedMode!=0;}
  // Bit 269.0 stayed clear through a real heated session (2026-10-21), so it cannot prove a session.
  // The device-run countdown can: it was 0 at idle, counted down while running, and cleared at expiry.
  public boolean sessionActive(){return requestedMode==1&&remaining>0;}
 }
 static int word(byte[]b,int p){return ((b[p]&255)<<8)|(b[p+1]&255);}
 static double temp(byte[]b,int p){return (word(b,p)+320)/10.0;}
 public static Status decode(byte[]b){
  require(b.length>=338&&b.length<=1024,"Incomplete status map");require(b[285]==13&&b[291]==3&&b[292]==3&&b[10]==0,"Unsupported pack, map or temperature unit");
  Status s=new Status(b);require(s.requestedMode<=2&&s.lightRequested<=1&&s.program<=2,"Unknown mode");
  require(s.maxMinutes>=1&&s.maxMinutes<=60&&s.requestedMinutes<=60&&s.remaining<=60,"Unsupported runtime limits");
  require(s.minF>=32&&s.minF<=s.maxF&&s.maxF<=150&&s.targetF>=s.minF&&s.targetF<=s.maxF,"Invalid temperature limits");return s;
 }
}
