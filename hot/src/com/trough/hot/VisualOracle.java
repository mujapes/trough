package com.trough.hot;

import android.graphics.Paint;
import android.graphics.text.LineBreaker;
import android.graphics.text.MeasuredText;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;
import java.io.File;
import java.text.Bidi;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Independent visual placement/retained-font oracle. Android supplies logical
 * line ranges here; android.paragraphmeasure separately verifies native ranges. */
final class VisualOracle {
    private int cases, lines, runs, glyphs, failures;
    private float worst;
    private final List<String> details = new ArrayList<>();
    private void fail(String value) { failures++; if (details.size() < 24) details.add(value); }
    private boolean near(float a, float b) {
        if (!Float.isFinite(a)) return false;
        float d = Math.abs(a-b); worst = Math.max(worst, d); return d <= 0.0001f;
    }
    private void line(char[] chars, Paint paint, String locale, Bidi paragraph,
                      int start, int end, float width, float lineWidth, String label) {
        lines++;
        float left = 3.25f, baseline = 11.75f + lines%7 * 19.5f;
        Object[] raw = NativeCore.visualLine(chars, locale, paint.getTextSize(), start, end,
                                            width, lineWidth, left, baseline);
        if (raw == null || raw.length != 2) { fail(label + " native failure: " + NativeCore.shapeError()); return; }
        float[] wire = (float[])raw[0]; String[] names = (String[])raw[1];
        int visible = end;
        while (visible > start && Character.isWhitespace(chars[visible-1])) visible--;
        Bidi bidi = visible == start ? null : paragraph.createLineBidi(start, visible);
        int count = bidi == null ? 0 : bidi.getRunCount();
        if (wire.length < 4 || wire[0] != visible || wire[1] != paragraph.getBaseLevel()
                || wire[2] != count || wire[3] != names.length || wire.length != 4+count*6+names.length*4) {
            fail(label + " header"); return;
        }
        byte[] levels = new byte[count]; Integer[] order = new Integer[count];
        for (int i = 0; i < count; ++i) { levels[i] = (byte)bidi.getRunLevel(i); order[i] = i; }
        if (count > 0) Bidi.reorderVisually(levels, 0, order, 0, count);
        float pen = paragraph.baseIsLeftToRight() ? left : left+width-lineWidth;
        int gi = 0, visual = 0, glyphAt = 4+count*6;
        for (int logical : order) {
            runs++;
            int rs = start+bidi.getRunStart(logical), re = start+bidi.getRunLimit(logical);
            int level = bidi.getRunLevel(logical), at = 4+visual++*6;
            PositionedGlyphs expected = TextRunShaper.shapeTextRun(chars, rs, re-rs,
                start, visible-start, pen, baseline, (level&1) != 0, paint);
            if (wire[at] != rs || wire[at+1] != re || wire[at+2] != level
                    || !near(wire[at+3], pen) || !near(wire[at+4], expected.getAdvance())
                    || wire[at+5] != expected.glyphCount()) fail(label + " visual run=" + (visual-1));
            for (int g = 0; g < expected.glyphCount(); ++g, ++gi) {
                glyphs++;
                if (gi >= names.length) { fail(label + " glyph count"); return; }
                int pos = glyphAt+gi*4;
                if (wire[pos] != expected.getGlyphId(g)
                        || !ShapeOracle.sameFont(names[gi], expected.getFont(g))
                        || !near(wire[pos+2], expected.getGlyphX(g))
                        || !near(wire[pos+3], expected.getGlyphY(g))) fail(label + " glyph=" + gi);
                if (wire[pos+1] < rs || wire[pos+1] >= re) fail(label + " cluster=" + gi);
            }
            pen += expected.getAdvance();
        }
        if (gi != names.length) fail(label + " final glyph count");
    }
    private void check(String text, float size, float width, String locale, String label) {
        cases++;
        char[] chars = text.toCharArray();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(size); paint.setTextLocale(Locale.forLanguageTag(locale));
        Bidi bidi = new Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
        if (chars.length == 0) { line(chars, paint, locale, bidi, 0, 0, width, 0, label); return; }
        MeasuredText.Builder builder = new MeasuredText.Builder(chars);
        for (int i = 0; i < bidi.getRunCount(); ++i)
            builder.appendStyleRun(paint, bidi.getRunLimit(i)-bidi.getRunStart(i), (bidi.getRunLevel(i)&1) != 0);
        LineBreaker breaker = new LineBreaker.Builder()
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(LineBreaker.HYPHENATION_FREQUENCY_NONE).build();
        LineBreaker.ParagraphConstraints constraints = new LineBreaker.ParagraphConstraints();
        constraints.setWidth(width); constraints.setTabStops(null, 20);
        LineBreaker.Result broken = breaker.computeLineBreaks(builder.build(), constraints, 0);
        int start = 0;
        for (int i = 0; i < broken.getLineCount(); ++i) {
            int end = broken.getLineBreakOffset(i);
            line(chars, paint, locale, bidi, start, end, width, broken.getLineWidth(i), label + " line=" + i);
            start = end;
        }
    }
    static Map<String, Object> run(File bundle) throws Exception {
        NativeCore.load(bundle);
        long[] before = NativeCore.fontResources();
        VisualOracle o = new VisualOracle();
        List<String> texts = new ArrayList<>();
        texts.addAll(Arrays.asList(ShapeOracle.TEXT));
        texts.add("  \t"); texts.add("אבג 123 (hello)\tالعربية   ");
        texts.add("a\u202bאב\u202a12 x\u202cגד\u202c z");
        texts.add("\u2067אב \u2066abc 12\u2069 גד\u2069   ");
        texts.add("abc\u2000\u2007\u3000\u00a0\u202f");
        for (int t = 0; t < texts.size(); ++t)
            for (float size : new float[]{0.5f, 17.3f, 32})
                for (float width : new float[]{1, 57.5f, 210})
                    o.check(texts.get(t), size, width, "en-US", "text="+t+" size="+size+" width="+width);
        long seed = 0x76697375616cL; Random random = new Random(seed);
        String[] pieces = {"abc", "אבג", "العربية", "日本語", "ภาษาไทย", "👩🏽‍🚀", " ", "\t", "123", "\ud800", "\udc00", "\u2067", "\u2069"};
        String[] locales = {"en-US", "ar", "he", "ja-JP", "th-TH"};
        for (int t = 0; t < 96; ++t) {
            StringBuilder s = new StringBuilder();
            for (int j = 0, n = 2+random.nextInt(12); j < n; ++j) s.append(pieces[random.nextInt(pieces.length)]);
            o.check(s.toString(), 9+random.nextInt(100)/4f, 1+random.nextInt(800)/4f,
                    locales[t%locales.length], "random="+t);
        }
        char[] one = {'a'};
        boolean invalid = NativeCore.visualLine(null, "en", 17, 0, 0, 20, 0, 0, 0) == null
            && NativeCore.visualLine(one, "en", 17, 0, 2, 20, 0, 0, 0) == null
            && NativeCore.visualLine(one, "en", 17, 0, 1, Float.NaN, 0, 0, 0) == null;
        long[] after = NativeCore.fontResources();
        boolean released = Arrays.equals(before, after);
        return Json.map("ok", o.failures == 0 && invalid && released, "cases", o.cases,
            "lines", o.lines, "runs", o.runs, "glyphs", o.glyphs, "mismatches", o.failures,
            "worst_difference", o.worst, "seed", seed, "invalid_inputs_rejected", invalid,
            "owners_released", released, "resources_before", Arrays.toString(before),
            "resources_after", Arrays.toString(after), "first_failures", o.details);
    }
}
