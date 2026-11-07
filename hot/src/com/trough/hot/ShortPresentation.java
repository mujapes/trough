package com.trough.hot;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.media.MediaPlayer;
import android.widget.VideoView;
import android.widget.FrameLayout;
import java.io.File;
import java.util.Map;
final class ShortPresentation {
 interface Listener { void finished(Map<String,Object> result); }
 static void show(Activity activity,final Listener listener) {
  if(activity==null || activity.isFinishing()) { listener.finished(Json.map("ok",false,"error","No foreground window"));return; }
  final Activity window=activity;
  final File media=new File(activity.getFilesDir(),"home/.trough/media");
  final File requestedOutput=new File(media,"requested-video.mp4");
  if(!requestedOutput.isFile() && new File(media,"my-song-video.mp4").isFile() && new File(media,"my-song-audio.m4a").isFile()) {
   new Thread(new Runnable(){public void run(){try{
    MediaJoin.join(new File(media,"my-song-video.mp4"),new File(media,"my-song-audio.m4a"),requestedOutput);
    window.runOnUiThread(new Runnable(){public void run(){if(!window.isFinishing())DogshitPlayer.show(window,requestedOutput,listener);else listener.finished(Json.map("ok",false,"error","Window closed"));}});
   }catch(final Exception error){window.runOnUiThread(new Runnable(){public void run(){listener.finished(Json.map("ok",false,"error",error.toString()));}});}}},"Trough media join").start();return;
  }
  File file=new File(activity.getFilesDir(),"home/.trough/media/gematria-57.mp4");
  if(!file.isFile() && !new File(activity.getFilesDir(),"home/.trough/media/requested-video.mp4").isFile()) { listener.finished(Json.map("ok",false,"error","Short unavailable"));return; }
  final File requested=new File(activity.getFilesDir(),"home/.trough/media/requested-video.mp4");
  final File policyFile=new File(activity.getFilesDir(),"home/.trough/media/playback-policy.json");
  Map<String,Object> loaded=null;
  try { if(policyFile.isFile()) loaded=Json.obj(Json.parse(new String(Payloads.readSmall(policyFile),"UTF-8"))); }
  catch(Exception error) { listener.finished(Json.map("ok",false,"error",error.toString()));return; }
  final Map<String,Object> policy=loaded;
  final boolean requestedVideo=requested.isFile() && policy!=null;
  final float gain=requestedVideo && policy.get("gain") instanceof Number
      ? Math.max(0,Math.min(1,((Number)policy.get("gain")).floatValue())):0;
  if(requestedVideo && !Boolean.TRUE.equals(policy.get("play"))) {
      listener.finished(Json.map("ok",false,"error","Dogshit playback policy is paused"));return;
  }
  if(requestedVideo) { DogshitPlayer.show(activity,requested,listener);return; }
  final VideoView video=new VideoView(activity);
  FrameLayout frame=new FrameLayout(activity);
  int height=(int)(activity.getResources().getDisplayMetrics().heightPixels*.65f);
  frame.addView(video,new FrameLayout.LayoutParams(-1,height,android.view.Gravity.CENTER));
  final AlertDialog dialog=new AlertDialog.Builder(activity).setView(frame).setPositiveButton("Back to chat",null).create();
  final boolean[] reported={false};
  video.setOnPreparedListener(new MediaPlayer.OnPreparedListener(){public void onPrepared(MediaPlayer player){
   player.setVolume(gain,gain);player.setLooping(!requestedVideo);video.start();
   if(!reported[0]) {reported[0]=true;listener.finished(Json.map("ok",true,"visible",dialog.isShowing(),"playing",video.isPlaying(),"duration_ms",video.getDuration(),"requested_video",requestedVideo));}
  }});
  video.setOnErrorListener(new MediaPlayer.OnErrorListener(){public boolean onError(MediaPlayer player,int what,int extra){
   if(!reported[0]) {reported[0]=true;listener.finished(Json.map("ok",false,"error","Video playback failed","what",what,"extra",extra));}dialog.dismiss();return true;
  }});
  dialog.setOnDismissListener(new DialogInterface.OnDismissListener(){public void onDismiss(DialogInterface ignored){video.stopPlayback();
   if(!reported[0]) {reported[0]=true;listener.finished(Json.map("ok",false,"error","Dismissed before playback"));}
  }});
  dialog.show();video.setVideoPath(file.getAbsolutePath());
 }
}
