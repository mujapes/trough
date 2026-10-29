package com.trough.hot;

import android.text.Editable;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.method.KeyListener;
import android.text.method.TextKeyListener;
import android.view.KeyEvent;
import android.view.View;

/** Key events on the derived document (docs/INPUT.md "Keys"): hardware keyboards, and IMEs that
 * send key events (KEYCODE_DEL, ENTER) through BaseInputConnection.sendKeyEvent. The platform's
 * own TextKeyListener (what EditText uses for the same inputType: sentence caps, no autotext)
 * runs on an Editable window of the document: the paragraphs holding the selection, one more on
 * each side, and further back over empty paragraphs so caps rules see the preceding sentence.
 * Its change to the window becomes one replacement in the document; paragraphs outside the
 * window are untouched. Arrow keys are not handled yet (they need the view's Layout). */
final class DocumentKeys {
    private static final int CONTEXT = 64;           /* empty paragraphs looked back over at most */
    private final long deriver;
    private final DocumentText doc;
    private final KeyListener listener = TextKeyListener.getInstance(false, TextKeyListener.Capitalize.SENTENCES);

    DocumentKeys(long deriver, DocumentText doc) { this.deriver = deriver; this.doc = doc; }

    /** A window of the document: its text, where it starts, and the selection within it. */
    private static final class Window { Editable text; int start; String before; }

    private Window window(long[] s) {
        long lo = Math.min(s[1], s[2]), hi = Math.max(s[1], s[2]);
        long a = NativeCore.deriveLocate(deriver, lo), b = NativeCore.deriveLocate(deriver, hi);
        int pa = (int) (a >> 32), pb = (int) (b >> 32), count = NativeCore.deriveCount(deriver);
        int first = Math.max(0, pa - 1), last = Math.min(count - 1, pb + 1);
        for (int k = 0; k < CONTEXT && first > 0 && NativeCore.deriveParagraph(deriver, first).isEmpty(); k++) first--;
        long start = lo - (a & 0xffffffffL);         /* start of paragraph pa */
        for (int p = pa - 1; p >= first; p--) start -= NativeCore.deriveParagraph(deriver, p).length() + 1;
        StringBuilder t = new StringBuilder();
        for (int p = first; p <= last; p++) { if (p > first) t.append('\n'); t.append(NativeCore.deriveParagraph(deriver, p)); }
        Window w = new Window();
        w.start = (int) start; w.before = t.toString();
        w.text = new SpannableStringBuilder(w.before);
        Selection.setSelection(w.text, (int) (s[1] - start), (int) (s[2] - start));
        return w;
    }

    boolean down(View view, int keyCode, KeyEvent event) { return run(view, keyCode, event, true); }
    boolean up(View view, int keyCode, KeyEvent event) { return run(view, keyCode, event, false); }

    private boolean run(View view, int keyCode, KeyEvent event, boolean down) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_MOVE_HOME || keyCode == KeyEvent.KEYCODE_MOVE_END
                || keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) return false;
        long[] s = doc.state();
        Window w = window(s);
        boolean handled = down ? listener.onKeyDown(view, w.text, keyCode, event) : listener.onKeyUp(view, w.text, keyCode, event);
        String after = w.text.toString();
        int sa = Selection.getSelectionStart(w.text), sb = Selection.getSelectionEnd(w.text);
        if (after.equals(w.before) && sa == s[1] - w.start && sb == s[2] - w.start) return handled;
        int prefix = 0, n = w.before.length(), m = after.length();
        while (prefix < n && prefix < m && w.before.charAt(prefix) == after.charAt(prefix)) prefix++;
        int suffix = 0;
        while (suffix < n - prefix && suffix < m - prefix && w.before.charAt(n - 1 - suffix) == after.charAt(m - 1 - suffix)) suffix++;
        int[] ranges = after.equals(w.before) ? new int[0] : new int[]{w.start + prefix, w.start + n - suffix};
        String[] texts = after.equals(w.before) ? new String[0] : new String[]{after.substring(prefix, m - suffix)};
        /* The composing region survives a selection-only key; a key that changes text clears it
         * (Editable would shift it; IMEs finish composing before sending keys, so not mapped yet). */
        boolean edited = ranges.length > 0;
        doc.apply(ranges, texts, w.start + sa, w.start + sb, edited ? -1 : (int) s[3], edited ? -1 : (int) s[4]);
        return true;
    }
}
