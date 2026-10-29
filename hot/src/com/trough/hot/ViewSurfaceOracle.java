package com.trough.hot;

import android.app.Activity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/** android.viewsurface: the derived view presented on screen (docs/VIEW.md). The view's frames
 * (scene, mask nodes, fenced atlas, frame ring) go to a SurfaceView's swapchain. Five frames
 * keep the presented image, which must equal the platform drawing the same scene glyphs; then
 * 120 frames scroll without readback, several in flight, to measure pacing. Needs Trough visible. */
final class ViewSurfaceOracle {
    static Map<String, Object> run(final Activity activity, final FrameLayout root, File bundle) throws Exception {
        NativeCore.load(bundle);
        final LinkedBlockingQueue<int[]> sizes = new LinkedBlockingQueue<>();
        final SurfaceView[] view = new SurfaceView[1];
        ui(activity, new Callable<Void>() { public Void call() {
            SurfaceView v = new SurfaceView(activity);
            v.setZOrderOnTop(true);
            v.getHolder().addCallback(new SurfaceHolder.Callback() {
                public void surfaceCreated(SurfaceHolder h) {}
                public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { sizes.add(new int[]{w, hh}); }
                public void surfaceDestroyed(SurfaceHolder h) {}
            });
            root.addView(v, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewOracle.VIEW_H));
            view[0] = v;
            return null;
        }});
        long d = -1;
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            int[] size = sizes.poll(10, TimeUnit.SECONDS);
            if (size == null) return Json.map("ok", false, "needs_visible_app", true, "error", "surface not created: Trough is not visible");
            int w = size[0], h = size[1];
            List<String> corpus = LayoutDeriveOracle.corpus(600);
            d = NativeCore.deriveOpen(2, corpus.size());
            NativeCore.deriveWidth(d, ViewOracle.WIDTH);
            for (int i = 0; i < corpus.size(); i++) NativeCore.deriveText(d, i, corpus.get(i));
            int anchor = NativeCore.deriveJump(d, 500);
            NativeCore.deriveViewport(d, anchor, 0, h);
            NativeCore.deriveDemandScene(d, true);
            NativeCore.deriveSettle(d);
            int open = NativeCore.deriveSurfaceOpen(d, view[0].getHolder().getSurface(), w, h, 1024, 1024);
            out.put("size", w + "x" + h);
            out.put("surface_open", open);
            if (open != 0) { out.put("ok", false); return out; }
            boolean ok = true;
            List<Object> frames = new ArrayList<>();
            int[] anchors = {anchor, anchor + 3, anchor + 10, anchor + 30, anchor};
            for (int a : anchors) {
                NativeCore.deriveViewport(d, a, 0, h);
                NativeCore.deriveSettle(d);
                double[] timing = new double[5];
                byte[] shown = NativeCore.deriveFrame(d, true, timing);
                byte[] scene = NativeCore.deriveBytes(d, -1);
                Map<String, Object> cmp = shown == null ? Json.map("ok", false, "error", "no frame", "status", timing[4])
                    : compare(shown, reference(scene, w, h), w, h);
                frames.add(Json.map("anchor", a, "build_ms", timing[1], "present_wait_ms", timing[2], "glyphs", (long) timing[3],
                    "uploads", (long) timing[4], "bad_pixels", cmp.get("bad_pixels"), "max_channel_diff", cmp.get("max_channel_diff")));
                ok &= Boolean.TRUE.equals(cmp.get("ok"));
            }
            out.put("frames", frames);
            /* Pacing: scroll 12 px per frame, present without readback, frames in flight. */
            NativeCore.deriveViewport(d, anchor, 0, h);
            NativeCore.deriveSettle(d);
            int n = 120; double build = 0, worst = 0; int failed = 0;
            long t0 = System.nanoTime();
            for (int k = 0; k < n; k++) {
                NativeCore.deriveScroll(d, 12);
                NativeCore.deriveSettle(d);
                double[] timing = new double[5];
                long f0 = System.nanoTime();
                NativeCore.deriveFrame(d, false, timing);
                double ms = (System.nanoTime() - f0) / 1e6;
                if (timing[4] < 0) failed++;
                build += ms; worst = Math.max(worst, ms);
            }
            double total = (System.nanoTime() - t0) / 1e6;
            out.put("pacing", Json.map("frames", n, "total_ms", total, "per_frame_ms", total / n, "frame_call_avg_ms", build / n,
                "frame_call_worst_ms", worst, "failed_presents", failed));
            ok &= failed == 0;
            /* Flings: 80 px per frame for 120 frames, without and then with background prefetch.
             * Each frame scrolls, settles what is visible and presents; a frame interval over
             * 20 ms is a missed vsync at 60 Hz. */
            for (int pf = 0; pf < 2; pf++) {
                NativeCore.deriveViewport(d, anchor + 40 + 120 * pf, 0, h);   /* fresh paragraphs each run */
                NativeCore.derivePrefetch(d, pf == 1 ? 8 : 0, pf == 1 ? 32 : 0);
                NativeCore.deriveSettle(d);
                long[] s0 = NativeCore.deriveStats(d);
                NativeCore.derivePresentTimes(d);                 /* drop earlier frames' timing */
                double scrollSum = 0, scrollWorst = 0; int missed = 0, fails = 0;
                List<Object> misses = new ArrayList<>();
                long last = System.nanoTime(), f0 = last;
                for (int k = 0; k < n; k++) {
                    long a0 = System.nanoTime();
                    NativeCore.deriveScroll(d, 80);
                    NativeCore.deriveSettleVisible(d);
                    double sm = (System.nanoTime() - a0) / 1e6;
                    scrollSum += sm; scrollWorst = Math.max(scrollWorst, sm);
                    double[] timing = new double[5];
                    NativeCore.deriveFrame(d, false, timing);
                    if (timing[4] < 0) fails++;
                    long now = System.nanoTime();
                    if (k > 0 && (now - last) / 1e6 > 20) {
                        missed++;
                        if (misses.size() < 8) misses.add(Json.map("frame", k, "interval_ms", (now - last) / 1e6, "scroll_settle_ms", sm,
                            "build_ms", timing[1], "present_wait_ms", timing[2], "uploads", (long) timing[4]));
                    }
                    last = now;
                }
                long[] s1 = NativeCore.deriveStats(d);
                Thread.sleep(100);                                /* the last frames reach the display */
                Map<String, Object> display = displayTiming(NativeCore.derivePresentTimes(d));
                out.put(pf == 1 ? "fling_prefetch_display" : "fling_display", display);
                out.put(pf == 1 ? "fling_prefetch" : "fling", Json.map("frames", n, "per_frame_ms", (System.nanoTime() - f0) / 1e6 / n,
                    "scroll_settle_avg_ms", scrollSum / n, "scroll_settle_worst_ms", scrollWorst, "missed_frames", missed,
                    "pauses", s1[20] - s0[20], "layout_runs", s1[11] - s0[11], "failed_presents", fails, "misses", misses));
                ok &= fails == 0;
            }
            NativeCore.derivePrefetch(d, 0, 0);
            out.put("ok", ok);
            return out;
        } finally {
            if (d > 0) NativeCore.deriveClose(d);
            ui(activity, new Callable<Void>() { public Void call() { if (view[0] != null) root.removeView(view[0]); return null; }});
        }
    }

    /** Display timing (VK_GOOGLE_display_timing): the intervals between frames actually shown, in
     * refresh periods. An interval of 2 or more periods is a vsync where no new frame was shown. */
    static Map<String, Object> displayTiming(long[] t) {
        if (t == null || t.length < 1 + 5 * 2) return Json.map("available", false, "entries", t == null ? 0 : (t.length - 1) / 5);
        long refresh = t[0]; int n = (t.length - 1) / 5;
        int[] hist = new int[5]; int skipped = 0; long worst = 0; double marginSum = 0;
        for (int i = 1; i < n; i++) {
            long gap = t[1 + 5 * i + 2] - t[1 + 5 * (i - 1) + 2];
            worst = Math.max(worst, gap);
            int periods = refresh > 0 ? (int) Math.round((double) gap / refresh) : 0;
            hist[Math.min(Math.max(periods, 0), 4)]++;
            if (periods >= 2) skipped += periods - 1;
        }
        for (int i = 0; i < n; i++) marginSum += t[1 + 5 * i + 4];
        return Json.map("available", true, "refresh_ms", refresh / 1e6, "frames", n, "periods_hist", Arrays.toString(hist),
            "skipped_vsyncs", skipped, "worst_interval_ms", worst / 1e6, "avg_margin_ms", marginSum / n / 1e6,
            "first_frame", t[1], "last_frame", t[1 + 5 * (n - 1)]);
    }

    static byte[] reference(byte[] scene, int w, int h) {
        ByteBuffer b = ByteBuffer.wrap(scene);
        int count = b.getInt();
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setAntiAlias(true);
        paint.setTextSize(DeriveLayout.TEXT_SIZE);
        paint.setColor(android.graphics.Color.BLACK);
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bm);
        for (int k = 0; k < count; k++) {
            int face = b.getInt(), id = b.getInt();
            float fx = b.getFloat(), fy = b.getFloat();
            c.drawGlyphs(new int[]{id}, 0, new float[]{Math.round(fx), Math.round(fy)}, 0, 1, DeriveLayout.face(face), paint);
        }
        ByteBuffer rb = ByteBuffer.allocate(w * h * 4);
        bm.copyPixelsToBuffer(rb);
        bm.recycle();
        return rb.array();
    }
    static Map<String, Object> compare(byte[] ours, byte[] ref, int w, int h) {
        long bad = 0, ink = 0; int max = 0;
        for (int p = 0; p < w * h; p++) {
            int worst = 0;
            for (int ch = 0; ch < 4; ch++) worst = Math.max(worst, Math.abs((ours[p * 4 + ch] & 255) - (ref[p * 4 + ch] & 255)));
            if ((ref[p * 4 + 3] & 255) != 0) ink++;
            max = Math.max(max, worst);
            if (worst > 3) bad++;
        }
        return Json.map("ok", bad == 0 && ink > 0, "bad_pixels", bad, "max_channel_diff", max);
    }
    private static <T> T ui(Activity a, Callable<T> c) throws Exception {
        FutureTask<T> t = new FutureTask<>(c);
        a.runOnUiThread(t);
        return t.get(20, TimeUnit.SECONDS);
    }
}
