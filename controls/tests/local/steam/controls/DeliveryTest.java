package local.steam.controls;

/** Used only by the loopback fault-injection server. No owner address is accepted. */
public final class DeliveryTest {
 public static void main(String[] args)throws Exception{
  if(args.length!=3||!args[0].equals("--loopback"))throw new IllegalArgumentException("Loopback test only");
  UdpTransport t=new UdpTransport("127.0.0.1",Integer.parseInt(args[1]),true);
  switch(args[2]){
   case "sequences":
    for(int i=0;i<66;i++)if(!t.write("time",20))throw new AssertionError("Fake bridge receipt missing");
    break;
   case "drop-first-stop":
    if(!t.write("stop",0))throw new AssertionError("Dropped first OFF packet was not recovered");
    if(t.read().possiblyOn())throw new AssertionError("Recovered OFF did not reach fake device");
    break;
   case "lose-stop-receipts":
    ControlEngine e=new ControlEngine(t,()->true,new ControlEngine.Clock(){
     public long now(){return System.nanoTime()/1000000;}
     public void pause(long ms)throws Exception{Thread.sleep(ms);}
    });
    e.refresh();
    if(e.execute("stop",0,0).possiblyOn())throw new AssertionError("Readback should confirm OFF even when receipts are lost");
    break;
   case "drop-all-stop":
    if(t.write("stop",0))throw new AssertionError("Missing receipt cannot mean delivered");
    if(!t.read().possiblyOn())throw new AssertionError("Dropped OFF must leave fake state on");
    break;
   case "drop-start":
    if(t.write("start",1))throw new AssertionError("Missing Start receipt cannot mean delivered");
    break;
   default:throw new IllegalArgumentException("Unknown fake scenario");
  }
 }
}
