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
            out.put("ok", ok);
            return out;
        } finally {
            if (d > 0) NativeCore.deriveClose(d);
            ui(activity, new Callable<Void>() { public Void call() { if (view[0] != null) root.removeView(view[0]); return null; }});
        }
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
