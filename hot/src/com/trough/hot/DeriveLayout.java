package com.trough.hot;

import android.graphics.Paint;
import android.graphics.fonts.Font;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/** The layout operator's Java half (native/android/derive.c, docs/LAYOUT_DERIVE.md). Called on
 * executor worker threads: one paragraph at one width, through TextLayout, serialized as bytes
 * the native side interns. Paint is not thread-safe, so each worker thread has its own. Fonts
 * are named by a process-wide id so equal layouts serialize to equal bytes. */
final class DeriveLayout {
    static final float TEXT_SIZE = 36;
    private static final ThreadLocal<Paint> PAINT = new ThreadLocal<Paint>() {
        @Override protected Paint initialValue() {
            Paint p = new Paint();
            p.setAntiAlias(true);
            p.setTextSize(TEXT_SIZE);
            return p;
        }
    };
    /* Fonts by what they are, not by wrapper object: PositionedGlyphs returns a new Font object
     * per shaping call, so identity would give equal layouts different bytes. Font.hashCode
     * costs 46 us per fresh wrapper on the Pixel (more than shaping a run) and equals 9 us, so
     * faces are keyed by source identifier (1.3 us), ttc index, style and variation axes. */
    private static final class Face {
        final long source; final int ttc, style; final float[] axes; final int hash;
        Face(Font f) {
            source = f.getSourceIdentifier(); ttc = f.getTtcIndex();
            style = f.getStyle().getWeight() * 2 + f.getStyle().getSlant();
            android.graphics.fonts.FontVariationAxis[] a = f.getAxes();
            axes = new float[a == null ? 0 : 2 * a.length];
            for (int i = 0; a != null && i < a.length; i++) { axes[2 * i] = a[i].getTag().hashCode(); axes[2 * i + 1] = a[i].getStyleValue(); }
            hash = (int) (source ^ (source >>> 32)) * 31 + ttc * 7 + style + Arrays.hashCode(axes);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Face)) return false;
            Face f = (Face) o;
            return f.source == source && f.ttc == ttc && f.style == style && Arrays.equals(f.axes, axes);
        }
    }
    private static final Map<Face, Integer> FONTS = new HashMap<>();
    private static final java.util.List<Font> FACES = new java.util.ArrayList<>();   /* id -> a Font of that face */
    private static final ThreadLocal<IdentityHashMap<Font, Integer>> SEEN = new ThreadLocal<IdentityHashMap<Font, Integer>>() {
        @Override protected IdentityHashMap<Font, Integer> initialValue() { return new IdentityHashMap<>(); }
    };

    static int fontId(Font f) {
        IdentityHashMap<Font, Integer> seen = SEEN.get();
        Integer id = seen.get(f);
        if (id != null) return id;
        Face face = new Face(f);
        synchronized (FONTS) {
            id = FONTS.get(face);
            if (id == null) { id = FONTS.size(); FONTS.put(face, id); FACES.add(f); }
        }
        if (seen.size() > 4096) seen.clear();
        seen.put(f, id);
        return id;
    }

    /** One glyph's coverage mask, as the platform rasterises it in black at TEXT_SIZE:
     * width, height, left, top (ints), then width*height coverage bytes. Empty glyphs are 0x0. */
    static byte[] mask(int faceId, int glyph) {
        Font font = face(faceId);
        Paint p = PAINT.get();
        p.setColor(android.graphics.Color.BLACK);
        android.graphics.RectF b = new android.graphics.RectF();
        font.getGlyphBounds(glyph, p, b);
        java.nio.ByteBuffer out;
        if (b.isEmpty()) { out = java.nio.ByteBuffer.allocate(16); out.putInt(0).putInt(0).putInt(0).putInt(0); return out.array(); }
        int left = (int) Math.floor(b.left) - 2, top = (int) Math.floor(b.top) - 2;
        int w = (int) Math.ceil(b.right) + 2 - left, h = (int) Math.ceil(b.bottom) + 2 - top;
        android.graphics.Bitmap m = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ALPHA_8);
        new android.graphics.Canvas(m).drawGlyphs(new int[]{glyph}, 0, new float[]{-left, -top}, 0, 1, font, p);
        java.nio.ByteBuffer px = java.nio.ByteBuffer.allocate(m.getRowBytes() * h);
        m.copyPixelsToBuffer(px);
        m.recycle();
        out = java.nio.ByteBuffer.allocate(16 + w * h);
        out.putInt(w).putInt(h).putInt(left).putInt(top);
        for (int y = 0; y < h; y++) out.put(px.array(), y * (px.capacity() / h), w);
        return out.array();
    }

    /** A Font object for a face id (for rasterising its glyphs). */
    static Font face(int id) { synchronized (FONTS) { return FACES.get(id); } }

    /** The caret at offset (clamped to the text): x by TextLayout's primary-caret rules, its
     * line's top, bottom and baseline as big-endian floats, then flags (1: the character at the
     * offset is RTL, Layout.isRtlCharAt). */
    static byte[] caret(String text, int width, int offset) {
        TextLayout l = TextLayout.layout(text, PAINT.get(), width, 0, 0);
        int o = Math.max(0, Math.min(offset, text.length()));
        TextLayout.Line line = l.lines.get(l.lineOf(o));
        return java.nio.ByteBuffer.allocate(20).putFloat(l.caretX(o)).putFloat(line.top).putFloat(line.bottom)
            .putFloat(line.baseline).putInt(l.rtlAt(o) ? 1 : 0).array();
    }
    /** Character bounds of [start, end) in the paragraph's coordinates (Layout.fillCharacterBounds)
     * and whether each unit is RTL (Layout.isRtlCharAt): 5 floats per unit, the last 1 or 0. */
    static float[] characterBounds(String text, int width, int start, int end) {
        TextLayout l = TextLayout.layout(text, PAINT.get(), width, 0, 0);
        int a = Math.max(0, Math.min(start, text.length())), b = Math.max(a, Math.min(end, text.length()));
        float[] bounds = l.characterBounds(a, b), out = new float[5 * (b - a)];
        for (int k = 0; k < b - a; k++) {
            System.arraycopy(bounds, 4 * k, out, 5 * k, 4);
            out[5 * k + 4] = l.rtlAt(a + k) ? 1 : 0;
        }
        return out;
    }
    /** Lines of a paragraph: start, end and direction (1 LTR, -1 RTL) per line, as
     * Layout.getLineStart/getLineEnd/getParagraphDirection. */
    static int[] lines(String text, int width) {
        TextLayout l = TextLayout.layout(text, PAINT.get(), width, 0, 0);
        int[] out = new int[3 * l.lines.size()];
        for (int i = 0; i < l.lines.size(); i++) { TextLayout.Line line = l.lines.get(i); out[3 * i] = line.start; out[3 * i + 1] = line.end; out[3 * i + 2] = line.rtl ? -1 : 1; }
        return out;
    }
    /** The offset a tap at x on line `line` selects (Layout.getOffsetForHorizontal). */
    static int offsetAt(String text, int width, int line, float x) {
        TextLayout l = TextLayout.layout(text, PAINT.get(), width, 0, 0);
        return l.offsetAt(Math.max(0, Math.min(line, l.lines.size() - 1)), x);
    }

    /** Selection rectangles for [start, end) of a paragraph (flags: 1 the range began in an
     * earlier paragraph, 2 it continues into a later one): count, then left, top, right, bottom
     * floats per rectangle, in the paragraph's coordinates. */
    static byte[] selection(String text, int width, int start, int end, int flags) {
        TextLayout l = TextLayout.layout(text, PAINT.get(), width, 0, 0);
        int n = text.length();
        java.util.List<float[]> rects = l.selection(Math.max(0, Math.min(start, n)), Math.max(0, Math.min(end, n)), (flags & 1) != 0, (flags & 2) != 0);
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(4 + 16 * rects.size());
        b.putInt(rects.size());
        for (float[] r : rects) b.putFloat(r[0]).putFloat(r[1]).putFloat(r[2]).putFloat(r[3]);
        return b.array();
    }

    /** Lines (start, end, baseline, top, bottom, width, rtl), then glyphs (font, id, x, y). */
    static byte[] layout(String text, int width) {
        TextLayout l = TextLayout.layout(text, PAINT.get(), width, 0, 0);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 + 24 * l.lines.size() + 16 * l.glyphs.size());
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeInt(l.lines.size());
            for (TextLayout.Line line : l.lines) {
                out.writeInt(line.start); out.writeInt(line.end);
                out.writeFloat(line.baseline); out.writeFloat(line.top); out.writeFloat(line.bottom); out.writeFloat(line.width);
                out.writeByte(line.rtl ? 1 : 0);
            }
            out.writeInt(l.glyphs.size());
            for (TextLayout.Glyph g : l.glyphs) {
                out.writeInt(fontId(g.font)); out.writeInt(g.id); out.writeFloat(g.x); out.writeFloat(g.y);
            }
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return bytes.toByteArray();
    }
}
