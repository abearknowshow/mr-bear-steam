import local.steam.controls.*;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttManager;
import java.util.concurrent.*;

/** Exercises the real reader adapter against SDK disconnect/subscription callbacks, offline. */
public final class ReaderCleanupTest {
 static int checks;
 static void check(boolean v){checks++;if(!v)throw new AssertionError();}
 public static void main(String[]args)throws Exception{
  AwsShadowRead wire=new AwsShadowRead(null,"unused.invalid");
  ShadowRead read=new ShadowRead("su-02:00:00:00:00:02-dev",wire,()->true,s->{});
  read.start();
  check(read.stage().equals("Checking live-update subscription failed (MQTT 128)"));
  check(!read.available());check(AWSIotMqttManager.last.disconnects==1);
  check(AWSIotMqttManager.last.publishes==0);
  read.close();check(AWSIotMqttManager.last.disconnects==1);

  AWSIotMqttManager.reject=false;
  wire=new AwsShadowRead(null,"unused.invalid");
  read=new ShadowRead("su-02:00:00:00:00:02-dev",wire,()->true,s->{});
  read.start();check(read.available());check(AWSIotMqttManager.last.publishes==0);
  read.refresh(System.currentTimeMillis(),0);check(AWSIotMqttManager.last.publishes==1);check(read.report()==null);
  check(AWSIotMqttManager.last.publishedPayload.equals(StatusRequest.build("su-02:00:00:00:00:02-dev")));
  check(AWSIotMqttManager.last.publishedTopic.equals("$aws/things/su-02:00:00:00:00:02-dev/shadow/update"));
  try{wire.requestStatus("foreign");throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
  check(AWSIotMqttManager.last.publishes==1);
  AWSIotMqttManager.rejectPublish=true;read.refresh(System.currentTimeMillis(),30000);check(read.available());check(read.stage().equals("Status refresh request failed"));AWSIotMqttManager.rejectPublish=false;
  read.close();read.refresh(System.currentTimeMillis(),60000);check(!read.available());check(AWSIotMqttManager.last.disconnects==1);check(AWSIotMqttManager.last.publishes==2);

  AWSIotMqttManager.deferConnect=true;AWSIotMqttManager.last=null;
  final AwsShadowRead late=new AwsShadowRead(null,"unused.invalid");
  ExecutorService runner=Executors.newSingleThreadExecutor();
  Future<?> task=runner.submit(()->{try{late.connect("app-bearread0123456789-dev",()->{});}catch(Exception expected){}});
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
  while((AWSIotMqttManager.last==null||!AWSIotMqttManager.last.ready)&&System.nanoTime()<deadline)Thread.yield();
  check(AWSIotMqttManager.last!=null&&AWSIotMqttManager.last.ready);
  late.close();AWSIotMqttManager.last.connected();
  task.get(2,TimeUnit.SECONDS);runner.shutdownNow();
  deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
  while(AWSIotMqttManager.last.disconnects<2&&System.nanoTime()<deadline)Thread.yield();
  check(AWSIotMqttManager.last.disconnects==2);
  System.out.println("Reader SDK callback checks passed: "+checks+"; no sockets or credentials");
 }
}
