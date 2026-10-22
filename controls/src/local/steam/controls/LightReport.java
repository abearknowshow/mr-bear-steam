package local.steam.controls;

import com.google.gson.*;

/** Chroma's reported state only. Desired state and Steamlinx bits are not evidence. */
public final class LightReport {
 public final boolean on;
 public final long at,freshUntil,changedAt;
 private LightReport(boolean on,long received,long changed,long server){
  this.on=on;at=received;changedAt=changed;
  freshUntil=received+120000-Math.max(0,server-changed)*1000;
 }
 public boolean usable(long now){return now>=at&&now<freshUntil;}
 public boolean confirms(boolean desired,long commandAt,long now){return usable(now)&&on==desired&&changedAt>=commandAt/1000-2;}
 public static LightReport parse(String json,long received){
  if(json==null||json.length()>131072)throw new IllegalArgumentException("Invalid light report");
  try{
   JsonObject root=JsonParser.parseString(json).getAsJsonObject();
   JsonObject devices=root.getAsJsonObject("state").getAsJsonObject("reported").getAsJsonObject("devices");
   JsonPrimitive connected=devices.getAsJsonPrimitive("deviceChromaConnected");
   JsonPrimitive state=devices.getAsJsonPrimitive("deviceLightStatus");
   if(!connected.isBoolean()||!state.isBoolean()||!connected.getAsBoolean())throw new IllegalArgumentException();
   long server=integer(root.get("timestamp"));
   JsonObject meta=root.getAsJsonObject("metadata").getAsJsonObject("reported").getAsJsonObject("devices");
   long changed=integer(meta.getAsJsonObject("deviceLightStatus").get("timestamp"));
   long linked=integer(meta.getAsJsonObject("deviceChromaConnected").get("timestamp"));
   if(server<=0||changed<=0||linked<=0||changed>server+5||linked>server+5)throw new IllegalArgumentException();
   return new LightReport(state.getAsBoolean(),received,Math.min(changed,linked),server);
  }catch(RuntimeException e){throw new IllegalArgumentException("Chroma reported state unavailable");}
 }
 private static long integer(JsonElement value){
  if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException();
  return value.getAsBigDecimal().longValueExact();
 }
}
