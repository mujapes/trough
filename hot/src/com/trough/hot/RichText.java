package com.trough.hot;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

import java.net.URI;
import java.util.ArrayList;

/** Native, selectable Markdown for partial and complete assistant messages. No HTML. */
final class RichText {
    private static final int CODE_BG = 0xFF1C2924, CODE_FG = 0xFFC5DFC9;
    private static final int MAX_SPANS = 4096;
    private RichText() {}

    /** Each character is visited a bounded number of times; unfinished code stays readable. */
    static CharSequence render(String source) {
        if (source == null || source.length() == 0) return "";
        State state = new State(source.length());
        int position = 0, codeStart = -1, fenceLength = 0;
        char fence = 0;
        while (position < source.length()) {
            int end = source.indexOf('\n', position);
            boolean newline = end >= 0;
            if (!newline) end = source.length();
            int contentEnd = end;
            if (contentEnd > position && source.charAt(contentEnd - 1) == '\r') contentEnd--;
            int first = position;
            while (first < contentEnd && first - position < 3 && source.charAt(first) == ' ') first++;
            int run = 0;
            char marker = first < contentEnd ? source.charAt(first) : 0;
            if (marker == '`' || marker == '~') {
                while (first + run < contentEnd && source.charAt(first + run) == marker) run++;
            }
            if (codeStart >= 0) {
                if (marker == fence && run >= fenceLength && onlySpace(source, first + run, contentEnd)) {
                    state.code(codeStart, state.text.length());
                    codeStart = -1;
                } else {
                    state.text.append(source, position, contentEnd);
                    if (newline) state.text.append('\n');
                }
            } else if (run >= 3) {
                codeStart = state.text.length();
                fence = marker;
                fenceLength = run;
            } else {
                int heading = 0;
                while (first + heading < contentEnd && source.charAt(first + heading) == '#' && heading < 6) heading++;
                int start = state.text.length();
                if (heading > 0 && first + heading < contentEnd && source.charAt(first + heading) == ' ') {
                    inline(state, source, first + heading + 1, contentEnd);
                    int stop = state.text.length();
                    state.span(new StyleSpan(Typeface.BOLD), start, stop);
                    state.span(new RelativeSizeSpan(heading == 1 ? 1.3f : heading == 2 ? 1.18f : 1.08f), start, stop);
                } else if (first + 1 < contentEnd && (marker == '-' || marker == '*' || marker == '+')
                        && source.charAt(first + 1) == ' ') {
                    state.text.append("\u2022 ");
                    inline(state, source, first + 2, contentEnd);
                    if (newline) state.text.append('\n');
                    state.span(new LeadingMarginSpan.Standard(12, 28), start, state.text.length());
                    position = newline ? end + 1 : end;
                    continue;
                } else {
                    inline(state, source, position, contentEnd);
                }
                if (newline) state.text.append('\n');
            }
            position = newline ? end + 1 : end;
        }
        if (codeStart >= 0) state.code(codeStart, state.text.length());
        SpannableString result = new SpannableString(state.text.toString());
        for (Mark mark : state.marks)
            result.setSpan(mark.value, mark.start, mark.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return result;
    }

    private static void inline(State out, String source, int from, int end) {
        int boldStart = -1;
        for (int i = from; i < end; ) {
            char c = source.charAt(i);
            if (c == '\\' && i + 1 < end && "\\`*[]()#_-.!+>".indexOf(source.charAt(i + 1)) >= 0) {
                out.text.append(source.charAt(i + 1));
                i += 2;
            } else if (c == '`') {
                int close = find(source, '`', i + 1, end);
                if (close < 0) close = end;
                int start = out.text.length();
                out.text.append(source, i + 1, close);
                out.code(start, out.text.length());
                i = close < end ? close + 1 : end;
            } else if (c == '*' && i + 1 < end && source.charAt(i + 1) == '*') {
                if (boldStart < 0) boldStart = out.text.length();
                else {
                    out.span(new StyleSpan(Typeface.BOLD), boldStart, out.text.length());
                    boldStart = -1;
                }
                i += 2;
            } else if (c == '[') {
                int close = find(source, ']', i + 1, end);
                if (close < 0) {
                    out.text.append(source, i, end);
                    break;
                }
                if (close + 1 < end && source.charAt(close + 1) == '(') {
                    int stop = find(source, ')', close + 2, end);
                    if (stop < 0) {
                        out.text.append(source, i, end);
                        break;
                    }
                    String url = source.substring(close + 2, stop);
                    if (safeUrl(url)) {
                        int start = out.text.length();
                        out.text.append(source, i + 1, close);
                        out.span(new URLSpan(url), start, out.text.length());
                    } else out.text.append(source, i, stop + 1);
                    i = stop + 1;
                } else {
                    // Consume the failed candidate once, including its closing bracket.
                    out.text.append(source, i, close + 1);
                    i = close + 1;
                }
            } else if (source.startsWith("https://", i) || source.startsWith("http://", i)) {
                int stop = i;
                while (stop < end && !Character.isWhitespace(source.charAt(stop))
                        && "<>\"'()".indexOf(source.charAt(stop)) < 0) stop++;
                int linkEnd = stop;
                while (linkEnd > i && ".,;:!?".indexOf(source.charAt(linkEnd - 1)) >= 0) linkEnd--;
                int start = out.text.length();
                out.text.append(source, i, stop);
                String url = source.substring(i, linkEnd);
                if (safeUrl(url)) out.span(new URLSpan(url), start, start + linkEnd - i);
                i = stop;
            } else {
                out.text.append(c);
                i++;
            }
        }
        if (boldStart >= 0) out.span(new StyleSpan(Typeface.BOLD), boldStart, out.text.length());
    }

    private static boolean onlySpace(String source, int start, int end) {
        for (int i = start; i < end; i++) if (!Character.isWhitespace(source.charAt(i))) return false;
        return true;
    }

    private static int find(String source, char wanted, int start, int end) {
        for (int i = start; i < end; i++) if (source.charAt(i) == wanted) return i;
        return -1;
    }

    private static boolean safeUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                && uri.getHost() != null && uri.getHost().length() > 0;
        } catch (Exception ignored) { return false; }
    }

    private static final class Mark {
        final Object value;
        final int start, end;
        Mark(Object value, int start, int end) { this.value = value; this.start = start; this.end = end; }
    }

    private static final class State {
        final StringBuilder text;
        final ArrayList<Mark> marks = new ArrayList<>();
        State(int capacity) { text = new StringBuilder(capacity); }
        void span(Object value, int start, int end) {
            if (end > start && marks.size() < MAX_SPANS) marks.add(new Mark(value, start, end));
        }
        void code(int start, int end) {
            span(new TypefaceSpan("monospace"), start, end);
            span(new BackgroundColorSpan(CODE_BG), start, end);
            span(new ForegroundColorSpan(CODE_FG), start, end);
        }
    }
}
