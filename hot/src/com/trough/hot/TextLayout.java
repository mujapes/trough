package com.trough.hot;

import android.graphics.Paint;
import android.graphics.fonts.Font;
import android.graphics.text.LineBreaker;
import android.graphics.text.MeasuredText;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;
import java.text.Bidi;
import java.util.ArrayList;
import java.util.List;

/** Paragraph layout through the platform's text stack (docs/TEXT_RENDERING.md): bidi runs
 * (java.text.Bidi), line breaking (MeasuredText + LineBreaker, high quality; hyphenation is not
 * drawn yet, so it is off), then each line's runs in visual order
 * shaped with TextRunShaper. The output is positioned glyphs plus line metrics, which the GPU
 * renderer composes and the interaction layer uses for hit testing. Offsets are UTF-16. */
final class TextLayout {
    static final class Glyph { Font font; int id; float x, y; }
    /** A run of one direction within a line, in visual order: logical [start, end) and its box. */
    static final class Run { int start, end; boolean rtl; float x, advance; }
    static final class Line { int start, end; float baseline, top, bottom, width; boolean rtl; final List<Run> runs = new ArrayList<>(); }
    final List<Glyph> glyphs = new ArrayList<>();
    final List<Line> lines = new ArrayList<>();
    float height;
    private String text;
    private Paint paint;
    private float left, width;

    /** Lays out text (paragraphs separated by '\n') in width px with the given paint, from (x, y). */
    static TextLayout layout(String text, Paint paint, float width, float x, float y) {
        TextLayout out = new TextLayout();
        out.text = text; out.paint = paint; out.left = x; out.width = width;
        Paint.FontMetrics fm = paint.getFontMetrics();
        float top = y;
        int start = 0;
        while (start <= text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) end = text.length();
            top = paragraph(out, text, start, end, paint, width, x, top, fm);
            if (end == text.length()) break;
            start = end + 1;
        }
        out.height = top - y;
        return out;
    }

    private static float paragraph(TextLayout out, String text, int start, int end, Paint paint, float width,
                                   float x, float top, Paint.FontMetrics fm) {
        float ascent = -fm.ascent, descent = fm.descent;
        if (start == end) {                               // empty paragraph: one empty line
            Line l = new Line();
            l.start = l.end = start; l.top = top; l.baseline = top + Math.round(ascent); l.bottom = l.baseline + Math.round(descent);
            l.rtl = false;
            out.lines.add(l);
            return l.bottom;
        }
        char[] chars = text.substring(start, end).toCharArray();
        Bidi bidi = new Bidi(chars, 0, null, 0, chars.length, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
        MeasuredText.Builder mb = new MeasuredText.Builder(chars);
        for (int r = 0; r < bidi.getRunCount(); r++)
            mb.appendStyleRun(paint, bidi.getRunLimit(r) - bidi.getRunStart(r), (bidi.getRunLevel(r) & 1) != 0);
        MeasuredText measured = mb.build();
        LineBreaker breaker = new LineBreaker.Builder()
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(LineBreaker.HYPHENATION_FREQUENCY_NONE)
            .setJustificationMode(LineBreaker.JUSTIFICATION_MODE_NONE).build();
        LineBreaker.ParagraphConstraints constraints = new LineBreaker.ParagraphConstraints();
        constraints.setWidth(width);
        LineBreaker.Result result = breaker.computeLineBreaks(measured, constraints, 0);
        boolean paragraphRtl = !bidi.baseIsLeftToRight();
        int lineStart = 0;
        for (int i = 0; i < result.getLineCount(); i++) {
            int lineEnd = result.getLineBreakOffset(i);
            Line l = new Line();
            l.start = start + lineStart; l.end = start + lineEnd; l.rtl = paragraphRtl;
            /* The paint's own metrics, extended (never shrunk) by fallback fonts on the line, in
             * whole pixels as the platform's integer font metrics are (StaticLayout's rule). */
            int lineAscent = Math.max(Math.round(ascent), Math.round(-result.getLineAscent(i)));
            int lineDescent = Math.max(Math.round(descent), Math.round(result.getLineDescent(i)));
            l.top = top; l.baseline = top + lineAscent; l.bottom = l.baseline + lineDescent;
            l.width = result.getLineWidth(i);
            /* Trailing whitespace is not drawn; the line's runs in visual order. */
            int visibleEnd = lineEnd;
            while (visibleEnd > lineStart && Character.isWhitespace(chars[visibleEnd - 1])) visibleEnd--;
            float pen = paragraphRtl ? x + width - l.width : x;
            if (visibleEnd > lineStart) {
                Bidi line = bidi.createLineBidi(lineStart, visibleEnd);
                int runs = line.getRunCount();
                byte[] levels = new byte[runs];
                Integer[] order = new Integer[runs];
                for (int r = 0; r < runs; r++) { levels[r] = (byte) line.getRunLevel(r); order[r] = r; }
                Bidi.reorderVisually(levels, 0, order, 0, runs);
                for (int v = 0; v < runs; v++) {
                    int r = order[v];
                    int rs = lineStart + line.getRunStart(r), re = lineStart + line.getRunLimit(r);
                    boolean rtl = (line.getRunLevel(r) & 1) != 0;
                    PositionedGlyphs pg = TextRunShaper.shapeTextRun(chars, rs, re - rs, lineStart, visibleEnd - lineStart,
                        pen, l.baseline, rtl, paint);
                    for (int g = 0; g < pg.glyphCount(); g++) {
                        Glyph q = new Glyph();
                        q.font = pg.getFont(g); q.id = pg.getGlyphId(g); q.x = pg.getGlyphX(g); q.y = pg.getGlyphY(g);
                        out.glyphs.add(q);
                    }
                    Run run = new Run();
                    run.start = start + rs; run.end = start + re; run.rtl = rtl; run.x = pen; run.advance = pg.getAdvance();
                    l.runs.add(run);
                    pen += pg.getAdvance();
                }
            }
            out.lines.add(l);
            top = l.bottom;
            lineStart = lineEnd;
        }
        return top;
    }

    /** The line holding offset (an offset at a line break belongs to the following line). */
    int lineOf(int offset) {
        for (int i = lines.size() - 1; i > 0; i--) if (offset >= lines.get(i).start) return i;
        return 0;
    }

    /** The caret's x for offset, in the direction of the run that holds it; at a run boundary the
     * paragraph direction's run wins (the primary caret). */
    float caretX(int offset) {
        Line l = lines.get(lineOf(offset));
        if (l.runs.isEmpty()) return l.rtl ? left + width : left;
        Run hit = null;
        for (Run r : l.runs) if (offset >= r.start && offset < r.end) hit = r;
        if (hit == null || (offset == hit.start && hit.rtl != l.rtl)) {
            /* Boundary: the run ending or starting here in the paragraph's direction. */
            for (Run r : l.runs) if (r.rtl == l.rtl && (offset == r.end || offset == r.start)) { hit = r; break; }
        }
        if (hit == null) {                        // past the drawn text (trailing whitespace)
            Run edge = l.runs.get(l.rtl ? 0 : l.runs.size() - 1);
            return l.rtl ? edge.x : edge.x + edge.advance;
        }
        int o = Math.max(hit.start, Math.min(offset, hit.end));
        float into = paint.getRunAdvance(text, hit.start, hit.end, l.start, Math.max(hit.end, lineVisibleEnd(l)), hit.rtl, o);
        return hit.rtl ? hit.x + hit.advance - into : hit.x + into;
    }

    /** The offset nearest x on line i (a tap). */
    int offsetAt(int i, float x) {
        Line l = lines.get(i);
        if (l.runs.isEmpty()) return l.start;
        Run first = l.runs.get(0), last = l.runs.get(l.runs.size() - 1);
        if (x <= first.x) return first.rtl ? first.end : first.start;
        if (x >= last.x + last.advance) return last.rtl ? last.start : last.end;
        for (Run r : l.runs) {
            if (x < r.x || x > r.x + r.advance) continue;
            float into = r.rtl ? r.x + r.advance - x : x - r.x;
            return paint.getOffsetForAdvance(text, r.start, r.end, l.start, Math.max(r.end, lineVisibleEnd(l)), r.rtl, into);
        }
        return l.end;
    }

    private int lineVisibleEnd(Line l) {
        int e = l.end;
        while (e > l.start && Character.isWhitespace(text.charAt(e - 1))) e--;
        return e;
    }
}
