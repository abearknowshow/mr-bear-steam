package local.steam.support;
import android.content.Context;import org.json.JSONObject;import javax.net.ssl.*;import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.security.KeyStore;import java.security.cert.CertificateFactory;
final class Transport {
 private final SSLSocketFactory sockets;
 Transport(Context c)throws Exception{KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null);try(InputStream in=c.getAssets().open("setup-server.crt")){trust.setCertificateEntry("owner",CertificateFactory.getInstance("X.509").generateCertificate(in));}TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,tm.getTrustManagers(),null);sockets=tls.getSocketFactory();}
 JSONObject send(String path,JSONObject data)throws Exception{return send(path,data,3000);}
 JSONObject send(String path,JSONObject data,int timeout)throws Exception{
  HttpsURLConnection c=(HttpsURLConnection)new URL("https://"+Household.MAC_HOST+":"+Household.RELAY_PORT+path).openConnection(Proxy.NO_PROXY);c.setSSLSocketFactory(sockets);c.setInstanceFollowRedirects(false);c.setConnectTimeout(3000);c.setReadTimeout(timeout);
  try{c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");byte[]body=data.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(body.length);try(OutputStream out=c.getOutputStream()){out.write(body);}if(c.getResponseCode()!=200)throw new IOException("Support unavailable");ByteArrayOutputStream result=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[]b=new byte[1024];int n;while((n=in.read(b))!=-1){if(result.size()+n>8192)throw new IOException("Oversize response");result.write(b,0,n);}}return new JSONObject(new String(result.toByteArray(),StandardCharsets.UTF_8));}finally{c.disconnect();}
 }
}
