package com.trough.hot;
import android.app.Activity;
import android.os.*;
import android.widget.FrameLayout;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
/** Actual production player bridge, isolated from the user's session; never starts audio. */
final class NativeMusicCheck {
 static void run(final Activity activity){new Thread(new Runnable(){public void run(){final Map<String,Object> result=Json.map("time_ms",System.currentTimeMillis(),"audio_started",false,"production_bridge",true);final FrameLayout[] test={null};try{
  Map<String,Object> search=NativeMusicView.runTool(activity,"--flat-playlist","--dump-single-json","ytsearch2:Strawberry Fields Forever Beatles");List<Object> entries=Json.arr(search.get("entries"));if(entries==null||entries.isEmpty())throw new IOException("No search results");result.put("search_results",entries.size());Map<String,Object> first=Json.obj(entries.get(0));String id=Json.str(first,"id");if(id==null||!id.matches("[A-Za-z0-9_-]{11}"))throw new IOException("Invalid result");
  Map<String,Object> source=NativeMusicView.runTool(activity,"--dump-single-json","--skip-download","-f","bestaudio[ext=m4a]/bestaudio","https://www.youtube.com/watch?v="+id);final String url=Json.str(source,"url");if(url==null||!url.startsWith("https://"))throw new IOException("No stream");
  final CountDownLatch done=new CountDownLatch(1);final Handler main=new Handler(Looper.getMainLooper());final long deadline=SystemClock.elapsedRealtime()+20000;
  main.post(new Runnable(){public void run(){test[0]=new FrameLayout(activity);MediaPanel.prepareMusic(activity,test[0],url,"Isolated production player check");main.postDelayed(new Runnable(){public void run(){Map<String,Object> state=MediaPanel.state(test[0]);String error=Json.str(state,"error");if(Boolean.TRUE.equals(state.get("ready"))||error!=null&&!error.isEmpty()||SystemClock.elapsedRealtime()>deadline){result.put("ready",state.get("ready"));result.put("duration_ms",state.get("duration_ms"));result.put("playing",state.get("playing"));result.put("protocol",state.get("protocol"));result.put("error",error);if(Boolean.TRUE.equals(state.get("ready")))MediaPanel.controlMusic(activity,test[0],"seek",1000);result.put("passed",Boolean.TRUE.equals(state.get("ready"))&&!Boolean.TRUE.equals(state.get("playing"))&&Integer.valueOf(2).equals(state.get("protocol")));done.countDown();}else main.postDelayed(this,100);}},100);}});
  if(!done.await(24,TimeUnit.SECONDS))throw new IOException("Production player prepare timed out");
 }catch(Exception failure){result.put("passed",false);result.put("error",failure.toString());}finally{if(test[0]!=null)new Handler(Looper.getMainLooper()).post(new Runnable(){public void run(){MediaPanel.releaseMusic(test[0]);}});try(FileOutputStream out=new FileOutputStream(new File(activity.getFilesDir(),"home/.trough/music-native-check-result.json"))){out.write(Json.write(result).getBytes("UTF-8"));}catch(Exception ignored){}}}},"trough-music-production-check").start();}
}
