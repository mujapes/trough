package com.trough.hot;
import android.os.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
/** Restores a previously validated private roster after a future worker launch. */
final class GameLiveRestore implements Runnable {
 private final IBinder worker;private final int pid;private final File live;private final long earliest;
 private final AtomicBoolean cancelled=new AtomicBoolean();
 private GameLiveRestore(IBinder worker,int pid,File live,long earliest){this.worker=worker;this.pid=pid;this.live=live;this.earliest=earliest;}
 static GameLiveRestore start(IBinder worker,int pid,File live,long earliest){GameLiveRestore task=new GameLiveRestore(worker,pid,live,earliest);new Thread(task,"Trough roster recovery").start();return task;}
 void cancel(){cancelled.set(true);}
 private static byte[] read(File file,int max)throws Exception{
  if(!file.isFile()||file.length()>max)throw new IOException("Invalid recovery artifact size");
  ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))>0){if(out.size()+n>max)throw new IOException("Recovery artifact grew");out.write(b,0,n);}}return out.toByteArray();
 }
 private static JSONObject json(File file)throws Exception{return new JSONObject(new String(read(file,65536),"UTF-8"));}
 private static String sha(byte[] bytes)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
 private static void write(File file,byte[] bytes)throws Exception{File tmp=new File(file.getParentFile(),file.getName()+".restore-next");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(bytes);}tmp.setWritable(false,false);if(!tmp.renameTo(file)){tmp.delete();throw new IOException("Recovery publish failed");}}
 private GameRestorePolicy.Receipt receipt(long dispatchMs){
  try{JSONObject row=json(new File(live,"roster-result.json"));if(row.optInt("worker_pid",-1)==pid&&row.optLong("observed_ms",0)>=earliest&&"independent_proportions".equals(row.optString("rig_mode")))return row.optString("error","").isEmpty()?GameRestorePolicy.Receipt.ACCEPTED:GameRestorePolicy.Receipt.FATAL;}catch(Exception ignored){}
  if(dispatchMs==0)return GameRestorePolicy.Receipt.NONE;
  try{JSONObject row=json(new File(live,"result.json"));if(row.optInt("worker_pid",-1)!=pid||row.optLong("observed_ms",0)<dispatchMs)return GameRestorePolicy.Receipt.NONE;String error=row.optString("error","");if(error.contains("Unity edit dispatch timed out"))return GameRestorePolicy.Receipt.DISPATCH_TIMEOUT;if(!error.isEmpty())return GameRestorePolicy.Receipt.FATAL;String text=row.optString("result","");if(text.startsWith("Loading")||text.startsWith("Whole model")||text.startsWith("Character cycle")||text.startsWith("Waiting for player"))return GameRestorePolicy.Receipt.ACCEPTED;}catch(Exception ignored){}
  return GameRestorePolicy.Receipt.NONE;
 }
 private void evidence(String phase,int attempts,String detail){try{JSONObject row=new JSONObject();row.put("worker_pid",pid);row.put("observed_ms",System.currentTimeMillis());row.put("phase",phase);row.put("result","Roster recovery "+phase);if("error".equals(phase))row.put("error",detail);row.put("attempts",attempts);row.put("detail",detail);write(new File(live,"roster-restore.json"),row.toString().getBytes("UTF-8"));}catch(Exception error){android.util.Log.w("Trough","Roster recovery evidence",error);}}
 public void run(){int attempts=0;long start=SystemClock.elapsedRealtime(),lastAttempt=0,dispatchMs=0;try{
  File active=new File(live,"roster-active.json");if(!active.isFile())return;JSONObject saved=json(active);if(saved.optInt("schema",0)!=1||!saved.optBoolean("enabled",true))return;String name=saved.optString("controller_dex","");if(!name.matches("libtroughlive-[0-9a-f]{24}\\.dex"))throw new IOException("Invalid saved roster identity");
  byte[] dex=read(new File(live,name),1048576);if(dex.length<112)throw new IOException("Truncated saved roster");JSONObject meta=json(new File(live,name.substring(0,name.length()-4)+".json"));if(!sha(dex).equals(meta.optString("dex_sha256","")))throw new IOException("Saved roster checksum mismatch");
  String nativeName=name.substring(0,name.length()-4)+".so";if(!nativeName.equals(meta.optString("native_library",""))||!sha(read(new File(live,nativeName),4194304)).equals(meta.optString("native_sha256","")))throw new IOException("Saved native controller checksum mismatch");
  while(true){long now=SystemClock.elapsedRealtime();GameRestorePolicy.Action action=GameRestorePolicy.decide(true,worker.isBinderAlive(),cancelled.get(),now-start,attempts,now-lastAttempt,receipt(dispatchMs));
   if(action==GameRestorePolicy.Action.COMPLETE){evidence("active",attempts,"Roster already active or startup accepted");return;}
   if(action==GameRestorePolicy.Action.STOP){evidence("stopped",attempts,cancelled.get()?"Cancelled by user/lifecycle":"Worker unavailable, startup error or deadline");return;}
   if(action==GameRestorePolicy.Action.DISPATCH){
    if(cancelled.get())return;write(new File(live,"classes.dex"),dex);if(cancelled.get()||!worker.isBinderAlive())return;
    dispatchMs=System.currentTimeMillis();Parcel data=Parcel.obtain();try{data.writeInt(5);if(!worker.transact(9,data,null,IBinder.FLAG_ONEWAY))throw new IOException("Worker rejected roster recovery");}finally{data.recycle();}
    attempts++;lastAttempt=SystemClock.elapsedRealtime();evidence("requested",attempts,name);
   }
   Thread.sleep(250);
  }
 }catch(Exception error){evidence("error",attempts,error.toString());android.util.Log.w("Trough","Roster recovery",error);}}
}
