package com.trough.hot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** One latest-value worker: editing and drawing never wait for semantic inference. */
final class VisualSteering implements AutoCloseable {
    interface Listener { void ready(Map<String,Object> material); }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        public Thread newThread(final Runnable task) {
            return new Thread(new Runnable() { public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                task.run();
            }},"Trough visual controls");
        }
    });
    private final Listener listener;
    private final NativeDogshit language;
    private SemanticSteering model;
    private Map<String,Object> pending, diagnostics=Json.map("ready",false,"state","loading");
    private long revision, applied;
    private boolean running,closed;
    private String encodedText;
    private SemanticSteering.Result encodedResult;
    private long inferenceStarted;

    static File modelFile(Context context,File bundle) {
        try {
            JSONObject m=new JSONObject(new String(Payloads.readSmall(new File(bundle,"manifest.json")),"UTF-8"));
            String sha=m.getJSONObject("payloads").optString("visual_model","");
            return sha.matches("[a-f0-9]{64}")?new File(Payloads.dir(context),sha):null;
        } catch(Exception error) { return null; }
    }
    VisualSteering(final File file,NativeDogshit language,Listener listener) {
        this.listener=listener;this.language=language;
        worker.execute(new Runnable() { public void run() {
            long start=System.nanoTime();
            try {
                if(file==null) throw new IllegalStateException("Visual model is absent");
                java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
                try(java.io.InputStream stream=new java.io.FileInputStream(file)) {
                    byte[] buffer=new byte[32768];
                    for(int n;(n=stream.read(buffer))!=-1;) digest.update(buffer,0,n);
                }
                StringBuilder hex=new StringBuilder(); for(byte value:digest.digest()) hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
                String hash=hex.toString();
                if(!hash.equals(file.getName())) throw new IllegalStateException("Visual model hash mismatch");
                SemanticSteering loaded=SemanticSteering.load(file);
                synchronized(VisualSteering.this) {
                    if(closed) return;
                    model=loaded;
                    diagnostics=Json.map("ready",true,"model",SemanticSteering.MODEL_ID,"sha256",hash,
                        "bytes",file.length(),"dimensions",loaded.dimensions(),"vocabulary",loaded.vocabularySize(),
                        "load_ns",System.nanoTime()-start,"calls",0,"worker",true);
                }
            } catch(Exception error) {
                synchronized(VisualSteering.this) { diagnostics=Json.map("ready",false,"state","unavailable","error",error.getClass().getSimpleName()); }
            }
        }});
    }
    synchronized void offer(Map<String,Object> material) {
        if(closed) return;
        pending=new LinkedHashMap<String,Object>(material); revision++;
        if(!running) schedule();
    }
    private void schedule() {
        running=true;
        long delay=Math.max(0,200-(android.os.SystemClock.elapsedRealtime()-inferenceStarted));
        worker.schedule(new Runnable() { public void run() { drain(); }},delay,TimeUnit.MILLISECONDS);
    }
    private void drain() {
        final Map<String,Object> material;
        final long captured;
        SemanticSteering engine;
        synchronized(this) {
            if(closed || pending==null) { running=false; return; }
            material=pending; pending=null; captured=revision; engine=model;
        }
        if(language!=null) material.put("dogshit",language.apply(material));
        if(engine!=null) {
            String draft=Json.str(material,"word"), prompt=Json.str(material,"prompt"),response=Json.str(material,"response");
            String text=draft!=null&&!draft.trim().isEmpty()?tail(draft,2048):
                prompt!=null&&!prompt.trim().isEmpty()?tail(prompt,2048):tail(response,2048);
            if(text.isEmpty()) text=tail(Json.str(material,"message"),512);
            text=separateLongRuns(text);
            boolean cached=text.equals(encodedText) && encodedResult!=null;
            SemanticSteering.Result result;
            if(cached) result=encodedResult;
            else {
                synchronized(this) { inferenceStarted=android.os.SystemClock.elapsedRealtime(); }
                result=engine.steer(text);
                encodedText=text; encodedResult=result;
            }
            Map<String,Object> semantic=result.state();
            semantic.remove("inference_ns");
            material.put("steering",semantic);
            synchronized(this) {
                if(closed) { running=false; return; }
                if(!cached) diagnostics.put("calls",((Number)diagnostics.get("calls")).longValue()+1);
                else diagnostics.put("cache_hits",number(diagnostics.get("cache_hits"))+1);
                diagnostics.put("inference_ns",result.inferenceNanos);
                diagnostics.put("values",result.values); diagnostics.put("tokens",result.tokenCount);
                diagnostics.put("source_revision",captured);
                diagnostics.put("minimum_interval_ms",200);
            }
        }
        main.post(new Runnable() { public void run() {
            synchronized(VisualSteering.this) {
                if(closed || captured<=applied) return;
                applied=captured;
            }
            // A completed snapshot remains useful while a newer one is queued. Rejecting every
            // non-latest result would freeze the visual surface during continuous typing.
            listener.ready(material);
        }});
        synchronized(this) {
            running=false;
            if(!closed && pending!=null) schedule();
        }
    }
    private static long number(Object value) { return value instanceof Number?((Number)value).longValue():0; }
    private static String tail(String text,int length) { return text==null?"":text.length()>length?text.substring(text.length()-length):text; }
    private static String separateLongRuns(String text) {
        // The user's continuous typing has no word spaces; keep each WordPiece run under its 100-character cap.
        StringBuilder result=new StringBuilder(text.length()); int run=0;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(Character.isLetterOrDigit(c)) { if(run==80) { result.append(' ');run=0; } run++; }
            else run=0;
            result.append(c);
        }
        return result.toString();
    }
    synchronized Map<String,Object> state() { return new LinkedHashMap<String,Object>(diagnostics); }
    @Override public synchronized void close() { closed=true; pending=null; model=null; encodedText=null; encodedResult=null; worker.shutdownNow(); }
}
