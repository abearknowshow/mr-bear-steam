package local.steam.fixture;
import android.app.Instrumentation;import android.os.Bundle;import android.accessibilityservice.AccessibilityServiceInfo;import android.view.accessibility.*;import android.graphics.Rect;import android.text.TextUtils;
/** UIAutomator's default dump omits non-focusable overlays. Inspect all interactive windows. */
public final class DumpWindows extends Instrumentation {
 public void onCreate(Bundle args){super.onCreate(args);start();}
 public void onStart(){Bundle out=new Bundle();try{
  android.app.UiAutomation ui=getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);AccessibilityServiceInfo info=ui.getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);Thread.sleep(300);
  StringBuilder xml=new StringBuilder("<hierarchy>");for(AccessibilityWindowInfo w:ui.getWindows()){AccessibilityNodeInfo n=w.getRoot();if(n!=null)append(xml,n,0);}xml.append("</hierarchy>");out.putString("tree",xml.toString());finish(-1,out);
 }catch(Exception e){out.putString("error",e.toString());finish(0,out);}}
 private void append(StringBuilder s,AccessibilityNodeInfo n,int depth){if(depth>40)return;Rect b=new Rect();n.getBoundsInScreen(b);
  s.append("<node text=\"").append(escape(n.getText())).append("\" content-desc=\"").append(escape(n.getContentDescription())).append("\" bounds=\"[").append(b.left).append(',').append(b.top).append("][").append(b.right).append(',').append(b.bottom).append("]\" enabled=\"").append(n.isEnabled()).append("\" checked=\"").append(n.isChecked()).append("\">");
  for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null)append(s,c,depth+1);}s.append("</node>");
 }
 private String escape(CharSequence v){return TextUtils.htmlEncode(v==null?"":v.toString());}
}
