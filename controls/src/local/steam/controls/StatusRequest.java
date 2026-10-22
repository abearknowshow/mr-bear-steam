package local.steam.controls;
/** Vendor status-only request. No caller-supplied payload, topic or equipment fields. */
public final class StatusRequest {
 public static String build(String thing){
  if(!LightPayload.ownThing(thing))throw new IllegalArgumentException("Unknown panel");
  return "{\"state\":{\"desired\":{\"online\":true}},\"clientToken\":\"app-"+thing+"\"}";
 }
}
