package com.trough.hot;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A model API, for TroughAgent. Two wire formats cover almost every model there is:
 *   anthropic -- POST {base}/v1/messages, SSE (Claude)
 *   openai    -- POST {base}/chat/completions, SSE (OpenAI, DeepSeek, OpenRouter, Groq, Mistral,
 *                llama.cpp / ollama / vLLM servers, ...)
 * Messages are Trough's own neutral form and converted per request:
 *   {"role":"user","text"}  {"role":"assistant","text","calls":[{"id","name","input"}]}
 *   {"role":"tool","id","text","error"}
 * Plain HttpURLConnection: no SDK, nothing between Trough and the API.
 */
abstract class Provider {
    final String base, model, key;

    Provider(String base, String model, String key) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.model = model;
        this.key = key;
    }

    static Provider of(Map<String, Object> cfg, String key) {
        String api = Json.str(cfg, "api"), base = Json.str(cfg, "base"), model = Json.str(cfg, "model");
        if ("anthropic".equals(api)) return new Anthropic(base == null ? "https://api.anthropic.com" : base, model, key);
        if ("openai".equals(api)) return new OpenAI(base == null ? "https://api.openai.com/v1" : base, model, key);
        throw new IllegalArgumentException("unknown api " + api + " (anthropic | openai)");
    }

    /** One model response. */
    static final class Reply {
        final StringBuilder text = new StringBuilder();
        final List<Map<String, Object>> calls = new ArrayList<>();
        String stop, error;
    }

    interface Sink { void onTextDelta(String t); }

    private volatile HttpURLConnection live;

    /** Aborts the request in flight, if any (interrupt). */
    void cancel() {
        HttpURLConnection c = live;
        if (c != null) c.disconnect();
    }

    abstract Reply send(String system, List<Map<String, Object>> messages, List<Map<String, Object>> tools, Sink sink) throws Exception;

    /** POSTs body and hands each SSE data payload to onData; returns null or an error. */
    String post(String url, Map<String, String> headers, Object body, DataHandler onData) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        live = c;
        try {
            byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(30000);
            c.setReadTimeout(300000);
            c.setFixedLengthStreamingMode(bytes.length);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("accept", "text/event-stream");
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            try (OutputStream o = c.getOutputStream()) { o.write(bytes); }
            int status = c.getResponseCode();
            if (status / 100 != 2) {
                InputStream es = c.getErrorStream();
                String err = es == null ? "" : read(es);
                return "HTTP " + status + ": " + (err.length() > 600 ? err.substring(0, 600) : err);
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || data.equals("[DONE]")) continue;
                    Map<String, Object> m = Json.obj(Json.parse(data));
                    if (m != null) onData.on(m);
                }
            }
            return null;
        } finally {
            live = null;
            c.disconnect();
        }
    }

    interface DataHandler { void on(Map<String, Object> m); }

    private static String read(InputStream in) throws Exception {
        try (InputStream s = in) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = s.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    // --- Anthropic Messages ---

    static final class Anthropic extends Provider {
        Anthropic(String base, String model, String key) { super(base, model, key); }

        @Override Reply send(String system, List<Map<String, Object>> messages, List<Map<String, Object>> tools, final Sink sink) throws Exception {
            List<Object> wire = new ArrayList<>(), results = null;
            for (Map<String, Object> m : messages) {
                String role = Json.str(m, "role");
                if ("tool".equals(role)) {   // consecutive tool results share one user turn
                    if (results == null) { results = new ArrayList<>(); wire.add(Json.map("role", "user", "content", results)); }
                    results.add(Json.map("type", "tool_result", "tool_use_id", Json.str(m, "id"),
                        "content", Json.str(m, "text"), "is_error", Boolean.TRUE.equals(m.get("error"))));
                    continue;
                }
                results = null;
                List<Object> content = new ArrayList<>();
                String text = Json.str(m, "text");
                if (text != null && !text.isEmpty()) content.add(Json.map("type", "text", "text", text));
                List<Object> calls = Json.arr(m.get("calls"));
                if (calls != null) for (Object o : calls) {
                    Map<String, Object> call = Json.obj(o);
                    content.add(Json.map("type", "tool_use", "id", Json.str(call, "id"), "name", Json.str(call, "name"), "input", call.get("input")));
                }
                if (content.isEmpty()) content.add(Json.map("type", "text", "text", "(empty)"));
                wire.add(Json.map("role", "user".equals(role) ? "user" : "assistant", "content", content));
            }
            List<Object> wt = new ArrayList<>();
            for (Map<String, Object> t : tools)
                wt.add(Json.map("name", t.get("name"), "description", t.get("description"), "input_schema", t.get("schema")));
            Map<String, Object> body = Json.map("model", model, "max_tokens", 16000, "system", system,
                "messages", wire, "tools", wt, "stream", true);
            Map<String, String> h = new TreeMap<>();
            h.put("x-api-key", key);
            h.put("anthropic-version", "2023-06-01");
            final Reply reply = new Reply();
            final Map<Integer, Map<String, Object>> blocks = new TreeMap<>();
            final Map<Integer, StringBuilder> json = new TreeMap<>();
            reply.error = post(base + "/v1/messages", h, body, new DataHandler() {
                @Override public void on(Map<String, Object> m) {
                    String type = Json.str(m, "type");
                    Object idx = m.get("index");
                    int i = idx instanceof Number ? ((Number) idx).intValue() : -1;
                    if ("content_block_start".equals(type)) {
                        Map<String, Object> b = Json.obj(m.get("content_block"));
                        if (b != null && "tool_use".equals(Json.str(b, "type"))) { blocks.put(i, b); json.put(i, new StringBuilder()); }
                    } else if ("content_block_delta".equals(type)) {
                        Map<String, Object> d = Json.obj(m.get("delta"));
                        String dt = Json.str(d, "type");
                        if ("text_delta".equals(dt)) { String t = Json.str(d, "text"); reply.text.append(t); sink.onTextDelta(t); }
                        else if ("input_json_delta".equals(dt) && json.containsKey(i)) json.get(i).append(Json.str(d, "partial_json"));
                    } else if ("message_delta".equals(type)) {
                        reply.stop = Json.str(Json.obj(m.get("delta")), "stop_reason");
                    } else if ("error".equals(type)) {
                        reply.error = Json.str(Json.obj(m.get("error")), "message");
                    }
                }
            });
            for (Map.Entry<Integer, Map<String, Object>> e : blocks.entrySet()) {
                String args = json.get(e.getKey()).toString();
                Object input = args.isEmpty() ? Json.map() : Json.parse(args);
                reply.calls.add(Json.map("id", Json.str(e.getValue(), "id"), "name", Json.str(e.getValue(), "name"), "input", input));
            }
            return reply;
        }
    }

    // --- OpenAI-compatible Chat Completions ---

    static final class OpenAI extends Provider {
        OpenAI(String base, String model, String key) { super(base, model, key); }

        @Override Reply send(String system, List<Map<String, Object>> messages, List<Map<String, Object>> tools, final Sink sink) throws Exception {
            List<Object> wire = new ArrayList<>();
            wire.add(Json.map("role", "system", "content", system));
            for (Map<String, Object> m : messages) {
                String role = Json.str(m, "role");
                if ("tool".equals(role)) {
                    wire.add(Json.map("role", "tool", "tool_call_id", Json.str(m, "id"),
                        "content", (Boolean.TRUE.equals(m.get("error")) ? "ERROR: " : "") + Json.str(m, "text")));
                } else if ("user".equals(role)) {
                    wire.add(Json.map("role", "user", "content", Json.str(m, "text")));
                } else {
                    String text = Json.str(m, "text");
                    Map<String, Object> a = Json.map("role", "assistant", "content", text == null || text.isEmpty() ? null : text);
                    List<Object> calls = Json.arr(m.get("calls"));
                    if (calls != null && !calls.isEmpty()) {
                        List<Object> tc = new ArrayList<>();
                        for (Object o : calls) {
                            Map<String, Object> call = Json.obj(o);
                            tc.add(Json.map("id", Json.str(call, "id"), "type", "function", "function",
                                Json.map("name", Json.str(call, "name"), "arguments", Json.write(call.get("input")))));
                        }
                        a.put("tool_calls", tc);
                    }
                    wire.add(a);
                }
            }
            List<Object> wt = new ArrayList<>();
            for (Map<String, Object> t : tools)
                wt.add(Json.map("type", "function", "function", Json.map("name", t.get("name"),
                    "description", t.get("description"), "parameters", t.get("schema"))));
            Map<String, Object> body = Json.map("model", model, "messages", wire, "tools", wt, "stream", true);
            Map<String, String> h = new TreeMap<>();
            if (key != null && !key.isEmpty()) h.put("authorization", "Bearer " + key);
            final Reply reply = new Reply();
            final Map<Integer, Map<String, Object>> calls = new TreeMap<>();
            final Map<Integer, StringBuilder> args = new TreeMap<>();
            reply.error = post(base + "/chat/completions", h, body, new DataHandler() {
                @Override public void on(Map<String, Object> m) {
                    List<Object> choices = Json.arr(m.get("choices"));
                    Map<String, Object> err = Json.obj(m.get("error"));
                    if (err != null) reply.error = Json.str(err, "message");
                    if (choices == null || choices.isEmpty()) return;
                    Map<String, Object> ch = Json.obj(choices.get(0));
                    Map<String, Object> d = Json.obj(ch.get("delta"));
                    String fin = Json.str(ch, "finish_reason");
                    if (fin != null) reply.stop = fin;
                    if (d == null) return;
                    String t = Json.str(d, "content");
                    if (t != null && !t.isEmpty()) { reply.text.append(t); sink.onTextDelta(t); }
                    List<Object> tc = Json.arr(d.get("tool_calls"));
                    if (tc != null) for (Object o : tc) {
                        Map<String, Object> c = Json.obj(o);
                        Object idx = c.get("index");
                        int i = idx instanceof Number ? ((Number) idx).intValue() : calls.size();
                        if (!calls.containsKey(i)) { calls.put(i, Json.map("id", null, "name", null)); args.put(i, new StringBuilder()); }
                        if (Json.str(c, "id") != null) calls.get(i).put("id", Json.str(c, "id"));
                        Map<String, Object> f = Json.obj(c.get("function"));
                        if (f != null && Json.str(f, "name") != null) calls.get(i).put("name", Json.str(f, "name"));
                        if (f != null && Json.str(f, "arguments") != null) args.get(i).append(Json.str(f, "arguments"));
                    }
                }
            });
            for (Map.Entry<Integer, Map<String, Object>> e : calls.entrySet()) {
                String a = args.get(e.getKey()).toString().trim();
                Object input;
                try { input = a.isEmpty() ? Json.map() : Json.parse(a); } catch (RuntimeException bad) { input = Json.map("_unparsed", a); }
                Map<String, Object> call = e.getValue();
                if (call.get("id") == null) call.put("id", "call_" + e.getKey());
                call.put("input", input);
                reply.calls.add(call);
            }
            return reply;
        }
    }
}
