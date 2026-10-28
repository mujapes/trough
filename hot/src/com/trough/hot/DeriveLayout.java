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

    /** A Font object for a face id (for rasterising its glyphs). */
    static Font face(int id) { synchronized (FONTS) { return FACES.get(id); } }

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
