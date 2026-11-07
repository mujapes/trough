package com.trough.hot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/** Resident scheduling only. The original boot still owns swap and host callbacks. */
final class ReloadDispatcher implements Runnable, Callable<Map<String,Object>> {
    private static final int TAG=0x7f0d7478, VERSION=1;
    private static volatile String attachError="", callbackClass="", callbackOwner="", attachStage="not_attempted";
    private final Context app;
    private final Class<?> owner;
    private final Field keyField, supersededField;
    private final Method swap;
    private final File bundle;
    private final JSONObject payloads;
    private final AtomicBoolean building, legacyPending;
    private final AtomicBoolean pending=new AtomicBoolean(), nativePending=new AtomicBoolean(), nativeRunning=new AtomicBoolean();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private volatile String error="", nativeState="idle";
    private long requests, builds;

    private ReloadDispatcher(Context app,Class<?> owner) throws Exception {
        this.app=app.getApplicationContext();this.owner=owner;
        keyField=field(owner,"runningKey");supersededField=field(owner,"superseded");
        building=(AtomicBoolean)field(owner,"reloading").get(null);
        AtomicBoolean legacy;
        try { legacy=(AtomicBoolean)field(owner,"reloadPending").get(null); }
        catch(NoSuchFieldException olderBoot) { legacy=new AtomicBoolean(); }
        legacyPending=legacy;
        bundle=(File)field(owner,"bootDir").get(null);
        payloads=(JSONObject)field(owner,"bootPayloads").get(null);
        swap=owner.getDeclaredMethod("swap",Class.class,JSONObject.class,Runnable.class);swap.setAccessible(true);
        if(bundle==null||payloads==null)throw new IllegalStateException("boot not ready");
    }
    private static Field field(Class<?> c,String name)throws Exception {Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}

    /** Called on UI during runWith. Bootstrap interfaces survive DEX replacement. */
    static Runnable attach(Context app,FrameLayout root,Runnable original) {
        try {
            attachStage="callback";
            callbackClass=original==null?"null":original.getClass().getName();
            Class<?> boot=original.getClass().getEnclosingClass();
            callbackOwner=boot==null?"null":boot.getName();
            attachStage="registry";
            Object old=root.getTag(TAG);
            if(old instanceof Map) {
                Map<?,?> m=(Map<?,?>)old;
                if(!Integer.valueOf(VERSION).equals(m.get("version")) || !(m.get("reload") instanceof Runnable))
                    throw new IllegalStateException("reload tag version mismatch");
                if(m.get("boot_owner")==boot)return (Runnable)m.get("reload");
                Object prior=m.get("boot_owner");
                if(!(prior instanceof Class) || !field((Class<?>)prior,"superseded").getBoolean(null))
                    throw new IllegalStateException("prior reload authority still active");
                old=null; // A real bundle takeover retired the old host; bind its successor.

            }
            if(old!=null)throw new IllegalStateException("reload tag occupied");
            attachStage="bind_boot_contract";
            ReloadDispatcher d=new ReloadDispatcher(app,boot);
            attachStage="publish_registry";
            Map<String,Object> m=new LinkedHashMap<>();m.put("version",VERSION);m.put("reload",d);m.put("state",d);m.put("boot_owner",boot);
            root.setTag(TAG,m);
            // Preserve a queued legacy request but prevent its old pipeline replaying it.
            if(d.legacyPending.getAndSet(false))d.run();
            attachStage="attached";attachError="";
            return d;
        }catch(Throwable e){attachError=String.valueOf(e);android.util.Log.w("trough","resident reload dispatcher unavailable",e);return original;}
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> state(FrameLayout root) {
        try {Object tag=root.getTag(TAG);Object c=tag instanceof Map?((Map<?,?>)tag).get("state"):null;
            if(c instanceof Callable)return (Map<String,Object>)((Callable<?>)c).call();
        }catch(Exception e){attachError=String.valueOf(e);attachStage="read_registry";}
        return Json.map("dispatcher_active",false,"dispatcher_attach_stage",attachStage,
            "dispatcher_attach_error",attachError,"dispatcher_callback_class",callbackClass,
            "dispatcher_callback_owner",callbackOwner);
    }
    private boolean superseded(){try{return supersededField.getBoolean(null);}catch(Exception e){return true;}}
    @Override public Map<String,Object> call(){return Json.map("dispatcher_active",true,"dispatcher_version",VERSION,"building",building.get(),
        "pending",pending.get(),"native_building",nativeRunning.get(),"native_pending",nativePending.get(),
        "native_state",nativeState,"dispatcher_error",error,"dispatcher_requests",requests,"dispatcher_builds",builds);}
    @Override public void run(){pending.set(true);requests++;ui.removeCallbacks(drain);ui.post(drain);}
    private final Runnable drain=new Runnable(){@Override public void run(){
        if(superseded()||!pending.get())return;
        if(!building.compareAndSet(false,true)){ui.postDelayed(this,100);return;}
        pending.set(false);builds++;
        new Thread(new Runnable(){@Override public void run(){buildJava();}},"trough-java-reload").start();
    }};
    private void finish(){
        if(legacyPending.getAndSet(false))pending.set(true);
        building.set(false);
        if(pending.get()&&!superseded())ui.post(drain);
    }
    private void buildJava(){
        Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
        try{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);}catch(Exception ignored){}
        final JSONObject report=new JSONObject(), b=new JSONObject();
        final File log=new File(Main.work(app),".trough/build.log");
        try {
            report.put("self_build",b).put("reload",true).put("native_validation","separate");
            final File built;final Class<?> entry;
            synchronized("com.trough.hot.boot") {
                if(superseded()){finish();return;}
                built=Self.build(app,Self.src(app),bundle,payloads,Self.builds(app),b);
                log.delete();
                if(built.getName().equals(keyField.get(null))){queueNative();finish();return;}
                Self.commitEdits(app,Self.src(app),"agent edits (built "+built.getName().substring(0,12)+")");
                entry=Self.load(app,built,bundle,Main.class.getName());
            }
            report.put("running","self "+built.getName().substring(0,12));
            ui.post(new Runnable(){@Override public void run(){
                if(superseded()){finish();return;}
                try {swap.invoke(null,entry,report,new Runnable(){@Override public void run(){
                    try{keyField.set(null,built.getName());error="";}catch(Exception e){error=String.valueOf(e);}
                    queueNative();finish();
                }});}catch(Throwable e){error=String.valueOf(e);finish();}
            }});
        }catch(Throwable e){
            error=String.valueOf(e);
            try{log.getParentFile().mkdirs();Payloads.writeSmall(log,error+"\n"+b.optString("log"));}catch(Exception ignored){}
            finish();
        }
    }
    private void queueNative(){
        if(superseded()||!DebRoot.present(app)||Root.guest())return;
        nativePending.set(true);
        if(!nativeRunning.compareAndSet(false,true))return;
        new Thread(new Runnable(){@Override public void run(){
            Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
            try{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);}catch(Exception ignored){}
            try{do{
                nativePending.set(false);if(superseded())return;
                JSONObject n=new JSONObject();writeNative(n,"building");
                try{
                    File candidate=Self.buildNative(app,Self.src(app),n);
                    if(superseded())return;
                    if(candidate==null){writeNative(n,"no_sources");continue;}
                    synchronized("com.trough.hot.boot"){
                        if(superseded())return;
                        String now=Self.treeSha(new File(Self.src(app),"native"));
                        if(!now.equals(n.optString("source_identity"))){nativePending.set(true);writeNative(n,"superseded");continue;}
                        // Compare against installed files, not bundle: reverting must undo custom runtime too.
                        Root.installNative(app,candidate);
                        n.put("installed_scope","runtime launcher/mapper").put("android_jni_active",false);
                    }
                    writeNative(n,"validated");
                }catch(Throwable e){try{n.put("error",String.valueOf(e));}catch(Exception ignored){}
                    if(n.optBoolean("source_changed"))nativePending.set(true);
                    writeNative(n,n.optBoolean("source_changed")?"superseded":"failed");}
            }while(nativePending.get()&&!superseded());
            }finally{nativeRunning.set(false);if(nativePending.get()&&!superseded())queueNative();}
        }},"trough-native-validation").start();
    }
    private void writeNative(JSONObject n,String state){
        nativeState=state;if(superseded())return;
        try{n.put("state",state).put("updated_ms",System.currentTimeMillis());
            File f=new File(app.getFilesDir(),"home/.trough/native-build-state.json");f.getParentFile().mkdirs();
            File tmp=new File(f.getPath()+".tmp");Payloads.writeSmall(tmp,n.toString());
            if(!tmp.renameTo(f))throw new java.io.IOException("native state rename");
        }catch(Exception e){error=String.valueOf(e);}
    }
}
