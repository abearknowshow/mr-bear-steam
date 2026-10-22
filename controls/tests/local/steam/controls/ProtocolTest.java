package local.steam.controls;
import java.nio.file.*;
import java.util.*;
public final class ProtocolTest {
 static int checks;
 static void check(boolean yes,String name){checks++;if(!yes)throw new AssertionError(name);}
 interface Work{void run()throws Exception;}
 static void rejects(Work w,String name)throws Exception{try{w.run();throw new AssertionError(name);}catch(IllegalArgumentException expected){checks++;}}
 static String hex(byte[]b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format("%02x",v&255));return s.toString();}
 public static void main(String[]args)throws Exception{
  byte[] fixture=Files.readAllBytes(Paths.get(args[0]));
  Protocol.Status s=Protocol.decode(fixture);check(!s.on&&!s.heater,"fixture off");check(s.targetF==115&&s.requestedMinutes==20,"temperature/runtime");check(s.roomF==94,"actual temperature");
  check(hex(Protocol.command(192,"start",1)).equals("535041434bc00d06460303010001"),"start exact wire bytes");
  check(hex(Protocol.command(193,"stop",0)).equals("535041434bc10d06460303010000"),"stop exact wire bytes");
  check(hex(Protocol.command(194,"temperature",115)).equals("535041434bc20d074603030108033e"),"Fahrenheit encoding");
  check(hex(Protocol.command(195,"time",20)).endsWith("01050014"),"runtime offset");
  check(hex(Protocol.command(255,"lights",1)).endsWith("010301"),"light power offset");
  rejects(()->Protocol.command(1,"stop",0),"query sequence forbidden for commands");
  rejects(()->Protocol.command(191,"stop",0),"lower command boundary");
  rejects(()->Protocol.command(256,"stop",0),"upper command boundary");
  rejects(()->Protocol.query(192,false),"command sequence forbidden for queries");
  rejects(()->Protocol.command(192,"aroma",1),"no arbitrary commands");rejects(()->Protocol.command(192,"start",2),"no diagnostic mode");
  rejects(()->Protocol.command(192,"temperature",121),"high temp");rejects(()->Protocol.command(192,"time",61),"high runtime");
  rejects(()->Protocol.command(192,"time",0),"zero runtime");rejects(()->Protocol.command(192,"lights",2),"invalid lights");
  rejects(()->Protocol.decode(new byte[20]),"truncated map");final byte[]bad=fixture.clone();bad[291]=4;rejects(()->Protocol.decode(bad),"unknown config");
  final byte[]c=fixture.clone();c[10]=1;rejects(()->Protocol.decode(c),"unsupported unit");
  byte[]body=new byte[]{0,60,1,62,(byte)255};byte[]envelope=Protocol.envelope("IOS-test","SPA-test",body);
  check(Arrays.equals(body,Protocol.unwrap(envelope,"IOS-test","SPA-test")),"binary envelope");
  rejects(()->Protocol.unwrap(envelope,"SPA-test","IOS-test"),"identity mismatch");rejects(()->Protocol.envelope("x<y","z",body),"identity injection");
  Protocol.Assembly a=new Protocol.Assembly();a.add(new byte[]{83,84,65,84,86,0,1,2,10,11});a.add(new byte[]{83,84,65,84,86,1,0,1,12});check(Arrays.equals(a.data(),new byte[]{10,11,12}),"segments");
  Protocol.Assembly reordered=new Protocol.Assembly();
  reordered.add(new byte[]{83,84,65,84,86,1,0,1,12});check(!reordered.complete,"tail alone is incomplete");
  reordered.add(new byte[]{83,84,65,84,86,1,0,1,12});check(!reordered.complete,"duplicate tail is harmless");
  reordered.add(new byte[]{83,84,65,84,86,0,1,2,10,11});check(Arrays.equals(reordered.data(),new byte[]{10,11,12}),"reordered fragments assemble exactly");
  Protocol.Assembly conflict=new Protocol.Assembly();conflict.add(new byte[]{83,84,65,84,86,1,0,1,12});
  rejects(()->conflict.add(new byte[]{83,84,65,84,86,1,0,1,99}),"conflicting duplicate rejected");
  rejects(()->new Protocol.Assembly().add(new byte[]{83,84,65,84,86,1,1,1,12}),"cyclic fragment rejected");
  rejects(()->new Protocol.Assembly().add(new byte[]{83,84,65,84,86,0,0,2,12}),"truncated fragment rejected");
  rejects(()->new Protocol.Assembly().data(),"incomplete");rejects(()->a.add(new byte[8]),"already complete");
  System.out.println("Protocol checks passed: "+checks);
 }
}
