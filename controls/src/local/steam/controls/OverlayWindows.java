package local.steam.controls;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.PixelFormat;
import android.view.*;
import android.widget.Button;
import java.util.*;

/** Presentation only: no Activity launches, equipment commands or state inference. */
public final class OverlayWindows {
 private final WindowManager manager;private final View dashboard;private final Button tab;
 private final List<AlertDialog> dialogs=new ArrayList<>();private boolean showing,tabVisible,keepScreenOn=true;
 public OverlayWindows(Context context,View dashboard,Runnable show,Runnable close){
  this.dashboard=dashboard;manager=(WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
  tab=new Button(context);tab.setText("Bear Steam");tab.setAllCaps(false);tab.setContentDescription("Return to Bear Steam");
  tab.setOnClickListener(v->show.run());tab.setOnLongClickListener(v->{close.run();return true;});
 }
 private WindowManager.LayoutParams params(boolean full){
  // 0.21: the dashboard keeps the screen on only while DashboardService says so (PanelPolicy.keepScreenOn).
  int flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|(full&&keepScreenOn?WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON:0);
  WindowManager.LayoutParams p=new WindowManager.LayoutParams(full?-1:-2,full?-1:-2,
   WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,flags,PixelFormat.OPAQUE);
  p.gravity=full?Gravity.TOP|Gravity.START:Gravity.BOTTOM|Gravity.END;
  p.setTitle(full?"Bear Steam dashboard":"Bear Steam return");
  if(!full){p.x=16;p.y=16;}
  return p;
 }
 public void show(){
  if(showing)return;
  // The opaque, touchable full-size window consumes dashboard taps, including blank areas.
  dashboard.setClickable(true);manager.addView(dashboard,params(true));showing=true;
  if(tabVisible){manager.removeView(tab);tabVisible=false;}
 }
 /** Changes the dashboard window's keep-screen-on flag in place. Returns whether it changed. */
 public boolean keepScreenOn(boolean on){
  if(on==keepScreenOn)return false;
  keepScreenOn=on;
  if(showing){try{manager.updateViewLayout(dashboard,params(true));}catch(IllegalArgumentException ignored){}}
  return true;
 }
 public void hide(){
  dismissDialogs();
  if(!tabVisible){manager.addView(tab,params(false));tabVisible=true;}
  if(showing){manager.removeView(dashboard);showing=false;}
 }
 public void dialog(AlertDialog dialog){
  dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
  // Dialogs stay touch-modal so taps outside cannot reach dashboard actions.
  dialog.setCanceledOnTouchOutside(false);dialogs.add(dialog);
  dialog.setOnDismissListener(d->dialogs.remove(dialog));dialog.show();
 }
 private void dismissDialogs(){for(AlertDialog d:new ArrayList<>(dialogs)){try{d.dismiss();}catch(IllegalArgumentException ignored){}}dialogs.clear();}
 private void remove(View view){try{manager.removeView(view);}catch(IllegalArgumentException ignored){/* System may remove windows before unbinding. */}}
 public void close(){
  dismissDialogs();
  if(showing){remove(dashboard);showing=false;}
  if(tabVisible){remove(tab);tabVisible=false;}
 }
}
