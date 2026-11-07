package com.trough.hot;
import android.content.Context;
import java.io.*;
import java.util.*;
/** Explicit one-shot backend enumeration. No model, context, UI action or automatic retry. */
final class LocalInferenceProbe {
 private static native String runtimeNative(String path);
 static void poll(final Context context, final File home) {
  final File base=new File(home,".trough"),request=new File(base,"local-inference-probe-request.json"),claim=new File(base,"local-inference-probe-running.json");
  if(!request.isFile()||claim.exists())return;
  try {
   if(request.length()>16384)throw new IOException("Request size");
   final Map<String,Object> value=Json.obj(Json.parse(new String(Payloads.readSmall(request),"UTF-8")));
   final String id=Json.str(value,"id");
   if(id==null||!id.matches("[A-Za-z0-9-]{1,80}"))throw new IOException("Request identity");
   if(!request.renameTo(claim))return;
   final Context app=context.getApplicationContext();
   new Thread(new Runnable(){public void run(){
    File result=new File(base,"local-inference-probe-result.json");long started=System.currentTimeMillis();
    try {
     Payloads.writeSmall(result,Json.write(Json.map("id",id,"state","running","pid",android.os.Process.myPid(),"started_ms",started)));
     // SONAME/JNI bindings survive classloader swaps: never attribute a reload to new bytes.
     File marker=new File(base,"local-inference-probe-loaded-pid-"+android.os.Process.myPid());
     if(!marker.createNewFile())throw new IOException("Runtime probe already attempted in this process");
     List<Object> entries=Json.arr(value.get("libraries"));
     if(entries==null||entries.size()<6||entries.size()>16)throw new IOException("Library count");
     File source=new File(base,"local-inference-runtime"),dir=new File(app.getCodeCacheDir(),"local-inference-"+id);
     if(dir.exists()||!dir.mkdirs())throw new IOException("Fresh cache directory required");
     List<File> copies=new ArrayList<File>();Set<String> names=new HashSet<String>();long total=0;
     for(Object entry:entries){
      Map<String,Object> item=Json.obj(entry);String name=Json.str(item,"name"),sha=Json.str(item,"sha256");
      if(name==null||!name.matches("lib(?:llama|ggml(?:-base|-cpu|-vulkan)?|local-inference-probe)\\.so")||!names.add(name)||sha==null||!sha.matches("[a-f0-9]{64}"))throw new IOException("Library identity");
      File original=new File(source,name);long size=original.length();total+=size;
      if(!original.isFile()||size<1||size>134217728||total>268435456||!sha.equals(NativeSwipe.sha(original)))throw new IOException("Library verification");
      File copy=new File(dir,name);
      try(InputStream in=new FileInputStream(original);FileOutputStream out=new FileOutputStream(copy)){byte[] block=new byte[8192];for(int n;(n=in.read(block))!=-1;)out.write(block,0,n);out.getFD().sync();}
      if(!copy.setReadOnly()||!sha.equals(NativeSwipe.sha(copy)))throw new IOException("Cache verification");
      copies.add(copy);
     }
     if(!names.containsAll(Arrays.asList("libggml-base.so","libggml-cpu.so","libggml-vulkan.so","libggml.so","libllama.so","liblocal-inference-probe.so")))throw new IOException("Required runtime libraries");
     // All bytes are checked before the first load; caller supplies verified dependency order.
     for(File copy:copies)System.load(copy.getAbsolutePath());
     String raw=runtimeNative(new File(dir,"libggml.so").getAbsolutePath());
     if(raw==null||raw.length()>32768)throw new IOException("Probe output size");
     Map<String,Object> capabilities=Json.obj(Json.parse(raw));
     if(capabilities==null||!"llama-runtime-v1".equals(Json.str(capabilities,"kind"))||!Boolean.FALSE.equals(capabilities.get("model_run")))throw new IOException("Native result identity");
     boolean success="enumerated".equals(Json.str(capabilities,"stage"));
     Payloads.writeSmall(result,Json.write(Json.map("id",id,"state",success?"completed":"failed","started_ms",started,"completed_ms",System.currentTimeMillis(),"pid",android.os.Process.myPid(),"capabilities",capabilities,"model_run",false)));
     if(success&&!claim.delete())android.util.Log.w("LocalInferenceProbe","Completed claim retained");
    }catch(Throwable failure){try{Payloads.writeSmall(result,Json.write(Json.map("id",id,"state","failed","started_ms",started,"completed_ms",System.currentTimeMillis(),"pid",android.os.Process.myPid(),"error",failure.toString(),"model_run",false)));}catch(Exception persistence){android.util.Log.e("LocalInferenceProbe","Result persistence",persistence);}}
   }},"Trough inference capability probe").start();
  }catch(Exception failure){android.util.Log.e("LocalInferenceProbe","Request rejected",failure);}
 }
}
