package com.trough.hot;

import android.content.Context;
import android.view.View;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;

/** Checked C graph controls, carried across DEX reload through bootstrap-only bindings. */
final class NativeDogshit {
    private static final int CACHE_TAG=0x7f0d7473;
    private final Runnable bridge;
    private final int[] command;
    private final double[] inputs,outputs;
    private final Object[] refs;
    private String error="";
    private Map<String,Object> installDiagnostic;
    private final String hash;
    private long lastNs;
    private static native int install(byte[] source);
    private static native byte[] installationDiagnostic();
    private static native int evaluate(double energy,double focus,double warmth,boolean held,long ordinal,double[] outputs);

    @SuppressWarnings("unchecked")
    static NativeDogshit open(Context context,View owner) {
        try {
            Map<String,Object> descriptor=Json.obj(Json.parse(new String(Payloads.readSmall(new File(context.getFilesDir(),"home/.trough/native-dogshit.json")),"UTF-8")));
            String hash=Json.str(descriptor,"sha256"),path=Json.str(descriptor,"path");
            if(hash==null || !hash.matches("[a-f0-9]{64}") || path==null) return null;
            Map<String,Object> cache;
            Object old=owner.getTag(CACHE_TAG);
            if(old instanceof Map) cache=(Map<String,Object>)old;
            else { cache=new LinkedHashMap<String,Object>();owner.setTag(CACHE_TAG,cache); }
            Callable<Object[]> factory=(Callable<Object[]>)cache.get(hash);
            if(factory==null) {
                File library=new File(path);
                if(!library.isFile() || library.length()>1048576 || !hash.equals(NativeSwipe.sha(library))) return null;
                File directory=new File(context.getCodeCacheDir(),"dogshit");directory.mkdirs();
                File copy=new File(directory,hash.substring(0,20)+"-"+Long.toHexString(System.nanoTime())+".so");
                try(FileInputStream input=new FileInputStream(library);FileOutputStream output=new FileOutputStream(copy)) {
                    byte[] bytes=new byte[16384];for(int n;(n=input.read(bytes))!=-1;) output.write(bytes,0,n);output.getFD().sync();
                }
                if(!copy.setReadOnly() || !hash.equals(NativeSwipe.sha(copy))) return null;
                System.load(copy.getAbsolutePath());
                factory=new Callable<Object[]>() { public Object[] call() { return new Binding().export(); }};
                cache.put(hash,factory);
            }
            NativeDogshit result=new NativeDogshit(factory.call(),hash);
            File program=new File(Json.str(descriptor,"program"));
            if(program.length()>32768 || !NativeSwipe.sha(program).equals(Json.str(descriptor,"program_sha256"))) return null;
            result.installProgram(Payloads.readSmall(program));
            return result;
        } catch(Exception failure) { return null; } catch(LinkageError failure) { return null; }
    }
    private NativeDogshit(Object[] values,String hash) {
        bridge=(Runnable)values[0];command=(int[])values[1];inputs=(double[])values[2];outputs=(double[])values[3];refs=(Object[])values[4];this.hash=hash;
    }
    private void installProgram(byte[] source) {
        refs[0]=source;command[0]=0;
        try { bridge.run(); }
        finally { refs[0]=null; }
        installDiagnostic=refs.length>1?Json.obj(refs[1]):null;
        String detail=Json.str(installDiagnostic,"message");
        error=command[2]==1?"":detail!=null&&!detail.isEmpty()?detail:"Program rejected; previous checked graph retained";
    }
    Map<String,Object> apply(Map<String,Object> material) {
        Map<String,Object> adaptive=Json.obj(material.get("adaptive"));
        Map<String,Object> values=adaptive==null?null:Json.obj(adaptive.get("values"));
        inputs[0]=value(values,"energy");inputs[1]=value(values,"focus");inputs[2]=value(values,"warmth");
        String text=Json.str(material,"word");if(text==null || text.trim().isEmpty()) text=Json.str(material,"prompt");
        inputs[3]=Math.max(0,CosmicWords.ordinal(text==null?"":text));
        command[0]=1;command[1]=Boolean.TRUE.equals(material.get("held"))?1:0;
        long start=System.nanoTime();bridge.run();lastNs=System.nanoTime()-start;
        if(command[2]!=1) return Json.map("ready",false,"error",error.isEmpty()?"Native graph evaluation rejected":error,"install_diagnostic",installDiagnostic);
        return Json.map("ready",true,"engine","dogshit-c-graph","sha256",hash,"error",error,"install_diagnostic",installDiagnostic,
            "values",Json.map("motion",outputs[0],"focus",outputs[1],"warmth",outputs[2]),
            "ordinal",outputs[3],"calls",outputs[4],"storage_bytes",outputs[5],"evaluation_ns",lastNs);
    }
    private static double value(Map<String,Object> m,String name) {
        Object v=m==null?null:m.get(name);double x=v instanceof Number?((Number)v).doubleValue():0.5;
        return Double.isNaN(x)||Double.isInfinite(x)?0.5:Math.max(0,Math.min(1,x));
    }
    private static final class Binding implements Runnable {
        final int[] command=new int[3];final double[] inputs=new double[4],outputs=new double[6];final Object[] refs=new Object[2];
        Object[] export() { return new Object[]{this,command,inputs,outputs,refs}; }
        public void run() {
            if(command[0]==0) {
                command[2]=install((byte[])refs[0]);refs[1]=null;
                if(command[2]!=1) {
                    try {
                        byte[] bytes=installationDiagnostic();
                        if(bytes!=null) refs[1]=Json.obj(Json.parse(new String(bytes,"UTF-8")));
                    } catch(Exception unavailable) { /* Existing generic message remains usable. */ }
                      catch(LinkageError olderLibrary) { /* ABI1 library has no accessor. */ }
                }
            } else {
                command[2]=evaluate(inputs[0],inputs[1],inputs[2],command[1]!=0,(long)inputs[3],outputs);
            }
        }
    }
}
