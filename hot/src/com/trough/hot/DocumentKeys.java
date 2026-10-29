package com.trough.hot;

import android.text.Editable;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.graphics.text.LineBreaker;
import android.text.InputType;
import android.text.Layout;
import android.text.Spannable;
import android.text.method.ArrowKeyMovementMethod;
import android.text.method.KeyListener;
import android.util.TypedValue;
import android.widget.EditText;
import android.text.method.TextKeyListener;
import android.view.KeyEvent;
import android.view.View;

/** Key events on the derived document (docs/INPUT.md "Keys"): hardware keyboards, and IMEs that
 * send key events (KEYCODE_DEL, ENTER) through BaseInputConnection.sendKeyEvent. The platform's
 * own TextKeyListener (what EditText uses for the same inputType: sentence caps, no autotext)
 * runs on an Editable window of the document: the paragraphs holding the selection, one more on
 * each side, and further back over empty paragraphs so caps rules see the preceding sentence.
 * Its change to the window becomes one replacement in the document; paragraphs outside the
 * window are untouched. Movement keys (arrows, Home/End, Page) run the platform's
 * ArrowKeyMovementMethod on a scratch EditText holding the window, laid out exactly as the
 * derived paragraphs are (same width, text size and line breaking: android.access checks the
 * lines are equal), so visual bidi movement and line moves follow the platform. */
final class DocumentKeys {
    private static final int CONTEXT = 64;           /* empty paragraphs looked back over at most */
    private final long deriver;
    private final DocumentText doc;
    private final KeyListener listener = TextKeyListener.getInstance(false, TextKeyListener.Capitalize.SENTENCES);

    private EditText scratch;
    /* Selection's own spans (its vertical-move column memory, android.text.Selection$...) left on
     * the window by the last movement key, in document offsets, kept while the selection stays
     * where that key put it. Rebuilding the window would otherwise forget the column. */
    private Object[] memory = new Object[0]; private int[] memoryAt = new int[0]; private long memoryAnchor = -1, memoryCaret = -1;

    DocumentKeys(long deriver, DocumentText doc) { this.deriver = deriver; this.doc = doc; }

    /** An EditText laid out like the derived view's paragraphs (AccessOracle.reference's settings). */
    static EditText laidOut(android.content.Context context) {
        EditText e = new EditText(context);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, DeriveLayout.TEXT_SIZE);
        e.setPadding(0, 0, 0, 0);
        e.setIncludeFontPadding(false);
        e.setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY);
        e.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        /* Detached views have no LayoutParams; TextView.checkForResize reads them on every edit. */
        e.setLayoutParams(new android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }
    static void layout(EditText e, int width) {
        e.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        e.layout(0, 0, width, e.getMeasuredHeight());
    }
    private static boolean movement(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_UP
            || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_MOVE_HOME || keyCode == KeyEvent.KEYCODE_MOVE_END
            || keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN;
    }

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
        long[] s = doc.state();
        Window w = window(s);
        boolean handled;
        if (movement(keyCode)) {
            if (scratch == null) scratch = laidOut(view.getContext());
            long[] visible = NativeCore.deriveVisible(deriver);
            scratch.setText(w.text);
            layout(scratch, visible != null && visible.length > 0 ? (int) visible[0] : ViewOracle.WIDTH);
            Spannable t = (Spannable) scratch.getText();
            Selection.setSelection(t, Selection.getSelectionStart(w.text), Selection.getSelectionEnd(w.text));
            if (memoryAnchor == s[1] && memoryCaret == s[2])
                for (int k = 0; k < memory.length; k++) {
                    int a = memoryAt[3 * k] - w.start, b = memoryAt[3 * k + 1] - w.start;
                    if (a >= 0 && b <= t.length() && a <= b) t.setSpan(memory[k], a, b, memoryAt[3 * k + 2]);
                }
            ArrowKeyMovementMethod m = (ArrowKeyMovementMethod) ArrowKeyMovementMethod.getInstance();
            handled = down ? m.onKeyDown(scratch, t, keyCode, event) : m.onKeyUp(scratch, t, keyCode, event);
            w.text = (Editable) t;
            if (down) {
                java.util.List<Object> keep = new java.util.ArrayList<>(); java.util.List<Integer> at = new java.util.ArrayList<>();
                for (Object span : t.getSpans(0, t.length(), Object.class)) {
                    String name = span.getClass().getName();
                    if (!name.startsWith("android.text.Selection$") || span == Selection.SELECTION_START || span == Selection.SELECTION_END) continue;
                    keep.add(span); at.add(w.start + t.getSpanStart(span)); at.add(w.start + t.getSpanEnd(span)); at.add(t.getSpanFlags(span));
                }
                memory = keep.toArray();
                memoryAt = new int[at.size()];
                for (int k = 0; k < memoryAt.length; k++) memoryAt[k] = at.get(k);
                memoryAnchor = w.start + Selection.getSelectionStart(t); memoryCaret = w.start + Selection.getSelectionEnd(t);
            }
        } else {
            handled = down ? listener.onKeyDown(view, w.text, keyCode, event) : listener.onKeyUp(view, w.text, keyCode, event);
            if (down) memoryAnchor = memoryCaret = -1;   /* an edit or typing ends the column memory */
        }
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
