package local.steam.controls;
import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;
/** Bounded read-only discovery of the already configured owner SPA, never pairing. */
public final class BridgeLocator {
 private static long now(){return System.nanoTime()/1000000;}
 static boolean matches(byte[] packet,String host,int port){
  if(port!=10022||packet.length>512||!host.startsWith(Household.LAN_PREFIX)||!host.substring(Household.LAN_PREFIX.length()).matches("(?:[1-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-4])"))return false;
  String text=new String(packet,StandardCharsets.UTF_8),prefix="<HELLO>"+Protocol.SPA+"|",suffix="</HELLO>";
  if(!text.startsWith(prefix)||!text.endsWith(suffix))return false;
  String name=text.substring(prefix.length(),text.length()-suffix.length());return name.length()<=128&&!name.matches("(?s).*[<>|\\r\\n].*");
 }
 public String locate()throws Exception{
  Set<String> found=new HashSet<>();byte[]hello="<HELLO>1</HELLO>".getBytes(StandardCharsets.US_ASCII);
  try(DatagramSocket socket=new DatagramSocket()){
   socket.setBroadcast(true);InetAddress broadcast=InetAddress.getByName("255.255.255.255");long end=now()+1800,next=0;int sent=0;
   for(int packets=0;now()<end&&packets<512;){
    if(sent<3&&now()>=next){socket.send(new DatagramPacket(hello,hello.length,broadcast,10022));sent++;next=now()+600;}
    socket.setSoTimeout((int)Math.max(1,Math.min(end-now(),sent<3?next-now():end-now())));
    DatagramPacket response=new DatagramPacket(new byte[513],513);
    try{socket.receive(response);packets++;String host=response.getAddress().getHostAddress();if(matches(Arrays.copyOf(response.getData(),response.getLength()),host,response.getPort()))found.add(host);}
    catch(SocketTimeoutException waiting){}
   }
  }
  if(found.size()!=1)throw new SocketTimeoutException(found.isEmpty()?"Owner Steamlinx not found on the home network.":"Multiple addresses claimed the owner Steamlinx; controls remain unavailable.");
  return found.iterator().next();
 }
}
