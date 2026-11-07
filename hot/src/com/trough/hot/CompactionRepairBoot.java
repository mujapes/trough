package com.trough.hot;
import android.app.*;
import android.content.*;
import android.widget.FrameLayout;
import java.io.*;
import java.lang.reflect.*;
import java.security.*;
import java.security.spec.*;
import org.json.*;

/** Signed, one-time migration of app-private sources; preserves the existing boot contract. */
public final class CompactionRepairBoot {
    private static Object call(String name,Class<?>[] types,Object... values) throws Exception {
        try{return Class.forName("com.trough.hot.Main").getMethod(name,types).invoke(null,values);}
        catch(InvocationTargetException failure){throw new Exception(failure.getCause());}
    }
    private static byte[] read(File file,int max) throws Exception {
        if(file.length()>max)throw new IOException("File exceeds limit: "+file.getName());
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(InputStream in=new FileInputStream(file)){byte[] b=new byte[32768];for(int n;(n=in.read(b))!=-1;){if(out.size()+n>max)throw new IOException("Growing file");out.write(b,0,n);}}
        return out.toByteArray();
    }
    private static void write(File file,byte[] bytes) throws Exception {
        file.getParentFile().mkdirs();File tmp=new File(file+".new");
        try(FileOutputStream out=new FileOutputStream(tmp)){out.write(bytes);out.getFD().sync();}
        if(!tmp.renameTo(file))throw new IOException("Publication failed: "+file.getName());
    }
    private static String sha(byte[] bytes) throws Exception {
        StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static File shared(){return new File(android.os.Environment.getExternalStoragePublicDirectory("Download"),"trough/compaction-fix");}
    private static void signed(Context ctx,String name,JSONObject report) throws Exception {
        byte[] data=report.toString().getBytes("UTF-8");
        PrivateKey key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(read(new File(ctx.getFilesDir(),"keys/trough_rsa.p8"),8192)));
        Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(key);signer.update(data);
        write(new File(shared(),name+".sig"),signer.sign());write(new File(shared(),name+".json"),data);
    }
    private static void prepare(final Context ctx,File bundle,Class<?> previous) {
        try {
            File saved=new File(ctx.getFilesDir(),"home/.trough/compaction-fix-v1"),mark=new File(saved,"installed");
            JSONObject receipt=new JSONObject().put("at_ms",System.currentTimeMillis());
            if(!mark.isFile()) {
                JSONArray patches=new JSONArray(new String(read(new File(bundle,"compaction-fix/patches.json"),1048576),"UTF-8"));
                java.util.LinkedHashMap<String,String> updates=new java.util.LinkedHashMap<String,String>();
                for(int i=0;i<patches.length();i++) {
                    JSONObject patch=patches.getJSONObject(i);String name=patch.getString("file");
                    if(!name.matches("[A-Za-z0-9]+\\.java"))throw new SecurityException("Source path");
                    File target=new File(ctx.getFilesDir(),"src/com/trough/hot/"+name);
                    String text=updates.containsKey(name)?updates.get(name):new String(read(target,1048576),"UTF-8");
                    String before=patch.getString("before"),after=patch.getString("after");
                    if(text.contains(before))text=text.replace(before,after);
                    else if(!text.contains(after))throw new IOException("Source changed; recapture required: "+name);
                    updates.put(name,text);
                }
                File helper=new File(ctx.getFilesDir(),"src/com/trough/hot/HistoryPreview.java");
                byte[] helperBytes=read(new File(bundle,"src/com/trough/hot/HistoryPreview.java"),65536);
                if(helper.exists() && !sha(read(helper,65536)).equals(sha(helperBytes)))throw new IOException("Helper source conflict");
                for(String name:updates.keySet()) {
                    File target=new File(ctx.getFilesDir(),"src/com/trough/hot/"+name);
                    if(!new File(saved,name).exists())write(new File(saved,name),read(target,1048576));
                }
                write(helper,helperBytes);
                for(String name:updates.keySet())write(new File(ctx.getFilesDir(),"src/com/trough/hot/"+name),updates.get(name).getBytes("UTF-8"));
                write(mark,"installed\n".getBytes("UTF-8"));receipt.put("patched_files",updates.size());
            }
            signed(ctx,"install",receipt.put("ok",true));
        }catch(Throwable failure){try{signed(ctx,"install",new JSONObject().put("ok",false).put("error",failure.toString()));}catch(Exception ignored){}}
        try{captureSources(ctx);}catch(Exception ignored){}
    }
    private static void captureSources(Context ctx)throws Exception {
        File source=new File(ctx.getFilesDir(),"src/com/trough/hot"),target=new File(shared(),"live-source/com/trough/hot");
        JSONArray inventory=new JSONArray();File[] files=source.listFiles();
        if(files!=null)for(File f:files)if(f.isFile()&&f.getName().matches("[A-Za-z0-9]+\\.java")) {
            byte[] data=read(f,1048576);write(new File(target,f.getName()),data);inventory.put(new JSONObject().put("file",f.getName()).put("sha256",sha(data)));
        }
        signed(ctx,"sources",new JSONObject().put("ok",true).put("files",inventory));
    }
    private static void diagnostics(Context ctx) throws Exception {
        JSONObject report=new JSONObject().put("at_ms",System.currentTimeMillis()).put("pid",android.os.Process.myPid());
        ActivityManager manager=(ActivityManager)ctx.getSystemService(Context.ACTIVITY_SERVICE);
        JSONArray exits=new JSONArray();
        for(ApplicationExitInfo exit:manager.getHistoricalProcessExitReasons(ctx.getPackageName(),0,6))exits.put(new JSONObject()
            .put("at_ms",exit.getTimestamp()).put("reason",exit.getReason()).put("status",exit.getStatus()).put("description",exit.getDescription())
            .put("pss_kb",exit.getPss()).put("rss_kb",exit.getRss()).put("process",exit.getProcessName()));
        report.put("exits",exits);
        File home=new File(ctx.getFilesDir(),"home");
        for(String path:new String[]{".trough/ui-stall.json",".trough/ui-runtime.json",".trough/reports/chat-state.txt",".trough/build.log"}) {
            File f=new File(home,path);if(!f.isFile())continue;
            try(RandomAccessFile input=new RandomAccessFile(f,"r")){int length=(int)Math.min(30000,input.length());byte[] tail=new byte[length];input.seek(input.length()-length);input.readFully(tail);report.put(path,new String(tail,"UTF-8"));}
        }
        File transcript=new File(home,".trough/chat-transcript.json");report.put("transcript_bytes",transcript.length());
        report.put("max_heap_bytes",Runtime.getRuntime().maxMemory());report.put("used_heap_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
        signed(ctx,"diagnostics",report);
    }
    public static void boot(Context ctx,FrameLayout root,File bundle)throws Exception{prepare(ctx,bundle,null);call("boot",new Class<?>[]{Context.class,FrameLayout.class,File.class},ctx,root,bundle);}
    public static void replace(Context ctx,FrameLayout root,File bundle,Class<?> previous,Activity window)throws Exception{prepare(ctx,bundle,previous);call("replace",new Class<?>[]{Context.class,FrameLayout.class,File.class,Class.class,Activity.class},ctx,root,bundle,previous,window);}
    public static void start(Activity activity,FrameLayout root,File bundle)throws Exception{boot(activity.getApplicationContext(),root,bundle);attach(activity);}
    public static void attach(Activity window)throws Exception{call("attach",new Class<?>[]{Activity.class},window);}
    public static void detach()throws Exception{call("detach",new Class<?>[]{});}
    public static Object handoff()throws Exception{return call("handoff",new Class<?>[]{});}
    public static void stop()throws Exception{call("stop",new Class<?>[]{});}
    public static void intent(Intent intent)throws Exception{call("intent",new Class<?>[]{Intent.class},intent);}
    public static Object dispatch(Object host,String event,Object[] args)throws Exception{return call("dispatch",new Class<?>[]{Object.class,String.class,Object[].class},host,event,args);}
}
