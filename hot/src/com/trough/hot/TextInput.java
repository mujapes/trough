package com.trough.hot;

import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.TextAttribute;

/** Android IME adapter over the native draft queue (docs/INPUT.md). Each IME operation becomes
 * one atomic edit batch with its final selection and composing region; the text lives only in
 * the queue (no Java mirror). Semantics follow Android's BaseInputConnection over an Editable,
 * which the android.ime scenario uses as a differential reference. Offsets are UTF-16 units. */
final class TextInput extends BaseInputConnection {
    /** Where the text lives. The IME rules below are the same for every backend. */
    interface Backend {
        /** {length, anchor, caret, composingStart, composingEnd}. */
        long[] state();
        /** Replace ranges in order (each in the text the previous one left), then set the
         * selection and composing region (final coordinates; -1 = none) atomically. */
        void apply(int[] ranges, String[] texts, int anchor, int caret, int cs, int ce);
        String slice(int start, int end);
    }
    /** The native draft queue (docs/DRAFTS.md). */
    static final class Draft implements Backend {
        private final long keeper, queue;
        Draft(long keeper, long queue) { this.keeper = keeper; this.queue = queue; }
        @Override public long[] state() {
            long[] v = NativeCore.value(queue);
            if (v == null) throw new IllegalStateException("draft value");
            return v;
        }
        @Override public void apply(int[] ranges, String[] texts, int anchor, int caret, int cs, int ce) {
            long r = NativeCore.batch(keeper, queue, ranges, texts, anchor, caret, cs, ce);
            if (r < 0) throw new IllegalStateException("draft edit: ts_status " + (-r));
        }
        @Override public String slice(int start, int end) { return NativeCore.slice(queue, start, end); }
    }

    /** The view hosting the connection. changed() runs after an edit outside a batch, or when the
     * outermost batch that edited ends (TextView reports selection at the same points). */
    interface Host {
        void changed();
        /** InputConnection.requestCursorUpdates: modes and filters OR-ed together. */
        boolean cursorUpdates(int mode);
    }
    volatile Host host;

    private final Backend text;
    private int batchDepth;
    private boolean editedInBatch;

    TextInput(View view, long keeper, long queue) { this(view, new Draft(keeper, queue)); }
    TextInput(View view, Backend text) {
        super(view, true);    // a full editor; false is the key-event fallback mode
        this.text = text;
    }

    private long[] state() { return text.state(); }
    private void apply(int[] ranges, String[] texts, int anchor, int caret, int cs, int ce) {
        text.apply(ranges, texts, anchor, caret, cs, ce);
        if (batchDepth > 0) { editedInBatch = true; return; }
        Host h = host;
        if (h != null) h.changed();
    }
    /** Where a cursor point at p lands when [a, b) is replaced by n units (Editable's point rule:
     * an insertion at the point pushes it forward; inside a replaced range it moves to the end). */
    private static int point(int p, int a, int b, int n) {
        if (a == b) return p < a ? p : p + n;
        if (p <= a) return p;
        return p <= b ? a + n : p - (b - a) + n;
    }
    /** Where a position at p lands when [s, e) is deleted. */
    private static int deleted(int p, int s, int e) { return p <= s ? p : p <= e ? s : p - (e - s); }

    /** commitText / setComposingText: replace the composing region (else the selection). */
    private boolean replace(CharSequence text, int cursor, boolean composing) {
        long[] v = state();
        int len = (int) v[0], a, b;
        if (v[3] >= 0) { a = (int) Math.min(v[3], v[4]); b = (int) Math.max(v[3], v[4]); }
        else { a = (int) Math.min(v[1], v[2]); b = (int) Math.max(v[1], v[2]); }
        String t = text == null ? "" : text.toString();
        int at = cursor > 0 ? cursor + b - 1 : cursor + a;
        if (at < 0) at = 0;
        if (at > len) at = len;
        /* Android keeps a cursor placed at the start of a pure insertion before it
         * (newCursorPosition 0); otherwise the point rule applies. */
        int caret = cursor == 0 && a == b ? at : point(at, a, b, t.length());
        boolean comp = composing && t.length() > 0;
        apply(new int[]{a, b}, new String[]{t}, caret, caret, comp ? a : -1, comp ? a + t.length() : -1);
        return true;
    }

    @Override public boolean commitText(CharSequence text, int newCursorPosition) { return replace(text, newCursorPosition, false); }
    @Override public boolean commitText(CharSequence text, int newCursorPosition, TextAttribute attribute) { return replace(text, newCursorPosition, false); }
    @Override public boolean setComposingText(CharSequence text, int newCursorPosition) { return replace(text, newCursorPosition, true); }
    @Override public boolean setComposingText(CharSequence text, int newCursorPosition, TextAttribute attribute) { return replace(text, newCursorPosition, true); }

    @Override public boolean setComposingRegion(int start, int end) {
        long[] v = state();
        int len = (int) v[0], a = Math.min(start, end), b = Math.max(start, end);
        a = Math.max(0, Math.min(a, len)); b = Math.max(0, Math.min(b, len));
        apply(new int[0], new String[0], (int) v[1], (int) v[2], a < b ? a : -1, a < b ? b : -1);
        return true;
    }
    @Override public boolean setComposingRegion(int start, int end, TextAttribute attribute) { return setComposingRegion(start, end); }

    @Override public boolean finishComposingText() {
        long[] v = state();
        if (v[3] >= 0) apply(new int[0], new String[0], (int) v[1], (int) v[2], -1, -1);
        return true;
    }

    @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        /* Negative lengths delete nothing on that side (BaseInputConnection; the IME framework,
         * not the connection, rejects them). */
        long[] v = state();
        int len = (int) v[0], a = (int) Math.min(v[1], v[2]), b = (int) Math.max(v[1], v[2]);
        int cs = (int) v[3], ce = (int) v[4];
        if (cs >= 0) { a = Math.min(a, Math.min(cs, ce)); b = Math.max(b, Math.max(cs, ce)); }
        int anchor = (int) v[1], caret = (int) v[2];
        int[] ranges = new int[4]; String[] texts = new String[2]; int count = 0;
        int deletedBefore = 0;
        if (beforeLength > 0) {
            int s = Math.max(0, a - beforeLength);
            if (a - s > 0) {
                ranges[0] = s; ranges[1] = a; texts[0] = ""; count = 1; deletedBefore = a - s;
                anchor = deleted(anchor, s, a); caret = deleted(caret, s, a);
                if (cs >= 0) { cs = deleted(cs, s, a); ce = deleted(ce, s, a); }
                len -= deletedBefore;
            }
        }
        if (afterLength > 0) {
            int bb = b - deletedBefore, e = Math.min(len, bb + afterLength);
            if (e - bb > 0) {
                ranges[2 * count] = bb; ranges[2 * count + 1] = e; texts[count] = ""; count++;
                anchor = deleted(anchor, bb, e); caret = deleted(caret, bb, e);
                if (cs >= 0) { cs = deleted(cs, bb, e); ce = deleted(ce, bb, e); }
            }
        }
        if (count == 0) return true;
        if (cs >= 0 && cs == ce) cs = ce = -1;    /* an empty composing span is dropped */
        int[] r = new int[2 * count]; String[] t = new String[count];
        System.arraycopy(ranges, 0, r, 0, 2 * count); System.arraycopy(texts, 0, t, 0, count);
        apply(r, t, anchor, caret, cs, ce);
        return true;
    }

    @Override public boolean setSelection(int start, int end) {
        long[] v = state();
        int len = (int) v[0];
        if (start > len || end > len || start < 0 || end < 0) return true;   /* ignored, as Android does */
        apply(new int[0], new String[0], start, end, (int) v[3], (int) v[4]);
        return true;
    }

    @Override public CharSequence getTextBeforeCursor(int n, int flags) {
        long[] v = state();
        int a = (int) Math.min(v[1], v[2]);
        if (n < 0) return null;
        return text.slice(a - Math.min(n, a), a);
    }
    @Override public CharSequence getTextAfterCursor(int n, int flags) {
        long[] v = state();
        int b = (int) Math.max(v[1], v[2]), len = (int) v[0];
        if (n < 0) return null;
        return text.slice(b, (int) Math.min((long) b + n, len));
    }
    @Override public CharSequence getSelectedText(int flags) {
        long[] v = state();
        int a = (int) Math.min(v[1], v[2]), b = (int) Math.max(v[1], v[2]);
        return a == b ? null : text.slice(a, b);
    }

    /** Batches are already atomic per operation; nesting is tracked for IME bookkeeping only. */
    @Override public boolean beginBatchEdit() { batchDepth++; return true; }
    @Override public boolean endBatchEdit() {
        if (batchDepth > 0) batchDepth--;
        if (batchDepth == 0 && editedInBatch) {
            editedInBatch = false;
            Host h = host;
            if (h != null) h.changed();
        }
        return batchDepth > 0;
    }
    @Override public boolean requestCursorUpdates(int mode) { Host h = host; return h != null && h.cursorUpdates(mode); }
    @Override public boolean requestCursorUpdates(int mode, int filter) { return requestCursorUpdates(mode | filter); }
}
