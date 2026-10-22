package local.steam.controls;
public final class ReadWindowTest {
 static int checks;
 static void check(boolean v){checks++;if(!v)throw new AssertionError();}
 public static void main(String[]args){
  ReadWindow window=new ReadWindow();long first=window.resume();
  check(window.allows(first,100));window.pause();check(!window.allows(first,101));
  long second=window.resume();check(second!=first);check(!window.allows(first,102));
  window.watchOriginal(200);window.pause();check(window.allows(second,200));check(window.observing(200));
  check(window.allows(second,90199));check(!window.allows(second,90200));
  long third=window.resume();check(window.allows(third,90201));check(!window.observing(90201));
  check(!window.allows(second,90201));window.watchOriginal(90202);window.cancelWatch();window.pause();check(!window.allows(third,90203));
  long fourth=window.resume();window.watchOriginal(100000);window.pause();window.close();check(!window.allows(fourth,100001));
  System.out.println("Read-only observation window checks passed: "+checks);
 }
}
