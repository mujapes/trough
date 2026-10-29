package com.trough.hot;

import android.app.Activity;
import android.graphics.Matrix;
import android.text.StaticLayout;
import android.view.View;
import android.view.inputmethod.CursorAnchorInfo;
import java.util.*;

/** android.anchor: CursorAnchorInfo over the derived document (docs/INPUT.md "Cursor anchor"),
 * against Editor.updateCursorAnchorInfo's rules evaluated on the platform's StaticLayout over the
 * same paragraphs: the insertion marker is getPrimaryHorizontal and the line's top, baseline and
 * bottom (a flat walk of heights from the scrolled anchor), visible or invisible at the top and
 * bottom by TextView.isPositionVisible, RTL by isRtlCharAt. Selection and composing ranges are
 * global offsets in the joined text. The viewport is scrolled into its anchor paragraph so
 * carets are clipped at both edges. Composing regions also check character bounds against
 * StaticLayout.fillCharacterBounds. Offscreen: Trough need not be visible. */
final class AnchorOracle {
    static Map<String, Object> run(Activity activity) {
        List<String> corpus = LayoutDeriveOracle.corpus(200);
        int n = corpus.size(), W = ViewOracle.VIEW_W, H = ViewOracle.VIEW_H;
        List<Object> failures = new ArrayList<>();
        long d = NativeCore.deriveOpen(2, n);
        if (d < 0) return Json.map("ok", false, "error", "deriveOpen " + d);
        int markers = 0, clipped = 0, rtl = 0, hidden = 0, composing = 0, bounded = 0;
        double worst = 0, worstBounds = 0;
        try {
            NativeCore.deriveWidth(d, ViewOracle.WIDTH);
            for (int i = 0; i < n; i++) NativeCore.deriveText(d, i, corpus.get(i));
            int anchor = NativeCore.deriveJump(d, 400);
            StaticLayout first = CaretOracle.platform(corpus.get(anchor));
            int scrolled = Math.max(1, first.getLineBottom(first.getLineCount() - 1) / 2 + 3);   /* mid-line, so its caret is clipped */
            NativeCore.deriveViewport(d, anchor, scrolled, H);
            NativeCore.deriveDemandScene(d, true);
            NativeCore.deriveSettle(d);
            DocumentText doc = new DocumentText(d);
            TextInput input = new TextInput(new View(activity), doc);
            Matrix matrix = new Matrix();
            matrix.setTranslate(0, 210);
            /* Paragraph tops in the viewport, from the anchor until past the bottom edge. */
            List<Integer> paras = new ArrayList<>();
            List<Float> tops = new ArrayList<>();
            float y = -scrolled;
            for (int i = anchor; i < n && y < H; i++) {
                paras.add(i); tops.add(y);
                StaticLayout l = CaretOracle.platform(corpus.get(i));
                y += l.getLineBottom(l.getLineCount() - 1);
            }
            Random r = new Random(7);
            for (int k = 0; k < 80; k++) {
                int which = k < 4 ? (k < 2 ? 0 : paras.size() - 1) : r.nextInt(paras.size());
                int p = paras.get(which), len = corpus.get(p).length();
                StaticLayout l = CaretOracle.platform(corpus.get(p));
                int o = k == 0 ? 0 : k == 3 ? len : r.nextInt(len + 1);
                if (k == 2 || k == 3) {   /* a line past the bottom edge, or the first line, clipped */
                    int line = k == 3 ? l.getLineCount() - 1 : 0;
                    o = Math.min(len, l.getLineStart(line));
                }
                NativeCore.deriveSelect(d, p, o);
                NativeCore.deriveSettle(d);
                CursorAnchorInfo info = doc.cursorAnchorInfo(matrix, W, H);
                long g = global(corpus, p, o);
                float top = tops.get(which);
                int line = l.getLineForOffset(o);
                float x = l.getPrimaryHorizontal(o), mt = top + l.getLineTop(line), mb = top + l.getLineBaseline(line), mbot = top + l.getLineBottom(line);
                boolean vt = DocumentText.visible(x, mt, W, H), vb = DocumentText.visible(x, mbot, W, H);
                int flags = (vt || vb ? CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION : 0) | (!vt || !vb ? CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION : 0)
                    | (l.isRtlCharAt(o) ? CursorAnchorInfo.FLAG_IS_RTL : 0);
                double diff = Math.max(Math.max(Math.abs(info.getInsertionMarkerHorizontal() - x), Math.abs(info.getInsertionMarkerTop() - mt)),
                    Math.max(Math.abs(info.getInsertionMarkerBaseline() - mb), Math.abs(info.getInsertionMarkerBottom() - mbot)));
                if (Double.isNaN(diff)) diff = Double.MAX_VALUE;
                worst = Math.max(worst, diff);
                markers++;
                if ((flags & CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION) != 0) clipped++;
                if ((flags & CursorAnchorInfo.FLAG_IS_RTL) != 0) rtl++;
                if (diff > 0.01 || info.getInsertionMarkerFlags() != flags || info.getSelectionStart() != g || info.getSelectionEnd() != g
                        || !matrix.equals(info.getMatrix()))
                    if (failures.size() < 8) failures.add(Json.map("para", p, "offset", o, "diff", diff, "flags", Arrays.asList(info.getInsertionMarkerFlags(), flags),
                        "selection", Arrays.asList(info.getSelectionStart(), info.getSelectionEnd(), g), "text", corpus.get(p).substring(Math.max(0, o - 6), Math.min(len, o + 6))));
                /* Composing text: a region around the caret, possibly across paragraphs. */
                if (k % 5 == 0) {
                    int a = (int) Math.max(0, g - r.nextInt(12)), b = (int) (g + 1 + r.nextInt(30));
                    input.setComposingRegion(a, b);
                    String joined = String.join("\n", corpus);
                    int ea = Math.min(a, joined.length()), eb = Math.min(b, joined.length());
                    info = doc.cursorAnchorInfo(matrix, W, H);
                    composing++;
                    if (info.getComposingTextStart() != ea || !joined.substring(ea, eb).equals(String.valueOf(info.getComposingText())))
                        failures.add(Json.map("composing", Arrays.asList(a, b), "got", info.getComposingTextStart(), "text", String.valueOf(info.getComposingText())));
                    /* Character bounds for the composing units in the caret's paragraph, as
                     * fillCharacterBounds; units in other paragraphs have none yet. */
                    int pg = (int) (g - o), ca = Math.max(ea, pg), ce = Math.min(eb, pg + len);
                    float[] ref = new float[4 * Math.max(0, ce - ca)];
                    if (ca < ce) l.fillCharacterBounds(ca - pg, ce - pg, ref, 0);
                    for (int u = ea; u < eb; u++) {
                        android.graphics.RectF got = info.getCharacterBounds(u);
                        boolean inside = u >= ca && u < ce;
                        if (!inside) { if (got != null) failures.add(Json.map("bounds_outside", u)); continue; }
                        bounded++;
                        int q = 4 * (u - ca);
                        float bl = ref[q], bt = top + ref[q + 1], br = ref[q + 2], bb = top + ref[q + 3];
                        boolean v0 = DocumentText.visible(bl, bt, W, H), v1 = DocumentText.visible(br, bb, W, H);
                        int bf = (v0 || v1 ? CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION : 0) | (!v0 || !v1 ? CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION : 0)
                            | (l.isRtlCharAt(u - pg) ? CursorAnchorInfo.FLAG_IS_RTL : 0);
                        double bd = got == null ? Double.MAX_VALUE : Math.max(Math.max(Math.abs(got.left - bl), Math.abs(got.top - bt)), Math.max(Math.abs(got.right - br), Math.abs(got.bottom - bb)));
                        worstBounds = Math.max(worstBounds, bd);
                        if ((bd > 0.01 || info.getCharacterBoundsFlags(u) != bf) && failures.size() < 12)
                            failures.add(Json.map("bounds", u - pg, "para", p, "diff", bd, "flags", Arrays.asList(info.getCharacterBoundsFlags(u), bf),
                                "got", got == null ? null : Arrays.asList(got.left, got.top, got.right, got.bottom), "want", Arrays.asList(bl, bt, br, bb),
                                "unit", corpus.get(p).substring(u - pg, u - pg + 1)));
                    }
                    input.finishComposingText();
                }
            }
            /* A caret in a paragraph the overlay does not draw has no marker yet (documented gap). */
            int below = paras.get(paras.size() - 1) + 1;
            if (below < n) {
                NativeCore.deriveSelect(d, below, 0);
                NativeCore.deriveSettle(d);
                if (doc.cursorAnchorInfo(matrix, W, H).getInsertionMarkerFlags() == 0 && Float.isNaN(doc.cursorAnchorInfo(matrix, W, H).getInsertionMarkerTop())) hidden++;
                else failures.add(Json.map("offscreen", "unexpected marker"));
            }
            /* A range selection reports its ends and no marker. */
            NativeCore.deriveSelectRange(d, paras.get(1), 0, paras.get(1), 5);
            NativeCore.deriveSettle(d);
            CursorAnchorInfo range = doc.cursorAnchorInfo(matrix, W, H);
            long ra = global(corpus, paras.get(1), 0);
            if (range.getSelectionStart() != ra || range.getSelectionEnd() != ra + 5) failures.add(Json.map("range", Arrays.asList(range.getSelectionStart(), range.getSelectionEnd(), ra)));
        } finally {
            NativeCore.deriveClose(d);
        }
        return Json.map("ok", failures.isEmpty() && clipped > 0 && rtl > 0 && bounded > 0, "markers", markers, "clipped", clipped, "rtl", rtl,
            "composing", composing, "character_bounds", bounded, "worst_bounds_diff", worstBounds, "offscreen_unmarked", hidden, "worst_marker_diff", worst, "failures", failures);
    }
    private static long global(List<String> corpus, int p, int o) {
        long g = 0;
        for (int i = 0; i < p; i++) g += corpus.get(i).length() + 1;
        return g + o;
    }
}
