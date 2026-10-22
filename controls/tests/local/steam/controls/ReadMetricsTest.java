package local.steam.controls;
public final class ReadMetricsTest {
 static int checks;
 static void check(boolean v){checks++;if(!v)throw new AssertionError();}
 public static void main(String[]args){
  ReadMetrics metrics=new ReadMetrics();check(metrics.snapshot().messages==0);
  metrics.observe("{\"state\":{\"desired\":{\"light\":{\"type\":1}}}}");
  check(metrics.snapshot().messages==1);check(metrics.snapshot().reported==0);check(metrics.snapshot().kind.equals("desired-only"));
  metrics.observe("{\"state\":{\"reported\":{\"devices\":{\"deviceLightStatus\":false,\"deviceChromaConnected\":true}}}}");
  check(metrics.snapshot().reported==1);check(metrics.snapshot().lights==1);check(metrics.snapshot().accepted==0);
  check(metrics.snapshot().kind.equals("light-fields"));metrics.accepted();check(metrics.snapshot().accepted==1);
  check(metrics.snapshot().kind.equals("accepted-report"));
  metrics.observe("secret-text-is-never-returned");check(metrics.snapshot().kind.equals("invalid"));
  check(metrics.snapshot().messages==3);check(metrics.snapshot().reported==1);
  metrics.observe("{\"state\":{\"reported\":{\"unrelated\":true}}}");check(metrics.snapshot().reported==2);check(metrics.snapshot().lights==1);
  System.out.println("Bounded reader diagnostic checks passed: "+checks);
 }
}
