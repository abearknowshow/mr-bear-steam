package local.steam.support;
import android.app.Activity;import android.content.Intent;import android.media.projection.MediaProjectionManager;import android.os.Bundle;import android.provider.Settings;import android.widget.*;
public final class MainActivity extends Activity {
 public void onCreate(Bundle b){super.onCreate(b);}
 public void onResume(){super.onResume();LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(32,20,32,20);
  TextView title=new TextView(this);title.setText("Bear Remote Support");title.setTextSize(26);root.addView(title);
  TextView info=new TextView(this);info.setText("Let your Mac see this screen and operate Bear, Mr. Steam, Chrome and Settings for up to 2 hours. Android will ask to start capturing the screen: tap Start now. Password fields, installers, permission screens and Accessibility or security settings are never sent and cannot be operated remotely. No microphone, camera or private app files.\n\nTap the persistent Stop support button, or lock the screen, to end access.\n\nThis is a separate support tool; your working Bear and original app are unchanged.");info.setTextSize(18);root.addView(info);
  Button b=new Button(this);b.setAllCaps(false);b.setText(SupportService.host==null?"Open Accessibility settings":"Start support · 2 hours");
  b.setOnClickListener(v->{if(SupportService.host==null)startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));else startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),1);});root.addView(b);
  if(SupportService.host!=null){Button plain=new Button(this);plain.setAllCaps(false);plain.setText("Start without screen images");plain.setOnClickListener(v->{SupportService h=SupportService.host;if(h!=null){h.begin();finish();}});root.addView(plain);}
  Button close=new Button(this);close.setText("Close");close.setOnClickListener(v->finish());root.addView(close);setContentView(root);
 }
 // Cancel on Android's capture prompt starts nothing. The consent is single-use and never stored.
 public void onActivityResult(int request,int code,Intent data){
  SupportService h=SupportService.host;if(request!=1)return;
  if(h==null||code!=RESULT_OK||data==null){Toast.makeText(this,"Support not started",Toast.LENGTH_SHORT).show();return;}
  h.begin();startForegroundService(new Intent(this,CaptureService.class).putExtra("code",code).putExtra("data",data));finish();
 }
}
