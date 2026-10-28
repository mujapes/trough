package com.trough.hot;

import android.graphics.*;
import android.text.*;
import java.nio.ByteBuffer;
import java.util.*;

/** android.layout: TextLayout against the platform's StaticLayout for the same text, width and
 * paint (high-quality breaking, no hyphenation, first-strong direction, no extra padding).
 * Line starts, line count and baselines must agree, and the glyphs TextLayout positions, drawn
 * by the platform, must produce StaticLayout's pixels. Offscreen; Trough need not be visible. */
final class LayoutOracle {
    private static final String[] TEXTS = {
        "The quick brown fox jumps over the lazy dog. Typography with ligatures: office, affluent, fjord.\nSecond paragraph after a newline, wrapping across several lines at narrow widths.",
        "مرحبا بالعالم. هذا نص عربي طويل يلتف على عدة أسطر لاختبار الاتجاه من اليمين إلى اليسار.",
        "Mixed direction: the word שלום means peace, and 12345 stays left to right inside עברית text.",
        "हिन्दी पाठ को कई पंक्तियों में लपेटना, संयुक्ताक्षर और मात्राएँ सही दिखनी चाहिए।",
        "中文文本没有空格也必须正确换行，日本語のテキストも同様に折り返されるべきです。混在 text too.",
        "\n\nLeading empty paragraphs, then text.\n"};

    static Map<String, Object> run() {
        TextPaint paint = new TextPaint();
        paint.setAntiAlias(true);
        paint.setTextSize(36);
        paint.setColor(Color.BLACK);
        int cases = 0, lineMismatch = 0, baselineMismatch = 0, pixelCases = 0, linesTotal = 0;
        long badPixels = 0;
        List<Object> failures = new ArrayList<>();
        int[] widths = {240, 410, 655, 1000};
        for (String text : TEXTS) for (int width : widths) {
            cases++;
            StaticLayout ref = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setIncludePad(false).setUseLineSpacingFromFallbacks(true).build();
            TextLayout ours = TextLayout.layout(text, paint, width, 0, 0);
            linesTotal += ref.getLineCount();
            boolean linesOk = ref.getLineCount() == ours.lines.size();
            for (int i = 0; linesOk && i < ref.getLineCount(); i++) linesOk = ref.getLineStart(i) == ours.lines.get(i).start;
            boolean baselinesOk = linesOk;
            for (int i = 0; baselinesOk && i < ref.getLineCount(); i++)
                baselinesOk = Math.abs(ref.getLineBaseline(i) - ours.lines.get(i).baseline) < 0.51f;
            if (!linesOk) lineMismatch++;
            if (!baselinesOk) baselineMismatch++;
            long bad = -1;
            if (linesOk) {
                pixelCases++;
                int h = Math.max(ref.getHeight(), (int) Math.ceil(ours.height)) + 8;
                Bitmap a = Bitmap.createBitmap(width + 8, h, Bitmap.Config.ARGB_8888), b = Bitmap.createBitmap(width + 8, h, Bitmap.Config.ARGB_8888);
                ref.draw(new Canvas(a));
                Canvas cb = new Canvas(b);
                for (TextLayout.Glyph g : ours.glyphs) cb.drawGlyphs(new int[]{g.id}, 0, new float[]{g.x, g.y}, 0, 1, g.font, paint);
                ByteBuffer ba = ByteBuffer.allocate(a.getByteCount()), bb = ByteBuffer.allocate(b.getByteCount());
                a.copyPixelsToBuffer(ba); b.copyPixelsToBuffer(bb);
                byte[] pa = ba.array(), pb = bb.array();
                bad = 0;
                for (int p = 0; p < pa.length; p += 4) {
                    int worst = 0;
                    for (int c = 0; c < 4; c++) worst = Math.max(worst, Math.abs((pa[p + c] & 255) - (pb[p + c] & 255)));
                    if (worst > 2) bad++;
                }
                badPixels += bad;
            }
            if ((!linesOk || !baselinesOk || bad != 0) && failures.size() < 6) {
                List<Integer> rs = new ArrayList<>(), os = new ArrayList<>();
                for (int i = 0; i < ref.getLineCount(); i++) rs.add(ref.getLineStart(i));
                for (TextLayout.Line l : ours.lines) os.add(l.start);
                failures.add(Json.map("text", text.substring(0, Math.min(24, text.length())), "width", width,
                    "ref_starts", rs, "our_starts", os, "bad_pixels", bad,
                    "ref_baseline0", ref.getLineCount() > 0 ? ref.getLineBaseline(0) : -1,
                    "our_baseline0", ours.lines.isEmpty() ? -1 : ours.lines.get(0).baseline));
            }
        }
        return Json.map("ok", lineMismatch == 0 && baselineMismatch == 0 && badPixels == 0, "cases", cases, "lines", linesTotal,
            "line_mismatches", lineMismatch, "baseline_mismatches", baselineMismatch, "pixel_cases", pixelCases,
            "bad_pixels", badPixels, "failures", failures);
    }
}
