package com.trough.hot;
import android.content.Context;
import java.io.*;
import java.util.Map;
/** One explicit, hash-pinned capability request; no model or UI changes. Compute requests submit one bounded workload. */
final class LocalGpuProbe {
 private static native String enumerateNative();
 private static native String computeNative();
 static void poll(final Context context,final File home){
  final File base=new File(home,".trough"),request=new File(base,"local-gpu-probe-request.json"),claim=new File(base,"local-gpu-probe-running.json");
  if(!request.isFile()||claim.exists())return;
  try{
   if(request.length()>1024)throw new IOException("Probe request size");
   final Map<String,Object> value=Json.obj(Json.parse(new String(Payloads.readSmall(request),"UTF-8")));
   final String id=Json.str(value,"id"),sha=Json.str(value,"sha256"),mode=value.containsKey("mode")?Json.str(value,"mode"):"enumerate";
   if(id==null||!id.matches("[A-Za-z0-9-]{1,80}")||sha==null||!sha.matches("[a-f0-9]{64}"))throw new IOException("Probe request identity");
   if(!("enumerate".equals(mode)||"compute".equals(mode)))throw new IOException("Probe mode");
   if(!request.renameTo(claim))return;
   final Context app=context.getApplicationContext();
   new Thread(new Runnable(){public void run(){
    File result=new File(base,"local-gpu-probe-result.json");long started=System.currentTimeMillis();
    try{
     Payloads.writeSmall(result,Json.write(Json.map("id",id,"state","running","started_ms",started,"pid",android.os.Process.myPid())));
     File original=new File(base,"local-gpu-probe-"+sha+".so");
     if(!original.isFile()||original.length()>4194304||!sha.equals(NativeSwipe.sha(original)))throw new IOException("Probe library identity");
     File dir=new File(app.getCodeCacheDir(),"local-gpu-probe");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Probe cache directory");
     File copy=new File(dir,sha.substring(0,20)+"-"+Long.toHexString(System.nanoTime())+".so");
     try(InputStream in=new FileInputStream(original);FileOutputStream out=new FileOutputStream(copy)){byte[] block=new byte[8192];for(int n;(n=in.read(block))!=-1;)out.write(block,0,n);out.getFD().sync();}
     if(!copy.setReadOnly()||!sha.equals(NativeSwipe.sha(copy)))throw new IOException("Probe cache verification");
     System.load(copy.getAbsolutePath());String raw="compute".equals(mode)?computeNative():enumerateNative();
     if(raw==null||raw.length()>32768)throw new IOException("Probe output size");
     Map<String,Object> capabilities=Json.obj(Json.parse(raw));
     String expectedKind="compute".equals(mode)?"smoke64-v1":"enumerate-v1";
     if(capabilities==null||!expectedKind.equals(Json.str(capabilities,"kind")))throw new IOException("Native probe kind mismatch; do not attribute a cached binding to this module");
     boolean success=("compute".equals(mode)?"compute_verified":"enumerated").equals(Json.str(capabilities,"stage"));
     Payloads.writeSmall(result,Json.write(Json.map("id",id,"state",success?"completed":"failed","mode",mode,"started_ms",started,"completed_ms",System.currentTimeMillis(),"pid",android.os.Process.myPid(),"sha256",sha,"capabilities",capabilities,"model_run",false,"gpu_workload_run",Boolean.TRUE.equals(capabilities.get("workload_run")))));
     if(success&&!claim.delete())android.util.Log.w("LocalGpuProbe","Completed claim retained");
    }catch(Throwable failure){try{Payloads.writeSmall(result,Json.write(Json.map("id",id,"state","failed","started_ms",started,"completed_ms",System.currentTimeMillis(),"error",failure.toString(),"pid",android.os.Process.myPid())));}catch(Exception persistence){android.util.Log.e("LocalGpuProbe","Result persistence",persistence);}/* Failed claim remains: no automatic replay. */}
   }},"Trough GPU capability probe").start();
  }catch(Exception failure){android.util.Log.e("LocalGpuProbe","Request rejected",failure);}
 }
}
