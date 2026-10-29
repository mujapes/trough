package com.trough.hot;

import android.content.Context;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.VelocityTracker;
import android.widget.OverScroller;

/** The derived document view on screen (docs/VIEW.md "Touch"): a SurfaceView whose frames come
 * from the derivation graph. Touch drags and flings feed a render thread. Each frame begins
 * paced (deriveFrameBegin), then takes all scroll input that has arrived, scrolls, settles what
 * is visible and presents. With no input and no fling running, no frames are made.
 *
 * The UI thread only accumulates: a pending pixel delta and the time of the oldest event not yet
 * shown. The render thread owns the scroller and the deriver's frame calls. */
final class DerivedView extends SurfaceView implements SurfaceHolder.Callback {
    interface Frames { void frame(long number, int dy); }

    final long deriver;
    private final Object lock = new Object();
    private int pendingDy;                    /* guarded by lock */
    private long pendingInputNs;              /* oldest unconsumed event, 0 = none */
    private float flingVelocity;              /* px/s, set at ACTION_UP; NaN = none */
    private boolean running, surfaceReady;
    private int width, height;
    private Thread render;
    private VelocityTracker velocity;
    private float lastY;
    private volatile long frames, consumedDy, flingDy;
    volatile Frames observer;
    volatile int openStatus = Integer.MIN_VALUE;

    DerivedView(Context context, long deriver) {
        super(context);
        this.deriver = deriver;
        flingVelocity = Float.NaN;
        setZOrderOnTop(true);
        getHolder().addCallback(this);
    }

    long frames() { return frames; }
    long consumedDy() { return consumedDy; }
    long flingDy() { return flingDy; }

    /** True when no input is pending and no fling runs. */
    boolean idle() {
        synchronized (lock) { return pendingDy == 0 && Float.isNaN(flingVelocity) && !flinging; }
    }
    private volatile boolean flinging;

    @Override public boolean onTouchEvent(MotionEvent e) {
        long t = e.getEventTimeNanos();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (velocity == null) velocity = VelocityTracker.obtain(); else velocity.clear();
                velocity.addMovement(e);
                lastY = e.getY();
                synchronized (lock) { flingVelocity = 0; lock.notifyAll(); }   /* 0: stop any fling */
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (velocity != null) velocity.addMovement(e);
                int dy = Math.round(lastY - e.getY());               /* finger up: content moves forward */
                if (dy == 0) return true;
                lastY -= dy;
                synchronized (lock) {
                    pendingDy += dy;
                    long oldest = e.getHistorySize() > 0 ? e.getHistoricalEventTimeNanos(0) : t;
                    if (pendingInputNs == 0) pendingInputNs = oldest;
                    lock.notifyAll();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (velocity != null) {
                    velocity.addMovement(e);
                    velocity.computeCurrentVelocity(1000);
                    float vy = -velocity.getYVelocity();
                    synchronized (lock) { flingVelocity = e.getActionMasked() == MotionEvent.ACTION_UP ? vy : 0; lock.notifyAll(); }
                }
                return true;
        }
        return false;
    }

    @Override public void surfaceCreated(SurfaceHolder h) {}
    @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
        synchronized (lock) {
            width = w; height = hh;
            if (render == null) {
                running = true;
                render = new Thread(new Runnable() { public void run() { renderLoop(); } }, "trough-view");
                render.start();
            }
            surfaceReady = true;
            lock.notifyAll();
        }
    }
    @Override public void surfaceDestroyed(SurfaceHolder h) { stop(); }

    void stop() {
        Thread t;
        synchronized (lock) { running = false; lock.notifyAll(); t = render; render = null; }
        if (t != null && t != Thread.currentThread()) {
            try { t.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void renderLoop() {
        int w, h;
        synchronized (lock) { w = width; h = height; }
        int st = NativeCore.deriveSurfaceOpen(deriver, getHolder().getSurface(), w, h, 1024, 1024);
        openStatus = st;
        if (st != 0) return;
        NativeCore.deriveSettleVisible(deriver);
        NativeCore.deriveFrame(deriver, false, new double[5]);   /* the first image */
        OverScroller scroller = new OverScroller(getContext());
        int flingLast = 0;
        double[] timing = new double[5];
        while (true) {
            synchronized (lock) {
                while (running && pendingDy == 0 && Float.isNaN(flingVelocity) && !flinging) {
                    try { lock.wait(); } catch (InterruptedException e) { return; }
                }
                if (!running) return;
            }
            NativeCore.deriveFrameBegin(deriver);            /* paced: input is sampled after this */
            int dy; long inputNs; float v;
            synchronized (lock) {
                dy = pendingDy; pendingDy = 0;
                inputNs = pendingInputNs; pendingInputNs = 0;
                v = flingVelocity; flingVelocity = Float.NaN;
            }
            if (!Float.isNaN(v)) {
                scroller.forceFinished(true);
                flingLast = 0;
                if (Math.abs(v) > 50) {
                    scroller.fling(0, 0, 0, Math.round(v), 0, 0, -1_000_000, 1_000_000);
                    flinging = true;
                } else flinging = false;
            }
            int fling = 0;
            if (flinging) {
                if (scroller.computeScrollOffset()) { int y = scroller.getCurrY(); fling = y - flingLast; flingLast = y; }
                else flinging = false;
            }
            int total = dy + fling;
            if (total == 0) continue;
            if (inputNs != 0) NativeCore.deriveFrameInput(deriver, inputNs);
            NativeCore.deriveScroll(deriver, total);
            NativeCore.deriveSettleVisible(deriver);
            NativeCore.deriveFrame(deriver, false, timing);
            consumedDy += total; flingDy += fling; frames++;
            Frames o = observer;
            if (o != null) o.frame(frames, total);
        }
    }
}
