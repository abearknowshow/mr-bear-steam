package local.steam.controls;

import com.google.gson.*;

/** Bounded structural counts only; never retains payloads, identifiers or credentials. */
public final class ReadMetrics {
 public static final class Snapshot {
  public final int messages,reported,lights,accepted;public final String kind;
  private Snapshot(int m,int r,int l,int a,String k){messages=m;reported=r;lights=l;accepted=a;kind=k;}
 }
 private int messages,reported,lights,accepted;private String kind="none";
 private static int next(int n){return Math.min(1000000,n+1);}
 public synchronized void observe(String json){
  messages=next(messages);kind="invalid";
  if(json==null||json.length()>131072)return;
  try{
   JsonObject state=JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("state");
   kind="other";if(state==null)return;
   if(!state.has("reported")){if(state.has("desired"))kind="desired-only";return;}
   JsonObject report=state.getAsJsonObject("reported");if(report==null)return;
   reported=next(reported);kind="reported";
   JsonObject devices=report.getAsJsonObject("devices");
   if(devices!=null&&devices.has("deviceLightStatus")&&devices.has("deviceChromaConnected")){
    lights=next(lights);kind="light-fields";
   }
  }catch(RuntimeException e){kind="invalid";}
 }
 public synchronized void accepted(){accepted=next(accepted);kind="accepted-report";}
 public synchronized Snapshot snapshot(){return new Snapshot(messages,reported,lights,accepted,kind);}
}
