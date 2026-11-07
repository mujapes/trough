package com.trough.hot;
import android.app.Activity;
import android.media.MediaPlayer;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
/** Bounded real search/decoder preflight; never starts audio or changes live playback. */
final class NativeMusicCheck {
 static void run(final Activity activity){new Thread(new Runnable(){public void run(){Map<String,Object> result=Json.map("time_ms",System.currentTimeMillis(),"audio_started",false);MediaPlayer player=null;try{
  Map<String,Object> search=NativeMusicView.runTool(activity,"--flat-playlist","--dump-single-json","ytsearch2:Strawberry Fields Forever Beatles");Object entries=search.get("entries");int count=entries instanceof List?((List<?>)entries).size():0;result.put("search_results",count);
  File source=new File(activity.getFilesDir(),"home/.trough/music-native-probe-source.json");if(source.length()>16384)throw new IOException("Invalid probe source");Map<String,Object> data=Json.obj(Json.parse(new String(java.nio.file.Files.readAllBytes(source.toPath()),"UTF-8")));String url=Json.str(data,"url");if(url==null||!url.startsWith("https://"))throw new IOException("No source");
  final CountDownLatch done=new CountDownLatch(1);final int[] duration={0};final String[] error={""};player=new MediaPlayer();player.setOnPreparedListener(new MediaPlayer.OnPreparedListener(){public void onPrepared(MediaPlayer p){duration[0]=p.getDuration();done.countDown();}});player.setOnErrorListener(new MediaPlayer.OnErrorListener(){public boolean onError(MediaPlayer p,int what,int extra){error[0]=what+":"+extra;done.countDown();return true;}});player.setDataSource(url);player.prepareAsync();if(!done.await(20,TimeUnit.SECONDS))throw new IOException("Decoder prepare timed out");result.put("duration_ms",duration[0]);result.put("decoder_error",error[0]);result.put("passed",count>0&&duration[0]>0&&error[0].isEmpty());
 }catch(Exception failure){result.put("passed",false);result.put("error",failure.toString());}finally{if(player!=null){player.setOnPreparedListener(null);player.setOnErrorListener(null);player.release();}try(FileOutputStream out=new FileOutputStream(new File(activity.getFilesDir(),"home/.trough/music-native-check-result.json"))){out.write(Json.write(result).getBytes("UTF-8"));}catch(Exception ignored){}}}},"trough-music-preflight").start();}
}
