package local.steam.controls;
public final class LightReportTest {
 static int checks;
 static void check(boolean value){checks++;if(!value)throw new AssertionError();}
 static String doc(String on,String connected,long changed){
  return "{\"timestamp\":1000,\"state\":{\"desired\":{\"light\":{\"type\":0}},\"reported\":{\"devices\":{\"deviceLightStatus\":"+on+",\"deviceChromaConnected\":"+connected+"}}},\"metadata\":{\"reported\":{\"devices\":{\"deviceLightStatus\":{\"timestamp\":"+changed+"},\"deviceChromaConnected\":{\"timestamp\":"+changed+"}}}}}";
 }
 static void invalid(String json){try{LightReport.parse(json,1000000);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}}
 public static void main(String[]args){
  LightReport r=LightReport.parse(doc("true","true",999),1000000);
  check(r.on);check(r.usable(1001000));check(!r.usable(999999));check(!r.usable(1120000));
  check(r.confirms(true,1000000,1001000));check(!r.confirms(false,1000000,1001000));
  check(!r.confirms(true,1010000,1011000)); // Old matching value cannot acknowledge a later command.
  check(!LightReport.parse(doc("false","true",800),1000000).usable(1000000));
  check(!LightReport.parse(doc("false","true",1000),1000000).on);
  invalid(doc("true","false",1000));invalid(doc("\"true\"","true",1000));
  invalid(doc("true","\"true\"",1000));invalid(doc("true","true",1100));
  invalid("{\"state\":{\"desired\":{\"light\":{\"type\":1}}}}");
  invalid(doc("true","true",1000).replace("\"timestamp\":1000","\"timestamp\":1000.1"));
  System.out.println("Chroma report checks passed: "+checks);
 }
}
