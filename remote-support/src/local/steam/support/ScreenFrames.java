package local.steam.support;
import android.graphics.*;import android.hardware.display.*;import android.media.*;import android.media.projection.MediaProjection;import android.os.*;import android.util.*;import android.view.WindowManager;import org.json.JSONObject;import java.io.ByteArrayOutputStream;import java.nio.ByteBuffer;import java.util.List;

/** Session-only screen images. SupportService decides blanking and masking before any pixel is encoded. */
final class ScreenFrames {
 static final class Plan{final String hash,blank;final List<Rect> masks;Plan(String h,String b,List<Rect> m){hash=h;blank=b;masks=m;}}
 private final SupportService service;private final MediaProjection projection;private final HandlerThread thread=new HandlerThread("support-frames");private Handler handler;
 private ImageReader reader;private VirtualDisplay display;private Image held;private boolean dirty;private long sentAt=-10000;private String sentHash;private int width,height,screenW,screenH;private volatile boolean closed;
 ScreenFrames(SupportService s,MediaProjection p){service=s;projection=p;}
 void start(){
  thread.start();handler=new Handler(thread.getLooper());
  projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){service.framesStopped(ScreenFrames.this);}},handler);
  handler.post(()->{try{open();}catch(RuntimeException e){service.framesStopped(this);}});handler.postDelayed(tick,250);
 }
 private Point real(){Point p=new Point();service.getSystemService(WindowManager.class).getDefaultDisplay().getRealSize(p);return p;}
 private void open(){
  Point size=real();screenW=size.x;screenH=size.y;width=Math.min(960,screenW);height=Math.max(1,Math.round(screenH*(float)width/screenW));
  DisplayMetrics m=new DisplayMetrics();service.getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(m);
  reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3);
  // Keep only the newest image; the virtual display produces one only when the screen changes.
  reader.setOnImageAvailableListener(r->{Image i;try{i=r.acquireLatestImage();}catch(RuntimeException e){return;}if(i==null)return;if(held!=null)held.close();held=i;dirty=true;},handler);
  display=projection.createVirtualDisplay("bear-support",width,height,Math.max(1,m.densityDpi*width/screenW),DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,handler);
 }
 private void release(){if(display!=null)display.release();display=null;if(held!=null)held.close();held=null;if(reader!=null)reader.close();reader=null;}
 private final Runnable tick=new Runnable(){public void run(){if(closed)return;try{step();}catch(Exception ignored){}if(!closed)handler.postDelayed(this,250);}};
 // On change at most every 500 ms, otherwise every 3 s. A UI hash change counts as a change.
 private void step()throws Exception{
  Point size=real();if(size.x!=screenW||size.y!=screenH){release();open();}
  long now=SystemClock.elapsedRealtime();String hash=service.latestHash();
  boolean due=now-sentAt>=3000||now-sentAt>=500&&(dirty||hash!=null&&!hash.equals(sentHash));
  if(!due||!service.framesWanted(this)||service.uploading())return;
  Plan plan=service.framePlan();if(plan==null)return;
  String blank=plan.blank!=null?plan.blank:held==null?"no screen image yet":null;
  JSONObject f=new JSONObject().put("hash",plan.hash).put("capturedAt",System.currentTimeMillis()).put("capturedMono",SystemClock.elapsedRealtime()).put("width",width).put("height",height).put("screenWidth",screenW).put("screenHeight",screenH)
   .put("blank",blank==null?JSONObject.NULL:blank).put("jpeg",blank==null?encode(held,plan.masks):JSONObject.NULL);
  dirty=false;sentAt=now;sentHash=plan.hash;service.upload(this,f);
 }
 private String encode(Image image,List<Rect> masks){
  Image.Plane p=image.getPlanes()[0];ByteBuffer src=p.getBuffer();src.rewind();int ps=p.getPixelStride(),rs=p.getRowStride();
  Bitmap full=Bitmap.createBitmap(rs/ps,height,Bitmap.Config.ARGB_8888);
  if(src.remaining()>=rs*height)full.copyPixelsFromBuffer(src);
  else{ByteBuffer rows=ByteBuffer.allocate(rs*height);rows.put(src);rows.rewind();full.copyPixelsFromBuffer(rows);}  // last row may be unpadded
  Canvas c=new Canvas(full);Paint black=new Paint();black.setColor(Color.BLACK);float sx=width/(float)screenW,sy=height/(float)screenH;
  for(Rect r:masks)c.drawRect((float)Math.floor(r.left*sx),(float)Math.floor(r.top*sy),(float)Math.ceil(r.right*sx),(float)Math.ceil(r.bottom*sy),black);
  Bitmap out=full.getWidth()==width?full:Bitmap.createBitmap(full,0,0,width,height);
  ByteArrayOutputStream jpeg=new ByteArrayOutputStream();out.compress(Bitmap.CompressFormat.JPEG,60,jpeg);if(out!=full)out.recycle();full.recycle();
  return Base64.encodeToString(jpeg.toByteArray(),Base64.NO_WRAP);
 }
 void close(){closed=true;handler.post(()->{release();thread.quitSafely();});projection.stop();}
}
