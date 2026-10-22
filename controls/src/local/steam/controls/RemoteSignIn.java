package local.steam.controls;

import android.content.Context;
import org.json.JSONObject;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;

/** One-use credential handoff from the owner's Mac, pinned to its bundled TLS certificate. */
public final class RemoteSignIn {
 private final SSLSocketFactory sockets;
 private static final String BASE="https://"+Household.MAC_HOST+":8777";
 public RemoteSignIn(Context context)throws Exception{
  KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null);
  try(InputStream in=context.getAssets().open("setup-server.crt")){trust.setCertificateEntry("owner-mac",CertificateFactory.getInstance("X.509").generateCertificate(in));}
  TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(trust);
  SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,factory.getTrustManagers(),null);sockets=tls.getSocketFactory();
 }
 private JSONObject request(String path,JSONObject body)throws Exception{
  HttpsURLConnection c=(HttpsURLConnection)new URL(BASE+path).openConnection(Proxy.NO_PROXY);
  c.setSSLSocketFactory(sockets); // Default hostname verification remains enabled.
  c.setInstanceFollowRedirects(false);c.setConnectTimeout(4000);c.setReadTimeout(4000);
  try{
   if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");byte[]data=body.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(data.length);try(OutputStream out=c.getOutputStream()){out.write(data);}}
   int code=c.getResponseCode();if(code!=200&&code!=201)throw new IOException("Setup unavailable");
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[]buffer=new byte[1024];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>8192)throw new IOException("Unexpected setup response");bytes.write(buffer,0,n);}}
   return new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
  }finally{c.disconnect();}
 }
 public JSONObject begin()throws Exception{
  try{JSONObject pair=request("/pair",new JSONObject());if(!pair.getString("id").matches("[0-9a-f]{64}")||!pair.getString("code").matches("[0-9]{6}"))throw new IOException();return pair;}
  catch(Exception e){throw new Exception("Wake the Mac and open its Bear Steam setup page, then tap Retry sign-in.");}
 }
 public JSONObject await(String identity,ControlEngine.Guard guard)throws Exception{
  long end=android.os.SystemClock.elapsedRealtime()+300000;
  while(guard.allowed()&&android.os.SystemClock.elapsedRealtime()<end){
   JSONObject response;
   try{response=request("/pair/"+identity,null);}catch(Exception e){throw new Exception("Mac setup disconnected. Tap Retry sign-in.");}
   if(response.optString("state").equals("credentials")){
    if(!guard.allowed())throw new Exception("Sign-in canceled.");
    if(response.getString("email").length()>254||response.getString("password").length()>2048)throw new Exception("Invalid sign-in response.");
    return response;
   }
   Thread.sleep(1000);
  }
  throw new Exception(guard.allowed()?"Setup code expired. Tap Retry sign-in.":"Sign-in canceled.");
 }
 public void finish(String identity,String state){try{request("/result/"+identity,new JSONObject().put("state",state));}catch(Exception ignored){}}
 public void syncResult(String stage,LightReport report,ReadMetrics.Snapshot counts,String view,int attempt,String command)throws Exception{
  JSONObject body=new JSONObject().put("build","0.22").put("stage",stage);
  body.put("reportedOn",report==null?JSONObject.NULL:report.on);
  body.put("fresh",report!=null&&report.usable(System.currentTimeMillis()));
  body.put("messages",counts.messages).put("reported",counts.reported).put("lights",counts.lights).put("accepted",counts.accepted).put("kind",counts.kind);
  body.put("view",view).put("attempt",attempt).put("command",command);
  request("/sync-result",body);
 }
 /** 0.18 diagnostics: one timeline batch (Timeline.batch JSON) to the Mac over the same pinned TLS.
  *  Returns the HTTP status so the caller can skip a batch the Mac refused. Sends no credentials. */
 public int timeline(byte[] batch)throws Exception{
  if(batch.length>Timeline.BATCH_BYTES+4096)throw new IOException("Timeline batch too large");
  HttpsURLConnection c=(HttpsURLConnection)new URL(BASE+"/timeline").openConnection(Proxy.NO_PROXY);
  c.setSSLSocketFactory(sockets);c.setInstanceFollowRedirects(false);c.setConnectTimeout(4000);c.setReadTimeout(4000);
  try{
   c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(batch.length);
   try(OutputStream out=c.getOutputStream()){out.write(batch);}
   int code=c.getResponseCode();
   try(InputStream in=code<400?c.getInputStream():c.getErrorStream()){if(in!=null){byte[]buffer=new byte[1024];int total=0,n;while(total<8192&&(n=in.read(buffer))!=-1)total+=n;}}
   return code;
  }finally{c.disconnect();}
 }
}
