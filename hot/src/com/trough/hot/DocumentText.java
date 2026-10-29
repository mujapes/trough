package com.trough.hot;

import android.graphics.Matrix;
import android.view.inputmethod.CursorAnchorInfo;

/** TextInput's backend over the derived document (docs/INPUT.md "Document"): the IME sees the
 * paragraphs joined by '\n'. A replacement becomes paragraph edits: the paragraphs it touches
 * are rejoined with the new text and split again on '\n', so typing a newline splits a
 * paragraph (deriveInsert) and deleting one merges two (deriveRemove). Every other paragraph
 * keeps its id, so its layout is not redone. The selection lives in the deriver's selection
 * cells; the composing region is kept here, in global offsets. */
final class DocumentText implements TextInput.Backend {
    private final long deriver;
    private int composingStart = -1, composingEnd = -1;

    DocumentText(long deriver) { this.deriver = deriver; }

    @Override public long[] state() {
        long[] s = NativeCore.deriveDocState(deriver);
        return new long[]{s[0], s[1], s[2], composingStart, composingEnd};
    }

    @Override public void apply(int[] ranges, String[] texts, int anchor, int caret, int cs, int ce) {
        for (int k = 0; k < texts.length; k++) replace(ranges[2 * k], ranges[2 * k + 1], texts[k]);
        long a = NativeCore.deriveLocate(deriver, anchor), c = NativeCore.deriveLocate(deriver, caret);
        if (a == c) NativeCore.deriveSelect(deriver, (int) (c >> 32), (int) (c & 0xffffffffL));
        else NativeCore.deriveSelectRange(deriver, (int) (a >> 32), (int) (a & 0xffffffffL), (int) (c >> 32), (int) (c & 0xffffffffL));
        composingStart = cs; composingEnd = ce;
    }

    @Override public String slice(int start, int end) { return NativeCore.deriveSlice(deriver, start, end); }

    /** CursorAnchorInfo by Editor.updateCursorAnchorInfo's rules (docs/INPUT.md "Cursor anchor"):
     * selection, view-to-screen matrix, composing text, and the insertion marker at the caret
     * with visibility judged like TextView.isPositionVisible against a w x h view. Coordinates are
     * the view's; the derived nodes must be settled. A range selection has no marker yet. */
    CursorAnchorInfo cursorAnchorInfo(Matrix viewToScreen, int w, int h) {
        long[] s = state();
        CursorAnchorInfo.Builder b = new CursorAnchorInfo.Builder();
        b.setSelectionRange((int) s[1], (int) s[2]);
        b.setMatrix(viewToScreen);
        int cs = (int) Math.min(s[3], s[4]), ce = (int) Math.max(s[3], s[4]);
        if (0 <= cs && cs < ce) b.setComposingText(cs, slice(cs, ce));
        float[] m = NativeCore.deriveCursorAnchor(deriver);
        if (m != null) {
            boolean top = visible(m[0], m[1], w, h), bottom = visible(m[0], m[3], w, h);
            int flags = (top || bottom ? CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION : 0)
                | (!top || !bottom ? CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION : 0)
                | (m[4] != 0 ? CursorAnchorInfo.FLAG_IS_RTL : 0);
            b.setInsertionMarkerLocation(m[0], m[1], m[2], m[3], flags);
        }
        return b.build();
    }
    static boolean visible(float x, float y, int w, int h) { return x >= 0 && y >= 0 && x <= w && y <= h; }

    /** [start, end) of the joined text becomes `text`. */
    private void replace(int start, int end, String text) {
        long s = NativeCore.deriveLocate(deriver, start), e = NativeCore.deriveLocate(deriver, end);
        int ps = (int) (s >> 32), os = (int) (s & 0xffffffffL), pe = (int) (e >> 32), oe = (int) (e & 0xffffffffL);
        String first = paragraph(ps), last = pe == ps ? first : paragraph(pe);
        String joined = first.substring(0, os) + text + last.substring(oe);
        String[] pieces = joined.split("\n", -1);
        if (pe == ps && pieces.length == 1 && pieces[0].equals(first)) return;   /* no change */
        NativeCore.deriveText(deriver, ps, pieces[0]);
        for (int p = pe; p > ps; p--) if (NativeCore.deriveRemove(deriver, ps + 1) < 0) throw new IllegalStateException("deriveRemove");
        for (int k = 1; k < pieces.length; k++)
            if (NativeCore.deriveInsert(deriver, ps + k, pieces[k]) < 0) throw new IllegalStateException("deriveInsert: no free paragraph ids");
    }
    private String paragraph(int p) {
        String t = NativeCore.deriveParagraph(deriver, p);
        return t == null ? "" : t;
    }
}
