package com.trough.hot;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Background flight recorder; each sample is a finished gzip member, surviving abrupt exit. */
final class DiagnosticRecorder implements AutoCloseable, Thread.UncaughtExceptionHandler {
    private final File dir, runtime;
    private final Thread.UncaughtExceptionHandler previous;
    private final String session = android.os.Process.myPid()+"-"+System.currentTimeMillis();
    private boolean closed;
    private long sequence;
    private long lastCheckpoint, lastIncident;
    private WindowFrameRecorder frames;
    void frames(WindowFrameRecorder value){frames=value;}
    DiagnosticRecorder(File home) {
        dir=new File(home,".trough/diagnostics");dir.mkdirs();
        runtime=new File(home,".trough/ui-runtime.json");
        previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);
    }
    synchronized void sample(long heartbeatAge, Map<String,Object> context) {
        if(closed)return;
        long now=android.os.SystemClock.uptimeMillis();
        boolean incident=(heartbeatAge>=1200 || frames!=null&&frames.suspect()) && now-lastIncident>=60000;
        if(sequence>0 && !incident && now-lastCheckpoint<60000)return;
        lastCheckpoint=now;if(incident)lastIncident=now;
        try {
            Map<String,Object> event=base(sequence++==0?"start":incident?heartbeatAge>=1200?"ui_stall":"frame_budget_incident":"sample");
            event.put("heartbeat_pending_ms",heartbeatAge);
            event.put("context",context);
            if(frames!=null)event.put("frames",frames.state());
            event.put("java_heap_used_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
            event.put("java_heap_limit_bytes",Runtime.getRuntime().maxMemory());
            event.put("native_heap_bytes",android.os.Debug.getNativeHeapAllocatedSize());
            if(heartbeatAge>=1200)event.put("main_stack",stack(android.os.Looper.getMainLooper().getThread()));
            save(event,false);
        }catch(Throwable ignored){}
    }
    private Map<String,Object> base(String event){return Json.map("protocol","trough-diagnostics-1","event",event,"at_ms",System.currentTimeMillis(),"uptime_ms",android.os.SystemClock.uptimeMillis(),"pid",android.os.Process.myPid(),"session",session);}
    private Object snapshot(File source)throws Exception {
        String text=read(source,262144);
        try{return Json.parse(text);}catch(Exception invalid){return Json.map("unavailable",true);}
    }
    private String read(File file,int limit)throws Exception {
        if(!file.isFile())return "";
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(InputStream in=new FileInputStream(file)){byte[] b=new byte[4096];int n;while(bytes.size()<limit&&(n=in.read(b,0,Math.min(b.length,limit-bytes.size())))>0)bytes.write(b,0,n);}
        return bytes.toString("UTF-8");
    }
    private List<Object> stack(Thread thread){List<Object> frames=new ArrayList<Object>();StackTraceElement[] trace=thread.getStackTrace();for(int i=0;i<Math.min(48,trace.length);i++)frames.add(trace[i].toString());return frames;}
    private void atomic(File target,byte[] bytes,boolean durable)throws Exception {
        File temp=new File(target.getPath()+".tmp");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(bytes);if(durable)out.getFD().sync();}
        if(!temp.renameTo(target))throw new IOException("Cannot publish "+target.getName());
    }
    private void save(Map<String,Object> event,boolean fatal)throws Exception {
        byte[] json=(Json.write(event)+"\n").getBytes("UTF-8");
        ByteArrayOutputStream packed=new ByteArrayOutputStream();
        try(GZIPOutputStream gzip=new GZIPOutputStream(packed)){gzip.write(json);}
        File current=new File(dir,"history.jsonl.gz");
        // Bounded segments; routine checkpoints do not force storage synchronization.
        if(current.length()+packed.size()>1048576 || sequence%60==0){
            new File(dir,"history-11.jsonl.gz").delete();
            for(int i=10;i>=1;i--)new File(dir,"history-"+i+".jsonl.gz").renameTo(new File(dir,"history-"+(i+1)+".jsonl.gz"));
            current.renameTo(new File(dir,"history-1.jsonl.gz"));
        }
        try(FileOutputStream out=new FileOutputStream(current,true)){out.write(packed.toByteArray());if(fatal)out.getFD().sync();}
        atomic(new File(dir,"latest.json"),json,fatal);
        if(fatal)atomic(new File(dir,"last-crash.json.gz"),packed.toByteArray(),true);
    }
    @Override public void uncaughtException(Thread thread,Throwable error) {
        try {
            synchronized(this){
                Map<String,Object> event=base("uncaught_exception");
                event.put("thread",thread.getName());
                StringWriter trace=new StringWriter();error.printStackTrace(new PrintWriter(trace));
                String text=trace.toString();event.put("exception",text.substring(0,Math.min(text.length(),65536)));
                event.put("runtime",snapshot(runtime));
                if(frames!=null){frames.drain(true);event.put("frames",frames.state());}
                event.put("main_stack",stack(android.os.Looper.getMainLooper().getThread()));
                save(event,true);
            }
        }catch(Throwable ignored){}
        finally{
            if(previous!=null)previous.uncaughtException(thread,error);
            else{android.os.Process.killProcess(android.os.Process.myPid());System.exit(10);}
        }
    }
    @Override public synchronized void close(){
        closed=true;
        if(Thread.getDefaultUncaughtExceptionHandler()==this)Thread.setDefaultUncaughtExceptionHandler(previous);
    }
}
