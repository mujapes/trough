package com.trough.hot;

import android.graphics.Paint;
import android.graphics.text.LineBreaker;
import android.graphics.text.MeasuredText;
import java.io.File;
import java.text.Bidi;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Independent public LineBreaker oracle, with measured advances as captured input. */
final class BreakOracle {
    private int cases, lines, failures;
    private boolean nativeMeasurement;
    private int widths;
    private int extents;
    private float worst;
    private final List<String> details = new ArrayList<>();
    private final List<Object> inputs = new ArrayList<>();
    private void fail(String s) { failures++; if (details.size() < 24) details.add(s); }
    private void check(String text, float size, float width, String locale, String label) {
        cases++;
        char[] chars = text.toCharArray();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(size); paint.setTextLocale(Locale.forLanguageTag(locale));
        Bidi bidi = new Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
        MeasuredText.Builder builder = new MeasuredText.Builder(chars);
        for (int i = 0; i < bidi.getRunCount(); i++) {
            int length = bidi.getRunLimit(i) - bidi.getRunStart(i);
            if (length > 0) builder.appendStyleRun(paint, length, (bidi.getRunLevel(i) & 1) != 0);
        }
        MeasuredText measured = builder.build();
        float[] advances = new float[chars.length];
        for (int i = 0; i < chars.length; i++) advances[i] = measured.getCharWidthAt(i);
        LineBreaker breaker = new LineBreaker.Builder()
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(LineBreaker.HYPHENATION_FREQUENCY_NONE)
            .setJustificationMode(LineBreaker.JUSTIFICATION_MODE_NONE).build();
        LineBreaker.ParagraphConstraints constraints = new LineBreaker.ParagraphConstraints();
        constraints.setWidth(width);
        constraints.setTabStops(null, 20);
        LineBreaker.Result expected = breaker.computeLineBreaks(measured, constraints, 0);
        int before = failures;
        float[] got;
        if (nativeMeasurement) {
            float[] paragraph = NativeCore.paragraphMeasure(chars, locale, size, width, -1);
            if (paragraph == null || paragraph.length < chars.length) {
                fail(label + " native paragraph failed: " + NativeCore.shapeError());
                got = null;
            } else {
                for (int i = 0; i < chars.length; i++) {
                    widths++;
                    float difference = Math.abs(paragraph[i] - advances[i]);
                    worst = Math.max(worst, difference);
                    if (!Float.isFinite(paragraph[i]) || difference > 0.0001f)
                        fail(label + " unit=" + i + " native=" + paragraph[i] + " measured=" + advances[i]);
                }
                int lineCount = (paragraph.length - chars.length) / 4;
                got = new float[lineCount * 2];
                for (int i = 0; i < lineCount; i++) {
                    got[i*2] = paragraph[chars.length + i*4];
                    got[i*2+1] = paragraph[chars.length + i*4+1];
                    if (i >= expected.getLineCount()) continue;
                    extents++;
                    float ascent = paragraph[chars.length + i*4+2];
                    float descent = paragraph[chars.length + i*4+3];
                    if (!Float.isFinite(ascent) || !Float.isFinite(descent)
                            || Math.abs(ascent - expected.getLineAscent(i)) > 0.0001f
                            || Math.abs(descent - expected.getLineDescent(i)) > 0.0001f)
                        fail(label + " line=" + i + " extent=" + ascent + "/" + descent
                             + " expected=" + expected.getLineAscent(i) + "/" + expected.getLineDescent(i));
                }
            }
        } else got = NativeCore.lineBreaks(chars, advances, locale, width, size);
        if (got == null || got.length != expected.getLineCount() * 2) {
            fail(label + " count got=" + (got == null ? -1 : got.length/2)
                 + " expected=" + expected.getLineCount());
        } else for (int i = 0; i < expected.getLineCount(); i++) {
            lines++;
            float diff = Math.abs(got[i*2+1] - expected.getLineWidth(i));
            worst = Math.max(worst, diff);
            if (got[i*2] != expected.getLineBreakOffset(i) || diff > 0.0001f)
                fail(label + " line=" + i + " got=" + got[i*2] + "/" + got[i*2+1]
                     + " expected=" + expected.getLineBreakOffset(i) + "/" + expected.getLineWidth(i));
        }
        if (before != failures && inputs.size() < 8) {
            float[] reference = new float[expected.getLineCount()*2];
            for (int i = 0; i < expected.getLineCount(); i++) {
                reference[i*2] = expected.getLineBreakOffset(i); reference[i*2+1] = expected.getLineWidth(i);
            }
            inputs.add(Json.map("label", label, "text", text, "size", size, "width", width, "locale", locale,
                "advances", Arrays.toString(advances), "native", Arrays.toString(got),
                "reference", Arrays.toString(reference), "candidates", Arrays.toString(
                    NativeCore.lineCandidates(chars, advances, locale, width, size))));
        }
    }
    static Map<String, Object> run(File bundle) throws Exception {
        return run(bundle, false);
    }
    static Map<String, Object> runNative(File bundle) throws Exception {
        return run(bundle, true);
    }
    private static Map<String, Object> run(File bundle, boolean nativeMeasurement) throws Exception {
        NativeCore.load(bundle);
        BreakOracle o = new BreakOracle();
        o.nativeMeasurement = nativeMeasurement;
        long[] resourcesBefore = NativeCore.fontResources();
        o.check("👩🏽‍🚀  👩🏽‍🚀العربيةภาษาไทย  \udc00العربيةa-bالعربية", 37.75f, 143.25f, "en-US", "regression120");
        o.check("\udc00ภาษาไทยאבג\udc00a\u0301العربيةabc123\udc00العربية👩🏽‍🚀a-b\udc00  a-b", 20.75f, 161, "he", "regression169");
        List<String> texts = new ArrayList<>();
        for (String s : ShapeOracle.TEXT) texts.add(s);
        texts.add(""); texts.add("     "); texts.add("one two three four five six seven eight nine ten");
        texts.add("https://example.com/a-long_path?q=abc&x=12 mail+a@example.co.uk end");
        texts.add("extraordinarilylongunbreakablewordwithoutspaces");
        texts.add("a-b c\u00add e\u2010f g\u2013h i\u058aj");
        texts.add("a\u2000b\u2007c\u3000 d\u00a0e\u202ff   ");
        texts.add("abc אבג 123 العربية (test) 42 xyz");
        texts.add("👩🏽‍🚀👨‍👩‍👧‍👦❤️🇺🇳 a\u0301 ffi क्‍ष hello");
        texts.add("\t"); texts.add("\t\t\t"); texts.add("one\ttwo\tthree\tfour   ");
        texts.add("longwordwithoutspaces\tאבג\t https://example.com/path?q=one&x=two\t");
        for (int t = 0; t < texts.size(); t++) {
            for (float size : new float[]{0.5f, 13, 23.75f}) {
                for (float width : new float[]{1, 24.5f, 80, 191.25f, 420})
                    o.check(texts.get(t), size, width, "en-US", "text=" + t + " s=" + size + " w=" + width);
            }
        }
        long seed = 0x627265616bL;
        Random r = new Random(seed);
        String[] parts = {"abc", " ", "  ", "אבג", "العربية", "123", "a-b", "👩🏽‍🚀", "a\u0301", "漢字", "ภาษาไทย", "\ud800", "\udc00", "\t"};
        String[] locales = {"en-US", "ja-JP", "th-TH", "ar", "he"};
        for (int t = 0; t < 256; t++) {
            StringBuilder s = new StringBuilder();
            for (int i = 0, n = 2 + r.nextInt(18); i < n; i++) s.append(parts[r.nextInt(parts.length)]);
            o.check(s.toString(), 9 + r.nextInt(120)/4f, 1 + r.nextInt(1600)/4f,
                    locales[t % locales.length], "random=" + t);
        }
        boolean invalid = NativeCore.lineBreaks(null, new float[0], "en", 20, 20) == null
            && NativeCore.lineBreaks(new char[]{'a'}, new float[0], "en", 20, 20) == null
            && NativeCore.lineBreaks(new char[]{'a'}, new float[]{1}, "en", Float.NaN, 20) == null
            && NativeCore.lineBreaks(new char[]{'\t'}, new float[]{Float.NaN}, "en", 20, 20) == null;
        boolean cancellation = true;
        if (nativeMeasurement) {
            invalid &= NativeCore.paragraphMeasure(null, "en", 20, 20, -1) == null
                && NativeCore.paragraphMeasure(new char[]{'a'}, "en", Float.NaN, 20, -1) == null
                && NativeCore.paragraphMeasure(new char[]{'a'}, "en", 20, Float.NaN, -1) == null;
            char[] mixed = "abc אבג العربية 123 日本語\tend".toCharArray();
            /* Cancellation before measurement, between bidi runs, during the
             * text scan and during line breaking must release every owner. */
            for (int at = 0; at < 30; at++)
                cancellation &= NativeCore.paragraphMeasure(mixed, "en", 20, 50, at) == null;
        }
        long[] resourcesAfter = NativeCore.fontResources();
        boolean released = Arrays.equals(resourcesBefore, resourcesAfter);
        return Json.map("ok", invalid && cancellation && released && o.failures == 0, "cases", o.cases, "lines", o.lines,
            "native_measurement", nativeMeasurement, "character_widths", o.widths, "line_extents", o.extents,
            "cancellation", cancellation, "owners_released", released,
            "resources_before", Arrays.toString(resourcesBefore), "resources_after", Arrays.toString(resourcesAfter),
            "mismatches", o.failures, "worst_width_difference", o.worst, "seed", seed,
            "invalid_inputs_rejected", invalid, "first_failures", o.details, "failure_inputs", o.inputs);
    }
}
