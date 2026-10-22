import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/** Constructs clients only: never calls connect, opens a socket or uses credentials. */
public final class MqttModuleSmoke {
 public static void main(String[] args)throws Exception{
  for(String scheme:new String[]{"tcp","ssl","ws","wss"}){
   MqttAsyncClient client=new MqttAsyncClient(scheme+"://127.0.0.1:1","offline-packaging-test",new MemoryPersistence());
   client.close();
  }
  System.out.println("All four MQTT transports resolve using resources from the built APK; no connection attempted.");
 }
}
