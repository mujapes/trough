package com.trough.hot;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** One persistent learned ink field. Its cellular updates never run on the UI thread. */
final class MuNcaField implements AutoCloseable {
    private static final int WIDTH=32, HEIGHT=56, CELLS=WIDTH*HEIGHT;
    private final Object lock=new Object();
    // The UI only tries this lock at handoff; it never waits for a cellular update.
    private final ReentrantLock stateLock=new ReentrantLock();
    private final View host;
    private final Bitmap bitmap=Bitmap.createBitmap(WIDTH,HEIGHT,Bitmap.Config.ARGB_8888);
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        public Thread newThread(final Runnable runnable) {
            Thread thread=new Thread(new Runnable() { public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                runnable.run();
            } },"trough-munca");
            thread.setDaemon(true); return thread;
        }
    });
    private ScheduledFuture<?> future;
    private long generation;
    private boolean visible,closed,hasImage;
    private int[] freeA=new int[CELLS],freeB=new int[CELLS],pending;
    private final float[] impulses=new float[24],workImpulses=new float[24];
    private int impulseCursor,impulseCount;
    private long lastDriftAt;
    private int driftSeed=0x19ad72f;
    private volatile long bursts,drifts,appliedImpulses;
    private MuNca model;
    private volatile Map<String,Object> pendingRestore;
    private volatile boolean restoredImage,restoredCells;
    private volatile String restoreFailure="";
    private volatile boolean ready;
    private volatile String failure="";
    private volatile float warmth=.5f,focus=.5f,depth=.5f,energy=.5f,calm=.5f,contrast=.5f,detail=.5f;
    private float smoothWarmth=.5f,smoothFocus=.5f,smoothDepth=.5f;
    private volatile long lastInputAt=SystemClock.elapsedRealtime();
    private volatile long lastStepNanos,maxStepNanos,lastPixelsNanos,lastUploadNanos,modelSteps,computedFrames,shownFrames,stateBytes;
    private volatile long nextInputStepAt;
    private volatile int intervalMillis=67;

    private final Runnable deliver=new Runnable() { public void run() {
        int[] image;
        synchronized(lock) { image=pending;pending=null; }
        if(image==null) return;
        try {
            if(!closed) {
                long started=System.nanoTime();
                bitmap.setPixels(image,0,WIDTH,0,0,WIDTH,HEIGHT);
                lastUploadNanos=System.nanoTime()-started; shownFrames++;hasImage=true;
                host.invalidate();
            }
        } finally { synchronized(lock) { release(image); } }
    } };
    private final Runnable tick=new Runnable() { public void run() { advance(); } };

    MuNcaField(View host) { this.host=host; }

    /** One copy per hot reload, using only platform arrays across class loaders. */
    Map<String,Object> handoff() {
        Map<String,Object> saved=Json.map("version",1,"width",WIDTH,"height",HEIGHT);
        if(hasImage && !closed) {
            int[] pixels=new int[CELLS];
            bitmap.getPixels(pixels,0,WIDTH,0,0,WIDTH,HEIGHT);
            saved.put("pixels",pixels);
        }
        if(stateLock.tryLock()) {
            try {
                if(pendingRestore!=null) saved.put("cells",pendingRestore);
                else if(model!=null) {
                    float[] cells=new float[CELLS*12];model.copyState(cells);
                    saved.put("cells",Json.map("field",cells,"random",model.randomState(),
                        "steps",model.steps(),"resets",model.numericalResets(),
                        "palette",new float[]{smoothWarmth,smoothFocus,smoothDepth}));
                }
            } finally { stateLock.unlock(); }
        } else return null; // Retry handoff; never replace a live field with pixels alone.
        return saved;
    }

    /** Show the last image now; a worker restores actual cells before its next advance. */
    void restore(Map<String,Object> saved) {
        if(saved==null || integer(saved.get("version"),-1)!=1
            || integer(saved.get("width"),-1)!=WIDTH || integer(saved.get("height"),-1)!=HEIGHT) return;
        Map<String,Object> cells=Json.obj(saved.get("cells"));
        if(cells!=null && (!(cells.get("field") instanceof float[])
            || ((float[])cells.get("field")).length!=CELLS*12)) cells=null;
        synchronized(lock) {
            if(closed) return;
            pendingRestore=cells;generation++;
            if(future!=null) { future.cancel(false);future=null; }
            if(pending!=null) { release(pending);pending=null; }
            host.removeCallbacks(deliver);
            if(visible) future=worker.schedule(tick,0,TimeUnit.MILLISECONDS);
        }
        Object image=saved.get("pixels");
        if(image instanceof int[] && ((int[])image).length==CELLS) {
            bitmap.setPixels((int[])image,0,WIDTH,0,0,WIDTH,HEIGHT);
            hasImage=true;restoredImage=true;host.invalidate();
        }
    }

    private static long integer(Object raw,long fallback) {
        if(!(raw instanceof Number)) return fallback;
        double value=((Number)raw).doubleValue();
        return Double.isNaN(value)||Double.isInfinite(value)||value!=Math.rint(value)?fallback:((Number)raw).longValue();
    }

    private void restoreOnWorker(Map<String,Object> saved) {
        long random=integer(saved.get("random"),Long.MIN_VALUE);
        long steps=integer(saved.get("steps"),-1),resets=integer(saved.get("resets"),-1);
        if(random<Integer.MIN_VALUE || random>Integer.MAX_VALUE || steps<0 || resets<0)
            throw new IllegalArgumentException("Invalid cellular snapshot metadata");
        model.restoreState((float[])saved.get("field"),(int)random,steps,resets);
        Object raw=saved.get("palette");
        if(raw instanceof float[] && ((float[])raw).length==3) {
            float[] palette=(float[])raw;boolean valid=true;
            for(float value:palette) if(Float.isNaN(value)||Float.isInfinite(value)||value<0||value>1) valid=false;
            if(valid) { smoothWarmth=palette[0];smoothFocus=palette[1];smoothDepth=palette[2]; }
        }
        lastDriftAt=SystemClock.elapsedRealtime();restoredCells=true;
    }

    /** Main-thread visibility controls the lifetime of the worker's scheduled work. */
    void setVisible(boolean value) {
        synchronized(lock) {
            if(closed || visible==value) return;
            visible=value;generation++;
            if(future!=null) { future.cancel(false);future=null; }
            if(visible) future=worker.schedule(tick,0,TimeUnit.MILLISECONDS);
        }
    }
    void controls(float warmth,float focus,float depth,float energy,float calm,float contrast,float detail) {
        this.warmth=warmth;this.focus=focus;this.depth=depth;this.energy=energy;this.calm=calm;
        this.contrast=contrast;this.detail=detail;
    }
    void perturb(float x,float y,float strength) {
        synchronized(lock) {
            if(closed) return;
            int index=(impulseCursor++%8)*3;
            impulses[index]=Math.max(0,Math.min(1,x));
            impulses[index+1]=Math.max(0,Math.min(1,y));
            impulses[index+2]=Math.max(0,Math.min(1,strength));
            impulseCount=Math.min(8,impulseCount+1);lastInputAt=SystemClock.elapsedRealtime();
            if(visible && future!=null && future.getDelay(TimeUnit.MILLISECONDS)>15 && future.cancel(false))
                future=worker.schedule(tick,Math.max(0,nextInputStepAt-lastInputAt),TimeUnit.MILLISECONDS);
        }
    }
    /** One prompt perturbs several learned-cell neighborhoods without clearing their history. */
    void burst(float[] xs,float[] ys,int count,float strength) {
        synchronized(lock) {
            if(closed) return;
            int size=Math.min(8,Math.min(count,Math.min(xs.length,ys.length)));
            for(int i=0;i<size;i++) {
                int index=(impulseCursor++%8)*3;
                impulses[index]=Math.max(0,Math.min(1,xs[i]));
                impulses[index+1]=Math.max(0,Math.min(1,ys[i]));
                impulses[index+2]=Math.max(0,Math.min(1,strength));
                impulseCount=Math.min(8,impulseCount+1);
                driftSeed=31*driftSeed+Float.floatToIntBits(xs[i])+Float.floatToIntBits(ys[i]);
            }
            bursts++;lastInputAt=SystemClock.elapsedRealtime();
            // Wake a quiet field; a running update will consume these on its next tick.
            if(visible && future!=null && future.getDelay(TimeUnit.MILLISECONDS)>15 && future.cancel(false))
                future=worker.schedule(tick,Math.max(0,nextInputStepAt-lastInputAt),TimeUnit.MILLISECONDS);
        }
    }
    private void advance() {
        long runGeneration;int count;int[] image;
        synchronized(lock) {
            future=null;
            if(closed || !visible) return;
            runGeneration=generation;count=impulseCount;
            for(int i=0;i<count;i++) {
                int source=Math.floorMod(impulseCursor-count+i,8)*3;
                System.arraycopy(impulses,source,workImpulses,i*3,3);
            }
            impulseCount=0;
            image=pending==null?takeBuffer():null;
        }
        long started=System.nanoTime();
        try {
            stateLock.lock();
            try {
            if(model==null) { model=new MuNca(WIDTH,HEIGHT,0x19ad72fL);stateBytes=model.stateBytes();ready=true; }
            Map<String,Object> restoring;
            synchronized(lock) { restoring=pendingRestore;pendingRestore=null; }
            if(restoring!=null) {
                try { restoreOnWorker(restoring); }
                catch(IllegalArgumentException invalid) { restoreFailure=String.valueOf(invalid.getMessage()); }
            }
            for(int i=0;i<count;i++) model.perturb(workImpulses[i*3],workImpulses[i*3+1],workImpulses[i*3+2]);
            appliedImpulses+=count;
            long now=SystemClock.elapsedRealtime();
            if(count==0 && now-lastDriftAt>1400) {
                // A bounded nudge keeps a converged field breathing at the existing worker cadence.
                driftSeed^=driftSeed<<13;driftSeed^=driftSeed>>>17;driftSeed^=driftSeed<<5;
                float x=.1f+(driftSeed&65535)/65535f*.8f;
                float y=.1f+((driftSeed>>>16)&65535)/65535f*.8f;
                model.perturb(x,y,.035f+energy*.025f+detail*.04f);lastDriftAt=now;drifts++;
            }
            long stepStarted=System.nanoTime();model.step(1);
            lastStepNanos=System.nanoTime()-stepStarted;maxStepNanos=Math.max(maxStepNanos,lastStepNanos);
            modelSteps=model.steps();
            if(image!=null) {
                smoothWarmth+=(warmth-smoothWarmth)*.12f;
                smoothFocus+=((focus*.35f+contrast*.65f)-smoothFocus)*.12f;
                smoothDepth+=(depth-smoothDepth)*.12f;
                long pixelsStarted=System.nanoTime();
                model.pixels(image,smoothWarmth,smoothFocus,smoothDepth);
                lastPixelsNanos=System.nanoTime()-pixelsStarted;computedFrames++;
                synchronized(lock) {
                    if(!closed && visible && runGeneration==generation) {
                        pending=image;image=null;
                        if(!host.post(deliver)) { release(pending);pending=null; }
                    }
                }
            }
            } finally { stateLock.unlock(); }
        } catch(RuntimeException error) {
            failure=error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage());ready=false;
        } finally {
            long elapsed=System.nanoTime()-started;
            long quietFor=SystemClock.elapsedRealtime()-lastInputAt;
            int target=quietFor>6000?200:Math.round(67+calm*20-energy*12);
            // At most 15 updates/s, and no more than roughly one sixth of a core's time.
            int minimumSpacing=Math.max(67,Math.min(1000,(int)(elapsed/1000000L)*6));
            nextInputStepAt=SystemClock.elapsedRealtime()+minimumSpacing;
            intervalMillis=Math.max(minimumSpacing,target);
            synchronized(lock) {
                if(image!=null) release(image);
                if(!closed && visible && runGeneration==generation && failure.isEmpty())
                    future=worker.schedule(tick,intervalMillis,TimeUnit.MILLISECONDS);
            }
        }
    }
    private int[] takeBuffer() {
        int[] result=freeA;
        if(result!=null) freeA=null;
        else { result=freeB;freeB=null; }
        return result;
    }
    private void release(int[] image) { if(image==null) return;if(freeA==null) freeA=image;else if(freeB==null) freeB=image; }
    void draw(Canvas canvas,RectF bounds,Paint paint) { if(hasImage&&!closed) canvas.drawBitmap(bitmap,null,bounds,paint); }
    Map<String,Object> stats() {
        return Json.map("model", "muNCA-588", "model_id",MuNca.MODEL_ID,"parameters",MuNca.PARAMETER_COUNT,
            "ready",ready,"grid",WIDTH+"x"+HEIGHT,"width",WIDTH,"height",HEIGHT,
            "state_bytes",stateBytes,"model_bytes",MuNca.QUANTIZED_BYTES,
            "weight_ram_bytes",MuNca.DEQUANTIZED_WEIGHT_BYTES,"pixel_buffer_bytes",CELLS*4*2,
            "bitmap_bytes",CELLS*4,"last_step_ns",lastStepNanos,"inference_ns",lastStepNanos,
            "max_step_ns",maxStepNanos,"pixels_ns",lastPixelsNanos,"upload_ns",lastUploadNanos,
            "steps",modelSteps,"frames",shownFrames,"computed_frames",computedFrames,
            "interval_ms",intervalMillis,"visible",visible,"error",failure,
            "restored_image",restoredImage,"restored_cells",restoredCells,"restore_error",restoreFailure,
            "controls",Json.map("warmth",warmth,"focus",focus,"depth",depth,"energy",energy,"calm",calm,"contrast",contrast,"detail",detail),
            "prompt_bursts",bursts,"applied_impulses",appliedImpulses,"drift_impulses",drifts);
    }
    @Override public void close() {
        synchronized(lock) {
            if(closed) return;closed=true;visible=false;generation++;
            if(future!=null) { future.cancel(false);future=null; }
        }
        worker.shutdownNow();host.removeCallbacks(deliver);
        bitmap.recycle();hasImage=false;
    }
}
