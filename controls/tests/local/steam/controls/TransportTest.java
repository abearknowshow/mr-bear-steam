package local.steam.controls;

/** Loopback only: the map survives a wrong-range reply and a failed refresh, and is never refreshed mid-command. */
public final class TransportTest {
 static int checks;static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
 public static void main(String[]args)throws Exception{
  if(args.length!=2||!args[0].equals("--fake"))throw new IllegalArgumentException("Loopback fake only");
  UdpTransport t=new UdpTransport("127.0.0.1",Integer.parseInt(args[1]),false);
  boolean[]idle={true};t.refreshMapWhen(()->idle[0]);t.abandonReadsWhen(()->false);
  t.read();checks++;                                                   // 1: full read builds the map
  try{t.read();throw new AssertionError("a wrong-range reply must not decode");}
  catch(java.net.SocketTimeoutException lost){check(lost.getMessage().contains("different range"),"wrong-range reply is a lost reply");} // 2
  t.read();checks++;                                                   // 3: live again, map kept (the fake checks no full read)
  t.refreshEveryForTest(1);idle[0]=false;
  t.read();checks++;                                                   // 4: refresh due, but a command is running: live read
  idle[0]=true;
  try{t.read();throw new AssertionError("the fake drops this refresh");}catch(java.net.SocketTimeoutException expected){checks++;} // 5
  t.read();checks++;                                                   // 6: the failed refresh kept the map; live read, retry later
  // 7: a full map read gives way to a Stop tap within a slice, not after its 6 s deadline.
  UdpTransport fresh=new UdpTransport("127.0.0.1",Integer.parseInt(args[1]),false);boolean[]stop={false};
  fresh.abandonReadsWhen(()->stop[0]);fresh.refreshMapWhen(()->true);
  new Thread(()->{try{Thread.sleep(300);}catch(InterruptedException e){}stop[0]=true;}).start();
  long t0=System.nanoTime();
  try{fresh.read();throw new AssertionError("the fake drops this full read");}
  catch(java.net.SocketTimeoutException e){check(e.getMessage().contains("abandoned"),"full read abandoned for Stop");}
  long ms=(System.nanoTime()-t0)/1000000;check(ms<1000,"full read gave way within a second, took "+ms+" ms");
  System.out.println("Transport checks passed: "+checks);
 }
}
