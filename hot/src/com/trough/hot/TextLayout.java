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
    static final class Run { int start, end, level; boolean rtl; float x, advance; }
    static final class Line {
        int start, end; float baseline, top, bottom, width; boolean rtl; final List<Run> runs = new ArrayList<>();
        int[] stops; float[] stopX;                       // tap candidates, filled on first hit test
    }
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
                    run.start = start + rs; run.end = start + re; run.rtl = rtl; run.level = line.getRunLevel(r); run.x = pen; run.advance = pg.getAdvance();
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

    /** The primary caret's x for offset, as Layout.getPrimaryHorizontal: the caret trails the
     * previous character when that character's bidi level is lower than the one at offset (at a
     * line start the paragraph level stands in for the previous character), else it leads the
     * character at offset. */
    float caretX(int offset) {
        Line l = lines.get(lineOf(offset));
        if (l.runs.isEmpty()) return l.rtl ? left + width : left;
        int paragraphLevel = l.rtl ? 1 : 0;
        Run at = runAt(l, offset), before = offset == l.start ? null : runAt(l, offset - 1);
        int levelAt = at != null ? at.level : paragraphLevel;
        int levelBefore = before != null ? before.level : paragraphLevel;
        if (levelBefore < levelAt) {
            if (offset == l.start) return l.rtl ? left + width : left;
            if (before != null) return edge(l, before, offset);
        } else if (at != null) return edge(l, at, offset);
        Run last = l.runs.get(l.rtl ? 0 : l.runs.size() - 1);   // past the drawn text (trailing whitespace)
        return l.rtl ? last.x : last.x + last.advance;
    }

    /** The offset whose primary caret is nearest x on line i (a tap), as
     * Layout.getOffsetForHorizontal: cursor positions from the line start to the line end, except
     * that a line other than the last stops before its final character (the break). Ties go to
     * the earlier offset, except the final position, which wins them. */
    int offsetAt(int i, float x) {
        Line l = lines.get(i);
        if (l.stops == null) {
            int max = l.end;
            if (i < lines.size() - 1 && l.end > l.start && text.charAt(l.end) != '\n')   // the platform's line holds its '\n'; ours stops before it
                max = paint.getTextRunCursor(text, l.start, l.end, l.rtl, l.end, Paint.CURSOR_BEFORE);
            int n = 0;
            int[] stops = new int[max - l.start + 1];
            for (int o = l.start; o <= max; o++)
                if (o == l.start || o == max || paint.getTextRunCursor(text, l.start, l.end, l.rtl, o, Paint.CURSOR_AT) == o) stops[n++] = o;
            float[] xs = new float[n];
            for (int k = 0; k < n; k++) xs[k] = caretX(stops[k]);
            l.stops = java.util.Arrays.copyOf(stops, n); l.stopX = xs;
        }
        int best = 0;
        float bestDist = Math.abs(l.stopX[0] - x);
        for (int k = 1; k < l.stops.length; k++) {
            float d = Math.abs(l.stopX[k] - x);
            if (d < bestDist || (k == l.stops.length - 1 && d <= bestDist)) { best = k; bestDist = d; }
        }
        return l.stops[best];
    }

    private static Run runAt(Line l, int offset) {
        for (Run r : l.runs) if (offset >= r.start && offset < r.end) return r;
        return null;
    }

    /** The x of the run's edge at offset (the run's own direction). */
    /** Selection rectangles (left, top, right, bottom) for [start, end), by Layout.getSelection's
     * rules: on a line, each directional run clipped to the range, from the leading edge of its
     * first character to the trailing edge of its last; a range over several lines fills its
     * first line from the text to the paragraph's far edge, middle lines entirely and its last
     * line from the near edge. startsBefore / continues: the range began in an earlier paragraph
     * or goes on into a later one (paragraphs are separate layouts), so this paragraph's first
     * or last line is a middle line of the whole range. */
    List<float[]> selection(int start, int end, boolean startsBefore, boolean continues) {
        List<float[]> out = new ArrayList<>();
        if (startsBefore) start = 0;
        if (continues) end = text.length();
        if (end < start || (start == end && !startsBefore && !continues)) return out;
        int first = lineOf(start), last = continues ? lines.size() : lineOf(end);
        for (int i = first; i <= last && i < lines.size(); i++) {
            Line l = lines.get(i);
            boolean isFirst = i == first && !startsBefore, isLast = i == last && !continues;
            if (isFirst && isLast) { runs(out, l, start, end); continue; }
            if (!isFirst && !isLast) { out.add(new float[]{left, l.top, left + width, l.bottom}); continue; }
            float lineLeft = l.rtl ? left + width - l.width : left, lineRight = l.rtl ? left + width : left + l.width;
            if (isFirst) {
                runs(out, l, start, l.end);
                /* On a right-to-left line the fill to the left edge stops short by the trailing
                 * whitespace's advance, which hangs there unselected (Layout's getLineLeft counts
                 * it; measured: android.selection). */
                float hang = l.rtl ? paint.measureText(text, lineVisibleEnd(l), l.end) : 0;
                out.add(l.rtl ? new float[]{left, l.top, lineLeft - hang, l.bottom} : new float[]{lineRight, l.top, left + width, l.bottom});
            } else {
                runs(out, l, l.start, end);
                out.add(l.rtl ? new float[]{lineRight, l.top, left + width, l.bottom} : new float[]{left, l.top, lineLeft, l.bottom});
            }
        }
        return out;
    }
    private void runs(List<float[]> out, Line l, int start, int end) {
        for (Run r : l.runs) {
            int st = Math.max(start, r.start), en = Math.min(end, r.end);
            if (st >= en) continue;
            float h1 = edge(l, r, st), h2 = edge(l, r, en);
            out.add(new float[]{Math.min(h1, h2), l.top, Math.max(h1, h2), l.bottom});
        }
        /* Trailing whitespace is not drawn, but on a left-to-right line Layout selects it, past
         * the drawn text by its advance. On a right-to-left line it selects nothing there
         * (measured: android.selection). */
        int visible = lineVisibleEnd(l), st = Math.max(start, visible), en = Math.min(end, l.end);
        if (st < en && !l.rtl) {
            float edgeX = left + l.width;
            out.add(new float[]{edgeX + paint.measureText(text, visible, st), l.top, edgeX + paint.measureText(text, visible, en), l.bottom});
        }
    }

    private float edge(Line l, Run r, int offset) {
        int o = Math.max(r.start, Math.min(offset, r.end));
        float into = paint.getRunAdvance(text, r.start, r.end, l.start, Math.max(r.end, lineVisibleEnd(l)), r.rtl, o);
        return r.rtl ? r.x + r.advance - into : r.x + into;
    }

    private int lineVisibleEnd(Line l) {
        int e = l.end;
        while (e > l.start && Character.isWhitespace(text.charAt(e - 1))) e--;
        return e;
    }
}
