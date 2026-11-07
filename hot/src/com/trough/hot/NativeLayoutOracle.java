package com.trough.hot;

import android.graphics.Paint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontVariationAxis;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextDirectionHeuristics;
import android.text.Layout;
import java.nio.ByteBuffer;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.HashMap;

/** Complete native paragraph output against the independently implemented,
 * platform-backed TextLayout. Does not feed platform geometry into the candidate. */
final class NativeLayoutOracle {
    private int cases, lines, runs, glyphs, carets, hits, failures, exceptions;
    private float worst;
    private int staticCases;
    private long badPixels;
    private final List<String> details = new ArrayList<>();
    private final Map<String, Font> fonts = new HashMap<>();
    private boolean recordedCase;
    private void fail(String value) {
        failures++;
        if (!recordedCase && details.size() < 64) details.add(value);
        recordedCase = true;
    }
    private boolean near(float a, float b) {
        if (!Float.isFinite(a)) return false;
        float d = Math.abs(a-b); worst = Math.max(worst, d); return d <= 0.0001f;
    }
    private void check(String text, float size, float width, String locale, String label) {
        recordedCase = false;
        try { checkValid(text, size, width, locale, label); }
        catch (RuntimeException exception) {
            exceptions++;
            fail(label + " text=" + text + " oracle exception=" + exception
                + " at=" + (exception.getStackTrace().length == 0 ? "unknown" : exception.getStackTrace()[0]));
        }
    }
    private void checkValid(String text, float size, float width, String locale, String label) {
        cases++;
        if (text.indexOf('\r') >= 0) { checkStatic(text, size, (int)Math.ceil(width), locale, label); return; }
        float left = 3.25f, top = 2.5f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(size); paint.setTextLocale(Locale.forLanguageTag(locale));
        TextLayout expected = TextLayout.layout(text, paint, width, left, top);
        Object[] raw = NativeCore.layoutParagraph(text.toCharArray(), locale, size, width, left, top);
        if (raw == null || raw.length != 4) { fail(label + " native failure: " + NativeCore.shapeError()); return; }
        float[] wire = (float[])raw[0]; String[] names = (String[])raw[1];
        int runCount = 0;
        for (TextLayout.Line line : expected.lines) runCount += line.runs.size();
        if (wire.length < 4 || wire[0] != expected.lines.size() || wire[1] != runCount
                || wire[2] != expected.glyphs.size() || names.length != expected.glyphs.size()
                || wire.length != 4+expected.lines.size()*10+runCount*6+names.length*4) {
            fail(label + " counts"); return;
        }
        if (!near(wire[3], expected.height)) fail(label + " height=" + wire[3] + " expected=" + expected.height);
        float[] caretData = (float[])raw[2];
        if (caretData.length != (text.length()+1)*2) fail(label + " caret count");
        else for (int offset = 0; offset <= text.length(); ++offset) {
            carets++;
            if (!near(caretData[offset*2], expected.caretX(offset))
                    || caretData[offset*2+1] != (expected.rtlAt(offset) ? 1 : 0))
                fail(label + " caret=" + offset + " native=" + caretData[offset*2] + " expected=" + expected.caretX(offset));
        }
        float[] hitData = (float[])raw[3];
        for (int at = 0; at < hitData.length; at += 3) {
            hits++;
            int line = (int)hitData[at]; float query = hitData[at+1];
            int answer = expected.offsetAt(line, query);
            if (hitData[at+2] != answer)
                fail(label + " hit line=" + line + " x=" + query + " native=" + hitData[at+2] + " expected=" + answer);
        }
        int ri = 0, runAt = 4+expected.lines.size()*10;
        for (int i = 0; i < expected.lines.size(); ++i) {
            lines++;
            TextLayout.Line line = expected.lines.get(i);
            int at = 4+i*10, visible = line.end;
            while (visible > line.start && Character.isWhitespace(text.charAt(visible-1))) visible--;
            if (wire[at] != line.start || wire[at+1] != line.end || wire[at+2] != visible
                    || !near(wire[at+3], line.top) || !near(wire[at+4], line.baseline)
                    || !near(wire[at+5], line.bottom) || !near(wire[at+6], line.width)
                    || wire[at+7] != (line.rtl ? 1 : 0) || wire[at+8] != ri
                    || wire[at+9] != line.runs.size())
                fail(label + " line=" + i + " top/base/bottom=" + wire[at+3]+"/"+wire[at+4]+"/"+wire[at+5]
                    + " expected=" + line.top+"/"+line.baseline+"/"+line.bottom);
            for (TextLayout.Run run : line.runs) {
                runs++; at = runAt+ri++*6;
                if (wire[at] != run.start || wire[at+1] != run.end || wire[at+2] != run.level
                        || !near(wire[at+3], run.x) || !near(wire[at+4], run.advance))
                    fail(label + " run=" + (ri-1));
            }
        }
        int glyphAt = runAt+runCount*6;
        for (int i = 0; i < names.length; ++i) {
            glyphs++;
            TextLayout.Glyph glyph = expected.glyphs.get(i);
            int at = glyphAt+i*4;
            if (wire[at] != glyph.id || !ShapeOracle.sameFont(names[i], glyph.font)
                    || !near(wire[at+2], glyph.x) || !near(wire[at+3], glyph.y)) fail(label + " glyph=" + i);
        }
    }
    private Font font(String name) throws java.io.IOException {
        Font known = fonts.get(name); if (known != null) return known;
        String[] parts = name.split("#", -1);
        Font.Builder builder = new Font.Builder(new File(parts[0])).setTtcIndex(Integer.parseInt(parts[1]));
        if (!parts[2].isEmpty()) {
            String[] values = parts[2].split(",");
            FontVariationAxis[] axes = new FontVariationAxis[values.length];
            for (int i = 0; i < axes.length; ++i) {
                String[] axis = values[i].split("=");
                axes[i] = new FontVariationAxis(axis[0], Float.parseFloat(axis[1]));
            }
            builder.setFontVariationSettings(axes);
        }
        Font result = builder.build(); fonts.put(name, result); return result;
    }
    /** TextLayout's createLineBidi cannot span CR's bidi paragraph boundary.
     * Use the public Android layout itself as the independent reference here. */
    private void checkStatic(String text, float size, int width, String locale, String label) {
        staticCases++;
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(size); paint.setTextLocale(Locale.forLanguageTag(locale)); paint.setColor(Color.BLACK);
        StaticLayout reference = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(false).setUseLineSpacingFromFallbacks(true).build();
        Object[] raw = NativeCore.layoutParagraph(text.toCharArray(), locale, size, width, 0, 0);
        if (raw == null || raw.length != 4) { fail(label + " native CR layout failed"); return; }
        float[] wire = (float[])raw[0]; String[] names = (String[])raw[1];
        int lineCount = (int)wire[0], runCount = (int)wire[1], count = (int)wire[2];
        if (lineCount != reference.getLineCount() || count != names.length) { fail(label + " CR counts"); return; }
        for (int i = 0; i < lineCount; ++i) {
            lines++; int at = 4+i*10;
            if (wire[at] != reference.getLineStart(i) || wire[at+1] != reference.getLineEnd(i)
                    || !near(wire[at+3], reference.getLineTop(i))
                    || !near(wire[at+4], reference.getLineBaseline(i))
                    || !near(wire[at+5], reference.getLineBottom(i)))
                fail(label + " CR line=" + i + " native=" + wire[at] + ":" + wire[at+1]
                    + " baseline=" + wire[at+4] + " static=" + reference.getLineStart(i) + ":" + reference.getLineEnd(i)
                    + " baseline=" + reference.getLineBaseline(i));
        }
        float[] caretData = (float[])raw[2], hitData = (float[])raw[3];
        for (int offset = 0; offset <= text.length(); ++offset) {
            carets++;
            if (!near(caretData[offset*2], reference.getPrimaryHorizontal(offset)))
                fail(label + " CR caret=" + offset + " native=" + caretData[offset*2]
                    + " static=" + reference.getPrimaryHorizontal(offset));
        }
        for (int at = 0; at < hitData.length; at += 3) {
            hits++;
            if (hitData[at+2] != reference.getOffsetForHorizontal((int)hitData[at], hitData[at+1]))
                fail(label + " CR hit line=" + hitData[at] + " x=" + hitData[at+1]);
        }
        runs += runCount; glyphs += count;
        int h = Math.max(reference.getHeight(), (int)Math.ceil(wire[3]))+8;
        Bitmap a = Bitmap.createBitmap(width+8, h, Bitmap.Config.ARGB_8888);
        Bitmap b = Bitmap.createBitmap(width+8, h, Bitmap.Config.ARGB_8888);
        try {
            reference.draw(new Canvas(a)); Canvas canvas = new Canvas(b);
            int glyphAt = 4+lineCount*10+runCount*6;
            for (int i = 0; i < count; ++i) {
                int at = glyphAt+i*4;
                canvas.drawGlyphs(new int[]{(int)wire[at]}, 0, new float[]{wire[at+2],wire[at+3]}, 0, 1, font(names[i]), paint);
            }
            ByteBuffer aa = ByteBuffer.allocate(a.getByteCount()), bb = ByteBuffer.allocate(b.getByteCount());
            a.copyPixelsToBuffer(aa); b.copyPixelsToBuffer(bb);
            byte[] av = aa.array(), bv = bb.array(); long bad = 0;
            for (int at = 0; at < av.length; at += 4) {
                int difference = 0;
                for (int channel = 0; channel < 4; ++channel)
                    difference = Math.max(difference, Math.abs((av[at+channel]&255)-(bv[at+channel]&255)));
                if (difference > 2) bad++;
            }
            badPixels += bad;
            if (bad != 0) fail(label + " CR bad pixels=" + bad);
        } catch (java.io.IOException error) { fail(label + " font rebuild=" + error); }
        finally { a.recycle(); b.recycle(); }
    }
    static Map<String, Object> run(File bundle) throws Exception {
        NativeCore.load(bundle);
        long[] before = NativeCore.fontResources();
        NativeLayoutOracle o = new NativeLayoutOracle();
        List<String> texts = new ArrayList<>(); texts.addAll(Arrays.asList(ShapeOracle.TEXT));
        texts.add("  \t"); texts.add("אבג 123 (hello)\tالعربية   ");
        texts.add("a\u202bאב\u202a12 x\u202cגד\u202c z");
        texts.add("\u2067אב \u2066abc 12\u2069 גד\u2069   ");
        texts.add("abc\u2000\u2007\u3000\u00a0\u202f");
        texts.add("a\rb"); texts.add("\r");
        texts.add("אב\rגד"); texts.add("abc\rאב  ");
        for (int t = 0; t < texts.size(); ++t)
            for (float size : new float[]{0.5f, 17.3f, 32})
                for (float width : new float[]{1, 57.5f, 210})
                    o.check(texts.get(t), size, width, "en-US", "text="+t+" size="+size+" width="+width);
        long seed = 0x6c61796f7574L; Random random = new Random(seed);
        String[] pieces = {"abc", "אבג", "العربية", "日本語", "ภาษาไทย", "👩🏽‍🚀", " ", "\t", "123", "\ud800", "\udc00", "\u2067", "\u2069"};
        String[] locales = {"en-US", "ar", "he", "ja-JP", "th-TH"};
        for (int t = 0; t < 128; ++t) {
            StringBuilder s = new StringBuilder();
            for (int j = 0, n = 2+random.nextInt(12); j < n; ++j) s.append(pieces[random.nextInt(pieces.length)]);
            o.check(s.toString(), 9+random.nextInt(100)/4f, 1+random.nextInt(800)/4f,
                    locales[t%locales.length], "random="+t);
        }
        boolean invalid = NativeCore.layoutParagraph(null, "en", 17, 20, 0, 0) == null
            && NativeCore.layoutParagraph(new char[]{'a'}, "en", 17, Float.NaN, 0, 0) == null
            && NativeCore.layoutParagraph(new char[]{'a','\n','b'}, "en", 17, 20, 0, 0) == null;
        long[] after = NativeCore.fontResources(); boolean released = Arrays.equals(before, after);
        return Json.map("ok", o.failures == 0 && invalid && released, "cases", o.cases,
            "lines", o.lines, "runs", o.runs, "glyphs", o.glyphs, "carets", o.carets, "hits", o.hits, "mismatches", o.failures,
            "worst_difference", o.worst, "seed", seed, "invalid_inputs_rejected", invalid,
            "oracle_exceptions", o.exceptions,
            "static_cases", o.staticCases, "bad_pixels", o.badPixels,
            "owners_released", released, "resources_before", Arrays.toString(before),
            "resources_after", Arrays.toString(after), "first_failures", o.details);
    }
}
