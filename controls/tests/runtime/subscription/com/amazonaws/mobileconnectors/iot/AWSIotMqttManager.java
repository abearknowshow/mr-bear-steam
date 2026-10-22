package com.amazonaws.mobileconnectors.iot;

import com.amazonaws.auth.CognitoCredentialsProvider;
import org.eclipse.paho.client.mqttv3.MqttException;

/** Test-only SDK boundary: mirrors synchronous ConnectionLost notification in SDK disconnect(). */
public final class AWSIotMqttManager {
 public static volatile AWSIotMqttManager last;
 public static boolean reject=true,deferConnect;
 public volatile int disconnects,publishes;
 public volatile boolean ready;public String publishedPayload,publishedTopic;public static boolean rejectPublish;
 private AWSIotMqttClientStatusCallback callback;
 public AWSIotMqttManager(String id,String endpoint){last=this;}
 public void setAutoReconnect(boolean v){}
 public void setOfflinePublishQueueEnabled(boolean v){}
 public void setCleanSession(boolean v){}
 public void setKeepAlive(int v){}
 public void setMetricsIsEnabled(boolean v){}
 public void connect(CognitoCredentialsProvider c,AWSIotMqttClientStatusCallback cb){
  callback=cb;ready=true;if(!deferConnect)connected();
 }
 public void connected(){callback.onStatusChanged(AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Connected,null);}
 public void subscribeToTopic(String t,AWSIotMqttQos q,AWSIotMqttSubscriptionStatusCallback cb,AWSIotMqttNewMessageCallback message){
  if(reject)cb.onFailure(new MqttException(128));else cb.onSuccess();
 }
 public void publishString(String p,String t,AWSIotMqttQos q){publishes++;}
 public void publishString(String p,String t,AWSIotMqttQos q,AWSIotMqttMessageDeliveryCallback cb,Object data){publishes++;publishedPayload=p;publishedTopic=t;cb.statusChanged(rejectPublish?AWSIotMqttMessageDeliveryCallback.MessageDeliveryStatus.Fail:AWSIotMqttMessageDeliveryCallback.MessageDeliveryStatus.Success,data);}
 public boolean disconnect(){
  if(++disconnects>10)throw new AssertionError("Recursive disconnect: the reader never reports its subscription result");
  callback.onStatusChanged(AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.ConnectionLost,null);
  return true;
 }
}
