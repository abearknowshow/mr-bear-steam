package local.steam.controls;

import android.content.Context;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.CognitoCredentialsProvider;
import com.amazonaws.logging.LogFactory;

import com.amazonaws.mobileconnectors.cognitoidentityprovider.*;
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.*;
import com.amazonaws.mobileconnectors.cognitoidentityprovider.handlers.AuthenticationHandler;
import com.amazonaws.mobileconnectors.iot.*;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.iotdata.AWSIotDataClient;
import com.amazonaws.services.iotdata.model.GetThingShadowRequest;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Account-scoped lighting. A separate unique client reads reports without replacing the panel. */
public final class CloudLights {
 private static final String POOL=Household.USER_POOL_ID,CLIENT=Household.APP_CLIENT_ID;
 private static final String IDENTITY=Household.IDENTITY_POOL_ID;
 private static final String PROVIDER="cognito-idp."+Household.REGION+".amazonaws.com/"+POOL;
 private static final String ENDPOINT=Household.IOT_ENDPOINT;
 private static final String REST=Household.DEVICES_URL;
 private final Context context;private final CognitoUserPool pool;private String email,thing;private CognitoUser user;
 public volatile boolean ready,requiresSignIn;
 public volatile LightReport report;
 public volatile String reportIssue="Not checked";
 private boolean reportAccessDenied;
 private volatile ShadowRead reader;
 public CloudLights(Context c){
  context=c.getApplicationContext();LogFactory.setLevel(LogFactory.Level.OFF);
  ClientConfiguration config=new ClientConfiguration();config.setConnectionTimeout(8000);config.setSocketTimeout(8000);config.setMaxErrorRetry(0);
  pool=new CognitoUserPool(context,POOL,CLIENT,null,config,Regions.fromName(Household.REGION));pool.setPersistenceEnabled(true);
  email=context.getSharedPreferences("lights",0).getString("email","");
  requiresSignIn=email.isEmpty();
 }
 public String savedEmail(){return email;}
 public void startReader(ControlEngine.Guard guard,Runnable changed)throws Exception{
  final CognitoCredentialsProvider provider;final String ownedThing;
  synchronized(this){
   if(!ready||!guard.allowed())return;
   provider=credentials(session(null));ownedThing=thing;
  }
  if(!guard.allowed())return;
  stopReader();
  final ShadowRead[] holder=new ShadowRead[1];
  ShadowRead next=new ShadowRead(ownedThing,new AwsShadowRead(provider,ENDPOINT),guard,stage->{
   if(guard.allowed()&&reader==holder[0]){
    report=holder[0].report();reportIssue=stage;changed.run();
   }
  });
  holder[0]=next;reader=next;next.start();
 }
 public void stopReader(){ShadowRead r=reader;reader=null;if(r!=null)r.close();report=null;}
 public ReadMetrics.Snapshot readMetrics(){ShadowRead r=reader;return r==null?new ReadMetrics().snapshot():r.metrics();}
 public void refreshLiveReport(){ShadowRead r=reader;if(r!=null)r.refresh(System.currentTimeMillis(),android.os.SystemClock.elapsedRealtime());}
 public boolean readerAvailable(){ShadowRead r=reader;return r!=null&&r.available();}
 private CognitoUserSession session(final String password)throws Exception{
  if(user==null)user=pool.getUser(email);
  AtomicReference<CognitoUserSession> session=new AtomicReference<>();AtomicReference<Exception> failure=new AtomicReference<>();
  user.getSession(new AuthenticationHandler(){
   public void onSuccess(CognitoUserSession s,CognitoDevice d){session.set(s);}
   public void getAuthenticationDetails(AuthenticationContinuation c,String id){if(password==null){requiresSignIn=true;ready=false;failure.set(new Exception("Your session expired. Sign in again."));return;}c.setAuthenticationDetails(new AuthenticationDetails(email,password,null));c.continueTask();}
   public void getMFACode(MultiFactorAuthenticationContinuation c){failure.set(new Exception("This account requires MFA. Complete sign-in in Mr. Steam; this test build does not support that challenge yet."));}
   public void authenticationChallenge(ChallengeContinuation c){failure.set(new Exception("Finish the account challenge in Mr. Steam before trying again."));}
   public void onFailure(Exception e){failure.set(new Exception("Mr. Steam sign-in failed. Check the account and connection."));}
  });
  if(failure.get()!=null)throw failure.get();if(session.get()==null)throw new Exception("Sign-in did not complete.");return session.get();
 }
 private String discover(String token)throws Exception{
  HttpURLConnection c=(HttpURLConnection)new URL(REST).openConnection(Proxy.NO_PROXY);c.setRequestMethod("POST");c.setInstanceFollowRedirects(false);c.setConnectTimeout(8000);c.setReadTimeout(8000);c.setDoOutput(true);
  c.setRequestProperty("Authorization",token);c.setRequestProperty("Content-Type","application/json");
  byte[]payload=new JSONObject().put("model_number","SU-70").put("user_id",email).toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(payload.length);
  try{
   try(OutputStream out=c.getOutputStream()){out.write(payload);}
   if(c.getResponseCode()!=200)throw new Exception("Could not verify the panel in this account.");
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[]buffer=new byte[2048];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>131072)throw new Exception("Unexpected account response.");bytes.write(buffer,0,n);}}
   JSONArray devices=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8)).optJSONArray("data");String match=null;
   if(devices!=null)for(int i=0;i<devices.length();i++){String name=devices.getJSONObject(i).optString("thingName","");if(LightPayload.ownThing(name)){if(match!=null)throw new Exception("Duplicate device identity.");match=name;}}
   if(match==null)throw new Exception("This account did not return the panel ending "+Household.LIGHT_THING.substring(Household.LIGHT_THING.length()-9,Household.LIGHT_THING.length()-4)+". Check the account linked on the wall panel.");return match;
  }finally{c.disconnect();}
 }
 private CognitoCredentialsProvider credentials(CognitoUserSession s){CognitoCredentialsProvider p=new CognitoCredentialsProvider(IDENTITY,Regions.fromName(Household.REGION));p.setLogins(Collections.singletonMap(PROVIDER,s.getIdToken().getJWTToken()));return p;}
 private AWSIotMqttManager connect(CognitoCredentialsProvider provider,ControlEngine.Guard guard)throws Exception{
  if(!guard.allowed())throw new Exception("Lighting request canceled.");
  AWSIotMqttManager mqtt=new AWSIotMqttManager("app-bear"+UUID.randomUUID().toString().replace("-","").substring(0,10)+"-dev",ENDPOINT);
  mqtt.setAutoReconnect(false);mqtt.setOfflinePublishQueueEnabled(false);mqtt.setKeepAlive(30);mqtt.setCleanSession(true);mqtt.setMetricsIsEnabled(false);
  CountDownLatch connected=new CountDownLatch(1);AtomicBoolean success=new AtomicBoolean(false),expired=new AtomicBoolean(false);AtomicReference<Throwable> failure=new AtomicReference<>();
  mqtt.connect(provider,(state,error)->{if(state==AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Connected){if(expired.get()||!guard.allowed()){mqtt.disconnect();return;}success.set(true);connected.countDown();}else if(error!=null){failure.set(error);connected.countDown();}});
  if(!connected.await(12,TimeUnit.SECONDS)||!success.get()||!guard.allowed()){ready=false;expired.set(true);mqtt.disconnect();throw new Exception("Account verified. Cloud connection unavailable. Tap Reconnect.",failure.get());}return mqtt;
 }
 public synchronized void signIn(String enteredEmail,String password,ControlEngine.Guard guard)throws Exception{
  ready=false;requiresSignIn=true;email=enteredEmail.trim();if(email.isEmpty()||password.isEmpty())throw new Exception("Enter your Mr. Steam email and password.");user=pool.getUser(email);
  CognitoUserSession session=session(password);String verified=discover(session.getIdToken().getJWTToken());
  thing=verified;requiresSignIn=false;
  context.getSharedPreferences("lights",0).edit().putString("email",email).apply();
  AWSIotMqttManager mqtt=connect(credentials(session),guard);mqtt.disconnect();ready=true;
 }
 public synchronized void restore(ControlEngine.Guard guard)throws Exception{
  if(email.isEmpty())return;ready=false;CognitoUserSession s=session(null);thing=discover(s.getIdToken().getJWTToken());
  AWSIotMqttManager mqtt=connect(credentials(s),guard);mqtt.disconnect();ready=true;
 }
 /** HTTPS GET uses the owner's credentials without opening a second MQTT client as the panel.
  * A denied read stays unavailable; never borrow the wall panel's client ID to bypass it. */
 public synchronized void refreshReport(ControlEngine.Guard guard){
  if(!ready||!guard.allowed()||reportAccessDenied)return;
  AWSIotDataClient data=null;
  try{
   if(!LightPayload.ownThing(thing))throw new IllegalArgumentException();
   ClientConfiguration config=new ClientConfiguration();config.setConnectionTimeout(3000);config.setSocketTimeout(3000);config.setMaxErrorRetry(0);
   data=new AWSIotDataClient(credentials(session(null)),config);
   data.setRegion(com.amazonaws.regions.Region.getRegion(Regions.fromName(Household.REGION)));data.setEndpoint("https://"+ENDPOINT);
   if(!guard.allowed())return;
   java.nio.ByteBuffer payload=data.getThingShadow(new GetThingShadowRequest().withThingName(thing)).getPayload().asReadOnlyBuffer();
   if(payload.remaining()>131072)throw new IllegalArgumentException();
   byte[] bytes=new byte[payload.remaining()];payload.get(bytes);
   LightReport next=LightReport.parse(new String(bytes,StandardCharsets.UTF_8),System.currentTimeMillis());
   if(guard.allowed()){report=next;reportIssue=next.usable(System.currentTimeMillis())?"Reported state available":"Reported state is old";}
  }catch(AmazonServiceException e){
   if(guard.allowed()){report=null;reportAccessDenied=e.getStatusCode()==401||e.getStatusCode()==403;reportIssue=reportAccessDenied?"Cloud status read not permitted (HTTP "+e.getStatusCode()+")":"Cloud status read failed (HTTP "+e.getStatusCode()+")";}
  }catch(Exception e){if(guard.allowed()){report=null;reportIssue="Chroma reported state unavailable";}}
  finally{if(data!=null)data.shutdown();}
 }
 public synchronized void send(boolean on,String color,int brightness,boolean disco,ControlEngine.Guard guard)throws Exception{
  if(!ready)throw new Exception("Sign in to your Mr. Steam account.");String payload=LightPayload.build(thing,on,color,brightness,disco);
  CognitoUserSession s=session(null);AWSIotMqttManager mqtt=connect(credentials(s),guard);
  try{
   if(!guard.allowed())throw new Exception("Lighting request canceled.");
   CountDownLatch delivered=new CountDownLatch(1);AtomicBoolean accepted=new AtomicBoolean(false);
   mqtt.publishString(payload,"$aws/things/"+thing+"/shadow/update",AWSIotMqttQos.QOS1,(state,data)->{accepted.set(state==AWSIotMqttMessageDeliveryCallback.MessageDeliveryStatus.Success);delivered.countDown();},null);
   if(!delivered.await(8,TimeUnit.SECONDS)||!accepted.get())throw new Exception("Light change unconfirmed. Check the lights before retrying.");
   // Broker acceptance is delivery evidence only, not hardware or color readback.
  }finally{mqtt.disconnect();}
 }
}
