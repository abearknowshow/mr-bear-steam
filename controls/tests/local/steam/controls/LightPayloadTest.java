package local.steam.controls;
public final class LightPayloadTest{
 static int count;static void check(boolean b){count++;if(!b)throw new AssertionError();}
 public static void main(String[]args){
  String own="su-02:00:00:00:00:02-dev";
  check(LightPayload.ownThing(own));check(!LightPayload.ownThing("su-02:00:00:00:00:03-dev"));check(!LightPayload.ownThing("su-02:00:00:00:00:02-dev/other"));
  String on=LightPayload.build(own,true,"#ffff00",100,false);check(on.contains("\"data\":32"));check(on.contains("\"type\":1"));check(on.contains("#FFFF00"));check(!on.contains("steam"));
  String disco=LightPayload.build(own,true,"#ffffff",60,true);check(disco.contains("\"type\":2"));check(disco.contains("\"data\":19"));
  String off=LightPayload.build(own,false,"#ffffff",100,false);check(off.contains("\"type\":0"));check(!off.contains("lightBright"));check(!off.contains("color"));
  check(LightPayload.build(own,true,"#ffffff",25,false).contains("\"data\":8"));
  try{LightPayload.build(own,true,"\"steam\"",100,false);throw new AssertionError();}catch(IllegalArgumentException ok){count++;}
  try{LightPayload.build(own,true,"#ffffff",101,false);throw new AssertionError();}catch(IllegalArgumentException ok){count++;}
  try{LightPayload.build("someone-else",true,"#ffffff",50,false);throw new AssertionError();}catch(IllegalArgumentException ok){count++;}
  System.out.println("Lighting payload checks passed: "+count);
 }
}
