package com.trough.hot;

import android.app.Activity;
import android.os.*;
import android.view.*;
import java.io.*;
import java.util.Map;


/** Actual rendered frames only. Metrics callbacks and bounded compression stay off the UI thread. */
final class WindowFrameRecorder implements AutoCloseable {
    private final FrameRing ring=new FrameRing();
    private final FrameBlockCodec codec=new FrameBlockCodec();
    private final File dir;
    private final HandlerThread thread=new HandlerThread("trough-frame-metrics",android.os.Process.THREAD_PRIORITY_BACKGROUND);
    private final Handler handler;
    private Window window;
    private volatile boolean closed;
    private volatile int budgetUs=16667;
    private final long[] times=new long[128];
    private final int[] costs=new int[128],misses=new int[128];
    private long lastDrain,frames,jank,lost,totalUs,maxUs,blocks,writeNs,maxWriteNs;
    private int lastCount,lastOverBudget;
    private volatile long callbacks,callbackNs,maxCallbackNs,lastFrameUs,lastJankUs,lastJankAt,meanWorkUs,lastGpuUs;
    private final long[] workWindow=new long[32];private int workIndex,workCount;private long workSum;
    private final Window.OnFrameMetricsAvailableListener listener=new Window.OnFrameMetricsAvailableListener(){
        public void onFrameMetricsAvailable(Window w,FrameMetrics metrics,int dropped){
            if(closed)return;
            long begin=System.nanoTime();
            long cpu=Math.max(0,metrics.getMetric(FrameMetrics.ANIMATION_DURATION))+Math.max(0,metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION))+Math.max(0,metrics.getMetric(FrameMetrics.DRAW_DURATION))+Math.max(0,metrics.getMetric(FrameMetrics.SYNC_DURATION))+Math.max(0,metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION));long gpu=Build.VERSION.SDK_INT>=31?metrics.getMetric(FrameMetrics.GPU_DURATION):-1;lastGpuUs=gpu<0?-1:gpu/1000;long work=Math.max(cpu,Math.max(0,gpu))/1000;if(work>0){workSum-=workWindow[workIndex];workWindow[workIndex]=work;workSum+=work;workIndex=(workIndex+1)%workWindow.length;if(workCount<workWindow.length)workCount++;meanWorkUs=workSum/workCount;}
            long duration=metrics.getMetric(FrameMetrics.TOTAL_DURATION);lastFrameUs=duration/1000;if(lastFrameUs>budgetUs){lastJankUs=lastFrameUs;lastJankAt=SystemClock.uptimeMillis();}
            ring.offer(metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),(int)Math.min(Integer.MAX_VALUE,Math.max(0,duration/1000)),dropped);
            long elapsed=System.nanoTime()-begin;callbacks++;callbackNs+=elapsed;if(elapsed>maxCallbackNs)maxCallbackNs=elapsed;
        }
    };
    WindowFrameRecorder(File home){dir=new File(home,".trough/diagnostics");dir.mkdirs();thread.start();handler=new Handler(thread.getLooper());}
    // Called only on the UI thread, at window attach/detach boundaries.
    void attach(Activity activity){
        if(window!=null){window.removeOnFrameMetricsAvailableListener(listener);window=null;}
        if(closed||activity==null)return;
        window=activity.getWindow();
        try{float hz=window.getWindowManager().getDefaultDisplay().getRefreshRate();if(hz>=20)budgetUs=(int)(1000000/hz);}catch(Exception ignored){}
        window.addOnFrameMetricsAvailableListener(listener,handler);
    }
    synchronized void drain(boolean force){
        if(closed&&!force)return;
        long now=SystemClock.uptimeMillis();if(!force&&now-lastDrain<1000)return;lastDrain=now;
        // At most 128 records per pass; backlog is bounded, never a render-thread wait.
        int count=ring.drain(times,costs,misses);if(count==0)return;
        long begin=System.nanoTime();
        try{
            int length=codec.encode(System.currentTimeMillis(),count,ring.overwritten(),budgetUs,android.os.Process.myPid(),times,costs,misses);
            lastCount=count;lastOverBudget=0;
            for(int i=0;i<count;i++){
                frames++;totalUs+=costs[i];maxUs=Math.max(maxUs,costs[i]);lost+=misses[i];if(costs[i]>budgetUs){jank++;lastOverBudget++;}
            }
            File target=new File(dir,"frames.bin.gz");
            if(target.length()+length>262144){
                new File(dir,"frames-7.bin.gz").delete();
                for(int i=6;i>=1;i--)new File(dir,"frames-"+i+".bin.gz").renameTo(new File(dir,"frames-"+(i+1)+".bin.gz"));
                target.renameTo(new File(dir,"frames-1.bin.gz"));
            }
            try(FileOutputStream file=new FileOutputStream(target,true)){file.write(codec.bytes(),0,length);}
            blocks++;
        }catch(Exception ignored){}
        long elapsed=System.nanoTime()-begin;writeNs+=elapsed;maxWriteNs=Math.max(maxWriteNs,elapsed);
    }
    long liveWorkUs(){return meanWorkUs;}
    long liveGpuUs(){return lastGpuUs;}
    long liveJankUs(){return lastJankUs;}
    long liveJankAge(){return lastJankAt>0?SystemClock.uptimeMillis()-lastJankAt:-1;}
    long liveFrameUs(){return lastFrameUs;}
    long liveCount(){return callbacks;}
    synchronized boolean suspect(){return lastCount>0 && lastOverBudget*4>lastCount;}
    synchronized Map<String,Object> state(){return Json.map("protocol","trough-frame-ring-1","source","Window.FrameMetrics","frames_received",ring.published(),"frames_archived",frames,"overwritten",ring.overwritten(),"framework_dropped",lost,"over_budget_frames",jank,"budget_us",budgetUs,"mean_frame_us",frames==0?0:totalUs/frames,"max_frame_us",maxUs,"mean_render_work_us",meanWorkUs,"last_gpu_us",lastGpuUs,"callback_mean_ns",callbacks==0?0:callbackNs/callbacks,"callback_max_ns",maxCallbackNs,"compression_blocks",blocks,"writer_total_ns",writeNs,"writer_max_ns",maxWriteNs,"ring_bytes",98304,"inference_on_frame_thread",false);}
    @Override public void close(){
        if(window!=null){window.removeOnFrameMetricsAvailableListener(listener);window=null;}
        closed=true;handler.post(new Runnable(){public void run(){synchronized(WindowFrameRecorder.this){codec.close();}}});thread.quitSafely();
    }
}
