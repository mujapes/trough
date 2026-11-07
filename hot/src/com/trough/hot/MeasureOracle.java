package com.trough.hot;

import android.graphics.Paint;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Independent public Paint queries check the native cluster/measurement candidate. */
final class MeasureOracle {
    private final List<String> failures = new ArrayList<>();
    private int runs, widths, carets, cursors, hits, clusters, mismatches;
    private float worst;

    private void fail(String message) {
        mismatches++;
        if (failures.size() < 24) failures.add(message);
    }
    private void equal(float got, float ref, String where) {
        float delta = Math.abs(got - ref);
        worst = Math.max(worst, delta);
        if (!Float.isFinite(got) || delta > 0.0001f) fail(where + " got=" + got + " expected=" + ref);
    }
    private void check(Paint paint, char[] text, int start, int end, boolean rtl, String where) {
        int count = end - start;
        float[] refWidths = new float[count];
        float total = paint.getTextRunAdvances(text, start, count, 0, text.length, rtl, refWidths, 0);
        PositionedGlyphs refGlyphs = TextRunShaper.shapeTextRun(text, start, count, 0,
            text.length, 0, 0, rtl, paint);
        float[] refCarets = new float[count + 1];
        for (int i = 0; i <= count; i++)
            refCarets[i] = paint.getRunAdvance(text, start, end, 0, text.length, rtl, start + i);
        // Sweep outside the range as well as both sides of every measured caret and midpoint.
        float[] queries = new float[33 + 3 * (count + 1) + count];
        int q = 0;
        for (int i = 0; i < 33; i++) queries[q++] = (i - 1) * (total + 1) / 30;
        for (float x : refCarets) { queries[q++] = x; queries[q++] = x - 0.01f; queries[q++] = x + 0.01f; }
        for (int i = 0; i < count; i++) queries[q++] = (refCarets[i] + refCarets[i + 1]) / 2;
        byte[] wire = NativeCore.shapeProbe(text, start, end, rtl, paint.getTextSize(), queries);
        runs++;
        if (wire == null || wire.length < 20) { fail(where + " native failure: " + NativeCore.shapeError()); return; }
        ByteBuffer b = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN);
        int version = b.getInt(), glyphs = b.getInt(), units = b.getInt(), nq = b.getInt();
        float advance = b.getFloat();
        if (version != 1 || glyphs < 0 || units != count || nq != queries.length
                || wire.length != 20L + 24L * glyphs + 4L * count + 24L * (count + 1) + 4L * nq) {
            fail(where + " malformed native probe"); return;
        }
        equal(advance, total, where + " total");
        if (glyphs != refGlyphs.glyphCount()) fail(where + " glyph count got=" + glyphs
            + " expected=" + refGlyphs.glyphCount());
        float[] clusterWidths = new float[count];
        for (int i = 0; i < glyphs; i++) {
            int face = b.getInt(), glyph = b.getInt(), cluster = b.getInt();
            float x = b.getFloat(), y = b.getFloat(), width = b.getFloat();
            clusters++;
            if (face < 0 || glyph < 0 || cluster < start || cluster >= end
                    || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(width))
                fail(where + " invalid glyph/cluster " + i);
            else clusterWidths[cluster - start] += width;
            if (i < refGlyphs.glyphCount()) {
                if (glyph != refGlyphs.getGlyphId(i)) fail(where + " glyph ID@" + i);
                if (!ShapeOracle.sameFont(NativeCore.shapeFace(face), refGlyphs.getFont(i)))
                    fail(where + " font@" + i);
                equal(x, refGlyphs.getGlyphX(i), where + " glyph x@" + i);
                equal(y, refGlyphs.getGlyphY(i), where + " glyph y@" + i);
            }
        }
        for (int i = 0; i < count; i++) {
            float width = b.getFloat(); widths++;
            equal(width, refWidths[i], where + " width@" + (start + i));
            equal(clusterWidths[i], width, where + " cluster sum@" + (start + i));
        }
        for (int i = 0; i <= count; i++) {
            carets++; equal(b.getFloat(), refCarets[i], where + " caret@" + (start + i));
        }
        for (int option = Paint.CURSOR_AFTER; option <= Paint.CURSOR_AT; option++) {
            for (int i = 0; i <= count; i++) {
                int got = b.getInt();
                // Paint's cursor API shapes exactly its context range, whereas run advances
                // above deliberately retain surrounding text. Check matching contexts only.
                if (start == 0 && end == text.length) {
                    int ref = paint.getTextRunCursor(text, 0, text.length, rtl, i, option);
                    cursors++;
                    if (got != ref) fail(where + " cursor@" + i + " option=" + option + " got=" + got + " expected=" + ref);
                }
            }
        }
        for (float query : queries) {
            int got = b.getInt();
            int ref = paint.getOffsetForAdvance(text, start, end, 0, text.length, rtl, query);
            hits++;
            if (got != ref) fail(where + " hit x=" + query + " got=" + got + " expected=" + ref);
        }
    }
    static Map<String, Object> run(File bundle) throws Exception {
        NativeCore.load(bundle);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (NativeCore.shapeInit(paint.getTextLocales().toLanguageTags()) != 0)
            return Json.map("ok", false, "error", "native shaping init");
        MeasureOracle oracle = new MeasureOracle();
        List<String> texts = new ArrayList<>();
        for (String text : ShapeOracle.TEXT) texts.add(text);
        texts.add("\r\n\tA\u0000B\u00ad\u200b\u2060\ufeff C");
        texts.add("\u1100\u1161\u11a8 가각 \u0600A \u0e33\u0e3a \u103a");
        texts.add("🇬🇧🇺🇸🇯 🇦🇧🇨🇩🇪 👩🏽‍🚀 a\u200d😀");
        texts.add("a\u0301\u0308 fi ffi ffl office \ud800 \udc00");
        for (int t = 0; t < texts.size(); t++) for (float size : ShapeOracle.SIZES)
                for (boolean rtl : new boolean[]{false, true}) {
            char[] text = texts.get(t).toCharArray(); paint.setTextSize(size);
            for (int mode = 0; mode < (text.length >= 4 ? 4 : 1); mode++) {
                int start = mode == 0 ? 0 : 1;
                int end = mode == 2 ? text.length - 1 : mode == 3 ? start : text.length;
                oracle.check(paint, text, start, end, rtl,
                    "text=" + t + " size=" + size + " rtl=" + rtl + " range=" + start + ":" + end);
            }
        }
        // Held-out combinations vary script/cache boundaries and slice through UTF-16 pairs.
        // Keep the seed stable so every device counterexample can be reproduced verbatim.
        final long seed = 0x74726f756768L;
        Random random = new Random(seed);
        String[] pieces = {"fi", "ffi", "AV", "a\u0301", "\u0308", " ", "\t", "\r\n",
            "العربية", "نَصّ", "אב", "क्षि", "क्\u200dष", "日本", "ก้", "👩🏽‍🚀", "🇬🇧",
            "\u2067", "\u2069", "\u200b", "\u200d", "\ud800", "\udc00"};
        paint.setTextSize(17.3f);
        for (int sample = 0; sample < 32; sample++) {
            StringBuilder mixed = new StringBuilder();
            for (int i = 0, n = 2 + random.nextInt(7); i < n; i++)
                mixed.append(pieces[random.nextInt(pieces.length)]);
            char[] text = mixed.toString().toCharArray();
            int start = random.nextInt(text.length + 1);
            int end = start + random.nextInt(text.length - start + 1);
            char[] slice = new char[end - start];
            System.arraycopy(text, start, slice, 0, slice.length);
            for (boolean rtl : new boolean[]{false, true}) {
                String where = "seed=" + seed + " sample=" + sample + " rtl=" + rtl;
                oracle.check(paint, text, 0, text.length, rtl, where + " full");
                oracle.check(paint, text, start, end, rtl, where + " range=" + start + ":" + end);
                oracle.check(paint, slice, 0, slice.length, rtl, where + " sliced context");
            }
        }
        char[] one = {'x'};
        boolean invalid = NativeCore.shapeProbe(null, 0, 0, false, 16, null) == null
            && NativeCore.shapeProbe(one, -1, 1, false, 16, null) == null
            && NativeCore.shapeProbe(one, 0, 2, false, 16, null) == null
            && NativeCore.shapeProbe(one, 1, 0, false, 16, null) == null
            && NativeCore.shapeProbe(one, 0, 1, false, Float.NaN, null) == null
            && NativeCore.shapeProbe(one, 0, 1, false, Float.POSITIVE_INFINITY, null) == null
            && NativeCore.shapeProbe(one, 0, 1, false, -1, null) == null
            && NativeCore.shapeProbe(one, 0, 1, false, 16, new float[]{Float.NaN}) == null
            && NativeCore.shapeProbe(one, 0, 1, false, 16, new float[]{Float.POSITIVE_INFINITY}) == null;
        Map<String, Object> ownership = Json.obj(Json.parse(NativeCore.shapeOwnership(paint.getTextLocales().toLanguageTags())));
        return Json.map("ok", invalid && oracle.mismatches == 0 && Boolean.TRUE.equals(ownership.get("ok")),
            "ownership", ownership, "invalid_inputs_rejected", invalid,
            "seed", seed,
            "runs", oracle.runs, "character_widths", oracle.widths, "glyph_clusters", oracle.clusters,
            "caret_advances", oracle.carets, "cursor_queries", oracle.cursors, "hit_queries", oracle.hits,
            "mismatches", oracle.mismatches, "worst_difference", oracle.worst, "first_failures", oracle.failures);
    }
}
