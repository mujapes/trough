package com.trough.hot;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trough's JSON: objects -> LinkedHashMap, arrays -> ArrayList, numbers -> Long or Double,
 * plus String, Boolean, null. One pass over the chars, no reflection, no dependency, so the
 * session core runs unchanged on the phone and on a plain JVM in tests.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("trailing data");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) { return o instanceof List ? (List<Object>) o : null; }

    public static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof String ? (String) v : null;
    }

    // --- parsing ---

    private Object value() {
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': lit("true"); return Boolean.TRUE;
            case 'f': lit("false"); return Boolean.FALSE;
            case 'n': lit("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') { i++; return m; }
        for (;;) {
            ws();
            if (peek() != '"') throw err("expected key");
            String k = string();
            ws();
            expect(':');
            ws();
            m.put(k, value());
            ws();
            char c = next();
            if (c == '}') return m;
            if (c != ',') throw err("expected , or }");
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') { i++; return a; }
        for (;;) {
            ws();
            a.add(value());
            ws();
            char c = next();
            if (c == ']') return a;
            if (c != ',') throw err("expected , or ]");
        }
    }

    private String string() {
        i++;  // opening quote
        StringBuilder b = null;
        int start = i;
        for (;;) {
            if (i >= s.length()) throw err("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b == null ? s.substring(start, i - 1) : b.toString();
            if (c != '\\') { if (b != null) b.append(c); continue; }
            if (b == null) b = new StringBuilder(s.substring(start, i - 1));
            char e = next();
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw err("bad \\u escape");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: throw err("bad escape");
            }
        }
    }

    private Object number() {
        int start = i;
        boolean frac = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+') { i++; continue; }
            if (c == '.' || c == 'e' || c == 'E') { frac = true; i++; continue; }
            break;
        }
        if (start == i) throw err("unexpected character");
        String t = s.substring(start, i);
        if (!frac) {
            try { return Long.parseLong(t); } catch (NumberFormatException big) { /* fall through */ }
        }
        return Double.parseDouble(t);
    }

    private void lit(String w) {
        if (!s.startsWith(w, i)) throw err("expected " + w);
        i += w.length();
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return;
            i++;
        }
    }

    private char peek() { return i < s.length() ? s.charAt(i) : 0; }

    private char next() {
        if (i >= s.length()) throw err("unexpected end");
        return s.charAt(i++);
    }

    private void expect(char c) { if (next() != c) throw err("expected " + c); }

    private IllegalArgumentException err(String what) {
        return new IllegalArgumentException("json: " + what + " at " + i);
    }

    // --- writing ---

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v) {
        if (v == null) { b.append("null"); return; }
        if (v instanceof String) { quote(b, (String) v); return; }
        if (v instanceof Boolean || v instanceof Long || v instanceof Integer) { b.append(v); return; }
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) b.append((long) d);
            else b.append(d);
            return;
        }
        if (v instanceof Map) {
            b.append('{');
            Iterator<? extends Map.Entry<?, ?>> it = ((Map<?, ?>) v).entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                quote(b, String.valueOf(e.getKey()));
                b.append(':');
                write(b, e.getValue());
                if (it.hasNext()) b.append(',');
            }
            b.append('}');
            return;
        }
        if (v instanceof List) {
            b.append('[');
            List<?> l = (List<?>) v;
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) b.append(',');
                write(b, l.get(k));
            }
            b.append(']');
            return;
        }
        quote(b, String.valueOf(v));
    }

    private static void quote(StringBuilder b, String t) {
        b.append('"');
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    /** Small builder for writing requests: Json.map("type", "user", "message", ...). */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k + 1 < kv.length; k += 2) m.put((String) kv[k], kv[k + 1]);
        return m;
    }

    public static List<Object> list(Object... items) {
        List<Object> l = new ArrayList<>();
        for (Object o : items) l.add(o);
        return l;
    }
}
