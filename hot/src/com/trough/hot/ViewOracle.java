package com.trough.hot;

import android.graphics.*;
import android.graphics.fonts.Font;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.*;

/** android.view: a viewport over many paragraphs, derived (docs/VIEW.md). Layout nodes feed a
 * tree of height summaries; the scene node walks it in order and emits the glyphs visible in the
 * viewport. The scene's bytes must equal a flat Java walk over directly computed layouts; edits
 * must cost only what they touch (nothing below the viewport; no scene rerun when a height is
 * unchanged); and the GPU drawing the derived scene must match the platform drawing the same
 * glyphs. Offscreen. */
final class ViewOracle {
    static final int WIDTH = 1000, VIEW_W = 1080, VIEW_H = 1400;

    static Map<String, Object> run(File bundle) {
        NativeCore.load(bundle);
        List<String> corpus = LayoutDeriveOracle.corpus(600);
        int n = corpus.size();
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> failures = new ArrayList<>();
        long d = NativeCore.deriveOpen(2, n);                 /* platform text scales to ~2 workers */
        if (d < 0) return Json.map("ok", false, "error", "deriveOpen " + d);
        boolean ok = true;
        try {
            NativeCore.deriveWidth(d, WIDTH);
            for (int i = 0; i < n; i++) NativeCore.deriveText(d, i, corpus.get(i));
            NativeCore.deriveViewport(d, 0, VIEW_H);
            List<Object> steps = new ArrayList<>();
            long[] before = NativeCore.deriveStats(d);       /* before the demand: work starts at once */
            long t0 = System.nanoTime();
            NativeCore.deriveDemandScene(d, true);
            NativeCore.deriveSettle(d);
            long[] tr = NativeCore.deriveTrace(d);
            List<Object> layoutJobs = new ArrayList<>();
            for (int k = 0; k < tr.length; k += 4) if (tr[k] == 0) layoutJobs.add(tr[k + 1] + ":" + tr[k + 2]);
            out.put("top_layout_jobs", layoutJobs);          /* key:outcome (1 value, 2 need, 3 cancelled, 4 stale) */
            out.put("visible_at_start", visibleCount(corpus, 0));
            ok &= step(d, corpus, 0, "top", before, t0, steps, failures, 2L * visibleCount(corpus, 0), 0, -1);
            int middle = documentHeight(corpus) / 2;
            before = NativeCore.deriveStats(d); t0 = System.nanoTime();
            NativeCore.deriveViewport(d, middle, VIEW_H);
            NativeCore.deriveSettle(d);
            ok &= step(d, corpus, middle, "scroll to middle", before, t0, steps, failures, -1, -1, -1);
            /* An edit far below the viewport: nothing runs. */
            int below = n - 3;
            corpus.set(below, corpus.get(below) + " below");
            before = NativeCore.deriveStats(d); t0 = System.nanoTime();
            NativeCore.deriveText(d, below, corpus.get(below));
            NativeCore.deriveSettle(d);
            ok &= step(d, corpus, middle, "edit below viewport", before, t0, steps, failures, 0, 0, 0);
            /* An edit inside the viewport: its layout, its summary chain, the scene. */
            int visible = firstVisible(corpus, middle) + 1;
            corpus.set(visible, corpus.get(visible) + " seen");
            before = NativeCore.deriveStats(d); t0 = System.nanoTime();
            NativeCore.deriveText(d, visible, corpus.get(visible));
            NativeCore.deriveSettle(d);
            ok &= step(d, corpus, middle, "edit in viewport", before, t0, steps, failures, 1, -1, -1);
            /* An edit above the viewport that keeps the paragraph's height: layout and its
             * level-0 summary rerun, the summary value is equal, the scene does not rerun. */
            int above = 5;
            String kept = corpus.get(above).replaceFirst("fox", "dog");
            if (kept.equals(corpus.get(above))) kept = corpus.get(above).replaceFirst(" ", "  ");
            boolean sameHeight = height(DeriveLayout.layout(kept, WIDTH)) == height(DeriveLayout.layout(corpus.get(above), WIDTH));
            corpus.set(above, kept);
            before = NativeCore.deriveStats(d); t0 = System.nanoTime();
            NativeCore.deriveText(d, above, kept);
            NativeCore.deriveSettle(d);
            ok &= step(d, corpus, middle, "edit above, same height", before, t0, steps, failures, 1, 1, sameHeight ? 0 : -1);
            /* An edit above the viewport that adds lines: everything visible moves. */
            corpus.set(above + 1, corpus.get(above + 1) + " " + corpus.get(above + 2));
            before = NativeCore.deriveStats(d); t0 = System.nanoTime();
            NativeCore.deriveText(d, above + 1, corpus.get(above + 1));
            NativeCore.deriveSettle(d);
            ok &= step(d, corpus, middle, "edit above, taller", before, t0, steps, failures, 1, -1, -1);
            out.put("same_height_case", sameHeight);
            List<Integer> heads = new ArrayList<>();
            for (int i = 0; i < 12; i++) heads.add(height(DeriveLayout.layout(corpus.get(i), WIDTH)));
            out.put("first_heights", heads);
            out.put("visible_at_top", visibleCount(corpus, 0));
            out.put("steps", steps);
            Map<String, Object> pixels = pixels(NativeCore.deriveBytes(d, -1));
            out.put("pixels", pixels);
            ok &= Boolean.TRUE.equals(pixels.get("ok"));
            /* The derived frame: mask nodes, atlas residency and the frame ring, scrolling. */
            int fo = NativeCore.deriveFrameOpen(d, VIEW_W, VIEW_H, 1024, 1024);
            List<Object> frames = new ArrayList<>();
            boolean framesOk = fo == 0;
            int[] scrolls = {middle, middle + 300, middle + 900, middle + 2400, middle};
            for (int k = 0; k < scrolls.length && framesOk; k++) {
                NativeCore.deriveViewport(d, scrolls[k], VIEW_H);
                NativeCore.deriveSettle(d);
                double[] timing = new double[5];
                byte[] got = NativeCore.deriveFrame(d, true, timing);
                byte[] scene = NativeCore.deriveBytes(d, -1);
                Map<String, Object> cmp = got == null ? Json.map("ok", false, "error", "no frame") : compare(got, reference(scene));
                long[] st = NativeCore.deriveStats(d);
                frames.add(Json.map("scroll", scrolls[k], "mask_settle_ms", timing[0], "build_ms", timing[1], "gpu_ms", timing[2],
                    "glyphs", (long) timing[3], "uploads", (long) timing[4], "bad_pixels", cmp.get("bad_pixels"),
                    "max_channel_diff", cmp.get("max_channel_diff"), "mask_runs", st[14], "atlas_hits", st[16], "atlas_evictions", st[17]));
                framesOk &= Boolean.TRUE.equals(cmp.get("ok"));
            }
            out.put("frame_open", fo);
            out.put("frames", frames);
            ok &= framesOk;
            long[] s = NativeCore.deriveStats(d);
            out.put("error", s[3]);
            ok &= s[3] == 0;
        } finally {
            NativeCore.deriveClose(d);
        }
        out.put("ok", ok && failures.isEmpty());
        out.put("paragraphs", n);
        out.put("failures", failures.subList(0, Math.min(6, failures.size())));
        return out;
    }

    /** Checks the scene against the flat reference; expected job deltas (-1 = any, >= 1 means
     * exactly that many) for layout, summary and scene operators. */
    private static boolean step(long d, List<String> corpus, int scroll, String name, long[] before, long t0, List<Object> steps,
                                List<Object> failures, long layouts, long sums, long scenes) {
        double ms = (System.nanoTime() - t0) / 1e6;
        long[] after = NativeCore.deriveStats(d);
        long dl = after[11] - before[11], ds = after[12] - before[12], dc = after[13] - before[13];
        byte[] got = NativeCore.deriveBytes(d, -1), want = reference(corpus, scroll);
        boolean equal = Arrays.equals(got, want);
        boolean counts = (layouts < 0 || dl == layouts) && (sums < 0 || ds == sums) && (scenes < 0 || dc == scenes);
        int glyphs = got == null || got.length < 4 ? -1 : ByteBuffer.wrap(got).getInt();
        steps.add(Json.map("step", name, "ms", ms, "equal", equal, "glyphs", glyphs,
            "layout_runs", dl, "summary_runs", ds, "scene_runs", dc, "counts_ok", counts));
        if (!equal) failures.add(Json.map("step", name, "got", got == null ? -1 : got.length, "want", want.length));
        if (!counts) failures.add(Json.map("step", name, "layout_runs", dl, "summary_runs", ds, "scene_runs", dc,
            "expected", Arrays.asList(layouts, sums, scenes)));
        return equal && counts;
    }

    static int height(byte[] layout) {
        ByteBuffer b = ByteBuffer.wrap(layout);
        int lines = b.getInt();
        if (lines == 0) return 0;
        return (int) (b.getFloat(4 + (lines - 1) * 25 + 16) + 0.5f);
    }
    static int documentHeight(List<String> corpus) {
        int y = 0;
        for (String t : corpus) y += height(DeriveLayout.layout(t, WIDTH));
        return y;
    }
    /** Paragraphs the scene must lay out at scroll: those it walks before the viewport ends. */
    static int visibleCount(List<String> corpus, int scroll) {
        int y = 0, count = 0;
        for (String t : corpus) { if (y >= scroll + VIEW_H) break; int h = height(DeriveLayout.layout(t, WIDTH)); if (y + h > scroll) count++; y += h; }
        return count;
    }
    static int firstVisible(List<String> corpus, int scroll) {
        int y = 0;
        for (int i = 0; i < corpus.size(); i++) { int h = height(DeriveLayout.layout(corpus.get(i), WIDTH)); if (y + h > scroll) return i; y += h; }
        return corpus.size() - 1;
    }

    /** The scene a flat walk over directly computed layouts gives. */
    static byte[] reference(List<String> corpus, int scroll) {
        ByteBuffer out = ByteBuffer.allocate(1 << 22);
        out.putInt(0);
        int count = 0;
        long y = 0;
        for (String t : corpus) {
            if (y >= scroll + VIEW_H) break;
            ByteBuffer b = ByteBuffer.wrap(DeriveLayout.layout(t, WIDTH));
            int lines = b.getInt(0);
            long h = lines == 0 ? 0 : (long) (b.getFloat(4 + (lines - 1) * 25 + 16) + 0.5f);
            if (y + h > scroll) {
                int g = 4 + lines * 25, glyphs = b.getInt(g);
                for (int k = 0; k < glyphs; k++) {
                    int q = g + 4 + k * 16;
                    float x = b.getFloat(q + 8), gy = b.getFloat(q + 12);
                    boolean visible = false;
                    for (int l = 0; l < lines; l++) {
                        float lt = b.getFloat(4 + l * 25 + 12), lb = b.getFloat(4 + l * 25 + 16);
                        if (gy >= lt && gy < lb) { visible = y + lb > scroll && y + lt < scroll + VIEW_H; break; }
                    }
                    if (!visible) continue;
                    out.putInt(b.getInt(q)); out.putInt(b.getInt(q + 4));
                    out.putFloat(x); out.putFloat((float) ((double) y - (double) scroll + gy));
                    count++;
                }
            }
            y += h;
        }
        out.putInt(0, count);
        return Arrays.copyOf(out.array(), out.position());
    }

    /** The platform drawing a scene's glyphs (black, at their rounded positions). */
    static byte[] reference(byte[] scene) {
        ByteBuffer b = ByteBuffer.wrap(scene);
        int count = b.getInt();
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextSize(DeriveLayout.TEXT_SIZE);
        paint.setColor(Color.BLACK);
        Bitmap reference = Bitmap.createBitmap(VIEW_W, VIEW_H, Bitmap.Config.ARGB_8888);
        Canvas rc = new Canvas(reference);
        for (int k = 0; k < count; k++) {
            int face = b.getInt(), id = b.getInt();
            float fx = b.getFloat(), fy = b.getFloat();
            rc.drawGlyphs(new int[]{id}, 0, new float[]{Math.round(fx), Math.round(fy)}, 0, 1, DeriveLayout.face(face), paint);
        }
        ByteBuffer rb = ByteBuffer.allocate(VIEW_W * VIEW_H * 4);
        reference.copyPixelsToBuffer(rb);
        reference.recycle();
        return rb.array();
    }
    static Map<String, Object> compare(byte[] ours, byte[] ref) {
        long bad = 0, ink = 0; int max = 0;
        for (int p = 0; p < VIEW_W * VIEW_H; p++) {
            int worst = 0;
            for (int ch = 0; ch < 4; ch++) worst = Math.max(worst, Math.abs((ours[p * 4 + ch] & 255) - (ref[p * 4 + ch] & 255)));
            if ((ref[p * 4 + 3] & 255) != 0) ink++;
            max = Math.max(max, worst);
            if (worst > 3) bad++;
        }
        return Json.map("ok", bad == 0 && ink > 0, "bad_pixels", bad, "max_channel_diff", max, "ink_pixels", ink);
    }

    /** The derived scene through the GPU renderer against the platform drawing the same glyphs. */
    static Map<String, Object> pixels(byte[] scene) {
        if (scene == null) return Json.map("ok", false, "error", "no scene");
        ByteBuffer b = ByteBuffer.wrap(scene);
        int count = b.getInt();
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextSize(DeriveLayout.TEXT_SIZE);
        paint.setColor(Color.BLACK);
        byte[] atlas = new byte[TextOracle.AW * TextOracle.AH];
        int[] shelf = {0, 0, 0};
        Map<Long, int[]> cache = new HashMap<>();
        float[] glyphs = new float[count * 16];
        int used = 0;
        Bitmap reference = Bitmap.createBitmap(VIEW_W, VIEW_H, Bitmap.Config.ARGB_8888);
        Canvas rc = new Canvas(reference);
        for (int k = 0; k < count; k++) {
            int face = b.getInt(), id = b.getInt();
            float fx = b.getFloat(), fy = b.getFloat();
            Font font = DeriveLayout.face(face);
            long key = (long) face << 32 | (id & 0xffffffffL);
            int[] m = cache.get(key);
            if (m == null && !cache.containsKey(key)) { m = TextOracle.rasterise(font, id, paint, 0xff000000, atlas, shelf); cache.put(key, m); }
            int x = Math.round(fx), y = Math.round(fy);
            rc.drawGlyphs(new int[]{id}, 0, new float[]{x, y}, 0, 1, font, paint);
            if (m == null) continue;
            float x0 = x + m[4], y0 = y + m[5];
            float[] q = {x0, y0, x0 + m[2], y0 + m[3], m[0], m[1], 0, 0, -1e6f, -1e6f, 1e6f, 1e6f, 0, 0, 0, 1};
            System.arraycopy(q, 0, glyphs, used * 16, 16);
            used++;
        }
        double[] timing = new double[1];
        byte[] ours = NativeCore.textRender(VIEW_W, VIEW_H, Arrays.copyOf(glyphs, used * 16), atlas, TextOracle.AW, TextOracle.AH, timing);
        if (ours == null) return Json.map("ok", false, "error", "gpu render failed");
        ByteBuffer rb = ByteBuffer.allocate(VIEW_W * VIEW_H * 4);
        reference.copyPixelsToBuffer(rb);
        byte[] ref = rb.array();
        long bad = 0, ink = 0; int max = 0;
        for (int p = 0; p < VIEW_W * VIEW_H; p++) {
            int worst = 0;
            for (int ch = 0; ch < 4; ch++) worst = Math.max(worst, Math.abs((ours[p * 4 + ch] & 255) - (ref[p * 4 + ch] & 255)));
            if ((ref[p * 4 + 3] & 255) != 0) ink++;
            max = Math.max(max, worst);
            if (worst > 3) bad++;
        }
        return Json.map("ok", bad == 0 && ink > 0, "glyphs", count, "unique_glyphs", cache.size(), "ink_pixels", ink,
            "bad_pixels", bad, "max_channel_diff", max, "gpu_ms", timing[0]);
    }
}
