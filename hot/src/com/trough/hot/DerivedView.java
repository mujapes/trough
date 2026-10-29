package com.trough.hot;

import android.content.Context;
import android.graphics.Matrix;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.VelocityTracker;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.OverScroller;

/** The derived document view on screen (docs/VIEW.md "Touch"): a SurfaceView whose frames come
 * from the derivation graph. Touch drags and flings feed a render thread. Each frame begins
 * paced (deriveFrameBegin), then takes all scroll input that has arrived, scrolls, settles what
 * is visible and presents. With no input and no fling running, no frames are made.
 *
 * The UI thread only accumulates: a pending pixel delta and the time of the oldest event not yet
 * shown. The render thread owns the scroller and the deriver's frame calls.
 *
 * It is also the document's text editor (docs/INPUT.md "Editor view"): the IME edits the
 * paragraphs through TextInput over DocumentText; each edit marks the content dirty and wakes the
 * render thread. Selection changes reach the IME through updateSelection (after IME edits and
 * after taps), and CursorAnchorInfo after frames while the IME monitors it. */
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
    private float lastY, downX, downY;
    private boolean dragged;
    private float tapX = Float.NaN, tapY;      /* guarded by lock: a tap for the render thread */
    private final int slop;
    volatile long lastTap = -1;                /* paragraph << 32 | offset of the last tap */
    private volatile long frames, consumedDy, flingDy;
    volatile Frames observer;
    volatile int openStatus = Integer.MIN_VALUE;
    final DocumentText doc;
    private boolean contentDirty;             /* guarded by lock: an edit not yet drawn */
    private volatile boolean cursorMonitor, cursorImmediate;
    private final long[] reported = {-2, -2, -2, -2};   /* UI thread: last updateSelection */
    volatile long selectionReports, anchorReports;
    private static final int SURROUNDING = 1024;

    DerivedView(Context context, long deriver) {
        super(context);
        this.deriver = deriver;
        flingVelocity = Float.NaN;
        slop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
        setZOrderOnTop(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        doc = new DocumentText(deriver);
        getHolder().addCallback(this);
    }

    private InputMethodManager imm() { return (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE); }

    @Override public boolean onCheckIsTextEditor() { return true; }
    @Override public InputConnection onCreateInputConnection(EditorInfo out) {
        out.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        out.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_NONE;
        long[] v = doc.state();
        out.initialSelStart = (int) v[1];
        out.initialSelEnd = (int) v[2];
        int a = (int) Math.min(v[1], v[2]), b = (int) Math.max(v[1], v[2]);
        int from = Math.max(0, a - SURROUNDING), to = (int) Math.min(v[0], (long) b + SURROUNDING);
        String around = doc.slice(from, to);
        if (around != null) out.setInitialSurroundingSubText(around, from);
        System.arraycopy(new long[]{v[1], v[2], v[3], v[4]}, 0, reported, 0, 4);
        TextInput input = new TextInput(this, doc);
        input.host = new TextInput.Host() {
            @Override public void changed() { contentChanged(); reportSelection(); }
            @Override public boolean cursorUpdates(int mode) {
                cursorMonitor = (mode & InputConnection.CURSOR_UPDATE_MONITOR) != 0;
                if ((mode & InputConnection.CURSOR_UPDATE_IMMEDIATE) != 0) { cursorImmediate = true; post(new Runnable() { public void run() { reportCursor(); } }); }
                return true;
            }
        };
        return input;
    }

    /** The document changed outside the scroll path (an IME edit): draw it. */
    void contentChanged() { synchronized (lock) { contentDirty = true; lock.notifyAll(); } }

    /** UI thread: tell the IME where the selection and composing region are, if they moved. */
    void reportSelection() {
        long[] v = doc.state();
        if (v[1] == reported[0] && v[2] == reported[1] && v[3] == reported[2] && v[4] == reported[3]) return;
        reported[0] = v[1]; reported[1] = v[2]; reported[2] = v[3]; reported[3] = v[4];
        selectionReports++;
        imm().updateSelection(this, (int) v[1], (int) v[2], (int) v[3], (int) v[4]);
    }
    /** UI thread: CursorAnchorInfo for the settled frame, if the IME asked for it. */
    void reportCursor() {
        if (!cursorMonitor && !cursorImmediate) return;
        cursorImmediate = false;
        int[] at = new int[2];
        getLocationOnScreen(at);
        Matrix m = new Matrix();
        m.setTranslate(at[0], at[1]);
        CursorAnchorInfo info = doc.cursorAnchorInfo(m, getWidth(), getHeight());
        anchorReports++;
        imm().updateCursorAnchorInfo(this, info);
    }

    long frames() { return frames; }
    long consumedDy() { return consumedDy; }
    long flingDy() { return flingDy; }

    /** True when no input is pending and no fling runs. */
    boolean idle() {
        synchronized (lock) { return pendingDy == 0 && Float.isNaN(flingVelocity) && Float.isNaN(tapX) && !contentDirty && !flinging && !working; }
    }
    private volatile boolean flinging, working;

    @Override public boolean onTouchEvent(MotionEvent e) {
        long t = e.getEventTimeNanos();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (velocity == null) velocity = VelocityTracker.obtain(); else velocity.clear();
                velocity.addMovement(e);
                lastY = downY = e.getY(); downX = e.getX(); dragged = false;
                synchronized (lock) { flingVelocity = 0; lock.notifyAll(); }   /* 0: stop any fling */
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (velocity != null) velocity.addMovement(e);
                if (!dragged && Math.abs(e.getY() - downY) <= slop && Math.abs(e.getX() - downX) <= slop) return true;
                dragged = true;
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
                if (!dragged && e.getActionMasked() == MotionEvent.ACTION_UP) {   /* a tap: place the caret */
                    if (!hasFocus()) requestFocus();
                    imm().showSoftInput(this, 0);
                    synchronized (lock) { tapX = e.getX(); tapY = e.getY(); if (pendingInputNs == 0) pendingInputNs = t; lock.notifyAll(); }
                    return true;
                }
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
                while (running && pendingDy == 0 && Float.isNaN(flingVelocity) && Float.isNaN(tapX) && !contentDirty && !flinging) {
                    try { lock.wait(); } catch (InterruptedException e) { return; }
                }
                if (!running) return;
                working = true;
            }
            NativeCore.deriveFrameBegin(deriver);            /* paced: input is sampled after this */
            int dy; long inputNs; float v, tx, ty; boolean edited;
            synchronized (lock) {
                edited = contentDirty; contentDirty = false;
                tx = tapX; ty = tapY; tapX = Float.NaN;
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
            if (total == 0 && Float.isNaN(tx) && !edited) { working = false; continue; }
            if (inputNs != 0) NativeCore.deriveFrameInput(deriver, inputNs);
            if (total != 0) NativeCore.deriveScroll(deriver, total);
            if (!Float.isNaN(tx)) lastTap = NativeCore.deriveTap(deriver, tx, ty);   /* after the scroll: what is shown now */
            NativeCore.deriveSettleVisible(deriver);
            NativeCore.deriveFrame(deriver, false, timing);
            consumedDy += total; flingDy += fling; frames++;
            final boolean tapped = !Float.isNaN(tx);
            if (tapped || cursorMonitor) post(new Runnable() { public void run() { if (tapped) reportSelection(); reportCursor(); } });
            working = false;
            Frames o = observer;
            if (o != null) o.frame(frames, total);
        }
    }
}
