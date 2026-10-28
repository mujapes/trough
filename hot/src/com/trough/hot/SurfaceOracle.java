package com.trough.hot;

import android.app.Activity;
import android.graphics.*;
import android.graphics.fonts.Font;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** android.surface: the presenter end to end (docs/PRESENT.md). A scene of rectangles and
 * shaped text is presented to a SurfaceView; the presented image (copied before present) must
 * equal the verified offscreen render of the same scene byte for byte, before and after the
 * view is resized (swapchain rebuilt). Needs Trough visible. */
final class SurfaceOracle {
    private static final String[] LINES = {"Trough presents: text, shapes, layers", "مرحبا — العربية من اليمين", "नमस्ते · 中文 · Ελληνικά"};
    private static final boolean[] RTL = {false, true, false};

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
            root.addView(v, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 480));
            view[0] = v;
            return null;
        }});
        long presenter = 0;
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            int[] size = sizes.poll(10, TimeUnit.SECONDS);
            if (size == null) return Json.map("ok", false, "needs_visible_app", true, "error", "surface not created: Trough is not visible");
            byte[] atlas = new byte[TextOracle.AW * TextOracle.AH];
            float[] glyphs = layout(atlas);
            presenter = NativeCore.surfaceOpen(view[0].getHolder().getSurface(), size[0], size[1]);
            if (presenter < 0) return Json.map("ok", false, "error", "surfaceOpen " + presenter);
            boolean first = check(out, "first", presenter, size, glyphs, atlas, true);
            /* Resize: the view grows, Android reports the new size, the swapchain is rebuilt. */
            ui(activity, new Callable<Void>() { public Void call() {
                view[0].setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 760));
                return null;
            }});
            int[] grown = null;
            for (int[] s; (s = sizes.poll(5, TimeUnit.SECONDS)) != null;) { grown = s; if (s[1] != size[1]) break; }
            boolean resized = grown != null && grown[1] != size[1];
            out.put("resized_to", grown == null ? null : grown[0] + "x" + grown[1]);
            if (resized) out.put("resize_status", NativeCore.surfaceResize(presenter, grown[0], grown[1]));
            boolean second = resized && check(out, "after_resize", presenter, grown, glyphs, atlas, false);
            /* Presenting again with nothing changed is the caller's choice; measure steady cost. */
            double[] result = new double[2]; double total = 0; int n = 30;
            for (int i = 0; i < n; i++) {
                NativeCore.surfacePresent(presenter, rects(grown != null ? grown : size), glyphs, null, 0, 0, 0, 0, false, result);
                total += result[1];
            }
            out.put("present_cpu_ms_avg", total / n);
            out.put("ok", first && second);
            return out;
        } finally {
            if (presenter > 0) NativeCore.surfaceClose(presenter);
            ui(activity, new Callable<Void>() { public Void call() { if (view[0] != null) root.removeView(view[0]); return null; }});
        }
    }

    private static boolean check(Map<String, Object> out, String label, long presenter, int[] size, float[] glyphs, byte[] atlas, boolean upload) {
        double[] result = new double[2];
        float[] rects = rects(size);
        byte[] shown = NativeCore.surfacePresent(presenter, rects, glyphs, upload ? atlas : null, TextOracle.AW, TextOracle.AH,
            size[0], size[1], true, result);
        if (result[0] == 1) {                                   // swapchain out of date: rebuild once and retry
            NativeCore.surfaceResize(presenter, size[0], size[1]);
            shown = NativeCore.surfacePresent(presenter, rects, glyphs, null, 0, 0, size[0], size[1], true, result);
        }
        byte[] expected = NativeCore.sceneRender(size[0], size[1], rects, glyphs, atlas, TextOracle.AW, TextOracle.AH);
        long differ = -1;
        if (shown != null && expected != null && shown.length == expected.length) {
            differ = 0;
            for (int i = 0; i < shown.length; i++) if (shown[i] != expected[i]) differ++;
        }
        long ink = 0;
        if (shown != null) for (int i = 3; i < shown.length; i += 4) if (shown[i] != 0) ink++;
        out.put(label, Json.map("status", result[0], "size", size[0] + "x" + size[1], "differing_bytes", differ,
            "ink_pixels", ink, "present_cpu_ms", result[1]));
        return result[0] == 0 && differ == 0 && ink > 0;
    }

    /** Panel, accent bar and a few translucent layers, scaled to the surface. */
    private static float[] rects(int[] size) {
        float w = size[0], h = size[1];
        float[][] r = {
            {0, 0, w, h, 0.07f, 0.08f, 0.10f, 1f},
            {24, 24, w - 24, h - 24, 0.12f, 0.13f, 0.16f, 1f},
            {24, 24, 32, h - 24, 0.10f, 0.45f, 0.91f, 1f},
            {w * 0.6f, h * 0.55f, w - 48, h - 48, 0.2f * 0.5f, 0.6f * 0.5f, 0.3f * 0.5f, 0.5f}};
        float[] f = new float[r.length * 12];
        for (int i = 0; i < r.length; i++) {
            float[] q = {r[i][0], r[i][1], r[i][2], r[i][3], -1e6f, -1e6f, 1e6f, 1e6f, r[i][4], r[i][5], r[i][6], r[i][7]};
            System.arraycopy(q, 0, f, i * 12, 12);
        }
        return f;
    }

    /** Shapes the lines with the platform and rasterises their glyphs into atlas (white text). */
    private static float[] layout(byte[] atlas) {
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextSize(44);
        paint.setColor(0xffe8eaed);
        Map<String, int[]> cache = new HashMap<>();
        List<Float> v = new ArrayList<>();
        int[] shelf = {0, 0, 0};
        for (int i = 0; i < LINES.length; i++) {
            String t = LINES[i];
            PositionedGlyphs pg = TextRunShaper.shapeTextRun(t, 0, t.length(), 0, t.length(), 56f, 100f + i * 70, RTL[i], paint);
            for (int j = 0; j < pg.glyphCount(); j++) {
                Font font = pg.getFont(j);
                int id = pg.getGlyphId(j);
                String key = System.identityHashCode(font) + ":" + id;
                int[] m = cache.get(key);
                if (m == null) { m = TextOracle.rasterise(font, id, paint, 0xffe8eaed, atlas, shelf); cache.put(key, m); }
                if (m == null) continue;
                float x0 = Math.round(pg.getGlyphX(j)) + m[4], y0 = Math.round(pg.getGlyphY(j)) + m[5];
                float[] q = {x0, y0, x0 + m[2], y0 + m[3], m[0], m[1], 0, 0, -1e6f, -1e6f, 1e6f, 1e6f,
                    0xe8 / 255f, 0xea / 255f, 0xed / 255f, 1f};
                for (float f : q) v.add(f);
            }
        }
        float[] out = new float[v.size()];
        for (int i = 0; i < out.length; i++) out[i] = v.get(i);
        return out;
    }

    private static <T> T ui(Activity a, Callable<T> c) throws Exception {
        FutureTask<T> t = new FutureTask<>(c);
        a.runOnUiThread(t);
        return t.get(20, TimeUnit.SECONDS);
    }
}
