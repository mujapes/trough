package com.trough.hot;

import android.app.Activity;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** android.touch: touch scrolling of the derived view (docs/VIEW.md "Touch"). Real MotionEvents
 * go through DerivedView.dispatchTouchEvent: a drag of 30 moves 8 ms apart, released with
 * velocity, so the view flings. The render thread paces frames (platform deadlines) with
 * prefetch on. Checks: the final position equals an exact-height walk of every pixel the view
 * consumed, the scene equals the reference, the view makes no frames once idle, and
 * touch-to-display latency from each frame's oldest event to its actual presentation.
 * Needs Trough visible. */
final class TouchOracle {
    static Map<String, Object> run(final Activity activity, final FrameLayout root, File bundle) throws Exception {
        NativeCore.load(bundle);
        List<String> corpus = LayoutDeriveOracle.corpus(600);
        long d = NativeCore.deriveOpen(2, corpus.size());
        if (d < 0) return Json.map("ok", false, "error", "deriveOpen " + d);
        final DerivedView[] view = new DerivedView[1];
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> failures = new ArrayList<>();
        try {
            NativeCore.deriveWidth(d, ViewOracle.WIDTH);
            for (int i = 0; i < corpus.size(); i++) NativeCore.deriveText(d, i, corpus.get(i));
            int anchor = NativeCore.deriveJump(d, 300);
            NativeCore.deriveViewport(d, anchor, 0, ViewOracle.VIEW_H);
            NativeCore.deriveDemandScene(d, true);
            NativeCore.derivePrefetch(d, 8, 32);
            int mode = NativeCore.derivePace(d, 2);
            NativeCore.deriveSettle(d);
            final long dd = d;
            ui(activity, new Callable<Void>() { public Void call() {
                DerivedView v = new DerivedView(activity, dd);
                root.addView(v, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewOracle.VIEW_H));
                view[0] = v;
                return null;
            }});
            DerivedView v = view[0];
            long deadline = SystemClock.uptimeMillis() + 10000;
            while (v.openStatus == Integer.MIN_VALUE && SystemClock.uptimeMillis() < deadline) Thread.sleep(20);
            if (v.openStatus == Integer.MIN_VALUE) return Json.map("ok", false, "needs_visible_app", true, "error", "surface not created: Trough is not visible");
            out.put("surface_open", v.openStatus);
            out.put("pace_mode", mode);
            if (v.openStatus != 0) { out.put("ok", false); return out; }
            Thread.sleep(200);
            NativeCore.derivePresentTimes(d);
            long frames0 = v.frames();

            /* The drag: finger up the screen 30 x 24 px, 8 ms apart, then released. */
            final long down = SystemClock.uptimeMillis();
            float y = 1300;
            dispatch(activity, v, MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 540, y, 0));
            for (int k = 1; k <= 30; k++) {
                Thread.sleep(8);
                y -= 24;
                long t = SystemClock.uptimeMillis();
                dispatch(activity, v, MotionEvent.obtain(down, t, MotionEvent.ACTION_MOVE, 540, y, 0));
            }
            long up = SystemClock.uptimeMillis();
            dispatch(activity, v, MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, 540, y, 0));
            long dragDone = System.nanoTime();
            deadline = SystemClock.uptimeMillis() + 15000;
            while (!v.idle() && SystemClock.uptimeMillis() < deadline) Thread.sleep(10);
            double flingMs = (System.nanoTime() - dragDone) / 1e6;
            Thread.sleep(150);
            long framesAtRest = v.frames();
            Thread.sleep(300);
            long idleFrames = v.frames() - framesAtRest;
            Map<String, Object> display = ViewSurfaceOracle.displayTiming(NativeCore.derivePresentTimes(d));
            out.put("display", display);

            /* Where the view must be: every consumed pixel walked over exact heights. */
            int consumed = (int) v.consumedDy();
            int[] want = ViewOracle.scrollReference(corpus, anchor, 0, consumed);
            byte[] got = NativeCore.deriveBytes(d, -1);
            long[] st = NativeCore.deriveStats(d);
            boolean equal = got != null && Arrays.equals(got, ViewOracle.reference(corpus, want[0], want[1]));
            out.put("drag_px", 30 * 24);
            out.put("consumed_px", consumed);
            out.put("fling_px", v.flingDy());
            out.put("fling_ms", flingMs);
            out.put("frames", v.frames() - frames0);
            out.put("idle_frames", idleFrames);
            out.put("expected", Arrays.asList(want[0], want[1]));
            out.put("scene_equal", equal);
            out.put("pauses", st[20]);
            out.put("pace_misses", st[23]);
            if (!equal) failures.add(Json.map("scene", "differs", "expected", Arrays.asList(want[0], want[1])));
            if (v.flingDy() <= 0) failures.add(Json.map("fling", v.flingDy()));
            if (consumed - v.flingDy() != 30 * 24) failures.add(Json.map("drag_consumed", consumed - v.flingDy()));
            if (idleFrames != 0) failures.add(Json.map("idle_frames", idleFrames));
            if (st[3] != 0) failures.add(Json.map("error", st[3]));

            /* A tap (down and up in place) places the caret where StaticLayout says, at the
             * scrolled position, and costs no layout or scene work. */
            float tx = 333, ty = 700;
            long[] t0 = NativeCore.deriveStats(d);
            long td = SystemClock.uptimeMillis();
            dispatch(activity, v, MotionEvent.obtain(td, td, MotionEvent.ACTION_DOWN, tx, ty, 0));
            Thread.sleep(40);
            long tu = SystemClock.uptimeMillis();
            dispatch(activity, v, MotionEvent.obtain(td, tu, MotionEvent.ACTION_UP, tx, ty, 0));
            deadline = SystemClock.uptimeMillis() + 5000;
            while ((v.lastTap < 0 || !v.idle()) && SystemClock.uptimeMillis() < deadline) Thread.sleep(10);
            long[] t1 = NativeCore.deriveStats(d);
            int[] tapWant = CaretOracle.tapReference(corpus, want[0], tx, ty + want[1]);
            long tap = v.lastTap;
            boolean tapOk = tap >= 0 && (int) (tap >> 32) == tapWant[0] && (int) (tap & 0xffffffffL) == tapWant[1];
            byte[] ov = NativeCore.deriveBytes(d, -2);
            int rects = ov == null ? -1 : java.nio.ByteBuffer.wrap(ov).getInt();
            out.put("tap", Json.map("got", Arrays.asList((int) (tap >> 32), (int) (tap & 0xffffffffL)), "want", Arrays.asList(tapWant[0], tapWant[1]),
                "overlay_rects", rects, "layout_runs", t1[11] - t0[11], "scene_runs", t1[13] - t0[13], "consumed_after", v.consumedDy() - consumed));
            if (!tapOk || rects != 1 || t1[11] != t0[11] || t1[13] != t0[13] || v.consumedDy() != consumed)
                failures.add(Json.map("tap", out.get("tap")));
        } finally {
            if (view[0] != null) view[0].stop();          /* the render thread ends before the deriver */
            NativeCore.deriveClose(d);
            if (view[0] != null) ui(activity, new Callable<Void>() { public Void call() { root.removeView(view[0]); return null; }});
        }
        out.put("failures", failures);
        out.put("ok", failures.isEmpty());
        return out;
    }

    private static void dispatch(Activity a, final DerivedView v, final MotionEvent e) throws Exception {
        ui(a, new Callable<Void>() { public Void call() { v.dispatchTouchEvent(e); e.recycle(); return null; }});
    }
    private static <T> T ui(Activity a, Callable<T> c) throws Exception {
        FutureTask<T> t = new FutureTask<>(c);
        a.runOnUiThread(t);
        return t.get(20, TimeUnit.SECONDS);
    }
}
