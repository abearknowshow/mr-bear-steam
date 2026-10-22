package local.steam.fixture;
import android.app.Activity;import android.os.Bundle;import android.view.*;import android.widget.*;
public final class SupportVendor extends Activity {public void onCreate(Bundle b){super.onCreate(b);LinearLayout l=new LinearLayout(this);l.setOrientation(1);TextView status=new TextView(this);status.setText("Physical fixture: OFF");l.addView(status);for(String label:new String[]{"LIGHT ON","LIGHT OFF"}){Button v=new Button(this);v.setText(label);v.setOnClickListener(x->status.setText(label.endsWith("OFF")?"Physical fixture: OFF":"Physical fixture: ON"));l.addView(v);}
 l.getChildAt(1).setOnLongClickListener(x->{status.setText("Physical fixture: HELD");return true;});
 EditText note=new EditText(this);note.setText("Editable note");l.addView(note);EditText secret=new EditText(this);secret.setInputType(129);secret.setText("test-secret");l.addView(secret);
 Button hide=new Button(this);hide.setText("HIDE SECRET");hide.setOnClickListener(x->secret.setVisibility(View.GONE));l.addView(hide);
 // A drag of more than 150 px left to right counts as a swipe.
 TextView pad=new TextView(this);pad.setText("Swipe pad");pad.setMinHeight(80);pad.setBackgroundColor(0xff3366cc);float[] down=new float[1];pad.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_DOWN)down[0]=e.getX();if(e.getAction()==MotionEvent.ACTION_UP&&e.getX()-down[0]>150)status.setText("Physical fixture: SWIPED");return true;});l.addView(pad);
 setContentView(l);}}
