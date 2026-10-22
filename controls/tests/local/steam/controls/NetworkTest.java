package local.steam.controls;
public final class NetworkTest {
 public static void main(String[]args)throws Exception{
  boolean fake=args.length==2&&args[0].equals("--fake");
  boolean real=args.length==1&&args[0].equals("--read-only-owner");
  if(!fake&&!real)throw new IllegalArgumentException("Only loopback fake tests or fixed owner read-only checks are allowed");
  UdpTransport t=new UdpTransport(fake?"127.0.0.1":"192.168.1.9",fake?Integer.parseInt(args[1]):10022,fake);
  Protocol.Status status=t.read();if(status.on)throw new AssertionError("Expected off fixture/status");
  if(real){System.out.println("Read-only owner bridge: off; target="+status.targetF+"F; requested="+status.requestedMinutes+"min; actual="+status.roomF+"F");try{t.write("start",1);throw new AssertionError("Read-only allowed a write");}catch(SecurityException expected){}return;}
  ControlEngine e=new ControlEngine(t,()->true,new ControlEngine.Clock(){public long now(){return System.nanoTime()/1000000;}public void pause(long ms)throws Exception{Thread.sleep(ms);}});
  if(!e.execute("start",114,15).sessionActive())throw new AssertionError("start");
  if(e.execute("time",16,0).remaining!=16)throw new AssertionError("add");
  if(e.execute("temperature",115,0).targetF!=115)throw new AssertionError("temperature");
  if(!e.execute("lights",1,0).lightOn)throw new AssertionError("light");
  if(e.execute("stop",0,0).possiblyOn())throw new AssertionError("stop");
  System.out.println("Real UDP client passed loopback fake-device Start, time, temperature, lights and Stop");
 }
}
