package com.trough.hot;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.*;
import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
/** Explicit visible, bounded observation. No diagnosis, raw audio storage or hidden upload. */
final class NativeCarePanel extends LinearLayout implements TextureView.SurfaceTextureListener,SensorEventListener {
 static final int TAG=0x7f0d74e3;
 final Activity activity;final TextureView preview;final TextView status;
 final Handler main=new Handler(Looper.getMainLooper());final HandlerThread cameraThread=new HandlerThread("Trough front camera");
 final ExecutorService analysis=Executors.newSingleThreadExecutor();
 final Map<String,Object> values=new LinkedHashMap<String,Object>();
 final SensorManager sensors;final long started=SystemClock.elapsedRealtime();
 CameraDevice camera;CameraCaptureSession session;Surface surface;Handler cameraHandler;AudioRecord recorder;
 volatile boolean closed;boolean opening,analysing,permissionRequested,cameraFailed;int frames;volatile float rms;volatile long audioSamples;
 static void pending(Activity a,FrameLayout root){
  File request=new File(a.getFilesDir(),"home/.trough/care-live-open");
  if(!request.isFile()||root.getTag(TAG)!=null)return;
  if(!request.delete())return;
  NativeCarePanel panel=new NativeCarePanel(a);root.setTag(TAG,panel);
  FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(Math.min(root.getWidth(),panel.dp(280)),panel.dp(310),Gravity.TOP|Gravity.RIGHT);lp.topMargin=panel.dp(8);root.addView(panel,lp);panel.setElevation(panel.dp(80));
 }
 NativeCarePanel(Activity a){
  super(a);activity=a;setOrientation(VERTICAL);setPadding(dp(8),dp(8),dp(8),dp(8));setBackgroundColor(0xff12252c);
  TextView title=new TextView(a);title.setText("Local camera + sensors · 60s trial");title.setTextColor(0xffe3f1e9);addView(title);
  preview=new TextureView(a);addView(preview,new LayoutParams(-1,dp(160)));preview.setSurfaceTextureListener(this);
  status=new TextView(a);status.setTextColor(0xffb6d9d0);status.setTextSize(11);status.setText("Camera/mic permission check…\nObservations only · no medical diagnosis");addView(status,new LayoutParams(-1,0,1));
  Button stop=new Button(a);stop.setText("Stop camera + mic");stop.setOnClickListener(new OnClickListener(){public void onClick(View v){close("user_stop");}});addView(stop,new LayoutParams(-1,dp(44)));
  sensors=(SensorManager)a.getSystemService(Context.SENSOR_SERVICE);
  cameraThread.start();cameraHandler=new Handler(cameraThread.getLooper());main.post(poll);
 }
 int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
 boolean granted(String name){return activity.checkSelfPermission(name)==PackageManager.PERMISSION_GRANTED;}
 final Runnable poll=new Runnable(){public void run(){
  if(closed)return;
  if(SystemClock.elapsedRealtime()-started>60000){close("trial_complete");return;}
  boolean cam=granted(android.Manifest.permission.CAMERA),mic=granted(android.Manifest.permission.RECORD_AUDIO);
  if(!permissionRequested&&(!cam||!mic)){
   permissionRequested=true;ArrayList<String> missing=new ArrayList<String>();if(!cam)missing.add(android.Manifest.permission.CAMERA);if(!mic)missing.add(android.Manifest.permission.RECORD_AUDIO);
   activity.requestPermissions(missing.toArray(new String[missing.size()]),5197);
  }
  if(cam&&preview.isAvailable()&&camera==null&&!opening&&!cameraFailed)openCamera();
  if(mic&&recorder==null&&audioSamples==0)startAudio();
  synchronized(values){values.put("camera_permission",cam);values.put("microphone_permission",mic);values.put("audio_rms",rms);values.put("audio_samples",audioSamples);}
  if(cam&&camera!=null&&preview.isAvailable()&&!analysing){
   final Bitmap image=preview.getBitmap(320,240);
   if(image!=null){analysing=true;try{analysis.execute(new Runnable(){public void run(){analyse(image);}});}catch(RejectedExecutionException stopped){image.recycle();analysing=false;}}
  }
  status.setText((cam?"Front camera":"Camera permission pending")+" · "+(mic?"Mic level only":"Mic unavailable")+"\n"+frames+" analysed frames · latest image only\nFace landmarks ≠ gaze or diagnosis");
  write(camera!=null||audioSamples>0?"observing":"awaiting_access");main.postDelayed(this,1000);
 }};
 void openCamera(){
  opening=true;
  try{
   CameraManager manager=(CameraManager)activity.getSystemService(Context.CAMERA_SERVICE);String front=null;
   for(String id:manager.getCameraIdList())if(Integer.valueOf(CameraCharacteristics.LENS_FACING_FRONT).equals(manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING))){front=id;break;}
   if(front==null)throw new IllegalStateException("No front camera");
   manager.openCamera(front,new CameraDevice.StateCallback(){
    public void onOpened(CameraDevice device){opening=false;if(closed){device.close();return;}camera=device;try{
     SurfaceTexture texture=preview.getSurfaceTexture();if(texture==null){close("surface_missing");return;}texture.setDefaultBufferSize(640,480);surface=new Surface(texture);
     final CaptureRequest.Builder request=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);request.addTarget(surface);
     device.createCaptureSession(Collections.singletonList(surface),new CameraCaptureSession.StateCallback(){public void onConfigured(CameraCaptureSession s){if(closed){s.close();return;}session=s;try{s.setRepeatingRequest(request.build(),null,cameraHandler);}catch(Exception e){close("capture_failed");}}public void onConfigureFailed(CameraCaptureSession s){close("configuration_failed");}},cameraHandler);
    }catch(Exception e){close("camera_setup_failed");}}
    public void onDisconnected(CameraDevice d){d.close();close("camera_disconnected");}
    public void onError(CameraDevice d,int error){d.close();close("camera_error_"+error);}
   },cameraHandler);
   if(sensors!=null)for(int type:new int[]{Sensor.TYPE_ACCELEROMETER,Sensor.TYPE_GYROSCOPE,Sensor.TYPE_LIGHT}){Sensor sensor=sensors.getDefaultSensor(type);if(sensor!=null)sensors.registerListener(NativeCarePanel.this,sensor,200000,cameraHandler);}
  }catch(Exception e){opening=false;cameraFailed=true;synchronized(values){values.put("camera_error",e.getClass().getSimpleName());}write("camera_unavailable");}
 }
 void startAudio(){
  try{
   int buffer=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);if(buffer<=0)throw new IllegalStateException("Audio buffer");
   final AudioRecord input=new AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(6400,buffer*2));
   if(input.getState()!=AudioRecord.STATE_INITIALIZED){input.release();throw new IllegalStateException("Audio init");}recorder=input;input.startRecording();
   new Thread(new Runnable(){public void run(){short[] samples=new short[1600];try{while(!closed){int n=input.read(samples,0,samples.length);if(n<=0)break;double sum=0;for(int i=0;i<n;i++){double f=samples[i]/32768.0;sum+=f*f;}rms=(float)Math.sqrt(sum/n);audioSamples+=n;}}catch(Exception ignored){}finally{try{input.stop();}catch(Exception ignored){}input.release();}}},"Trough local mic level").start();
  }catch(Exception error){audioSamples=-1;synchronized(values){values.put("audio_error",error.getClass().getSimpleName());}}
 }
 void analyse(Bitmap image){Bitmap rgb=null;try{
  File frame=new File(activity.getFilesDir(),"home/.trough/care-frame.jpg"),temp=new File(frame.getPath()+".tmp");
  try(java.io.FileOutputStream out=new java.io.FileOutputStream(temp)){if(!image.compress(Bitmap.CompressFormat.JPEG,75,out))throw new java.io.IOException("Frame encoding");}
  if(!temp.renameTo(frame)){temp.delete();throw new java.io.IOException("Frame replacement");}
  synchronized(values){values.put("frame_time_ms",System.currentTimeMillis());}

  rgb=image.copy(Bitmap.Config.RGB_565,false);FaceDetector.Face[] faces=new FaceDetector.Face[1];int count=new FaceDetector(rgb.getWidth(),rgb.getHeight(),1).findFaces(rgb,faces);
  double light=0;for(int y=0;y<image.getHeight();y+=8)for(int x=0;x<image.getWidth();x+=8){int c=image.getPixel(x,y);light+=(((c>>16)&255)+((c>>8)&255)+(c&255))/3.0;}
  synchronized(values){frames++;values.put("frames",frames);values.put("face_count",count);values.put("face_detector","Android FaceDetector; not emotion or gaze");values.put("brightness_sample_sum",light);if(count>0){PointF midpoint=new PointF();faces[0].getMidPoint(midpoint);values.put("eye_midpoint",Arrays.asList(midpoint.x/rgb.getWidth(),midpoint.y/rgb.getHeight()));values.put("face_confidence",faces[0].confidence());values.put("eye_distance_px",faces[0].eyesDistance());}else{values.remove("eye_midpoint");values.remove("face_confidence");values.remove("eye_distance_px");}}
 }catch(Exception e){synchronized(values){values.put("analysis_error",e.getClass().getSimpleName());}}finally{image.recycle();if(rgb!=null)rgb.recycle();analysing=false;}}
 public void onSensorChanged(SensorEvent event){synchronized(values){ArrayList<Float> v=new ArrayList<Float>();for(int i=0;i<Math.min(3,event.values.length);i++)v.add(event.values[i]);values.put("sensor_"+event.sensor.getType(),v);}}
 public void onAccuracyChanged(Sensor s,int a){}
 void write(String state){final Map<String,Object> snapshot; synchronized(values){snapshot=new LinkedHashMap<String,Object>(values);}snapshot.put("state",state);snapshot.put("time_ms",System.currentTimeMillis());snapshot.put("elapsed_ms",SystemClock.elapsedRealtime()-started);snapshot.put("raw_audio_saved",false);snapshot.put("video_archive",false);snapshot.put("latest_frame_only",true);snapshot.put("capture_uploads",false);snapshot.put("medical_diagnosis",false);
  try{analysis.execute(new Runnable(){public void run(){try{Payloads.writeSmall(new File(activity.getFilesDir(),"home/.trough/care-live.json"),Json.write(snapshot));}catch(Exception ignored){}}});}catch(RejectedExecutionException ignored){}
 }
 void close(final String reason){if(Looper.myLooper()!=Looper.getMainLooper()){main.post(new Runnable(){public void run(){close(reason);}});return;}if(closed)return;closed=true;main.removeCallbacks(poll);if(sensors!=null)sensors.unregisterListener(this);if(recorder!=null)try{recorder.stop();}catch(Exception ignored){}if(session!=null)session.close();if(camera!=null)camera.close();if(surface!=null)surface.release();write(reason);analysis.shutdown();cameraThread.quitSafely();status.setText("Stopped · "+reason);main.post(new Runnable(){public void run(){preview.setVisibility(GONE);if(getParent() instanceof ViewGroup){ViewGroup parent=(ViewGroup)getParent();if(parent.getTag(TAG)==NativeCarePanel.this)parent.setTag(TAG,null);parent.removeView(NativeCarePanel.this);}}});}
 protected void onDetachedFromWindow(){close("detached");super.onDetachedFromWindow();}
 protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE&&started>0)close("window_hidden");}
 public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){}
 public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){}
 public boolean onSurfaceTextureDestroyed(SurfaceTexture s){
  // TextureView still owns its texture until this callback returns. Removing
  // our parent here re-enters detach and releases the same texture twice.
  main.post(new Runnable(){public void run(){close("surface_destroyed");}});
  return true;
 }
 public void onSurfaceTextureUpdated(SurfaceTexture s){}
}
