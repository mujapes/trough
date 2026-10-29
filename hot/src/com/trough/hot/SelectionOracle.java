package com.trough.hot;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.StaticLayout;
import java.nio.ByteBuffer;
import java.util.*;

/** android.selection: range selection on the derived view (docs/VIEW.md "Selection"). The
 * reference is one StaticLayout over the joined paragraphs and its getSelectionPath; ours is
 * per-paragraph TextLayout geometry composed across paragraphs. Both are rasterised as a filled
 * path without antialiasing (pixel-centre coverage) and compared pixel for pixel.
 * (a) TextLayout.selection vs getSelectionPath on random ranges in every script of the corpus,
 *     one to three paragraphs;
 * (b) the derived overlay in viewport coordinates for ranges in the viewport;
 * (c) the frame with the translucent highlight under the glyphs vs the platform drawing it;
 * (d) extending a range runs no layout or scene job. Offscreen. */
final class SelectionOracle {
    static Map<String, Object> run() {
        List<String> corpus = LayoutDeriveOracle.corpus(600);
        int n = corpus.size(), W = ViewOracle.WIDTH, H = ViewOracle.VIEW_H;
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> failures = new ArrayList<>();
        Random rnd = new Random(29);

        /* (a) geometry */
        int cases = 0, bad = 0; long badPixels = 0; List<Object> examples = new ArrayList<>();
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setAntiAlias(true); paint.setTextSize(DeriveLayout.TEXT_SIZE);
        for (int k = 0; k < 160; k++) {
            int pa = rnd.nextInt(n - 3), span = rnd.nextInt(3), pb = pa + span;
            int la = corpus.get(pa).length(), lb = corpus.get(pb).length();
            int oa = rnd.nextInt(la + 1), ob = rnd.nextInt(lb + 1);
            if (span == 0 && oa > ob) { int t = oa; oa = ob; ob = t; }
            List<float[]> ours = new ArrayList<>();
            float top = 0;
            for (int p = pa; p <= pb; p++) {
                String text = corpus.get(p);
                TextLayout l = TextLayout.layout(text, paint, W, 0, 0);
                for (float[] r : l.selection(p == pa ? oa : 0, p == pb ? ob : text.length(), p > pa, p < pb))
                    ours.add(new float[]{r[0], r[1] + top, r[2], r[3] + top});
                top += l.height;
            }
            StringBuilder joined = new StringBuilder();
            int start = 0;
            for (int p = pa; p <= pb; p++) {
                if (p == pa) start = oa;
                if (p > pa) joined.append('\n');
                joined.append(corpus.get(p));
            }
            int end = joined.length() - lb + ob;
            StaticLayout ref = CaretOracle.platform(joined.toString());
            Path path = new Path();
            ref.getSelectionPath(start, end, path);
            int h = (int) Math.ceil(Math.max(top, ref.getHeight())) + 2;
            long diff = compare(ours, path, W + 4, h);
            cases++;
            if (diff != 0) {
                bad++; badPixels += diff;
                if (examples.size() < 6) {
                    int[] box = diffBox(ours, path, W + 4, h);
                    int line = ref.getLineForVertical((box[1] + box[3]) / 2);
                    String lt = joined.substring(ref.getLineStart(line), ref.getLineEnd(line));
                    examples.add(Json.map("paragraphs", Arrays.asList(pa, pb), "offsets", Arrays.asList(oa, ob), "bad_pixels", diff,
                        "box", Arrays.asList(box[0], box[1], box[2], box[3]), "ours_extra", box[4], "ref_extra", box[5],
                        "line", line, "line_range", Arrays.asList(ref.getLineStart(line), ref.getLineEnd(line)), "sel", Arrays.asList(start, end),
                        "line_right", ref.getLineRight(line), "line_max", ref.getLineMax(line), "rtl", ref.getParagraphDirection(line) < 0,
                        "tail", lt.length() > 12 ? lt.substring(lt.length() - 12) : lt));
                }
            }
        }
        out.put("geometry", Json.map("cases", cases, "mismatched", bad, "bad_pixels", badPixels, "examples", examples));
        if (bad != 0) failures.add(Json.map("geometry_mismatched", bad));

        long d = NativeCore.deriveOpen(2, n);
        if (d < 0) return Json.map("ok", false, "error", "deriveOpen " + d);
        try {
            NativeCore.deriveWidth(d, W);
            for (int i = 0; i < n; i++) NativeCore.deriveText(d, i, corpus.get(i));
            int anchor = NativeCore.deriveJump(d, 500);
            int voff = 37;
            NativeCore.deriveViewport(d, anchor, voff, H);
            NativeCore.deriveDemandScene(d, true);
            NativeCore.deriveSettle(d);

            /* (b) the derived overlay */
            int obad = 0; List<Object> overlays = new ArrayList<>();
            int[][] ranges = {{anchor, 5, anchor, 60}, {anchor, 10, anchor + 2, 15}, {anchor + 3, 40, anchor + 1, 2}, {anchor + 1, 0, anchor + 1, 0},
                              {anchor - 2, 3, anchor + 1, 20}, {anchor + 2, 0, anchor + 6, 30}};
            for (int[] r : ranges) {
                long[] b0 = NativeCore.deriveStats(d);
                NativeCore.deriveSelectRange(d, r[0], r[1], r[2], r[3]);
                NativeCore.deriveSettle(d);
                long[] b1 = NativeCore.deriveStats(d);
                byte[] ov = NativeCore.deriveBytes(d, -2);
                List<float[]> ours = overlayRects(ov, 1);
                Path want = viewportReference(corpus, anchor, voff, r);
                long diff = compare(ours, want, ViewOracle.VIEW_W, H);
                overlays.add(Json.map("range", Arrays.asList(r[0], r[1], r[2], r[3]), "rects", ours.size(), "bad_pixels", diff,
                    "highlight_runs", b1[26] - b0[26], "layout_runs", b1[11] - b0[11], "scene_runs", b1[13] - b0[13]));
                if (diff != 0) obad++;
                if (b1[11] != b0[11] || b1[13] != b0[13]) failures.add(Json.map("range_reran", Arrays.asList(r[0], r[1], r[2], r[3])));
            }
            out.put("overlays", overlays);
            if (obad != 0) failures.add(Json.map("overlay_mismatched", obad));

            /* (d) extending the focus within a paragraph: highlight and overlay only */
            int[] last = ranges[1];
            long[] e0 = NativeCore.deriveStats(d);
            NativeCore.deriveSelectRange(d, last[0], last[1], last[2], last[3] + 9);
            NativeCore.deriveSettle(d);
            long[] e1 = NativeCore.deriveStats(d);
            out.put("extend", Json.map("highlight_runs", e1[26] - e0[26], "overlay_runs", e1[25] - e0[25], "layout_runs", e1[11] - e0[11], "scene_runs", e1[13] - e0[13]));
            if (e1[11] != e0[11] || e1[13] != e0[13] || e1[26] == e0[26]) failures.add(Json.map("extend", out.get("extend")));

            /* (c) pixels */
            int fo = NativeCore.deriveFrameOpen(d, ViewOracle.VIEW_W, H, 1024, 1024);
            byte[] frame = fo == 0 ? NativeCore.deriveFrame(d, true, new double[5]) : null;
            byte[] scene = NativeCore.deriveBytes(d, -1);
            List<float[]> hl = overlayRects(NativeCore.deriveBytes(d, -2), 1);
            Map<String, Object> cmp = frame == null ? Json.map("ok", false, "error", "frame " + fo) : ViewOracle.compare(frame, reference(scene, hl));
            cmp.put("highlight_rects", hl.size());
            out.put("pixels", cmp);
            if (!Boolean.TRUE.equals(cmp.get("ok"))) failures.add(Json.map("pixels", cmp));

            /* A caret again clears the range. */
            NativeCore.deriveSelect(d, anchor + 1, 4);
            NativeCore.deriveSettle(d);
            if (!overlayRects(NativeCore.deriveBytes(d, -2), 1).isEmpty()) failures.add(Json.map("caret_after_range", "highlight left"));
            if (overlayRects(NativeCore.deriveBytes(d, -2), 0).size() != 1) failures.add(Json.map("caret_after_range", "no caret"));
            long[] s = NativeCore.deriveStats(d);
            if (s[3] != 0) failures.add(Json.map("error", s[3]));
        } finally {
            NativeCore.deriveClose(d);
        }
        out.put("failures", failures);
        out.put("ok", failures.isEmpty());
        return out;
    }

    /** Overlay rectangles of one kind (0 caret, 1 highlight). */
    static List<float[]> overlayRects(byte[] ov, int kind) {
        List<float[]> r = new ArrayList<>();
        if (ov == null || ov.length < 4) return r;
        ByteBuffer b = ByteBuffer.wrap(ov);
        int count = b.getInt();
        for (int k = 0; k < count; k++) {
            float x0 = b.getFloat(), y0 = b.getFloat(), x1 = b.getFloat(), y1 = b.getFloat(); int kd = b.getInt();
            if (kd == kind) r.add(new float[]{x0, y0, x1, y1});
        }
        return r;
    }
    /** The platform's selection for a range in viewport coordinates: one StaticLayout over the
     * paragraphs from the viewport's anchor to the range's end, its path moved up by the
     * offset. Paragraphs above the anchor are not shown; the range is clipped to start there. */
    static Path viewportReference(List<String> corpus, int anchor, int voff, int[] r) {
        int pa = r[0], oa = r[1], pb = r[2], ob = r[3];
        if (pa > pb || (pa == pb && oa > ob)) { int t = pa; pa = pb; pb = t; t = oa; oa = ob; ob = t; }
        StringBuilder joined = new StringBuilder();
        int start = -1, end = 0;
        for (int p = anchor; p <= pb; p++) {
            if (p > anchor) joined.append('\n');
            if (p == pa) start = joined.length() + oa;
            if (p == pb) end = joined.length() + ob;
            joined.append(corpus.get(p));
        }
        Path path = new Path();
        if (pa < anchor) start = 0;                               /* begins above: from the anchor */
        if (start < 0 || (start == end && pa == pb)) return path;
        StaticLayout ref = CaretOracle.platform(joined.toString());
        ref.getSelectionPath(start, end, path);
        if (pa < anchor) {                                         /* the range began before the anchor's first line */
            Path full = new Path();
            full.addRect(0, 0, ViewOracle.WIDTH, ref.getLineBottom(0), Path.Direction.CW);
            path.op(full, Path.Op.UNION);
        }
        path.offset(0, -voff);
        return path;
    }
    /** Pixels where our rectangles and the platform path differ, both filled without antialiasing. */
    static long compare(List<float[]> ours, Path ref, int w, int h) {
        Bitmap a = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8), b = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Paint p = new Paint();
        p.setAntiAlias(false);
        Path mine = new Path();
        for (float[] r : ours) if (r[2] > r[0] && r[3] > r[1]) mine.addRect(r[0], r[1], r[2], r[3], Path.Direction.CW);
        new Canvas(a).drawPath(mine, p);
        new Canvas(b).drawPath(ref, p);
        ByteBuffer ba = ByteBuffer.allocate(a.getRowBytes() * h), bb = ByteBuffer.allocate(b.getRowBytes() * h);
        a.copyPixelsToBuffer(ba); b.copyPixelsToBuffer(bb);
        a.recycle(); b.recycle();
        long diff = 0;
        byte[] x = ba.array(), y = bb.array();
        for (int i = 0; i < x.length; i++) if ((x[i] == 0) != (y[i] == 0)) diff++;
        return diff;
    }
    /** Bounding box of differing pixels, and how many are ours only / the reference's only. */
    static int[] diffBox(List<float[]> ours, Path ref, int w, int h) {
        Bitmap a = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8), b = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Paint p = new Paint();
        p.setAntiAlias(false);
        Path mine = new Path();
        for (float[] r : ours) if (r[2] > r[0] && r[3] > r[1]) mine.addRect(r[0], r[1], r[2], r[3], Path.Direction.CW);
        new Canvas(a).drawPath(mine, p);
        new Canvas(b).drawPath(ref, p);
        int[] box = {w, h, -1, -1, 0, 0};
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            boolean oa = Color.alpha(a.getPixel(x, y)) != 0, ob = Color.alpha(b.getPixel(x, y)) != 0;
            if (oa == ob) continue;
            box[0] = Math.min(box[0], x); box[1] = Math.min(box[1], y); box[2] = Math.max(box[2], x); box[3] = Math.max(box[3], y);
            if (oa) box[4]++; else box[5]++;
        }
        a.recycle(); b.recycle();
        return box;
    }
    /** The platform drawing the highlight rectangles (translucent, no antialiasing) under the glyphs. */
    static byte[] reference(byte[] scene, List<float[]> rects) {
        ByteBuffer b = ByteBuffer.wrap(scene);
        int count = b.getInt();
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextSize(DeriveLayout.TEXT_SIZE);
        paint.setColor(Color.BLACK);
        Paint hl = new Paint();
        hl.setAntiAlias(false);
        hl.setColor(Color.argb(89, 26, 115, 230));
        Bitmap bm = Bitmap.createBitmap(ViewOracle.VIEW_W, ViewOracle.VIEW_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        for (float[] r : rects) c.drawRect(r[0], r[1], r[2], r[3], hl);
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
