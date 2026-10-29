package com.trough.hot;

import android.graphics.*;
import android.graphics.fonts.Font;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.*;

/** android.view: a viewport over many paragraphs, derived (docs/VIEW.md). Layout nodes feed a
 * tree of height summaries; the scene node walks it in order and emits the glyphs visible in the
 * viewport, stacked from an anchor paragraph (scroll anchoring); estimated heights summed in a
 * tree serve jumps. The scene's bytes must equal a flat Java walk from the anchor; edits must
 * cost only what they touch (nothing shown moves for edits above the anchor); and the GPU
 * drawing the derived scene must match the platform drawing the same glyphs. Offscreen. */
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
            NativeCore.deriveViewport(d, 0, 0, VIEW_H);
            List<Object> steps = new ArrayList<>();
            long[] before = NativeCore.deriveStats(d);       /* before the demand: work starts at once */
            long t0 = System.nanoTime();
            NativeCore.deriveDemandScene(d, true);
            NativeCore.deriveSettle(d);
            ok &= step(d, corpus, 0, 0, "top", before, t0, steps, failures, 2L * visibleCount(corpus, 0, 0), 0, -1, 0);
            /* Jump to the middle through the estimate tree: estimates everywhere, layouts only
             * for what is shown. */
            before = NativeCore.deriveStats(d); t0 = System.nanoTime();
            int anchor = NativeCore.deriveJump(d, 500);
            int expectAnchor = estimateAnchor(corpus, 500);
            NativeCore.deriveViewport(d, anchor, 0, VIEW_H);
            NativeCore.deriveSettle(d);
            ok &= step(d, corpus, anchor, 0, "jump to middle", before, t0, steps, failures, 2L * visibleCount(corpus, anchor, 0), -1, -1, n);
            out.put("anchor", anchor); out.put("expected_anchor", expectAnchor);
            if (anchor != expectAnchor) { ok = false; failures.add(Json.map("anchor", anchor, "expected", expectAnchor)); }
            /* An edit below the viewport: its estimate and summary chain only. */
            int below = n - 3;
            corpus.set(below, corpus.get(below) + " below");
            before = NativeCore.deriveStats(d); t0 = System.nanoTime();
            NativeCore.deriveText(d, below, corpus.get(below));
            NativeCore.deriveSettle(d);
            ok &= step(d, corpus, anchor, 0, "edit below viewport", before, t0, steps, failures, 0, -1, 0, leaf(d, below));
            /* An edit inside the viewport: its layout and the scene. */
            corpus.set(anchor + 1, corpus.get(anchor + 1) + " seen");
            before = NativeCore.deriveStats(d); t0 = System.nanoTime();
            NativeCore.deriveText(d, anchor + 1, corpus.get(anchor + 1));
            NativeCore.deriveSettle(d);
            ok &= step(d, corpus, anchor, 0, "edit in viewport", before, t0, steps, failures, 1, -1, -1, leaf(d, anchor + 1));
            /* An edit above the anchor that adds lines: nothing shown moves, no layout, no scene. */
            corpus.set(anchor - 3, corpus.get(anchor - 3) + " " + corpus.get(anchor - 2) + " " + corpus.get(anchor - 1));
            before = NativeCore.deriveStats(d); t0 = System.nanoTime();
            NativeCore.deriveText(d, anchor - 3, corpus.get(anchor - 3));
            NativeCore.deriveSettle(d);
            ok &= step(d, corpus, anchor, 0, "edit above anchor, taller", before, t0, steps, failures, 0, -1, 0, leaf(d, anchor - 3));
            /* Scrolling within a paragraph: the offset moves, the same layouts are reused. */
            before = NativeCore.deriveStats(d); t0 = System.nanoTime();
            NativeCore.deriveViewport(d, anchor, 40, VIEW_H);
            NativeCore.deriveSettle(d);
            ok &= step(d, corpus, anchor, 40, "scroll 40 px", before, t0, steps, failures, -1, 0, -1, 0);
            /* Scrolling by pixels across paragraphs, down then up past the original anchor. */
            int[] moves = {700, 1300, -2600, -900, 333};
            int ra = anchor, ro = 40;
            List<Object> scrolls = new ArrayList<>();
            for (int dy : moves) {
                long[] b0 = NativeCore.deriveStats(d); long s0 = System.nanoTime();
                long pos = NativeCore.deriveScroll(d, dy);
                NativeCore.deriveSettle(d);
                int ga = (int) (pos & 0xffffffffL), go = (int) (pos >>> 32);
                int[] want = scrollReference(corpus, ra, ro, dy);
                ra = want[0]; ro = want[1];
                byte[] got = NativeCore.deriveBytes(d, -1);
                boolean same = ga == ra && go == ro && Arrays.equals(got, reference(corpus, ra, ro));
                long[] a1 = NativeCore.deriveStats(d);
                scrolls.add(Json.map("dy", dy, "anchor", ga, "offset", go, "expected", Arrays.asList(ra, ro), "equal", same,
                    "layout_runs", a1[11] - b0[11], "scene_runs", a1[13] - b0[13], "pauses", a1[20] - b0[20],
                    "ms", (System.nanoTime() - s0) / 1e6));
                if (!same) { ok = false; failures.add(Json.map("scroll", dy, "got", Arrays.asList(ga, go), "want", Arrays.asList(ra, ro))); }
            }
            out.put("scrolls", scrolls);
            /* Background prefetch: layouts from 8 above to 24 below the anchor are demanded at
             * DV_BACKGROUND, so a scroll into them crosses laid-out paragraphs without pausing,
             * and the scene settles (deriveSettleVisible) without waiting for the window. */
            List<Object> prefetched = new ArrayList<>();
            long[] p0 = NativeCore.deriveStats(d); long w0 = System.nanoTime();
            NativeCore.derivePrefetch(d, 8, 24);
            NativeCore.deriveSettle(d);
            long[] p1 = NativeCore.deriveStats(d);
            out.put("prefetch_window", Json.map("paragraphs", p1[21], "layout_runs", p1[11] - p0[11], "ms", (System.nanoTime() - w0) / 1e6));
            for (int dy : new int[] {700, 1300, 900, -600}) {
                long[] b0 = NativeCore.deriveStats(d); long s0 = System.nanoTime();
                long pos = NativeCore.deriveScroll(d, dy);
                double scrollMs = (System.nanoTime() - s0) / 1e6;
                NativeCore.deriveSettleVisible(d);
                double visibleMs = (System.nanoTime() - s0) / 1e6;
                int ga = (int) (pos & 0xffffffffL), go = (int) (pos >>> 32);
                int[] want = scrollReference(corpus, ra, ro, dy);
                ra = want[0]; ro = want[1];
                boolean same = ga == ra && go == ro && Arrays.equals(NativeCore.deriveBytes(d, -1), reference(corpus, ra, ro));
                long[] a1 = NativeCore.deriveStats(d);
                long pauses = a1[20] - b0[20];
                NativeCore.deriveSettle(d);                  /* the window catches up before the next scroll */
                long[] a2 = NativeCore.deriveStats(d);
                prefetched.add(Json.map("dy", dy, "anchor", ga, "offset", go, "equal", same, "pauses", pauses,
                    "scroll_ms", scrollMs, "visible_ms", visibleMs, "window_layout_runs", a2[11] - b0[11],
                    "window", a2[21], "ms", (System.nanoTime() - s0) / 1e6));
                if (!same || pauses != 0) {
                    ok = false;
                    failures.add(Json.map("prefetch_scroll", dy, "equal", same, "pauses", pauses, "got", Arrays.asList(ga, go), "want", Arrays.asList(ra, ro)));
                }
            }
            NativeCore.derivePrefetch(d, 0, 0);
            NativeCore.deriveSettle(d);
            long[] p2 = NativeCore.deriveStats(d);
            if (p2[21] != 0) { ok = false; failures.add(Json.map("prefetch_off_window", p2[21])); }
            out.put("prefetch_scrolls", prefetched);
            out.put("steps", steps);
            Map<String, Object> pixels = pixels(NativeCore.deriveBytes(d, -1));
            out.put("pixels", pixels);
            ok &= Boolean.TRUE.equals(pixels.get("ok"));
            /* The derived frame: mask nodes, atlas residency and the frame ring, scrolling. */
            int fo = NativeCore.deriveFrameOpen(d, VIEW_W, VIEW_H, 1024, 1024);
            List<Object> frames = new ArrayList<>();
            boolean framesOk = fo == 0;
            int[] anchors = {anchor, anchor + 3, anchor + 10, anchor + 30, anchor};
            for (int k = 0; k < anchors.length && framesOk; k++) {
                NativeCore.deriveViewport(d, anchors[k], 0, VIEW_H);
                NativeCore.deriveSettle(d);
                double[] timing = new double[5];
                byte[] got = NativeCore.deriveFrame(d, true, timing);
                byte[] scene = NativeCore.deriveBytes(d, -1);
                Map<String, Object> cmp = got == null ? Json.map("ok", false, "error", "no frame") : compare(got, reference(scene));
                long[] st = NativeCore.deriveStats(d);
                frames.add(Json.map("anchor", anchors[k], "mask_settle_ms", timing[0], "build_ms", timing[1], "gpu_ms", timing[2],
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

    /** Checks the scene against the flat reference; expected run deltas (-1 = any) for layout,
     * summary, scene and estimate operators. */
    private static boolean step(long d, List<String> corpus, int anchor, int offset, String name, long[] before, long t0, List<Object> steps,
                                List<Object> failures, long layouts, long sums, long scenes, long estimates) {
        double ms = (System.nanoTime() - t0) / 1e6;
        long[] after = NativeCore.deriveStats(d);
        long dl = after[11] - before[11], ds = after[12] - before[12], dc = after[13] - before[13], de = after[19] - before[19];
        byte[] got = NativeCore.deriveBytes(d, -1), want = reference(corpus, anchor, offset);
        boolean equal = Arrays.equals(got, want);
        boolean counts = (layouts < 0 || dl == layouts) && (sums < 0 || ds == sums) && (scenes < 0 || dc == scenes) && (estimates < 0 || de == estimates);
        int glyphs = got == null || got.length < 4 ? -1 : ByteBuffer.wrap(got).getInt();
        steps.add(Json.map("step", name, "ms", ms, "equal", equal, "glyphs", glyphs,
            "layout_runs", dl, "summary_runs", ds, "scene_runs", dc, "estimate_runs", de, "counts_ok", counts));
        if (!equal) failures.add(Json.map("step", name, "got", got == null ? -1 : got.length, "want", want.length));
        if (!counts) failures.add(Json.map("step", name, "layout_runs", dl, "summary_runs", ds, "scene_runs", dc, "estimate_runs", de,
            "expected", Arrays.asList(layouts, sums, scenes, estimates)));
        return equal && counts;
    }

    static int height(byte[] layout) {
        ByteBuffer b = ByteBuffer.wrap(layout);
        int lines = b.getInt();
        if (lines == 0) return 0;
        return (int) (b.getFloat(4 + (lines - 1) * 25 + 16) + 0.5f);
    }
    /** The anchor and offset after scrolling by dy over exact heights. */
    static int[] scrollReference(List<String> corpus, int anchor, int offset, int dy) {
        long o = (long) offset + dy;
        int a = anchor;
        for (;;) {
            if (o < 0 && a == 0) { o = 0; break; }
            if (o >= 0 && a == corpus.size() - 1) break;
            if (o < 0) { a--; o += height(DeriveLayout.layout(corpus.get(a), WIDTH)); continue; }
            int h = height(DeriveLayout.layout(corpus.get(a), WIDTH));
            if (o >= h && h > 0) { o -= h; a++; continue; }
            break;
        }
        return new int[]{a, (int) Math.min(o, 0xffff)};
    }
    /** Paragraphs in the 16-paragraph block holding i: what a level-0 summary re-estimates. */
    /** Paragraphs one edit re-estimates: those in the order-tree leaf holding paragraph i
     * (docs/ORDER.md), 8 to 16 except in a document that small. */
    static long leaf(long d, int i) { int e = NativeCore.deriveLeafSize(d, i); return e >= 1 && e <= 16 ? e : -2; }
    /** The native estimate: ceil(units * 17 / width) lines (at least 1) of 48 px. */
    static long estimate(String t) { long lines = ((long) t.length() * 17 + WIDTH - 1) / WIDTH; return Math.max(1, lines) * 48; }
    static int estimateAnchor(List<String> corpus, int permille) {
        long total = 0;
        for (String t : corpus) total += estimate(t);
        long target = total * permille / 1000, y = 0;
        for (int i = 0; i < corpus.size(); i++) { long h = estimate(corpus.get(i)); if (y + h > target) return i; y += h; }
        return corpus.size() - 1;
    }
    /** Paragraphs the scene lays out from the anchor until the viewport is full. */
    static int visibleCount(List<String> corpus, int anchor, int offset) {
        int y = -offset, count = 0;
        for (int i = anchor; i < corpus.size() && y < VIEW_H; i++) { y += height(DeriveLayout.layout(corpus.get(i), WIDTH)); count++; }
        return count;
    }

    /** The scene a flat walk from the anchor over directly computed layouts gives. */
    static byte[] reference(List<String> corpus, int anchor, int offset) {
        ByteBuffer out = ByteBuffer.allocate(1 << 22);
        out.putInt(0);
        int count = 0;
        long y = -offset;
        for (int i = anchor; i < corpus.size() && y < VIEW_H; i++) {
            ByteBuffer b = ByteBuffer.wrap(DeriveLayout.layout(corpus.get(i), WIDTH));
            int lines = b.getInt(0);
            long h = lines == 0 ? 0 : (long) (b.getFloat(4 + (lines - 1) * 25 + 16) + 0.5f);
            int g = 4 + lines * 25, glyphs = b.getInt(g);
            for (int k = 0; k < glyphs; k++) {
                int q = g + 4 + k * 16;
                float x = b.getFloat(q + 8), gy = b.getFloat(q + 12);
                boolean visible = false;
                for (int l = 0; l < lines; l++) {
                    float lt = b.getFloat(4 + l * 25 + 12), lb = b.getFloat(4 + l * 25 + 16);
                    if (gy >= lt && gy < lb) { visible = (double) y + lb > 0 && (double) y + lt < VIEW_H; break; }
                }
                if (!visible) continue;
                out.putInt(b.getInt(q)); out.putInt(b.getInt(q + 4));
                out.putFloat(x); out.putFloat((float) ((double) y + gy));
                count++;
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
