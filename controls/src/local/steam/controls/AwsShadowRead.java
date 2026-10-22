package local.steam.controls;

import com.amazonaws.auth.CognitoCredentialsProvider;
import com.amazonaws.mobileconnectors.iot.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.eclipse.paho.client.mqttv3.MqttException;

/** Separate read connection: a denied subscription cannot change command readiness. */
public final class AwsShadowRead implements ShadowRead.Wire {
 private final CognitoCredentialsProvider credentials;private final String endpoint;
 private volatile AWSIotMqttManager mqtt;private volatile boolean closed,connected;
 public AwsShadowRead(CognitoCredentialsProvider credentials,String endpoint){this.credentials=credentials;this.endpoint=endpoint;}
 public void connect(String clientId,Runnable disconnected)throws Exception{
  if(!clientId.matches("app-bearread[0-9a-f]{10}-dev")||closed)throw new Exception("Invalid reader");
  AWSIotMqttManager manager=new AWSIotMqttManager(clientId,endpoint);mqtt=manager;
  manager.setAutoReconnect(false);manager.setOfflinePublishQueueEnabled(false);manager.setCleanSession(true);manager.setKeepAlive(30);manager.setMetricsIsEnabled(false);
  CountDownLatch done=new CountDownLatch(1);AtomicBoolean success=new AtomicBoolean();AtomicInteger failure=new AtomicInteger(-1);
  manager.connect(credentials,(state,error)->{
   if(closed){
    // disconnect() synchronously reports ConnectionLost. Never disconnect in response
    // to that notification, and do not block the SDK callback on a late connection.
    if(state==AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Connected){
     Thread cleanup=new Thread(()->disconnectQuietly(manager),"bear-reader-close");cleanup.setDaemon(true);cleanup.start();
    }
    done.countDown();return;
   }
   if(state==AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Connected){connected=true;success.set(true);done.countDown();}
   else if(error!=null||state==AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.ConnectionLost){
    connected=false;success.set(false);failure.set(code(error));done.countDown();disconnected.run();
   }
  });
  if(!done.await(10,TimeUnit.SECONDS)||!success.get()||closed)throw new ShadowRead.ReadFailure(failure.get());
 }
 public void subscribe(String topic,ShadowRead.Message receive)throws Exception{
  if(closed||!connected)throw new Exception("Closed");
  CountDownLatch done=new CountDownLatch(1);AtomicBoolean success=new AtomicBoolean();AtomicInteger failure=new AtomicInteger(-1);
  mqtt.subscribeToTopic(topic,AWSIotMqttQos.QOS1,new AWSIotMqttSubscriptionStatusCallback(){
   public void onSuccess(){success.set(true);done.countDown();}
   public void onFailure(Throwable e){failure.set(code(e));done.countDown();}
  },(name,data)->{if(!closed&&topic.equals(name)&&data.length<=131072)receive.received(new String(data,StandardCharsets.UTF_8));});
  if(!done.await(5,TimeUnit.SECONDS)||!success.get()||closed||!connected)throw new ShadowRead.ReadFailure(failure.get());
 }
 public void requestStatus(String thing)throws Exception{
  String payload=StatusRequest.build(thing);
  AWSIotMqttManager manager=mqtt;if(closed||!connected||manager==null)throw new Exception("Closed");
  CountDownLatch done=new CountDownLatch(1);AtomicBoolean accepted=new AtomicBoolean();
  manager.publishString(payload,"$aws/things/"+thing+"/shadow/update",AWSIotMqttQos.QOS1,(state,data)->{
   accepted.set(state==AWSIotMqttMessageDeliveryCallback.MessageDeliveryStatus.Success);done.countDown();
  },null);
  if(!done.await(3,TimeUnit.SECONDS)||!accepted.get()||closed||!connected)throw new Exception("Status refresh unavailable");
 }
 public void close(){
  AWSIotMqttManager manager;
  synchronized(this){if(closed)return;closed=true;connected=false;manager=mqtt;}
  disconnectQuietly(manager);
 }
 private static void disconnectQuietly(AWSIotMqttManager manager){
  if(manager!=null)try{manager.disconnect();}catch(RuntimeException ignored){/* Keep the original read failure available to the UI. */}
 }
 private static int code(Throwable e){for(int i=0;e!=null&&i<5;i++,e=e.getCause())if(e instanceof MqttException)return ((MqttException)e).getReasonCode();return -1;}
}
