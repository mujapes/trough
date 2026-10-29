package com.trough.hot;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import java.nio.ByteBuffer;
import java.util.*;

/** android.caret: taps and the caret on the derived view (docs/VIEW.md "Caret"), against the
 * platform's StaticLayout over the same paragraphs. A tap at viewport (x, y) must select the
 * paragraph and offset that a flat walk of StaticLayout heights from the anchor, then
 * getLineForVertical and getOffsetForHorizontal, select. The overlay's caret rectangle must sit
 * at getPrimaryHorizontal and the line's top and bottom. Moving the caret runs one caret job
 * and one overlay job, never layout or the scene. The frame equals the platform drawing the
 * caret and the glyphs. Offscreen: Trough need not be visible. */
final class CaretOracle {
    static Map<String, Object> run() {
        List<String> corpus = LayoutDeriveOracle.corpus(600);
        int n = corpus.size(), W = ViewOracle.WIDTH, H = ViewOracle.VIEW_H;
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> failures = new ArrayList<>();
        long d = NativeCore.deriveOpen(2, n);
        if (d < 0) return Json.map("ok", false, "error", "deriveOpen " + d);
        try {
            NativeCore.deriveWidth(d, W);
            for (int i = 0; i < n; i++) NativeCore.deriveText(d, i, corpus.get(i));
            int anchor = NativeCore.deriveJump(d, 500);
            NativeCore.deriveViewport(d, anchor, 0, H);
            NativeCore.deriveDemandScene(d, true);
            NativeCore.deriveSettle(d);
            byte[] empty = NativeCore.deriveBytes(d, -2);
            if (empty == null || ByteBuffer.wrap(empty).getInt() != 0) failures.add(Json.map("overlay_without_selection", empty == null ? -1 : ByteBuffer.wrap(empty).getInt()));

            /* Taps over the viewport, including the right margin and past line ends. */
            List<Object> taps = new ArrayList<>();
            int tapMismatch = 0, caretMismatch = 0;
            double worstCaret = 0;
            for (int k = 0; k < 24; k++) {
                float x = 5 + (k * 397) % 1070, y = 8 + (k * 613) % (H - 16);
                long[] b0 = NativeCore.deriveStats(d);
                long got = NativeCore.deriveTap(d, x, y);
                NativeCore.deriveSettle(d);
                long[] b1 = NativeCore.deriveStats(d);
                int[] want = tapReference(corpus, anchor, x, y);
                int gp = (int) (got >> 32), go = (int) (got & 0xffffffffL);
                boolean same = got >= 0 && gp == want[0] && go == want[1];
                if (!same) tapMismatch++;
                float[] rect = caretReference(corpus, anchor, want[0], want[1]);
                byte[] ov = NativeCore.deriveBytes(d, -2);
                double diff = Double.MAX_VALUE;
                if (ov != null && ov.length >= 20 && ByteBuffer.wrap(ov).getInt() == 1) {
                    ByteBuffer ob = ByteBuffer.wrap(ov, 4, 16);
                    diff = 0;
                    for (int c = 0; c < 4; c++) diff = Math.max(diff, Math.abs(ob.getFloat() - rect[c]));
                }
                worstCaret = Math.max(worstCaret, diff);
                if (diff > 0.01) caretMismatch++;
                if (taps.size() < 8 || !same || diff > 0.01)
                    taps.add(Json.map("x", x, "y", y, "got", Arrays.asList(gp, go), "want", Arrays.asList(want[0], want[1]),
                        "caret_diff", diff, "caret_runs", b1[24] - b0[24], "overlay_runs", b1[25] - b0[25],
                        "layout_runs", b1[11] - b0[11], "scene_runs", b1[13] - b0[13]));
                if (b1[11] != b0[11] || b1[13] != b0[13]) failures.add(Json.map("tap_reran", k, "layout", b1[11] - b0[11], "scene", b1[13] - b0[13]));
            }
            out.put("taps", taps);
            out.put("tap_mismatches", tapMismatch);
            out.put("caret_mismatches", caretMismatch);
            out.put("worst_caret_diff", worstCaret);
            if (tapMismatch != 0) failures.add(Json.map("tap_mismatches", tapMismatch));
            if (caretMismatch != 0) failures.add(Json.map("caret_mismatches", caretMismatch));

            /* Exact costs: no layout or scene work for any caret move; a position already derived
             * costs no caret job; the same selection again costs at most an overlay re-run. */
            int p = anchor + 1;
            long[] c0 = NativeCore.deriveStats(d);
            NativeCore.deriveSelect(d, p, 3); NativeCore.deriveSettle(d);
            long[] c1 = NativeCore.deriveStats(d);
            NativeCore.deriveSelect(d, p, 7); NativeCore.deriveSettle(d);
            NativeCore.deriveSelect(d, p, 3); NativeCore.deriveSettle(d);
            long[] c2 = NativeCore.deriveStats(d);
            NativeCore.deriveSelect(d, p, 3); NativeCore.deriveSettle(d);
            long[] c3 = NativeCore.deriveStats(d);
            Map<String, Object> costs = Json.map(
                "new_caret", Arrays.asList(c1[24] - c0[24], c1[25] - c0[25], c1[11] - c0[11], c1[13] - c0[13]),
                "move_and_back", Arrays.asList(c2[24] - c1[24], c2[25] - c1[25], c2[11] - c1[11], c2[13] - c1[13]),
                "same_again", Arrays.asList(c3[24] - c2[24], c3[25] - c2[25], c3[11] - c2[11], c3[13] - c2[13]));
            out.put("costs_caret_overlay_layout_scene", costs);
            /* Runs count invocations: a fresh caret node restarts once for its text and width, and
             * the overlay restarts once when it reads a caret node it did not read before. */
            /* The overlay's restarts depend on which layouts the previous selection read, so it is
             * bounded rather than exact; caret, layout and scene counts are exact. */
            boolean costsOk = c1[24] - c0[24] == 2 && c1[25] - c0[25] >= 1 && c1[25] - c0[25] <= 4 && c1[11] == c0[11] && c1[13] == c0[13]
                && c2[24] - c1[24] == 2 && c2[25] - c1[25] <= 8 && c2[11] == c1[11] && c2[13] == c1[13]
                && c3[24] == c2[24] && c3[25] - c2[25] <= 1 && c3[11] == c2[11] && c3[13] == c2[13];
            if (!costsOk) failures.add(Json.map("costs", costs));

            /* An edit of the caret's paragraph re-derives the caret; the caret stays exact. */
            String edited = corpus.get(p) + " edited";
            corpus.set(p, edited);
            NativeCore.deriveText(d, p, edited);
            NativeCore.deriveSettle(d);
            long[] e1 = NativeCore.deriveStats(d);
            float[] rect = caretReference(corpus, anchor, p, 3);
            byte[] ov = NativeCore.deriveBytes(d, -2);
            ByteBuffer ob = ByteBuffer.wrap(ov, 4, 16);
            double ediff = 0;
            for (int c = 0; c < 4; c++) ediff = Math.max(ediff, Math.abs(ob.getFloat() - rect[c]));
            out.put("edit", Json.map("caret_runs", e1[24] - c3[24], "caret_diff", ediff));
            if (e1[24] - c3[24] != 1 || ediff > 0.01) failures.add(Json.map("edit_caret_runs", e1[24] - c3[24], "diff", ediff));

            /* Pixels: the derived frame (caret under the glyphs) against the platform. */
            int fo = NativeCore.deriveFrameOpen(d, ViewOracle.VIEW_W, H, 1024, 1024);
            byte[] frame = fo == 0 ? NativeCore.deriveFrame(d, true, new double[5]) : null;
            byte[] scene = NativeCore.deriveBytes(d, -1);
            Map<String, Object> cmp = frame == null ? Json.map("ok", false, "error", "frame " + fo)
                : ViewOracle.compare(frame, reference(scene, rect));
            int caretPixels = 0;
            if (frame != null) {
                int cx = Math.round(rect[0] + 1), cy = Math.round((rect[1] + rect[3]) / 2);
                for (int yy = Math.max(0, cy - 4); yy < Math.min(H, cy + 4); yy++) {
                    int i = (yy * ViewOracle.VIEW_W + cx) * 4;
                    if (cx >= 0 && cx < ViewOracle.VIEW_W && (frame[i + 2] & 255) > 150) caretPixels++;
                }
            }
            cmp.put("caret_blue_pixels_sampled", caretPixels);
            if (frame != null) {                            /* where they differ, for diagnosis */
                byte[] ref = reference(scene, rect);
                List<Object> bad = new ArrayList<>();
                for (int i = 0; i < ViewOracle.VIEW_W * H && bad.size() < 12; i++) {
                    int worst = 0;
                    for (int ch = 0; ch < 4; ch++) worst = Math.max(worst, Math.abs((frame[i * 4 + ch] & 255) - (ref[i * 4 + ch] & 255)));
                    if (worst > 3) bad.add(Json.map("x", i % ViewOracle.VIEW_W, "y", i / ViewOracle.VIEW_W,
                        "ours", Arrays.asList(frame[i * 4] & 255, frame[i * 4 + 1] & 255, frame[i * 4 + 2] & 255, frame[i * 4 + 3] & 255),
                        "ref", Arrays.asList(ref[i * 4] & 255, ref[i * 4 + 1] & 255, ref[i * 4 + 2] & 255, ref[i * 4 + 3] & 255)));
                }
                cmp.put("first_bad", bad);
                cmp.put("caret_rect", Arrays.asList(rect[0], rect[1], rect[2], rect[3]));
            }
            out.put("pixels", cmp);
            if (!Boolean.TRUE.equals(cmp.get("ok"))) failures.add(Json.map("pixels", cmp));

            NativeCore.deriveSelect(d, 0, -1);
            NativeCore.deriveSettle(d);
            byte[] cleared = NativeCore.deriveBytes(d, -2);
            if (cleared == null || ByteBuffer.wrap(cleared).getInt() != 0) failures.add(Json.map("cleared_overlay", "not empty"));
            long[] s = NativeCore.deriveStats(d);
            if (s[3] != 0) failures.add(Json.map("error", s[3]));
        } finally {
            NativeCore.deriveClose(d);
        }
        out.put("failures", failures.subList(0, Math.min(8, failures.size())));
        out.put("ok", failures.isEmpty());
        return out;
    }

    static StaticLayout platform(String text) {
        TextPaint paint = new TextPaint();
        paint.setAntiAlias(true);
        paint.setTextSize(DeriveLayout.TEXT_SIZE);
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, ViewOracle.WIDTH)
            .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(false).setUseLineSpacingFromFallbacks(true).build();
    }
    /** The paragraph and offset a tap selects, by StaticLayout over a flat walk from the anchor. */
    static int[] tapReference(List<String> corpus, int anchor, float x, float y) {
        float top = 0;
        for (int i = anchor; i < corpus.size(); i++) {
            StaticLayout l = platform(corpus.get(i));
            int h = l.getLineBottom(l.getLineCount() - 1);
            if (y < top + h || i == corpus.size() - 1) {
                int line = l.getLineForVertical((int) Math.floor(y - top));
                return new int[]{i, l.getOffsetForHorizontal(line, x)};
            }
            top += h;
        }
        return new int[]{-1, -1};
    }
    /** The caret rectangle in viewport coordinates: getPrimaryHorizontal +- 1, the line box. */
    static float[] caretReference(List<String> corpus, int anchor, int para, int offset) {
        float top = 0;
        for (int i = anchor; i < para; i++) { StaticLayout l = platform(corpus.get(i)); top += l.getLineBottom(l.getLineCount() - 1); }
        StaticLayout l = platform(corpus.get(para));
        int o = Math.max(0, Math.min(offset, corpus.get(para).length()));
        int line = l.getLineForOffset(o);
        float x = l.getPrimaryHorizontal(o);
        return new float[]{x - 1, top + l.getLineTop(line), x + 1, top + l.getLineBottom(line)};
    }
    /** The platform drawing the caret (no antialiasing: the GPU's pixel-centre rule) under the glyphs. */
    static byte[] reference(byte[] scene, float[] rect) {
        ByteBuffer b = ByteBuffer.wrap(scene);
        int count = b.getInt();
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextSize(DeriveLayout.TEXT_SIZE);
        paint.setColor(Color.BLACK);
        Paint caret = new Paint();
        caret.setAntiAlias(false);                   /* new Paint() antialiases on current Android */
        caret.setColor(Color.rgb(26, 115, 230));
        Bitmap bm = Bitmap.createBitmap(ViewOracle.VIEW_W, ViewOracle.VIEW_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.drawRect(rect[0], rect[1], rect[2], rect[3], caret);
        for (int k = 0; k < count; k++) {
            int face = b.getInt(), id = b.getInt();
            float fx = b.getFloat(), fy = b.getFloat();
            c.drawGlyphs(new int[]{id}, 0, new float[]{Math.round(fx), Math.round(fy)}, 0, 1, DeriveLayout.face(face), paint);
        }
        ByteBuffer rb = ByteBuffer.allocate(ViewOracle.VIEW_W * ViewOracle.VIEW_H * 4);
        bm.copyPixelsToBuffer(rb);
        bm.recycle();
        return rb.array();
    }
}
