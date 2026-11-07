package com.trough.hot;

import android.os.Handler;
import android.os.Looper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** One ordered disk worker; neither typing nor drawing waits for fsync. */
final class InboxBridge implements AutoCloseable {
    interface Listener {
        void loaded(String draft,Map<String,Object> snapshot);
        void changed(Map<String,Object> snapshot);
        void candidate(Map<String,Object> message);
        void error(String message);
        void warning(String message);
        void modelLoaded(String encoded);
        void swipeLoaded(String encoded);
        void teaching(Map<String,Object> action,String ticket);
        void keyboardCheck(String id,String ticket);
        void transcriptCheck(String id,String ticket);
        void mediaCheck(String id,String ticket);
        void nativeTextCheck(String id,String ticket);
        void platformTextCheck(String id,String ticket);
        void makeShort(String id,String ticket);
        void presentShort(String id,String ticket);
        void workControl(String command,String id,String ticket);
    }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final AtomicInteger pending=new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean nativeTextChecking=new java.util.concurrent.atomic.AtomicBoolean();
    private final Listener listener;
    private final File runtimeFile;
    private final File heuristicFile,swipeFile,interactionActions,teachingLog;
    private final File workPolicyFile,workControlResult;
    private final File captureRequest,captureProcessing,captureImage,captureResult;
    private final View captureRoot;
    private volatile boolean capturePending;
    private MessageInbox store;
    private volatile boolean closed;
    private boolean polling;
    private long published=-1;
    private String lastRuntime="";
    private long runtimeWritten;

    InboxBridge(final File home,View root,Listener listener) {
        this(home,root,listener,false);
    }
    InboxBridge(final File home,View root,Listener listener,final boolean liveHandoff) {
        this.listener=listener;
        captureRoot=root;
        runtimeFile=new File(home,".trough/ui-runtime.json");
        heuristicFile=new File(home,".trough/heuristic.json");
        swipeFile=new File(home,".trough/swipe-learning.json");
        workPolicyFile=new File(home,".trough/work-policy.json");
        workControlResult=new File(home,".trough/work-control-result.json");
        interactionActions=new File(home,".trough/inbox-actions");
        teachingLog=new File(home,".trough/learning-events.jsonl");
        captureRequest=new File(home,".trough/ui-capture-request.json");
        captureProcessing=new File(home,".trough/ui-capture-processing.json");
        captureImage=new File(home,".trough/ui-capture.png");
        captureResult=new File(home,".trough/ui-capture-result.json");
        run(new Runnable() { public void run() {
            try {
                store=new MessageInbox(home,!liveHandoff);
                File[] interrupted=interactionActions.listFiles();
                if(interrupted!=null) for(File item:interrupted) {
                    String suffix=item.getName().endsWith(".json.controlling")?".controlling":
                        (item.getName().endsWith(".json.teaching")?".teaching":
                        (item.getName().endsWith(".json.checking")?".checking":null));
                    if(suffix==null) continue;
                    File retry=new File(item.getPath().substring(0,item.getPath().length()-suffix.length()));
                    if(!retry.exists()) item.renameTo(retry);
                }
                String memory="";
                if(heuristicFile.isFile() && heuristicFile.length()<=32768)
                    memory=new String(Payloads.readSmall(heuristicFile),"UTF-8");
                final String savedModel=memory;
                post(new Runnable() { public void run() { InboxBridge.this.listener.modelLoaded(savedModel); }});
                String swipeMemory="";
                if(swipeFile.isFile() && swipeFile.length()<=65536)
                    swipeMemory=new String(Payloads.readSmall(swipeFile),"UTF-8");
                final String savedSwipe=swipeMemory;
                post(new Runnable() { public void run() { InboxBridge.this.listener.swipeLoaded(savedSwipe); }});
                final String draft=store.draft();
                final Map<String,Object> snapshot=store.snapshot();
                published=((Number)snapshot.get("last_updated_ms")).longValue();
                post(new Runnable() { public void run() { InboxBridge.this.listener.loaded(draft,snapshot); }});
            } catch(Exception error) { fail(error); }
        }});
    }
    boolean hasPendingWrites() { return pending.get()>0; }
    void saveHeuristic(final String encoded) {
        run(new Runnable() { public void run() {
            try { writeHeuristic(encoded); }
            catch(Exception error) { fail(error); }
        }});
    }
    /** Persist the controller before advertising its new mode; callbacks also own handoff work. */
    void saveWorkPolicy(final String encoded,final Runnable saved,final Runnable failed) {
        run(new Runnable() { public void run() {
            try {
                if(encoded==null || encoded.getBytes("UTF-8").length>65536
                    || Json.obj(Json.parse(encoded))==null)
                    throw new java.io.IOException("Invalid work policy snapshot");
                writeWorkState(workPolicyFile,encoded);
                if(saved!=null) post(saved);
            } catch(Exception error) { fail(error); if(failed!=null) post(failed); }
        }});
    }
    /** An idempotent pause/resume/status claim is acknowledged only after its durable result. */
    void finishWorkControl(final String ticket,final Map<String,Object> result) {
        run(new Runnable() { public void run() {
            try {
                if(ticket==null || result==null) throw new java.io.IOException("Invalid work control result");
                File active=new File(ticket);
                if(!interactionActions.getAbsoluteFile().equals(active.getParentFile())
                    || !active.getName().matches("[0-9a-fA-F-]{36}\\.json\\.controlling"))
                    throw new java.io.IOException("Invalid work control ticket");
                String encoded=Json.write(result);
                if(encoded.getBytes("UTF-8").length>65536) throw new java.io.IOException("Work control result too large");
                writeWorkState(workControlResult,encoded);
                if(active.exists() && !active.delete()) throw new java.io.IOException("Cannot finish work control");
            } catch(Exception error) { fail(error); }
        }});
    }
    /** Payloads supplies atomic publication; explicitly sync before reporting control success. */
    private void writeWorkState(File target,String encoded) throws Exception {
        Payloads.writeSmall(target,encoded);
        try(FileOutputStream output=new FileOutputStream(target,true)) { output.getFD().sync(); }
    }
    /** The keyboard and the scene use the same ordered persistence worker and handoff guard. */
    void saveSwipeLearning(final String encoded,Map<String,Object> event) {
        final Map<String,Object> savedEvent=swipeEvent(event);
        run(new Runnable() { public void run() {
            try {
                byte[] bytes=encoded==null?new byte[0]:encoded.getBytes("UTF-8");
                if(bytes.length==0 || bytes.length>65536) throw new java.io.IOException("Invalid swipe learning snapshot");
                File temporary=new File(swipeFile.getPath()+".new");
                try(FileOutputStream output=new FileOutputStream(temporary)) {
                    output.write(bytes);output.getFD().sync();
                }
                if(!temporary.renameTo(swipeFile)) throw new java.io.IOException("Cannot save swipe learning");
                try(FileOutputStream output=new FileOutputStream(teachingLog,true)) {
                    output.write((Json.write(savedEvent)+"\n").getBytes("UTF-8"));output.getFD().sync();
                }
            } catch(Exception error) { fail(error); }
        }});
    }
    /** Keep correction evidence useful without copying a prompt or arbitrary event text into the log. */
    private static Map<String,Object> swipeEvent(Map<String,Object> event) {
        Map<String,Object> result=Json.map("model","personal-swipe-sgd","at_ms",System.currentTimeMillis());
        if(event==null) return result;
        for(String key:new String[]{"name","outcome"}) {
            Object value=event.get(key);
            if(value instanceof String && ((String)value).matches("[a-zA-Z0-9_-]{1,48}")) result.put(key,value);
        }
        Object source=event.get("source");
        if("you: swipe choice".equals(source) || "swipe-correction".equals(source)) result.put("source",source);
        for(String key:new String[]{"word","correction","original","selected","previous","from","to"}) {
            Object value=event.get(key);
            if(value instanceof String && ((String)value).matches("[a-z]{2,32}")) result.put(key,value);
        }
        for(String key:new String[]{"version","weights_version","examples","updates"}) {
            Object value=event.get(key);
            if(value instanceof Number) {
                double number=((Number)value).doubleValue();
                if(!Double.isNaN(number) && !Double.isInfinite(number) && number>=0
                    && number<=9007199254740991d && number==Math.rint(number)) result.put(key,((Number)value).longValue());
            }
        }
        return result;
    }
    private void writeHeuristic(String encoded) throws Exception {
        File temporary=new File(heuristicFile.getPath()+".new");
        try(FileOutputStream output=new FileOutputStream(temporary)) {
            output.write(encoded.getBytes("UTF-8"));output.getFD().sync();
        }
        if(!temporary.renameTo(heuristicFile)) throw new java.io.IOException("Cannot save learned model");
    }
    void finishTeaching(final String ticket,final String encoded,final Map<String,Object> result) {
        run(new Runnable() { public void run() {
            try {
                writeHeuristic(encoded);
                try(FileOutputStream output=new FileOutputStream(teachingLog,true)) {
                    output.write((Json.write(result)+"\n").getBytes("UTF-8"));output.getFD().sync();
                }
                File active=new File(ticket);
                if(active.getParentFile().equals(interactionActions)) active.delete();
            } catch(Exception error) { fail(error); }
        }});
    }
    /** A fixed native diagnostic on a separate editor; never executes arbitrary input or code. */
    void finishKeyboardCheck(final String ticket,final Map<String,Object> result) {
        run(new Runnable() { public void run() {
            try {
                Payloads.writeSmall(new File(runtimeFile.getParentFile(),"keyboard-check-result.json"),Json.write(result));
                File active=new File(ticket);
                if(active.getParentFile().equals(interactionActions)) active.delete();
            } catch(Exception error) { fail(error); }
        }});
    }
    void finishTranscriptCheck(final String ticket,final Map<String,Object> result) {
        run(new Runnable() { public void run() {
            try {
                Payloads.writeSmall(new File(runtimeFile.getParentFile(),"transcript-check-result.json"),Json.write(result));
                File active=new File(ticket);
                if(active.getParentFile().equals(interactionActions)) active.delete();
            } catch(Exception error) { fail(error); }
        }});
    }
    /** Isolated fixed diagnostic; keeps reload admission pending without blocking inbox IO. */
    void checkNativeText(final String id,final String ticket,final java.util.concurrent.Callable<String> oracle) {
        checkText(id,ticket,oracle,"native-text-check-result.json");
    }
    void checkPlatformText(final String id,final String ticket,final java.util.concurrent.Callable<String> oracle) {
        checkText(id,ticket,oracle,"platform-text-check-result.json");
    }
    private void checkText(final String id,final String ticket,final java.util.concurrent.Callable<String> oracle,final String resultFile) {
        if(closed)return;
        if(!nativeTextChecking.compareAndSet(false,true)) {
            finishTextCheck(ticket,resultFile,Json.map("id",id,"ok",false,"error","Native text check already running","at_ms",System.currentTimeMillis()));return;
        }
        pending.incrementAndGet();
        try {
        Thread worker=new Thread(new Runnable() { public void run() {
            Map<String,Object> result;
            try {
                result=Json.obj(Json.parse(oracle.call()));
                if(result==null)throw new IllegalStateException("Native text oracle returned invalid result");
            }catch(Throwable error){result=Json.map("ok",false,"error",error.toString());}
            result.put("id",id);result.put("at_ms",System.currentTimeMillis());
            try { finishTextCheck(ticket,resultFile,result); }
            finally { nativeTextChecking.set(false);pending.decrementAndGet(); }
        }},"Trough native text check");
        worker.start();
        }catch(Throwable error){nativeTextChecking.set(false);pending.decrementAndGet();finishTextCheck(ticket,resultFile,Json.map("id",id,"ok",false,"error",error.toString(),"at_ms",System.currentTimeMillis()));}
    }
    void finishNativeTextCheck(final String ticket,final Map<String,Object> result) {
        finishTextCheck(ticket,"native-text-check-result.json",result);
    }
    void finishPlatformTextCheck(final String ticket,final Map<String,Object> result) {
        finishTextCheck(ticket,"platform-text-check-result.json",result);
    }
    private void finishTextCheck(final String ticket,final String resultFile,final Map<String,Object> result) {
        run(new Runnable() { public void run() {
            try {
                Payloads.writeSmall(new File(runtimeFile.getParentFile(),resultFile),Json.write(result));
                File active=new File(ticket);
                if(active.getParentFile().equals(interactionActions))active.delete();
            }catch(Exception error){fail(error);}
        }});
    }
    void finishMediaCheck(final String ticket,final Map<String,Object> result) {
        run(new Runnable() { public void run() {
            try {
                Payloads.writeSmall(new File(runtimeFile.getParentFile(),"media-check-result.json"),Json.write(result));
                File active=new File(ticket);
                if(active.getParentFile().equals(interactionActions)) active.delete();
            } catch(Exception error) { fail(error); }
        }});
    }
    void finishShort(final String ticket,final Map<String,Object> result) {
        run(new Runnable() { public void run() {
            try {
                Payloads.writeSmall(new File(runtimeFile.getParentFile(),"short-result.json"),Json.write(result));
                File active=new File(ticket);
                if(active.getParentFile().equals(interactionActions)) active.delete();
            } catch(Exception error) { fail(error); }
        }});
    }
    void finishPresentation(final String ticket,final Map<String,Object> result) {
        run(new Runnable() { public void run() {
            try {
                Payloads.writeSmall(new File(runtimeFile.getParentFile(),"short-presentation-result.json"),Json.write(result));
                File active=new File(ticket);
                if(active.getParentFile().equals(interactionActions)) active.delete();
            } catch(Exception error) { fail(error); }
        }});
    }
    /** The cloud teacher uses the same durable action plane as inbox pickup. */
    private void applyActions() throws Exception {
        File[] files=interactionActions.listFiles();
        if(files!=null) for(File file:files) {
            if(!file.getName().matches("[0-9a-fA-F-]{36}\\.json") || file.length()>4096) continue;
            Map<String,Object> action;
            try { action=Json.obj(Json.parse(new String(Payloads.readSmall(file),"UTF-8"))); }
            catch(RuntimeException invalid) { continue; }
            String kind=Json.str(action,"action");
            if("work-control".equals(kind)) {
                final String id=Json.str(action,"id"),command=Json.str(action,"command");
                if(id==null || !id.matches("[0-9a-fA-F-]{36}")
                    || !("pause".equals(command) || "resume".equals(command) || "status".equals(command))) continue;
                File active=new File(file.getPath()+".controlling");
                if(!file.renameTo(active)) continue;
                final String ticket=active.getAbsolutePath();
                post(new Runnable() { public void run() { listener.workControl(command,id,ticket); }});
                continue;
            }
            if("keyboard-check".equals(kind)) {
                final String id=Json.str(action,"id");
                if(id==null || !id.matches("[0-9a-fA-F-]{36}")) continue;
                File active=new File(file.getPath()+".checking");
                if(!file.renameTo(active)) continue;
                final String ticket=active.getAbsolutePath();
                post(new Runnable() { public void run() { listener.keyboardCheck(id,ticket); }});
                continue;
            }
            if("transcript-check".equals(kind)) {
                final String id=Json.str(action,"id");
                if(id==null || !id.matches("[0-9a-fA-F-]{36}")) continue;
                File active=new File(file.getPath()+".checking");
                if(!file.renameTo(active)) continue;
                final String ticket=active.getAbsolutePath();
                post(new Runnable() { public void run() { listener.transcriptCheck(id,ticket); }});
                continue;
            }
            if("native-text-check".equals(kind)) {
                final String id=Json.str(action,"id");
                if(id==null || !id.matches("[0-9a-fA-F-]{36}"))continue;
                File active=new File(file.getPath()+".checking");
                if(!file.renameTo(active))continue;
                final String ticket=active.getAbsolutePath();
                post(new Runnable() { public void run() { listener.nativeTextCheck(id,ticket); }});
                continue;
            }
            if("platform-text-check".equals(kind)) {
                final String id=Json.str(action,"id");
                if(id==null || !id.matches("[0-9a-fA-F-]{36}"))continue;
                File active=new File(file.getPath()+".checking");
                if(!file.renameTo(active))continue;
                final String ticket=active.getAbsolutePath();
                post(new Runnable() { public void run() { listener.platformTextCheck(id,ticket); }});
                continue;
            }
            if("media-check".equals(kind)) {
                final String id=Json.str(action,"id");
                if(id==null || !id.matches("[0-9a-fA-F-]{36}")) continue;
                File active=new File(file.getPath()+".checking");
                if(!file.renameTo(active)) continue;
                final String ticket=active.getAbsolutePath();
                post(new Runnable() { public void run() { listener.mediaCheck(id,ticket); }});
                continue;
            }
            if("make-short".equals(kind)) {
                final String id=Json.str(action,"id");
                if(id==null || !id.matches("[0-9a-fA-F-]{36}")) continue;
                File active=new File(file.getPath()+".checking");
                if(!file.renameTo(active)) continue;
                final String ticket=active.getAbsolutePath();
                post(new Runnable() { public void run() { listener.makeShort(id,ticket); }});
                continue;
            }
            if("present-short".equals(kind)) {
                final String id=Json.str(action,"id");
                if(id==null || !id.matches("[0-9a-fA-F-]{36}")) continue;
                File active=new File(file.getPath()+".checking");
                if(!file.renameTo(active)) continue;
                final String ticket=active.getAbsolutePath();
                post(new Runnable() { public void run() { listener.presentShort(id,ticket); }});
                continue;
            }
            if(!"teach".equals(kind)) continue;
            final Map<String,Object> captured=action;
            File active=new File(file.getPath()+".teaching");
            if(!file.renameTo(active)) continue;
            final String ticket=active.getAbsolutePath();
            post(new Runnable() { public void run() { listener.teaching(captured,ticket); }});
        }
        // Both poll and transitionIf use this boundary. Quarantined invalid
        // actions must not turn a queue-claim race into a delivery failure.
        try { store().applyActions(); }
        catch(MessageInbox.ActionRejectedException rejected) { warn(rejected); }
    }
    private void run(final Runnable task) {
        if(closed) return;
        pending.incrementAndGet();
        io.execute(new Runnable() { public void run() {
            try { task.run(); }
            finally { pending.decrementAndGet(); }
        }});
    }
    private void post(final Runnable event) {
        pending.incrementAndGet();
        if(!main.post(new Runnable() { public void run() {
            try { if(!closed) event.run(); }
            finally { pending.decrementAndGet(); }
        }})) pending.decrementAndGet();
    }
    private void fail(Exception error) {
        final String message=error.getMessage()==null?error.toString():error.getMessage();
        post(new Runnable() { public void run() { listener.error(message); }});
    }
    private MessageInbox store() {
        if(store==null) throw new IllegalStateException("Message storage is unavailable; your draft is retained");
        return store;
    }
    private void publish() {
        final Map<String,Object> snapshot=store().snapshot();
        published=((Number)snapshot.get("last_updated_ms")).longValue();
        post(new Runnable() { public void run() { listener.changed(snapshot); }});
    }
    void saveDraft(final String text) {
        run(new Runnable() { public void run() {
            try { store().saveDraft(text); }
            catch(Exception error) { fail(error); }
        }});
    }
    void saveOutputDraft(final String lane,final String text){if(!("work".equals(lane)||"thought".equals(lane)))throw new IllegalArgumentException("Output lane");run(new Runnable(){public void run(){try{Payloads.writeSmall(new File(runtimeFile.getParentFile(),"output-draft-"+lane+".txt"),text);}catch(Exception error){fail(error);}}});}
    void enqueue(final String text,final String agent,final String thread,final Runnable saved,final Runnable failed) {
        run(new Runnable() { public void run() {
            try {
                store().enqueue(text,agent,thread);
            } catch(Exception error) { fail(error); post(failed); return; }
            // The durable journal owns this message now. Release the editor before
            // repainting the inbox; projection or telemetry must never strand a send.
            post(saved); publish();
        }});
    }
    void enqueueHeld(final String text,final String agent,final String thread,final String detail) {
        run(new Runnable() { public void run() {
            try { store().enqueueHeld(text,agent,thread,detail); publish(); }
            catch(Exception error) { fail(error); }
        }});
    }
    void recoverDraft(final String expected,final String agent,final String thread) {
        run(new Runnable() { public void run() {
            try {
                if(store().draft().equals(expected)) store().recoverDraftToInbox(agent,thread);
                else store().enqueueHeld(expected,agent,thread,"Recovered unsent text; held for review");
                publish();
            } catch(Exception error) { fail(error); }
        }});
    }
    /** Agent pickup actions are applied immediately before a queue claim. */
    void transitionIf(final String id,final String expected,final String state,final String detail,
            final Runnable claimed,final Runnable skipped,final Runnable failed) {
        run(new Runnable() { public void run() {
            try {
                applyActions();
                boolean changed=store().transitionIf(id,expected,state,detail);
                publish();
                Runnable result=changed?claimed:skipped;
                if(result!=null) post(result);
            } catch(Exception error) { fail(error); if(failed!=null) post(failed); }
        }});
    }
    void retry(final String id) {
        run(new Runnable() { public void run() {
            try { store().retry(id); publish(); }
            catch(Exception error) { fail(error); }
        }});
    }
    void transition(final String id,final String state,final String detail,final Runnable done) {
        transition(id,state,detail,done,null);
    }
    void transition(final String id,final String state,final String detail,final Runnable done,final Runnable failed) {
        run(new Runnable() { public void run() {
            try {
                store().transition(id,state,detail);
                publish();
                if(done!=null) post(done);
            } catch(Exception error) { fail(error); if(failed!=null) post(failed); }
        }});
    }
    void poll(final String agent,final String thread,final Map<String,Object> runtime) {
        if(closed || polling) return;
        polling=true;
        run(new Runnable() { public void run() {
            try {
                captureIfRequested();
                HeapSnapshot.captureIfRequested(runtimeFile.getParentFile());
                // Runtime diagnostics are a projection, not a prerequisite for delivery.
                try {
                    String content=Json.write(runtime);
                    long now=System.currentTimeMillis();
                    if(!content.equals(lastRuntime) || now-runtimeWritten>=5000) {
                        runtime.put("observed_ms",now);
                        Payloads.writeSmall(runtimeFile,Json.write(runtime));
                        lastRuntime=content; runtimeWritten=now;
                    }
                } catch(Exception telemetryError) { warn(telemetryError); }
                applyActions();
                final Map<String,Object> snapshot=store().snapshot();
                long revision=((Number)snapshot.get("last_updated_ms")).longValue();
                if(revision!=published) {
                    published=revision;
                    post(new Runnable() { public void run() { listener.changed(snapshot); }});
                }
                final Map<String,Object> candidate=store().next(agent,thread);
                if(candidate!=null) post(new Runnable() { public void run() { listener.candidate(candidate); }});
            } catch(Exception error) { fail(error); }
            finally { post(new Runnable() { public void run() { polling=false; }}); }
        }});
    }
    /** A retained bad local action/diagnostic is not an unresolved human send. */
    private void warn(final Exception warning) {
        final String message=warning.getMessage()==null?warning.toString():warning.getMessage();
        post(new Runnable() { public void run() { listener.warning(message); }});
    }
    /** Explicit read-only native frame capture, requested from this app's private filesystem. */
    private void captureIfRequested() {
        if(capturePending || !captureRequest.isFile() || !captureRequest.renameTo(captureProcessing)) return;
        String requested="";
        try {
            if(captureProcessing.length()>4096) throw new IllegalArgumentException("Capture request too large");
            Map<String,Object> request=Json.obj(Json.parse(new String(Payloads.readSmall(captureProcessing),"UTF-8")));
            requested=Json.str(request,"id");
            if(requested==null || !requested.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("Capture needs an id");
        } catch(Exception error) { writeCaptureResult(Json.map("id",requested,"ok",false,"error",error.toString())); return; }
        finally { captureProcessing.delete(); }
        final String id=requested;
        capturePending=true;
        post(new Runnable() { public void run() {
            try {
                if(captureRoot==null || !captureRoot.isAttachedToWindow() || captureRoot.getWidth()<1 || captureRoot.getHeight()<1)
                    throw new IllegalStateException("The native view is not attached");
                final int width=captureRoot.getWidth(),height=captureRoot.getHeight();
                float scale=Math.min(1f,Math.min(720f/width,1800f/height));
                final Bitmap bitmap=Bitmap.createBitmap(Math.max(1,Math.round(width*scale)),Math.max(1,Math.round(height*scale)),Bitmap.Config.ARGB_8888);
                int textureStart=HalfSwordPreviewLayout.softwareTextureCaptures;
                Canvas canvas=new Canvas(bitmap); canvas.scale(scale,scale); captureRoot.draw(canvas);
                int textureLayers=HalfSwordPreviewLayout.softwareTextureCaptures-textureStart;
                final Map<String,Object> result=Json.map("id",id,"ok",true,"pid",android.os.Process.myPid(),
                    "half_sword_texture_layers",textureLayers,"root_width",width,"root_height",height,"width",bitmap.getWidth(),"height",bitmap.getHeight(),
                    "window_focus",captureRoot.hasWindowFocus(),"path",captureImage.getAbsolutePath());
                InboxBridge.this.run(new Runnable() { public void run() {
                    try {
                        File temporary=new File(captureImage.getPath()+".new");
                        try(FileOutputStream output=new FileOutputStream(temporary)) {
                            if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,output)) throw new java.io.IOException("PNG capture failed");
                        }
                        if(!temporary.renameTo(captureImage)) throw new java.io.IOException("Capture rename failed");
                        result.put("saved_ms",System.currentTimeMillis()); writeCaptureResult(result);
                    } catch(Exception error) { writeCaptureResult(Json.map("id",id,"ok",false,"error",error.toString())); }
                    finally { bitmap.recycle(); capturePending=false; }
                }});
            } catch(Exception error) {
                final String problem=error.toString();
                InboxBridge.this.run(new Runnable() { public void run() { writeCaptureResult(Json.map("id",id,"ok",false,"error",problem)); capturePending=false; }});
            }
        }});
    }
    private void writeCaptureResult(Map<String,Object> result) {
        try { Payloads.writeSmall(captureResult,Json.write(result)); }
        catch(Exception ignored) {} // Capturing cannot block message storage or delivery.
    }
    @Override public void close() { closed=true; io.shutdown(); }
}
