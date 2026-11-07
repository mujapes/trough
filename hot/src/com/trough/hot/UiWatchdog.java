package com.trough.hot;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Bounded diagnostics: one outstanding heartbeat, no prompts, file IO off the UI thread. */
final class UiWatchdog implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Thread ui = Looper.getMainLooper().getThread();
    private final File file, runtime, surveillance;
    private final Thread worker;
    private final DiagnosticRecorder recorder;
    private final WindowFrameRecorder frames;
    long liveWorkUs(){return frames.liveWorkUs();}
    long liveGpuUs(){return frames.liveGpuUs();}
    long liveJankUs(){return frames.liveJankUs();}
    long liveJankAge(){return frames.liveJankAge();}
    long liveFrameUs(){return frames.liveFrameUs();}
    long liveFrameCount(){return frames.liveCount();}
    void attach(android.app.Activity activity){frames.attach(activity);}
    private final java.util.concurrent.ArrayBlockingQueue<Map<String,Object>> reports=new java.util.concurrent.ArrayBlockingQueue<Map<String,Object>>(4);
    void transportStall(Map<String,Object> health,Map<String,Object> detail){
        reports.offer(Json.map("at_ms",System.currentTimeMillis(),"issue","transport_recovery_requested","health",health,"detail",detail));
    }
    private volatile boolean closed, pending;
    private volatile long requested;
    private final Runnable heartbeat = new Runnable() { public void run() { pending = false; } };

    UiWatchdog(File home) {
        recorder = new DiagnosticRecorder(home);
        frames = new WindowFrameRecorder(home);recorder.frames(frames);
        file = new File(home, ".trough/ui-stall.json");
        runtime = new File(home, ".trough/ui-runtime.json");
        surveillance = new File(home, ".trough/freeze-surveillance.json");
        requested = SystemClock.uptimeMillis(); pending = true; main.post(heartbeat);
        worker = new Thread(new Runnable() { public void run() { monitor(); } }, "trough-ui-watchdog");
        worker.setDaemon(true); worker.setPriority(Thread.MIN_PRIORITY); worker.start();
    }

    private void monitor() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        ArrayList<Object> history = new ArrayList<Object>();
        try {
            if (file.length() > 0 && file.length() < 262144) {
                byte[] bytes = new byte[(int) file.length()];
                try (FileInputStream in = new FileInputStream(file)) {
                    int offset = 0, n; while (offset < bytes.length && (n = in.read(bytes, offset, bytes.length - offset)) > 0) offset += n;
                    Map<String,Object> previous = Json.obj(Json.parse(new String(bytes, 0, offset, "UTF-8")));
                    List<Object> events = previous == null ? null : Json.arr(previous.get("events"));
                    if (events != null) for (int i = Math.max(0, events.size() - 16); i < events.size(); i++) history.add(events.get(i));
                }
            }
        } catch (Exception ignored) {}
        
        long nextSample=0, nextContext=0;
        ArrayList<Object> incidents=new ArrayList<Object>();
        Map<String,Object> context=Json.map();
        try {
            Map<String,Object> previous=readSnapshot(surveillance);
            List<Object> saved=Json.arr(previous.get("incidents"));
            if(saved!=null)for(int i=Math.max(0,saved.size()-16);i<saved.size();i++)incidents.add(saved.get(i));
        }catch(Exception ignored){}
        while (!closed) {
            frames.drain(false);
            long now = SystemClock.uptimeMillis();
            Map<String,Object> report;
            while((report=reports.poll())!=null){incidents.add(report);while(incidents.size()>16)incidents.remove(0);nextContext=0;}
            if(now>=nextContext){
                nextContext=now+5000;
                try {
                    Map<String,Object> snapshot=readSnapshot(runtime);
                    Map<String,Object> transport=Json.obj(snapshot.get("transport"));
                    Map<String,Object> backend=transport==null?null:Json.obj(transport.get("backend_state"));
                    context=Json.map("observed_ms",snapshot.get("observed_ms"),"busy",snapshot.get("busy"),
                        "trace_metrics",snapshot.get("trace_metrics"),"live_metrics",snapshot.get("live_metrics"),"work_state",snapshot.get("work_state"),"live_reload",snapshot.get("live_reload"),
                        "work_policy",snapshot.get("work_policy"),"health",backend==null?null:backend.get("health"),
                        "last_event",backend==null?null:backend.get("last_event"),
                        "last_item_type",backend==null?null:backend.get("last_item_type"),
                        "last_event_age_ms",backend==null?null:backend.get("last_event_age_ms"),
                        "java_heap_used_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(),
                        "java_heap_committed_bytes",Runtime.getRuntime().totalMemory(),
                        "native_heap_bytes",android.os.Debug.getNativeHeapAllocatedSize());
                    Map<String,Object> health=backend==null?null:Json.obj(backend.get("health"));
                    long age=snapshot.get("observed_ms") instanceof Number?System.currentTimeMillis()-((Number)snapshot.get("observed_ms")).longValue():0;
                    String issue=age>15000?"ui_snapshot_stale":health!=null&&Boolean.TRUE.equals(health.get("stalled"))?"transport_stalled":"healthy";
                    if(!"healthy".equals(issue)){incidents.add(Json.map("at_ms",System.currentTimeMillis(),"issue",issue,"context",context));while(incidents.size()>16)incidents.remove(0);}
                    write(surveillance,Json.map("protocol","trough-freeze-surveillance-1","at_ms",System.currentTimeMillis(),"pid",android.os.Process.myPid(),"status",issue,"context",context,"incidents",incidents));
                    recorder.sample(pending ? now-requested : 0,context);
                }catch(Exception ignored){}
            }
            if (!pending) {
                requested = now; pending = true; nextSample=now+1200; main.post(heartbeat);
            } else if (now >= nextSample && now - requested >= 1200) {
                nextSample=now+5000;
                try {
                    StackTraceElement[] stack = ui.getStackTrace();
                    if (!pending || closed) continue; // The UI may have recovered during stack collection.
                    ArrayList<Object> frames = new ArrayList<Object>();
                    for (int i = 0; i < Math.min(40, stack.length); i++) frames.add(stack[i].toString());
                    Map<String,Object> event = Json.map("blocked_ms", now - requested, "at_ms", System.currentTimeMillis(),
                        "pid", android.os.Process.myPid(), "thread_state", ui.getState().toString(), "stack", frames,"context",context);
                    history.add(event); while (history.size() > 16) history.remove(0);
                    byte[] bytes = Json.write(Json.map("protocol", "trough-ui-stall-1", "latest", event, "events", history)).getBytes("UTF-8");
                    file.getParentFile().mkdirs(); File temporary = new File(file.getPath() + ".new");
                    try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(bytes); out.getFD().sync(); }
                    if (closed || !temporary.renameTo(file)) temporary.delete();
                } catch (Exception ignored) {}
            }
            try { Thread.sleep(250); } catch (InterruptedException stop) { break; }
        }
    }

    private Map<String,Object> readSnapshot(File source) throws Exception {
        if(source.length()<=0||source.length()>262144)return Json.map();
        byte[] bytes=new byte[(int)source.length()];int offset=0,n;
        try(FileInputStream in=new FileInputStream(source)){while(offset<bytes.length&&(n=in.read(bytes,offset,bytes.length-offset))>0)offset+=n;}
        Map<String,Object> value=Json.obj(Json.parse(new String(bytes,0,offset,"UTF-8")));
        return value==null?Json.map():value;
    }
    private void write(File target,Map<String,Object> value)throws Exception {
        File temporary=new File(target.getPath()+".new");
        try(FileOutputStream out=new FileOutputStream(temporary)){out.write(Json.write(value).getBytes("UTF-8"));}
        if(closed||!temporary.renameTo(target))temporary.delete();
    }

    @Override public void close() { closed = true; main.removeCallbacks(heartbeat); worker.interrupt(); frames.close(); recorder.close(); }
}
