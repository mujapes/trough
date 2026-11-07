package com.trough.hot;

import android.graphics.Paint;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontVariationAxis;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native shaping is a candidate until this independent platform oracle passes. No renderer
 * uses the candidate here. Compare selected font identities as well as glyphs and geometry. */
final class ShapeOracle {
    static final String[] TEXT = {
        "", "AVATAR Wave fi ffl office Typography", "The quick brown fox 0123456789",
        "مرحبا بالعالم والنص العربي", "نَصّ عربي لا إله إلا الله", "עברית מימין לשמאל טקסט",
        "नमस्ते दुनिया हिन्दी पाठ", "中文字体排版测试 日本語のテキスト", "Ελληνικά κείμενο ΑΒΓΔ",
        "a\u0301 e\u0308 o\u0327\u0301", "👨‍👩‍👧‍👦 🇬🇧 👍🏽 ☺️", "abc العربية עברית हिन्दी 日本語",
        "x\u200e\u2067العربية\u2069y", "क्\u200dष क्षि ज्ञ श्र", "ก้าษาไทย ทดสอบ", "\ud800x\udc00"
    };
    static final float[] SIZES = {0.5f, 16, 17.3f, 24.5f, 40};
    private ShapeOracle() {}

    static Map<String, Object> run(File bundle) throws Exception {
        NativeCore.load(bundle);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (NativeCore.shapeInit(paint.getTextLocales().toLanguageTags()) != 0)
            return Json.map("ok", false, "error", "native shaping init");
        char[] one = {'x'};
        boolean invalid = NativeCore.shapeRun(null, 0, 0, false, 16) == null
            && NativeCore.shapeRun(one, -1, 1, false, 16) == null
            && NativeCore.shapeRun(one, 0, 2, false, 16) == null
            && NativeCore.shapeRun(one, 1, 0, false, 16) == null
            && NativeCore.shapeRun(one, 0, 1, false, Float.NaN) == null
            && NativeCore.shapeRun(one, 0, 1, false, Float.POSITIVE_INFINITY) == null
            && NativeCore.shapeRun(one, 0, 1, false, -1) == null;
        int calls = 0, glyphs = 0, counts = 0, ids = 0, fonts = 0, positions = 0, advances = 0;
        float worst = 0;
        List<String> failures = new ArrayList<>();
        Map<Integer, String> faces = new LinkedHashMap<>();
        Map<Integer, Integer> formats = new LinkedHashMap<>();
        for (int t = 0; t < TEXT.length; t++) for (float size : SIZES) for (boolean rtl : new boolean[]{false, true}) {
            String text = TEXT[t]; char[] chars = text.toCharArray(); paint.setTextSize(size);
            // Include run boundaries inside context, at both cache breaks and non-breaks.
            for (int mode = 0; mode < (chars.length >= 4 ? 3 : 1); mode++) {
                int start = mode == 0 ? 0 : 1, end = mode == 2 ? chars.length - 1 : chars.length;
                PositionedGlyphs ref = TextRunShaper.shapeTextRun(chars, start, end - start, 0, chars.length, 0, 0, rtl, paint);
                float[] got = NativeCore.shapeRun(chars, start, end, rtl, size);
                calls++;
                String where = "text=" + t + " size=" + size + " rtl=" + rtl + " range=" + start + ":" + end;
                if (got == null || got.length < 2 || got[1] != ref.glyphCount() || got.length != 2 + 4 * ref.glyphCount()) {
                    counts++; if (failures.size() < 20) failures.add(where + " count " + (got == null ? "null (" + NativeCore.shapeError() + ")" : got[1]) + " expected " + ref.glyphCount());
                    continue;
                }
                float delta = Math.abs(got[0] - ref.getAdvance()); worst = Math.max(worst, delta);
                if (delta > 0.0001f) { advances++; if (failures.size() < 20) failures.add(where + " advance " + got[0] + " expected " + ref.getAdvance()); }
                for (int g = 0; g < ref.glyphCount(); g++) {
                    glyphs++;
                    if ((int) got[3 + 4 * g] != ref.getGlyphId(g)) { ids++; if (failures.size() < 20) failures.add(where + " glyph " + g + " id " + got[3 + 4 * g] + " expected " + ref.getGlyphId(g)); }
                    int face = (int) got[2 + 4 * g];
                    String name = faces.get(face);
                    if (name == null) { name = NativeCore.shapeFace(face); faces.put(face, name); formats.put(face, NativeCore.shapeFaceFormats(face)); }
                    if (!sameFont(name, ref.getFont(g))) { fonts++; if (failures.size() < 20) failures.add(where + " font " + g + " " + name + " expected " + fontName(ref.getFont(g))); }
                    delta = Math.max(Math.abs(got[4 + 4 * g] - ref.getGlyphX(g)), Math.abs(got[5 + 4 * g] - ref.getGlyphY(g)));
                    worst = Math.max(worst, delta);
                    if (delta > 0.0001f) { positions++; if (failures.size() < 20) failures.add(where + " position " + g + " " + got[4 + 4 * g] + "," + got[5 + 4 * g] + " expected " + ref.getGlyphX(g) + "," + ref.getGlyphY(g)); }
                }
            }
        }
        return Json.map("ok", invalid && counts + ids + fonts + positions + advances == 0, "invalid_inputs_rejected", invalid, "runs", calls, "glyphs", glyphs,
            "count_mismatches", counts, "glyph_mismatches", ids, "font_mismatches", fonts,
            "position_mismatches", positions, "advance_mismatches", advances, "worst_difference", worst,
            "faces", faces, "font_format_bits", formats, "first_failures", failures);
    }

    private static String fontName(Font font) {
        return String.valueOf(font.getFile()) + "#" + font.getTtcIndex() + "#" + FontVariationAxis.toFontVariationSettings(font.getAxes());
    }

    static boolean sameFont(String name, Font font) {
        if (name == null || font.getFile() == null) return false;
        String[] parts = name.split("#", -1);
        if (parts.length != 3 || !parts[0].equals(font.getFile().getPath()) || !parts[1].equals(String.valueOf(font.getTtcIndex()))) return false;
        Map<String, Float> ours = new LinkedHashMap<>(), expected = new LinkedHashMap<>();
        if (!parts[2].isEmpty()) for (String axis : parts[2].split(",")) {
            String[] value = axis.split("=", 2); if (value.length != 2) return false;
            ours.put(value[0], Float.valueOf(value[1]));
        }
        FontVariationAxis[] axes = font.getAxes();
        if (axes != null) for (FontVariationAxis axis : axes) expected.put(axis.getTag(), axis.getStyleValue());
        return ours.equals(expected);
    }
}
