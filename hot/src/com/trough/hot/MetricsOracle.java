package com.trough.hot;

import android.graphics.Paint;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;

/** Per-font vertical metrics against public Font.getMetrics, at the same effective
 * default-Paint size used by Minikin's measured runs. Output owners retire before
 * native metric inspection, so this also exercises the retained font lease. */
final class MetricsOracle {
    static Map<String, Object> run(File bundle) throws Exception {
        NativeCore.load(bundle);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint metricPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        String locale = paint.getTextLocales().toLanguageTags();
        int cases = 0, glyphs = 0, values = 0, failures = 0;
        int zeroSizeMismatches = 0, nonzeroSizeMismatches = 0;
        float worst = 0;
        List<String> details = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        String[] fields = {"top", "ascent", "descent", "bottom", "leading"};
        int defaults = 0;
        for (String tag : new String[]{"en-US", "ar", "he", "ja-JP", "th-TH"}) {
            Paint primary = new Paint(Paint.ANTI_ALIAS_FLAG);
            primary.setTextLocale(Locale.forLanguageTag(tag));
            for (float size : new float[]{0.5f, 1, 13, 17.3f, 24.5f, 40, 80.125f}) {
                defaults++;
                primary.setTextSize(size);
                Paint.FontMetrics m = primary.getFontMetrics();
                float[] expected = {m.top, m.ascent, m.descent, m.bottom, m.leading};
                float[] got = NativeCore.defaultMetrics(size, tag);
                if (got == null || got.length != 5) { failures++; continue; }
                for (int j = 0; j < 5; ++j) {
                    float difference = Math.abs(got[j]-expected[j]);
                    worst = Math.max(worst, difference);
                    if (!Float.isFinite(got[j]) || difference > 0.0001f) {
                        failures++;
                        if (details.size() < 20) details.add("default locale=" + tag + " size=" + size
                            + " " + fields[j] + " native=" + got[j] + " platform=" + expected[j]);
                    }
                }
            }
        }
        for (int t = 0; t < ShapeOracle.TEXT.length; t++) {
            char[] text = ShapeOracle.TEXT[t].toCharArray();
            for (float size : new float[]{0.5f, 13, 17.3f, 24.5f, 40}) for (boolean rtl : new boolean[]{false, true}) {
                cases++; paint.setTextSize(size); metricPaint.setTextSize((int)size);
                PositionedGlyphs reference = TextRunShaper.shapeTextRun(text, 0, text.length, 0, text.length, 0, 0, rtl, paint);
                float[] got = NativeCore.shapeMetrics(text, rtl, size, locale);
                String label = "text=" + t + " size=" + size + " rtl=" + rtl;
                if (got == null || got.length != reference.glyphCount()*6) {
                    failures++; if (details.size() < 20) details.add(label + " count/native failure"); continue;
                }
                for (int i = 0; i < reference.glyphCount(); i++) {
                    glyphs++;
                    if (got[i*6] != reference.getGlyphId(i)) { failures++; if (details.size() < 20) details.add(label + " glyph=" + i + " ID"); }
                    Paint.FontMetrics m = new Paint.FontMetrics();
                    reference.getFont(i).getMetrics(metricPaint, m);
                    float[] expected = {m.top, m.ascent, m.descent, m.bottom, m.leading};
                    for (int j = 0; j < 5; j++) {
                        values++; float difference = Math.abs(got[i*6+1+j] - expected[j]);
                        worst = Math.max(worst, difference);
                        if (!Float.isFinite(got[i*6+1+j]) || difference > 0.0001f) {
                            failures++;
                            if (size < 1) zeroSizeMismatches++; else nonzeroSizeMismatches++;
                            String key = label + " " + fields[j] + " " + reference.getFont(i).getFile();
                            if (reported.add(key) && details.size() < 20) details.add(label + " glyph=" + i + " " + fields[j]
                                + " native=" + got[i*6+1+j] + " platform=" + expected[j]
                                + " font=" + reference.getFont(i).getFile());
                        }
                    }
                }
            }
        }
        boolean invalid = NativeCore.shapeMetrics(null, false, 16, locale) == null
            && NativeCore.shapeMetrics(new char[]{'x'}, false, Float.NaN, locale) == null;
        return Json.map("ok", invalid && failures == 0, "cases", cases, "glyphs", glyphs,
            "default_metric_cases", defaults,
            "metric_values", values, "mismatches", failures, "worst_difference", worst,
            "zero_size_mismatches", zeroSizeMismatches, "nonzero_size_mismatches", nonzeroSizeMismatches,
            "invalid_inputs_rejected", invalid, "first_failures", details);
    }
}
