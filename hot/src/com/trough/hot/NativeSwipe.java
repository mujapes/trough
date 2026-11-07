package com.trough.hot;

import android.content.Context;
import android.view.View;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/** Native gesture state, with a bootstrap-typed factory that survives DEX replacement.
 * Only the UI owner calls a binding. Each pointer owns a generational native handle.
 * The normal event path reuses primitive buffers: no reflection, boxing, or new arrays.
 */
final class NativeSwipe implements SwipePath, AutoCloseable {
    private static final int ABI=1, CACHE_TAG=0x7f0d7472;
    private static Callable<Object[]> factory;
    private static boolean attempted,factoryReused;
    private static String binaryHash="",error="";
    private static long steps,lastStepNs,maxStepNs,opened,closedCount;
    private final LiteralSwipe.Geometry geometry;
    private final LiteralSwipe.Listener listener;
    private final Runnable bridge;
    private final int[] command,output;
    private final float[] args,flat=new float[LiteralSwipe.MAX_KEYS*4];
    private final Object[] refs;
    private int layoutVersion=Integer.MIN_VALUE;
    private boolean closed,faulted;
    private LiteralSwipe fallback;

    private static native int abi();
    private static native long create(float inset,float hysteresis,float minLoopRadius);
    private static native int layout(long handle,float[] bounds,boolean[] repeatable,int count);
    private static native int step(long handle,int command,float x,float y,int key,int[] out);
    private static native void destroy(long handle);

    static NativeSwipe open(Context context,View owner,LiteralSwipe.Geometry geometry,LiteralSwipe.Listener listener) {
        try {
            Callable<Object[]> current=loadFactory(context,owner);
            return current==null?null:new NativeSwipe(current,geometry,listener);
        } catch(Exception failure) { remember(failure);return null; }
          catch(LinkageError failure) { remember(failure);return null; }
    }

    private NativeSwipe(Callable<Object[]> current,LiteralSwipe.Geometry geometry,LiteralSwipe.Listener listener) throws Exception {
        this.geometry=geometry;this.listener=listener;
        Object[] binding=current.call();
        bridge=(Runnable)binding[0];command=(int[])binding[1];args=(float[])binding[2];
        refs=(Object[])binding[3];output=(int[])binding[4];
        command[0]=0;args[0]=geometry.inset;args[1]=geometry.hysteresis;args[2]=geometry.minLoopRadius;
        bridge.run();
        if(command[3]!=1) throw new IllegalStateException("Native gesture capacity unavailable");
        opened++;
    }

    @SuppressWarnings("unchecked")
    private static synchronized Callable<Object[]> loadFactory(Context context,View owner) throws Exception {
        if(attempted) return factory;
        attempted=true;
        File descriptor=new File(context.getFilesDir(),"home/.trough/native-input.json");
        Map<String,Object> data=Json.obj(Json.parse(new String(Payloads.readSmall(descriptor),"UTF-8")));
        if(data==null || !(data.get("abi") instanceof Number) || ((Number)data.get("abi")).intValue()!=ABI)
            throw new IllegalStateException("Unsupported native input ABI");
        String hash=Json.str(data,"sha256"),path=Json.str(data,"path");
        if(hash==null || !hash.matches("[0-9a-f]{64}") || path==null)
            throw new IllegalStateException("Invalid native input descriptor");
        binaryHash=hash;
        Map<String,Object> cache=null;
        if(owner!=null) {
            Object existing=owner.getTag(CACHE_TAG);
            if(existing instanceof Map) cache=(Map<String,Object>)existing;
            else if(existing==null) { cache=new LinkedHashMap<String,Object>();owner.setTag(CACHE_TAG,cache); }
        }
        String key="trough-input-"+ABI+"-"+hash;
        Object carried=cache==null?null:cache.get(key);
        if(carried instanceof Callable) {
            factory=(Callable<Object[]>)carried;factoryReused=true;
            return factory;
        }
        File source=new File(path);
        if(!source.isFile() || source.length()<=0 || source.length()>1048576 || !hash.equals(sha(source)))
            throw new IllegalStateException("Native input checksum mismatch");
        File directory=new File(context.getCodeCacheDir(),"trough-native-input");
        if(!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Native input cache unavailable");
        // A fresh Android root/classloader needs its own path. Live root handoffs reuse the factory above.
        File copy=new File(directory,hash.substring(0,20)+"-"+Long.toHexString(System.nanoTime())+".so");
        try(FileInputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(copy)) {
            byte[] buffer=new byte[16384];
            for(int n;(n=in.read(buffer))!=-1;) out.write(buffer,0,n);
            out.getFD().sync();
        }
        if(!copy.setReadOnly()) throw new java.io.IOException("Cannot seal native input library");
        if(!hash.equals(sha(copy))) throw new java.io.IOException("Native input copy mismatch");
        System.load(copy.getAbsolutePath());
        if(abi()!=ABI) throw new IllegalStateException("Native input ABI mismatch");
        factory=new Callable<Object[]>() {
            @Override public Object[] call() { return new Binding().export(); }
        };
        if(cache!=null) cache.put(key,factory);
        return factory;
    }

    static String sha(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(FileInputStream input=new FileInputStream(file)) {
            byte[] buffer=new byte[16384];
            for(int n;(n=input.read(buffer))!=-1;) digest.update(buffer,0,n);
        }
        char[] chars=new char[64];byte[] bytes=digest.digest();
        final char[] alphabet="0123456789abcdef".toCharArray();
        for(int i=0;i<bytes.length;i++) { chars[i*2]=alphabet[(bytes[i]>>>4)&15];chars[i*2+1]=alphabet[bytes[i]&15]; }
        return new String(chars);
    }

    /** This binding stays in the classloader that owns JNI; all exposed types are bootstrap types. */
    private static final class Binding implements Runnable {
        final int[] command=new int[4],output=new int[8];
        final float[] args=new float[3];
        final Object[] refs=new Object[2];
        long handle;
        Object[] export() { return new Object[]{this,command,args,refs,output}; }
        @Override public void run() {
            switch(command[0]) {
                case 0:
                    if(handle!=0) { command[3]=-1;return; }
                    handle=create(args[0],args[1],args[2]);command[3]=handle==0?-1:1;return;
                case 1:
                    command[3]=layout(handle,(float[])refs[0],(boolean[])refs[1],command[2]);return;
                case 2:
                    command[3]=step(handle,command[1],args[0],args[1],command[2],output);return;
                case 3:
                    if(handle!=0) { destroy(handle);handle=0; }
                    command[3]=0;return;
                default: command[3]=-2;
            }
        }
    }

    private boolean syncGeometry() {
        if(closed || faulted) return false;
        if(layoutVersion==geometry.version) return true;
        for(int i=0;i<geometry.count;i++) {
            int p=i*4;flat[p]=geometry.left[i];flat[p+1]=geometry.top[i];
            flat[p+2]=geometry.right[i];flat[p+3]=geometry.bottom[i];
        }
        refs[0]=flat;refs[1]=geometry.repeatable;command[0]=1;command[2]=geometry.count;
        bridge.run();refs[0]=null;refs[1]=null;
        if(command[3]<0) { fail("Native input geometry rejected: "+command[3]);return false; }
        layoutVersion=geometry.version;return true;
    }

    private void send(int operation,float x,float y,int key) {
        if(closed || faulted) return;
        command[0]=2;command[1]=operation;command[2]=key;args[0]=x;args[1]=y;
        long start=System.nanoTime();bridge.run();
        lastStepNs=System.nanoTime()-start;maxStepNs=Math.max(maxStepNs,lastStepNs);steps++;
        int count=command[3];
        if(count<0 || count>output.length) { fail("Native input step rejected: "+count);return; }
        for(int i=0;i<count;i++) {
            int value=output[i],index=value&255;
            if(index>=geometry.count) { fail("Native input key outside geometry");return; }
            listener.key(index,(value&256)!=0);
        }
    }
    @Override public void begin(float x,float y,long at,int key) {
        if(closed) return;
        if(faulted) fallback.begin(x,y,at,key);
        else if(syncGeometry()) send(1,x,y,key);
    }
    @Override public void move(float x,float y,long at) { if(closed) return;if(faulted) fallback.move(x,y,at);else send(2,x,y,-1); }
    @Override public void finish(float x,float y,long at) { if(closed) return;if(faulted) fallback.finish(x,y,at);else send(3,x,y,-1); }
    @Override public void end() { if(closed) return;if(faulted) fallback.end();else send(4,0,0,-1); }
    @Override public void close() {
        if(closed) return;
        if(fallback!=null) fallback.end();
        command[0]=3;bridge.run();closed=true;closedCount++;
    }
    boolean healthy() { return !closed && !faulted; }
    private void fail(String message) {
        if(!faulted) { fallback=new LiteralSwipe(geometry,listener);faulted=true; }
        error=message;
    }
    private static void remember(Throwable failure) {
        String message=failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage());
        error=message.length()>240?message.substring(0,240):message;
    }
    static Map<String,Object> stats() {
        return Json.map("abi",ABI,"sha256",binaryHash,"factory_identity",factory==null?0:System.identityHashCode(factory),
            "factory_reused",factoryReused,"error",error,"opened",opened,"closed",closedCount,
            "steps",steps,"last_step_ns",lastStepNs,"max_step_ns",maxStepNs,"event_buffers_reused",true);
    }
}
