package com.trough.hot;

import android.graphics.Rect;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Accessibility for the derived document (docs/ACCESSIBILITY.md): the host view is one editable
 * multi-line text field whose children are the paragraphs the viewport shows, as virtual views
 * named by paragraph id (stable across inserts and removals). Each paragraph node behaves as a
 * TextView holding that paragraph: text, selection while the document's selection lies in it,
 * bounds from the scene's exact heights, and View/TextView's traversal rules (the platform's
 * AccessibilityIterators for character, word, line and paragraph granularity; TextView's
 * ACTION_SET_SELECTION). The host scrolls. */
final class DocumentAccessibility extends AccessibilityNodeProvider {
    static final int GRANULARITIES = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER | AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD
        | AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE | AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH;
    /** Scrolls the host by a page (forward or back); returns whether it moved. */
    interface Scroller { boolean scroll(boolean forward); }

    private final View host;
    private final long deriver;
    private final Scroller scroller;
    private final Locale locale;
    private int accessibilityFocus = -2;              /* virtual id; -2 none */

    DocumentAccessibility(View host, long deriver, Scroller scroller) {
        this.host = host; this.deriver = deriver; this.scroller = scroller;
        this.locale = host.getContext().getResources().getConfiguration().getLocales().get(0);
    }

    /** A shown paragraph: position, id, viewport top and height. */
    static final class Shown { int position, id; long top, height; }
    /** The shown paragraphs and the layout width (row 0 of deriveVisible). */
    List<Shown> shown(int[] width) {
        long[] v = NativeCore.deriveVisible(deriver);
        List<Shown> out = new ArrayList<>();
        if (v == null || v.length < 1) return out;
        if (width != null) width[0] = (int) v[0];
        for (int k = 1; k + 4 <= v.length; k += 4) {
            Shown s = new Shown();
            s.position = (int) v[k]; s.id = (int) v[k + 1]; s.top = v[k + 2]; s.height = v[k + 3];
            out.add(s);
        }
        return out;
    }
    private Shown find(int id) {
        for (Shown s : shown(null)) if (s.id == id) return s;
        return null;
    }

    @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualId) {
        if (virtualId == View.NO_ID) {
            AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(host);
            host.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.EditText");
            info.setEditable(true);
            info.setMultiLine(true);
            info.setFocusable(true);
            info.setScrollable(true);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            for (Shown s : shown(null)) info.addChild(host, s.id);
            return info;
        }
        int[] width = new int[1];
        Shown at = null;
        for (Shown s : shown(width)) if (s.id == virtualId) at = s;
        if (at == null) return null;
        String text = NativeCore.deriveParagraph(deriver, at.position);
        if (text == null) return null;
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(host, virtualId);
        info.setParent(host);
        info.setPackageName(host.getContext().getPackageName());
        info.setClassName("android.widget.EditText");
        info.setText(text);
        info.setEditable(true);
        info.setMultiLine(true);
        info.setFocusable(true);
        info.setVisibleToUser(true);
        info.setEnabled(true);
        info.setMovementGranularities(GRANULARITIES);
        int[] sel = selection(at.position, text.length());
        if (sel != null) info.setTextSelection(sel[0], sel[1]);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
        info.addAction(accessibilityFocus == virtualId ? AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            : AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
        info.setAccessibilityFocused(accessibilityFocus == virtualId);
        /* As ExploreByTouchHelper: parent bounds unclipped; screen bounds clipped to what the host shows. */
        Rect r = new Rect(0, (int) at.top, host.getWidth() > 0 ? host.getWidth() : width[0], (int) (at.top + at.height));
        info.setBoundsInParent(r);
        Rect screen = new Rect(r);
        if (host.getWidth() > 0 && host.getHeight() > 0 && !screen.intersect(0, 0, host.getWidth(), host.getHeight())) screen.setEmpty();
        int[] loc = new int[2];
        host.getLocationOnScreen(loc);
        screen.offset(loc[0], loc[1]);
        info.setBoundsInScreen(screen);
        return info;
    }

    /** The document's selection as offsets in paragraph `position`, when both ends lie in it. */
    int[] selection(int position, int length) {
        long[] s = NativeCore.deriveDocState(deriver);
        if (s == null || s[3] == 0) return null;
        long a = NativeCore.deriveLocate(deriver, s[1]), c = NativeCore.deriveLocate(deriver, s[2]);
        if ((int) (a >> 32) != position || (int) (c >> 32) != position) return null;
        int start = (int) (a & 0xffffffffL), end = (int) (c & 0xffffffffL);
        return new int[]{Math.min(start, length), Math.min(end, length)};
    }

    @Override public boolean performAction(int virtualId, int action, Bundle args) {
        if (virtualId == View.NO_ID) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return scroller.scroll(true);
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return scroller.scroll(false);
            return host.performAccessibilityAction(action, args);
        }
        Shown at = find(virtualId);
        int position = at != null ? at.position : NativeCore.derivePosition(deriver, virtualId);
        if (position < 0) return false;
        String text = NativeCore.deriveParagraph(deriver, position);
        if (text == null) return false;
        switch (action) {
            case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                if (accessibilityFocus == virtualId) return false;
                accessibilityFocus = virtualId;
                return true;
            case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                if (accessibilityFocus != virtualId) return false;
                accessibilityFocus = -2;
                return true;
            case AccessibilityNodeInfo.ACTION_CLICK:
                NativeCore.deriveSelect(deriver, position, text.length());
                return true;
            case AccessibilityNodeInfo.ACTION_SET_SELECTION: {   /* TextView's rule */
                int start = args != null ? args.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, -1) : -1;
                int end = args != null ? args.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, -1) : -1;
                int[] cur = selection(position, text.length());
                int cs = cur == null ? -1 : cur[0], ce = cur == null ? -1 : cur[1];
                if (cs == start && ce == end) return false;
                if (start == end && end == -1) { NativeCore.deriveSelect(deriver, position, -1); return true; }
                if (start >= 0 && start <= end && end <= text.length()) { select(position, start, end); return true; }
                return false;
            }
            case AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY:
            case AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY: {
                int granularity = args != null ? args.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, 0) : 0;
                boolean extend = args != null && args.getBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false);
                return traverse(position, text, granularity, action == AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, extend);
            }
        }
        return false;
    }

    private void select(int position, int start, int end) {
        if (start == end) NativeCore.deriveSelect(deriver, position, end);
        else NativeCore.deriveSelectRange(deriver, position, start, position, end);
    }

    /** View.traverseAtGranularity with TextView's selection as the accessibility selection. */
    boolean traverse(int position, String text, int granularity, boolean forward, boolean extend) {
        if (TextUtils.isEmpty(text)) return false;
        int[] cur = selection(position, text.length());
        int current = cur == null ? -1 : cur[1];
        if (current == -1) current = forward ? 0 : text.length();
        int[] range = forward ? following(text, granularity, current) : preceding(text, granularity, current);
        if (range == null) return false;
        int start, end;
        if (extend) {
            start = cur == null ? -1 : cur[0];
            if (start == -1) start = forward ? range[0] : range[1];
            end = forward ? range[1] : range[0];
        } else start = end = forward ? range[1] : range[0];
        int[] now = selection(position, text.length());
        if (now == null || now[0] != start || now[1] != end) {   /* TextView.setAccessibilitySelection */
            if (Math.min(start, end) >= 0 && Math.max(start, end) <= text.length()) select(position, start, end);
            else NativeCore.deriveSelect(deriver, position, -1);
        }
        return true;
    }

    /* ---- the platform's AccessibilityIterators, over one paragraph ---- */
    private int[] following(String t, int granularity, int offset) {
        switch (granularity) {
            case AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER: return charFollowing(t, offset);
            case AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD: return wordFollowing(t, offset);
            case AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE: return lineFollowing(t, offset);
            case AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH: return paragraphFollowing(t, offset);
        }
        return null;
    }
    private int[] preceding(String t, int granularity, int offset) {
        switch (granularity) {
            case AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER: return charPreceding(t, offset);
            case AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD: return wordPreceding(t, offset);
            case AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE: return linePreceding(t, offset);
            case AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH: return paragraphPreceding(t, offset);
        }
        return null;
    }
    private int[] charFollowing(String t, int offset) {
        int n = t.length();
        if (n <= 0 || offset >= n) return null;
        BreakIterator it = BreakIterator.getCharacterInstance(locale); it.setText(t);
        int start = Math.max(0, offset);
        while (!it.isBoundary(start)) { start = it.following(start); if (start == BreakIterator.DONE) return null; }
        int end = it.following(start);
        return end == BreakIterator.DONE ? null : new int[]{start, end};
    }
    private int[] charPreceding(String t, int offset) {
        int n = t.length();
        if (n <= 0 || offset <= 0) return null;
        BreakIterator it = BreakIterator.getCharacterInstance(locale); it.setText(t);
        int end = Math.min(offset, n);
        while (!it.isBoundary(end)) { end = it.preceding(end); if (end == BreakIterator.DONE) return null; }
        int start = it.preceding(end);
        return start == BreakIterator.DONE ? null : new int[]{start, end};
    }
    private static boolean letterOrDigit(String t, int i) { return i >= 0 && i < t.length() && Character.isLetterOrDigit(t.codePointAt(i)); }
    private static boolean wordStart(String t, int i) { return letterOrDigit(t, i) && (i == 0 || !letterOrDigit(t, i - 1)); }
    private static boolean wordEnd(String t, int i) { return (i > 0 && letterOrDigit(t, i - 1)) && (i == t.length() || !letterOrDigit(t, i)); }
    private int[] wordFollowing(String t, int offset) {
        int n = t.length();
        if (n <= 0 || offset >= n) return null;
        BreakIterator it = BreakIterator.getWordInstance(locale); it.setText(t);
        int start = Math.max(0, offset);
        while (!letterOrDigit(t, start) && !wordStart(t, start)) { start = it.following(start); if (start == BreakIterator.DONE) return null; }
        int end = it.following(start);
        return end == BreakIterator.DONE || !wordEnd(t, end) ? null : new int[]{start, end};
    }
    private int[] wordPreceding(String t, int offset) {
        int n = t.length();
        if (n <= 0 || offset <= 0) return null;
        BreakIterator it = BreakIterator.getWordInstance(locale); it.setText(t);
        int end = Math.min(offset, n);
        while (end > 0 && !letterOrDigit(t, end - 1) && !wordEnd(t, end)) { end = it.preceding(end); if (end == BreakIterator.DONE) return null; }
        int start = it.preceding(end);
        return start == BreakIterator.DONE || !wordStart(t, start) ? null : new int[]{start, end};
    }
    private int lineWidth() { int[] w = new int[1]; shown(w); return w[0]; }
    private static int lineOf(int[] lines, int offset) {
        for (int i = lines.length / 3 - 1; i > 0; i--) if (offset >= lines[3 * i]) return i;
        return 0;
    }
    /** LineTextSegmentIterator.getLineEdgeIndex: direction -1 start, 1 end. */
    private static int edge(int[] lines, int line, int direction) {
        return direction * lines[3 * line + 2] < 0 ? lines[3 * line] : lines[3 * line + 1] - 1;
    }
    private int[] lineFollowing(String t, int offset) {
        int n = t.length();
        if (n <= 0 || offset >= n) return null;
        int[] lines = DeriveLayout.lines(t, lineWidth());
        int next;
        if (offset < 0) next = lineOf(lines, 0);
        else { int cur = lineOf(lines, offset); next = edge(lines, cur, -1) == offset ? cur : cur + 1; }
        if (next >= lines.length / 3) return null;
        return range(n, edge(lines, next, -1), edge(lines, next, 1) + 1);
    }
    private int[] linePreceding(String t, int offset) {
        int n = t.length();
        if (n <= 0 || offset <= 0) return null;
        int[] lines = DeriveLayout.lines(t, lineWidth());
        int prev;
        if (offset > n) prev = lineOf(lines, n);
        else { int cur = lineOf(lines, offset); prev = edge(lines, cur, 1) + 1 == offset ? cur : cur - 1; }
        if (prev < 0) return null;
        return range(n, edge(lines, prev, -1), edge(lines, prev, 1) + 1);
    }
    /** AbstractTextSegmentIterator.getRange: null when empty or reversed. */
    private static int[] range(int n, int start, int end) { return start < 0 || end < 0 || start == end ? null : new int[]{start, end}; }
    private static boolean paraStart(String t, int i) { return t.charAt(i) != '\n' && (i == 0 || t.charAt(i - 1) == '\n'); }
    private static boolean paraEnd(String t, int i) { return (i > 0 && t.charAt(i - 1) != '\n') && (i == t.length() || t.charAt(i) == '\n'); }
    private static int[] paragraphFollowing(String t, int offset) {
        int n = t.length();
        if (n <= 0 || offset >= n) return null;
        int start = Math.max(0, offset);
        while (start < n && t.charAt(start) == '\n' && !paraStart(t, start)) start++;
        if (start >= n) return null;
        int end = start + 1;
        while (end < n && !paraEnd(t, end)) end++;
        return new int[]{start, end};
    }
    private static int[] paragraphPreceding(String t, int offset) {
        int n = t.length();
        if (n <= 0 || offset <= 0) return null;
        int end = Math.min(offset, n);
        while (end > 0 && t.charAt(end - 1) == '\n' && !paraEnd(t, end)) end--;
        if (end <= 0) return null;
        int start = end - 1;
        while (start > 0 && !paraStart(t, start)) start--;
        return new int[]{start, end};
    }
}
