package local.steam.controls;
import java.util.Locale;
/** Lighting-only shadow update; never accepts raw JSON, topic or a steam command. */
public final class LightPayload {
 public static boolean ownThing(String thing){return thing!=null&&thing.matches("[A-Za-z0-9:_-]{1,128}")&&thing.replaceAll("[^A-Za-z0-9]","").equalsIgnoreCase(Household.LIGHT_THING.replaceAll("[^A-Za-z0-9]",""));}
 public static String build(String thing,boolean on,String color,int percent,boolean disco){
  if(!ownThing(thing))throw new IllegalArgumentException("The cloud device does not match this panel.");
  if(color==null||!color.matches("#[0-9a-fA-F]{6}")||percent<1||percent>100)throw new IllegalArgumentException("Invalid light setting.");
  int level=Math.max(1,Math.min(32,Math.round(percent*32f/100)));
  String desired=on?"\"lightBright\":{\"data\":"+level+"},\"light\":{\"type\":"+(disco?2:1)+",\"color\":\""+color.toUpperCase(Locale.US)+"\"}":"\"light\":{\"type\":0}";
  return "{\"state\":{\"desired\":{"+desired+"}},\"clientToken\":\"app-"+thing+"\"}";
 }
}
