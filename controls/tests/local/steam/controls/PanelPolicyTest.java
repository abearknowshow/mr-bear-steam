package local.steam.controls;

/** 0.21 startup decisions (STARTUP-DESIGN.md, owner decisions 2026-10-22): wake opens, the guard, keep-screen-on, accent. */
public final class PanelPolicyTest {
 static int checks;static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
 public static void main(String[]args){
  // Open on wake ships on; two unrendered automatic opens in a row turn it off.
  check(PanelPolicy.mayAutoOpen(true,0),"setting on, no failures: opens");
  check(PanelPolicy.mayAutoOpen(true,1),"one failed open still opens");
  check(!PanelPolicy.mayAutoOpen(true,2),"two failed opens: no third");
  check(PanelPolicy.shouldDisable(true,2),"two failed opens turn the setting off");
  check(!PanelPolicy.shouldDisable(true,1),"one failed open leaves it on");
  check(!PanelPolicy.mayAutoOpen(false,0),"setting off: never opens");
  check(!PanelPolicy.shouldDisable(false,5),"an off setting is not turned off again");
  // Boot: once, about 45 s after boot; a settings toggle long after boot stays idle.
  check(PanelPolicy.bootDelay(10_000)==35_000,"connected 10 s after boot waits until 45 s");
  check(PanelPolicy.bootDelay(60_000)==0,"connected after 45 s opens at once");
  check(PanelPolicy.bootDelay(PanelPolicy.BOOT_WINDOW_MS)==0,"edge of the boot window still counts as boot");
  check(PanelPolicy.bootDelay(PanelPolicy.BOOT_WINDOW_MS+1)==-1,"later reconnects are not a boot");
  check(PanelPolicy.bootDelay(-5)==-1,"a bad clock never opens");
  // Screen-on broadcasts close together open once.
  check(PanelPolicy.debounced(5000,0),"first wake opens");
  check(!PanelPolicy.debounced(5000,4000),"a second broadcast within 3 s is ignored");
  check(PanelPolicy.debounced(7000,4000),"3 s later opens again");
  // The guard: only after an automatic open, 1.5 s, never for Stop.
  long t=100_000;long until=PanelPolicy.guardUntil(true,t);
  check(until==t+1500,"automatic open guards for 1.5 s");
  check(PanelPolicy.guardUntil(false,t)==0,"a manual open has no guard");
  for(String action:new String[]{"start","time","temperature","lights"}){
   check(!PanelPolicy.commandAllowed(action,t+10,until),action+" refused during the guard");
   check(PanelPolicy.commandAllowed(action,t+1500,until),action+" allowed once the guard ends");
  }
  check(PanelPolicy.commandAllowed("stop",t,until),"Stop is never guarded");
  check(PanelPolicy.commandAllowed("start",t,0),"no guard: Start allowed");
  // Keep-screen-on: steam running always; steam off only within five minutes of a touch.
  check(PanelPolicy.keepScreenOn(true,t,0),"steam running keeps the screen on with no touch");
  check(PanelPolicy.keepScreenOn(true,t+60*60_000L,t),"steam running keeps it on an hour after the last touch");
  check(PanelPolicy.keepScreenOn(false,t+299_999,t),"just under five minutes after a touch stays on");
  check(!PanelPolicy.keepScreenOn(false,t+300_000,t),"five minutes idle with steam off lets it sleep");
  check(!PanelPolicy.keepScreenOn(false,t,0),"steam off and never touched may sleep");
  check(!PanelPolicy.keepScreenOn(false,t,t+5),"a touch time in the future is ignored");
  // Accent: only the five offered values, stored in one canonical form.
  check("#FFFFFF".equals(PanelPolicy.accent("#ffffff")),"white in any case");
  check("#FF9900".equals(PanelPolicy.accent(" #ff9900 ")),"orange, trimmed");
  check(PanelPolicy.accent("#123456")==null,"other colors refused");
  check(PanelPolicy.accent("red")==null,"names refused");
  check(PanelPolicy.accent("#FFFFFF;background:url(x)")==null,"no CSS through the accent");
  check(PanelPolicy.accent(null)==null,"missing refused");
  check(PanelPolicy.ACCENTS.length==5&&PanelPolicy.DEFAULT_ACCENT.equals(PanelPolicy.ACCENTS[0]),"white is the default and first");
  // 0.22 remembered light look: only the page's own ranges are stored.
  check(PanelPolicy.validLook(214,70,40),"Blue at 40% is kept");
  check(PanelPolicy.validLook(0,0,1)&&PanelPolicy.validLook(359,100,100),"range edges are kept");
  check(!PanelPolicy.validLook(360,70,40)&&!PanelPolicy.validLook(-1,70,40),"hue outside 0-359 refused");
  check(!PanelPolicy.validLook(214,101,40)&&!PanelPolicy.validLook(214,-1,40),"saturation outside 0-100 refused");
  check(!PanelPolicy.validLook(214,70,0)&&!PanelPolicy.validLook(214,70,101),"brightness outside 1-100 refused");
  System.out.println("Panel policy checks passed: "+checks);
 }
}
