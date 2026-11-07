package com.trough.hot;
import android.content.Context;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
/** One hash-pinned, non-forking capability child; no models or UI edits. */
final class LocalInferenceChild {
 static boolean cancelRequested(File base,String id){try{File f=new File(base,"local-inference-child-cancel.json");return f.isFile()&&f.length()<=1024&&id.equals(Json.str(Json.obj(Json.parse(new String(Payloads.readSmall(f),"UTF-8"))),"id"));}catch(Exception invalid){return false;}}
 static void poll(final Context context,final File home){
  final File base=new File(home,".trough"),request=new File(base,"local-inference-child-request.json"),claim=new File(base,"local-inference-child-running.json");
  if(!request.isFile()||claim.exists())return;
  try{
   if(request.length()>16384)throw new IOException("Request size");
   final Map<String,Object> value=Json.obj(Json.parse(new String(Payloads.readSmall(request),"UTF-8")));
   final String id=Json.str(value,"id"),sha=Json.str(value,"sha256"),mode=Json.str(value,"mode");
   final boolean modelMode="bonsai-cpu".equals(mode)||"bonsai-gpu".equals(mode)||"qwen-cpu".equals(mode);
   final String prompt=Json.str(value,"prompt");
   if(prompt!=null&&prompt.getBytes("UTF-8").length>4096)throw new IOException("Prompt size");
   if(id==null||!id.matches("[A-Za-z0-9-]{1,80}")||sha==null||!sha.matches("[a-f0-9]{64}")||!("enumerate".equals(mode)||"timeout-test".equals(mode)||"q2-matmul".equals(mode)||"dns".equals(mode)||modelMode))throw new IOException("Request identity");
   if(!request.renameTo(claim))return;
   final Context app=context.getApplicationContext();
   new Thread(new Runnable(){public void run(){
    File result=new File(base,"local-inference-child-result.json");long start=System.currentTimeMillis();Process process=null;
    try{
     Payloads.writeSmall(result,Json.write(Json.map("id",id,"state","running","started_ms",start,"pid",android.os.Process.myPid())));
     File original=new File(base,"local-inference-child-"+sha);
     if(!original.isFile()||original.length()<1||original.length()>4194304||!sha.equals(NativeSwipe.sha(original)))throw new IOException("Executable identity");
     File dir=new File(app.getCodeCacheDir(),"inference-child");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cache directory");
     File copy=new File(dir,id+"-"+Long.toHexString(System.nanoTime()));
     try(InputStream in=new FileInputStream(original);FileOutputStream out=new FileOutputStream(copy)){byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);out.getFD().sync();}
     if(!copy.setReadOnly()||!sha.equals(NativeSwipe.sha(copy)))throw new IOException("Cache verification");
     ProcessBuilder pb=new ProcessBuilder("/system/bin/linker64",copy.getAbsolutePath(),"--"+mode).redirectErrorStream(true);
     if(!modelMode)pb.redirectInput(new File("/dev/null"));
     pb.environment().clear();pb.environment().put("PATH","/system/bin");
     if("q2-matmul".equals(mode)||modelMode){
      List<Object> libs=Json.arr(value.get("libraries"));Set<String> names=new HashSet<String>();
      if(libs==null||libs.size()!=5)throw new IOException("Runtime library set");
      File runtime=new File(base,"local-inference-runtime");
      for(Object entry:libs){Map<String,Object> item=Json.obj(entry);String name=Json.str(item,"name"),hash=Json.str(item,"sha256");
       if(name==null||!name.matches("lib(?:llama|ggml(?:-base|-cpu|-vulkan)?)\\.so")||!names.add(name)||hash==null||!hash.matches("[a-f0-9]{64}"))throw new IOException("Runtime identity");
       File lib=new File(runtime,name);if(!lib.isFile()||lib.length()>134217728||!hash.equals(NativeSwipe.sha(lib)))throw new IOException("Runtime verification");
      }
      pb.environment().put("LD_LIBRARY_PATH",runtime.getAbsolutePath());
     }
     if(modelMode){
      boolean qwen="qwen-cpu".equals(mode);
      File model=new File(home,qwen?"../work/trough-lab/local-inference/models/Qwen3.5-0.8B-Q4_K_M.gguf":"../work/trough-lab/local-inference/bonsai2/Ternary-Bonsai-2-2B-Q2_0.gguf");
      String expected=qwen?"bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517":"c2893fa6f951daee8b7263d5f49b455e57989ef56417a22125b03566dc560650";
      if(!model.isFile()||model.length()!=(qwen?532517120L:546689504L)||!expected.equals(NativeSwipe.sha(model)))throw new IOException("Pinned model verification");
     }
     final Process child=pb.start();process=child;
     if(modelMode){try(OutputStream input=child.getOutputStream()){if(prompt!=null)input.write(prompt.getBytes("UTF-8"));}}
     final ByteArrayOutputStream bytes=new ByteArrayOutputStream();final boolean[] truncated={false},readerError={false};
     Thread reader=new Thread(new Runnable(){public void run(){try(InputStream in=child.getInputStream()){byte[] b=new byte[4096];for(int n;(n=in.read(b))!=-1;){synchronized(bytes){int keep=Math.min(n,65536-bytes.size());bytes.write(b,0,keep);if(keep<n){truncated[0]=true;child.destroyForcibly();}}}}catch(IOException e){synchronized(bytes){readerError[0]=true;}}}},"Inference child output");
     reader.setDaemon(true);reader.start();
     long limit="timeout-test".equals(mode)?250:(modelMode?120000:("q2-matmul".equals(mode)?60000:15000));
     long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(limit);boolean done=false,cancelled=false;
     while(!(done=child.waitFor(50,TimeUnit.MILLISECONDS))){if(cancelRequested(base,id)){cancelled=true;break;}if(System.nanoTime()>=deadline)break;}
     if(cancelRequested(base,id))cancelled=true;
     boolean timedOut=!done&&!cancelled;
     if(!done){child.destroy();if(!child.waitFor(500,TimeUnit.MILLISECONDS))child.destroyForcibly();}
     boolean reaped=child.waitFor(2000,TimeUnit.MILLISECONDS);reader.join(1000);
     String output;boolean complete,overflow,readerStopped;synchronized(bytes){output=bytes.toString("UTF-8");readerStopped=!reader.isAlive();complete=!readerError[0]&&readerStopped;overflow=truncated[0];}
     Map<String,Object> capabilities=LocalInferenceResult.parse(mode,timedOut||cancelled,reaped,complete,overflow,output);
     boolean success=!cancelled&&("timeout-test".equals(mode)?timedOut&&reaped&&readerStopped&&!overflow:LocalInferenceResult.completed(mode,reaped?child.exitValue():-1,capabilities));
     Payloads.writeSmall(result,Json.write(Json.map("id",id,"state",cancelled&&reaped&&readerStopped?"cancelled":success?"completed":"failed","mode",mode,"pid",android.os.Process.myPid(),"started_ms",start,"completed_ms",System.currentTimeMillis(),"cancelled",cancelled,"timed_out",timedOut,"reaped",reaped,"exit",reaped?child.exitValue():-1,"reader_complete",complete,"reader_stopped",readerStopped,"truncated",overflow,"output",output,"capabilities",capabilities,"model_run",capabilities!=null?capabilities.get("model_run"):(modelMode?null:Boolean.FALSE))));
     if((success||(cancelled&&reaped&&readerStopped))&&!claim.delete())android.util.Log.w("LocalInferenceChild","Completed claim retained");
     if(cancelled&&reaped&&readerStopped&&cancelRequested(base,id))new File(base,"local-inference-child-cancel.json").delete();
    }catch(Throwable failure){try{Payloads.writeSmall(result,Json.write(LocalInferenceResult.failure(id,mode,modelMode,process!=null,start,System.currentTimeMillis(),android.os.Process.myPid(),failure)));}catch(Exception persistence){android.util.Log.e("LocalInferenceChild","Persistence",persistence);}}
    finally{if(process!=null){if(process.isAlive())process.destroyForcibly();try{process.waitFor(2,TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}try{process.getInputStream().close();}catch(IOException ignored){}}}
   }},"Trough isolated inference probe").start();
  }catch(Exception failure){android.util.Log.e("LocalInferenceChild","Request rejected",failure);}
 }
}
