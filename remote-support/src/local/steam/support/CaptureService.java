package local.steam.support;
import android.app.*;import android.content.Intent;import android.content.pm.ServiceInfo;import android.media.projection.*;import android.os.IBinder;

/** Foreground shell Android 10 requires for MediaProjection. Holds no state; the support session owns the projection. */
public final class CaptureService extends Service {
 static final String STOP="local.steam.support.STOP";
 @Override public int onStartCommand(Intent i,int flags,int id){
  if(i==null||STOP.equals(i.getAction())){SupportService h=SupportService.host;if(h!=null&&i!=null)h.end();stopSelf();return START_NOT_STICKY;}
  NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("support","Remote support",NotificationManager.IMPORTANCE_LOW));
  PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,CaptureService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
  Notification n=new Notification.Builder(this,"support").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Bear remote support is viewing the screen").setContentText("Tap Stop support to end it now.").setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop support",stop).build()).build();
  startForeground(1,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
  MediaProjection p=null;try{p=getSystemService(MediaProjectionManager.class).getMediaProjection(i.getIntExtra("code",0),i.getParcelableExtra("data"));}catch(RuntimeException ignored){}
  SupportService h=SupportService.host;
  if(p==null||h==null||!h.attachProjection(p)){if(p!=null)p.stop();stopForeground(true);stopSelf();}
  return START_NOT_STICKY;
 }
 @Override public IBinder onBind(Intent i){return null;}
}
