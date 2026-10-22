package local.steam.fixture;
import android.app.*;import android.os.*;import android.content.*;import android.widget.*;
/** Fake original app, emulator only. Its lifecycle counters detect unintended relaunches. */
public final class VendorFixture extends Activity {
 public void onCreate(Bundle b){super.onCreate(b);android.util.Log.i("BearWindowTest","VENDOR_CREATE");
  LinearLayout root=new LinearLayout(this);root.setOrientation(1);
  for(String text:new String[]{"FIXTURE LIGHT ON","FIXTURE LIGHT OFF","UNDERNEATH TOUCH"}){Button button=new Button(this);button.setText(text);button.setOnClickListener(v->{
   android.util.Log.i("BearWindowTest",text);
   if(!text.equals("UNDERNEATH TOUCH"))sendBroadcast(new Intent("bear.test.report").setPackage("local.steam.controls.offlinetest").putExtra("on",text.endsWith("ON")));
  });root.addView(button);}setContentView(root);
 }
 protected void onNewIntent(Intent i){super.onNewIntent(i);android.util.Log.i("BearWindowTest","VENDOR_NEW_INTENT");}
 protected void onResume(){super.onResume();android.util.Log.i("BearWindowTest","VENDOR_RESUME");}
 protected void onPause(){android.util.Log.i("BearWindowTest","VENDOR_PAUSE");super.onPause();}
 protected void onDestroy(){android.util.Log.i("BearWindowTest","VENDOR_DESTROY");super.onDestroy();}
}
