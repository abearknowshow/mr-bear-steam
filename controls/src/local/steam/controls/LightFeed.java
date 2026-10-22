package local.steam.controls;

import com.google.gson.*;

/** Ordered device telemetry. Commands, delivery receipts and preferences cannot enter this feed. */
public final class LightFeed {
 private long version=-1;
 private LightReport report;
 public synchronized LightReport current(){return report;}
 public synchronized void clear(){report=null;version=-1;}
 public synchronized boolean accept(String json,long now){
  if(json==null||json.length()>131072)return false;
  try{
   JsonObject root=JsonParser.parseString(json).getAsJsonObject();
   JsonObject state=root.getAsJsonObject("state");
   if(state==null||!state.has("reported"))return false;
   JsonObject devices=state.getAsJsonObject("reported").getAsJsonObject("devices");
   if(devices==null||!devices.has("deviceLightStatus")||!devices.has("deviceChromaConnected"))return false;
   JsonPrimitive v=root.getAsJsonPrimitive("version");
   if(!v.isNumber())return false;
   long next=v.getAsBigDecimal().longValueExact();
   if(next<=version||next<1)return false;
   JsonPrimitive connected=devices.getAsJsonPrimitive("deviceChromaConnected");
   if(!connected.isBoolean())return false;
   LightReport parsed=connected.getAsBoolean()?LightReport.parse(json,now):null;
   version=next;report=parsed;return true;
  }catch(RuntimeException e){return false;}
 }
}
