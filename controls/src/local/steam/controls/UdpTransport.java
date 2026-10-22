package local.steam.controls;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fixed owner bridge. No broadcast, pairing, discovery, cloud or incoming server. */
public final class UdpTransport implements ControlEngine.Transport {
 private final InetAddress address;private final int port;private final boolean writesEnabled;private boolean formatKnown;
 private int commandSequence=191;
 // One client identity for every read and command of this transport. The bridge dropped exactly 1 in 5 requests
 // from a new identity and none from a kept one (read-only probe, 2026-10-21: 60/75 vs 75/75). Each request still
 // gets its own socket, so a late reply to an earlier request arrives at a closed port and cannot mix into a new read.
 private final String clientId="IOS"+UUID.randomUUID().toString();
 private synchronized int nextCommandSequence(){commandSequence=commandSequence==255?192:commandSequence+1;return commandSequence;}
 public UdpTransport(String host,int port,boolean enableWrites)throws Exception{address=InetAddress.getByName(host);this.port=port;writesEnabled=enableWrites;}
 private static long now(){return System.nanoTime()/1000000;}
 private static final class Session implements AutoCloseable {
  final DatagramSocket socket;final String id;
  Session(InetAddress host,int port,String id)throws Exception{this.id=id;socket=new DatagramSocket();socket.connect(host,port);}
  void send(byte[]body)throws Exception{byte[]p=Protocol.envelope(id,Protocol.SPA,body);socket.send(new DatagramPacket(p,p.length));}
  byte[] receive(long deadline)throws Exception{
   for(int ignored=0;ignored<512&&now()<deadline;ignored++){
    socket.setSoTimeout((int)Math.max(1,deadline-now()));byte[]buffer=new byte[4096];DatagramPacket packet=new DatagramPacket(buffer,buffer.length);socket.receive(packet);
    byte[]body;
    try{body=Protocol.unwrap(Arrays.copyOf(buffer,packet.getLength()),Protocol.SPA,id);}catch(IllegalArgumentException staleOrMalformed){continue;}
    if(Protocol.verb(body,"RFERR"))throw new Exception("Steamlinx radio link error.");return body;
   }
   throw new SocketTimeoutException("No matching Steamlinx response.");
  }
  public void close(){socket.close();}
 }
 // The last full map supplies limits and map identity; live reads replace only offsets 256..297.
 private byte[] full;private long fullAt;static final long FULL_REFRESH_MS=300000;private long refreshMs=FULL_REFRESH_MS;
 void refreshEveryForTest(long ms){refreshMs=ms;}
 private volatile java.util.function.BooleanSupplier abandon=()->false,idle=()->true;
 public void abandonReadsWhen(java.util.function.BooleanSupplier a){abandon=a;}
 public void refreshMapWhen(java.util.function.BooleanSupplier i){idle=i;}
 // After a failed refresh the cached map is kept and the refresh is tried again this much later.
 static final long REFRESH_RETRY_MS=30000;
 // 0.18 timeline only: what the last read asked and received. Guarded by read()'s lock.
 private int asks,fragments;
 @Override public synchronized Protocol.Status read()throws Exception{
  // Without a map only a full read can decode. With one, the periodic refresh waits for idle reads,
  // so a command never pays for a 4 s full read.
  boolean fullRead=full==null||now()-fullAt>refreshMs&&idle.getAsBoolean();long started=now();asks=0;fragments=0;
  try{
   Protocol.Status s=fullRead?readFull():readLive();
   Timeline.note("bridge.read","ok",true,"kind",fullRead?"full":"live","ms",now()-started,"asks",asks,"frags",fragments);
   return s;
  }catch(Exception e){
   Timeline.note("bridge.read","ok",false,"kind",fullRead?"full":"live","ms",now()-started,"asks",asks,"frags",fragments,
    "reason",Timeline.readFailure(e,fragments),"error",Timeline.text(e));
   // A failed refresh keeps the map it was refreshing; live reads carry on and the refresh is retried later.
   if(fullRead&&full!=null)fullAt=now()-refreshMs+REFRESH_RETRY_MS;
   throw e;
  }
 }
 private Protocol.Status readLive()throws Exception{
  byte[]live=null;
  // One quick re-ask on a fresh socket, so a late reply to the first ask cannot mix in.
  for(int attempt=0;attempt<2&&live==null;attempt++){
   try(Session s=new Session(address,port,clientId)){
    s.send(Protocol.status(2,Protocol.LIVE_START,Protocol.LIVE_LENGTH));asks++;long deadline=now()+1000;Protocol.Assembly a=new Protocol.Assembly();
    // Wait in 100 ms slices so a Stop tap is noticed within one slice instead of after both asks.
    while(live==null&&now()<deadline){
     if(abandon.getAsBoolean())throw new SocketTimeoutException("Read abandoned for Stop.");
     try{byte[]body=s.receive(Math.min(deadline,now()+100));if(Protocol.verb(body,"STATV")){fragments++;a.add(body);if(a.complete)live=a.data();}}
     catch(SocketTimeoutException slice){}
    }
   }
  }
  if(live==null)throw new SocketTimeoutException("No complete Steamlinx status.");
  // A reply of another size answers some other reader's range (the bridge mixes concurrent readers up).
  // That is a lost reply, not a sign the map changed, so the map is kept.
  if(live.length!=Protocol.LIVE_LENGTH)throw new SocketTimeoutException("Steamlinx answered a different range.");
  try{return Protocol.decode(Protocol.overlay(full,live));}
  catch(RuntimeException invalid){full=null;throw invalid;}
 }
 // Waits for the next reply in 100 ms slices, so a Stop tap is not held up by a 4-6 s full map read (emulator, 2026-10-21).
 private byte[] sliced(Session s,long deadline)throws Exception{
  while(true){
   if(abandon.getAsBoolean())throw new SocketTimeoutException("Read abandoned for Stop.");
   try{return s.receive(Math.min(deadline,now()+100));}
   catch(SocketTimeoutException slice){if(now()>=deadline)throw slice;}
  }
 }
 private Protocol.Status readFull()throws Exception{
  try(Session s=new Session(address,port,clientId)){
   long deadline;
   if(!formatKnown){s.send(Protocol.query(1,true));asks++;deadline=now()+6000;boolean known=false;
   while(now()<deadline){byte[]body=sliced(s,deadline);if(Protocol.verb(body,"FILES")){Protocol.require(new String(body,StandardCharsets.US_ASCII).equals(Protocol.FORMAT),"Unknown Steamlinx map");known=true;break;}}
   if(!known)throw new SocketTimeoutException("No compatible Steamlinx map.");formatKnown=true;}
   s.send(Protocol.query(2,false));asks++;deadline=now()+6000;Protocol.Assembly a=new Protocol.Assembly();
   while(now()<deadline){byte[]body=sliced(s,deadline);if(Protocol.verb(body,"STATV")){fragments++;a.add(body);if(a.complete){byte[]data=a.data();Protocol.Status st=Protocol.decode(data);full=data;fullAt=now();return st;}}}
   throw new SocketTimeoutException("No complete Steamlinx status.");
  }
 }
 @Override public boolean write(String action,int value)throws Exception{
  long started=now();int[]sends={0};
  try{
   boolean receipt=send(action,value,sends);
   Timeline.note("bridge.write","action",action,"value",value,"receipt",receipt,"sends",sends[0],"ms",now()-started);
   return receipt;
  }catch(Exception e){
   Timeline.note("bridge.write","action",action,"value",value,"ok",false,"sends",sends[0],"ms",now()-started,"error",Timeline.text(e));
   throw e;
  }
 }
 private boolean send(String action,int value,int[]sends)throws Exception{
  if(!writesEnabled)throw new SecurityException("This transport is read-only.");
  byte[]body=Protocol.command(nextCommandSequence(),action,value);
  try(Session s=new Session(address,port,clientId)){
   s.send(body);long deadline=now()+1200;
   // A bounded retransmission is allowed only for the explicit, idempotent OFF.
   // Keep the same packet identity/sequence; do not create a new command for a retry.
   int sent=1;sends[0]=1;boolean stopping=action.equals("stop");long nextRetry=now()+400;
   while(now()<deadline){
    long receiveUntil=stopping&&sent<3?Math.min(deadline,nextRetry):deadline;
    try{if(Protocol.verb(s.receive(receiveUntil),"PACKS"))return true;}
    catch(SocketTimeoutException noReceipt){
     if(stopping&&sent<3&&now()<deadline){s.send(body);sent++;sends[0]=sent;nextRetry=now()+400;}
    }
   }
   return false;
  }
 }
}
