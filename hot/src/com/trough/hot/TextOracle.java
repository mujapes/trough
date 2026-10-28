package com.trough.hot;

import android.graphics.*;
import android.graphics.fonts.Font;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.*;

/** android.text: text through the platform's shaping and glyph rasterisation, composed by
 * Trough's GPU renderer (docs/TEXT_RENDERING.md). Runs are shaped with TextRunShaper; each
 * unique glyph is rasterised once into an R8 atlas with Canvas.drawGlyphs; the GPU composes
 * the glyph instances. The reference is the platform drawing the same glyphs at the same
 * (pixel-snapped) positions straight into an ARGB_8888 bitmap. */
final class TextOracle {
    private static final String[] RUNS = {
        "AVATAR Wave fi ffl office Typography", "The quick brown fox jumps over 0123456789",
        "مرحبا بالعالم والنص العربي", "नमस्ते दुनिया हिन्दी पाठ", "中文字体排版测试 日本語のテキスト",
        "Ελληνικά κείμενο ΑΒΓΔ", "עברית מימין לשמאל טקסט"};
    private static final boolean[] RTL = {false, false, true, false, false, false, true};
    private static final int[] COLORS = {0xff000000, 0xff1a73e8, 0xc0d93025, 0xff188038, 0x80202124, 0xfff9ab00, 0xff6f42c1};
    private static final int W = 1080, AW = 1024, AH = 1024, LINE = 64;

    static Map<String, Object> run(File bundle) {
        NativeCore.load(bundle);
        int h = LINE * RUNS.length + 32;
        Paint base = new Paint();
        base.setAntiAlias(true);
        base.setTextSize(40);
        byte[] atlas = new byte[AW * AH];
        Map<String, int[]> cache = new HashMap<>();          // glyph key -> {ax, ay, w, h, left, top}
        List<Float> instances = new ArrayList<>();
        Bitmap reference = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888);
        Canvas rc = new Canvas(reference);
        int[] shelf = {0, 0, 0};                              // x, y, row height
        int glyphCount = 0, fontCount = 0;
        Set<Font> fonts = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < RUNS.length; i++) {
            String text = RUNS[i];
            Paint paint = new Paint(base);
            paint.setColor(COLORS[i]);
            PositionedGlyphs pg = TextRunShaper.shapeTextRun(text, 0, text.length(), 0, text.length(), 20f, 48f + i * LINE, RTL[i], paint);
            float alpha = Color.alpha(COLORS[i]) / 255f;
            float r = Color.red(COLORS[i]) / 255f * alpha, g = Color.green(COLORS[i]) / 255f * alpha, b = Color.blue(COLORS[i]) / 255f * alpha;
            for (int j = 0; j < pg.glyphCount(); j++) {
                Font font = pg.getFont(j);
                int id = pg.getGlyphId(j);
                int x = Math.round(pg.getGlyphX(j)), y = Math.round(pg.getGlyphY(j));
                fonts.add(font);
                /* The platform's glyph masks depend on the text colour's luminance (Skia's
                 * contrast/gamma preblend), so masks are made with the run's opaque colour. */
                String key = System.identityHashCode(font) + ":" + id + ":" + (COLORS[i] & 0xffffff);
                int[] m = cache.get(key);
                if (m == null) { m = rasterise(font, id, base, COLORS[i] | 0xff000000, atlas, shelf); cache.put(key, m); }
                if (m == null) continue;                       // empty glyph (space)
                glyphCount++;
                float x0 = x + m[4], y0 = y + m[5];
                float[] q = {x0, y0, x0 + m[2], y0 + m[3], m[0], m[1], 0, 0, -1e6f, -1e6f, 1e6f, 1e6f, r, g, b, alpha};
                for (float v : q) instances.add(v);
                rc.drawGlyphs(new int[]{id}, 0, new float[]{x, y}, 0, 1, font, paint);
            }
        }
        fontCount = fonts.size();
        float[] glyphs = new float[instances.size()];
        for (int i = 0; i < glyphs.length; i++) glyphs[i] = instances.get(i);
        double[] timing = new double[1];
        byte[] ours = NativeCore.textRender(W, h, glyphs, atlas, AW, AH, timing);
        if (ours == null) return Json.map("ok", false, "error", "gpu text render failed");
        ByteBuffer rb = ByteBuffer.allocate(W * h * 4);
        reference.copyPixelsToBuffer(rb);                     // ARGB_8888 is RGBA bytes, premultiplied
        byte[] ref = rb.array();
        long bad = 0, large = 0, ink = 0; int max = 0; String first = "";
        int[] badPerRun = new int[RUNS.length];
        for (int p = 0; p < W * h; p++) {
            int worst = 0;
            for (int ch = 0; ch < 4; ch++) worst = Math.max(worst, Math.abs((ours[p * 4 + ch] & 255) - (ref[p * 4 + ch] & 255)));
            if ((ref[p * 4 + 3] & 255) != 0) ink++;
            max = Math.max(max, worst);
            if (worst > 16) large++;
            if (worst > 3) {
                if (bad++ == 0) first = "pixel " + (p % W) + "," + (p / W) + " ref " + rgba(ref, p) + " gpu " + rgba(ours, p);
                int line = (p / W - 8) / LINE;
                if (line >= 0 && line < RUNS.length) badPerRun[line]++;
            }
        }
        List<Integer> perRun = new ArrayList<>();
        for (int v : badPerRun) perRun.add(v);
        return Json.map("ok", bad == 0 && ink > 0, "glyphs", glyphCount, "unique_glyphs", cache.size(), "fonts", fontCount,
            "ink_pixels", ink, "bad_pixels", bad, "large_diffs", large, "max_channel_diff", max, "bad_per_run", perRun,
            "first_bad", first, "gpu_ms", timing[0]);
    }

    private static String rgba(byte[] a, int p) {
        return (a[p * 4] & 255) + "," + (a[p * 4 + 1] & 255) + "," + (a[p * 4 + 2] & 255) + "," + (a[p * 4 + 3] & 255);
    }

    /** Rasterises one glyph's coverage into the atlas; {ax, ay, w, h, left, top} or null if empty. */
    private static int[] rasterise(Font font, int id, Paint base, int opaque, byte[] atlas, int[] shelf) {
        RectF b = new RectF();
        font.getGlyphBounds(id, base, b);
        if (b.isEmpty()) return null;
        int left = (int) Math.floor(b.left) - 2, top = (int) Math.floor(b.top) - 2;
        int w = (int) Math.ceil(b.right) + 2 - left, h = (int) Math.ceil(b.bottom) + 2 - top;
        if (shelf[0] + w > AW) { shelf[0] = 0; shelf[1] += shelf[2]; shelf[2] = 0; }
        if (shelf[1] + h > AH) throw new IllegalStateException("atlas full");
        int ax = shelf[0], ay = shelf[1];
        shelf[0] += w; shelf[2] = Math.max(shelf[2], h);
        Bitmap mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Paint p = new Paint(base);
        p.setColor(opaque);
        new Canvas(mask).drawGlyphs(new int[]{id}, 0, new float[]{-left, -top}, 0, 1, font, p);
        ByteBuffer buf = ByteBuffer.allocate(mask.getRowBytes() * h);
        mask.copyPixelsToBuffer(buf);
        byte[] px = buf.array();
        for (int y = 0; y < h; y++) System.arraycopy(px, y * mask.getRowBytes(), atlas, (ay + y) * AW + ax, w);
        return new int[]{ax, ay, w, h, left, top};
    }
}
