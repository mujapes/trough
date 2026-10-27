package com.trough.hot;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Trough's claude session: one child process, stream-json both ways over its pipes.
 * Nothing between Trough and claude -- no server, port, token or event bus (DESIGN.md
 * "Direction: ground up").
 *
 * Protocol (captured from claude 2.1.311 and claude_agent_sdk's query.py, 10-27):
 *   -> {"type":"control_request","request_id":..,"request":{"subtype":"initialize"}}
 *   -> {"type":"user","message":{"role":"user","content":[{"type":"text","text":..}]}}
 *   -> {"type":"control_request",..,"request":{"subtype":"interrupt"}}
 *   <- system/init, stream_event (content_block_delta text_delta = live text), assistant,
 *      user (tool_result), result, control_request {"subtype":"can_use_tool",tool_name,input}
 *   -> {"type":"control_response","response":{"subtype":"success","request_id":..,
 *        "response":{"behavior":"allow","updatedInput":..} | {"behavior":"deny","message":..}}}
 * Tool use is never pre-approved: every can_use_tool goes to the Listener.
 */
public final class ClaudeAgent implements Agent {
    private final Process proc;
    private final OutputStream in;
    private final Agent.Listener listener;
    private int counter;
    private final StringBuilder stderr = new StringBuilder();

    /** cmd: the claude executable followed by any extra flags (e.g. --model). */
    public ClaudeAgent(List<String> cmd, File cwd, Map<String, String> env, Agent.Listener listener) throws Exception {
        this.listener = listener;
        cmd.add("--input-format");
        cmd.add("stream-json");
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--verbose");
        cmd.add("--include-partial-messages");
        cmd.add("--permission-prompt-tool");
        cmd.add("stdio");
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd);
        if (env != null) pb.environment().putAll(env);
        proc = pb.start();
        in = proc.getOutputStream();
        thread("trough-claude-out", new Runnable() { @Override public void run() { readOut(); } });
        thread("trough-claude-err", new Runnable() { @Override public void run() { readErr(); } });
        control(Json.map("subtype", "initialize", "hooks", null));
    }

    @Override public void send(String text) {
        write(Json.map("type", "user", "message", Json.map("role", "user",
            "content", Json.list(Json.map("type", "text", "text", text)))));
    }

    @Override public void allow(String requestId, Map<String, Object> input) {
        respond(requestId, Json.map("behavior", "allow", "updatedInput", input));
    }

    @Override public void deny(String requestId, String message) {
        respond(requestId, Json.map("behavior", "deny", "message", message));
    }

    @Override public void interrupt() { control(Json.map("subtype", "interrupt")); }

    @Override public void close() {
        try { in.close(); } catch (Exception ignored) {}
        proc.destroy();
    }

    // --- wire ---

    private synchronized void write(Map<String, Object> msg) {
        try {
            in.write((Json.write(msg) + "\n").getBytes(StandardCharsets.UTF_8));
            in.flush();
        } catch (Exception e) {
            // the reader sees the exit and reports it
        }
    }

    private void control(Map<String, Object> request) {
        write(Json.map("type", "control_request", "request_id", "trough_" + (++counter), "request", request));
    }

    private void respond(String requestId, Map<String, Object> response) {
        write(Json.map("type", "control_response", "response",
            Json.map("subtype", "success", "request_id", requestId, "response", response)));
    }

    private void readOut() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isEmpty()) continue;
                try { dispatch(Json.obj(Json.parse(line))); } catch (RuntimeException bad) { /* one bad line never ends a session */ }
            }
        } catch (Exception ignored) {
        }
        int code;
        try { code = proc.waitFor(); } catch (InterruptedException e) { code = -1; }
        String tail;
        synchronized (stderr) { tail = stderr.toString(); }
        listener.onExit(code, tail);
    }

    private void readErr() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                synchronized (stderr) {
                    stderr.append(line).append('\n');
                    if (stderr.length() > 4000) stderr.delete(0, stderr.length() - 4000);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void dispatch(Map<String, Object> m) {
        if (m == null) return;
        String type = Json.str(m, "type");
        if (type == null) return;
        switch (type) {
            case "system":
                if ("init".equals(Json.str(m, "subtype"))) listener.onInit(Json.str(m, "session_id"), Json.str(m, "model"));
                break;
            case "stream_event": {
                Map<String, Object> ev = Json.obj(m.get("event"));
                Map<String, Object> delta = ev == null ? null : Json.obj(ev.get("delta"));
                if (delta != null && "text_delta".equals(Json.str(delta, "type"))) listener.onTextDelta(Json.str(delta, "text"));
                break;
            }
            case "assistant":
                for (Object b : blocks(m)) {
                    Map<String, Object> blk = Json.obj(b);
                    String bt = Json.str(blk, "type");
                    if ("text".equals(bt)) listener.onAssistantText(Json.str(blk, "text"));
                    else if ("tool_use".equals(bt)) listener.onToolUse(Json.str(blk, "id"), Json.str(blk, "name"), Json.obj(blk.get("input")));
                }
                break;
            case "user":
                for (Object b : blocks(m)) {
                    Map<String, Object> blk = Json.obj(b);
                    if ("tool_result".equals(Json.str(blk, "type")))
                        listener.onToolResult(Json.str(blk, "tool_use_id"), text(blk.get("content")), Boolean.TRUE.equals(blk.get("is_error")));
                }
                break;
            case "result": {
                Object cost = m.get("total_cost_usd"), dur = m.get("duration_ms");
                listener.onResult("success".equals(Json.str(m, "subtype")) && !Boolean.TRUE.equals(m.get("is_error")),
                    cost instanceof Number ? ((Number) cost).doubleValue() : 0,
                    dur instanceof Number ? ((Number) dur).longValue() : 0, Json.str(m, "result"));
                break;
            }
            case "control_request": {
                Map<String, Object> req = Json.obj(m.get("request"));
                if (req != null && "can_use_tool".equals(Json.str(req, "subtype")))
                    listener.onPermission(Json.str(m, "request_id"), Json.str(req, "tool_name"), Json.obj(req.get("input")));
                else if (req != null)  // anything else we do not implement: say so, never hang the CLI
                    write(Json.map("type", "control_response", "response", Json.map("subtype", "error",
                        "request_id", Json.str(m, "request_id"), "error", "unsupported: " + Json.str(req, "subtype"))));
                break;
            }
            default:
                break;
        }
    }

    private static List<Object> blocks(Map<String, Object> m) {
        Map<String, Object> msg = Json.obj(m.get("message"));
        List<Object> c = msg == null ? null : Json.arr(msg.get("content"));
        return c == null ? java.util.Collections.emptyList() : c;
    }

    private static String text(Object content) {
        if (content instanceof String) return (String) content;
        List<Object> l = Json.arr(content);
        if (l == null) return "";
        StringBuilder b = new StringBuilder();
        for (Object o : l) {
            String t = Json.str(Json.obj(o), "text");
            if (t != null) b.append(t);
        }
        return b.toString();
    }

    /**
     * Claude's own sign-in for this device (`claude auth login`, no TTY needed): the sign-in URL
     * goes to the Listener's onAuth; the code the user copies from the page goes to code(); the
     * result to done. Gives the phone its own login instead of a copy of another machine's
     * rotating refresh token, which would log one or the other out.
     */
    public static final class Login {
        public interface Done { void onDone(boolean ok, String detail); }
        private final Process proc;
        private final OutputStream in;

        public Login(String claude, File cwd, Map<String, String> env, final Agent.Listener auth, final Done done) throws Exception {
            ProcessBuilder pb = new ProcessBuilder(claude, "auth", "login").directory(cwd).redirectErrorStream(true);
            if (env != null) { pb.environment().clear(); pb.environment().putAll(env); }
            pb.environment().put("BROWSER", "/system/bin/false");   // Trough opens the page itself
            proc = pb.start();
            in = proc.getOutputStream();
            thread("trough-claude-login", new Runnable() { @Override public void run() {
                StringBuilder all = new StringBuilder();
                boolean sent = false;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder line = new StringBuilder();
                    for (int c; (c = r.read()) >= 0; ) {   // the code prompt ends without a newline
                        if (c != '\n') { line.append((char) c); if (!line.toString().endsWith("> ")) continue; }
                        String l = line.toString();
                        line.setLength(0);
                        all.append(l).append('\n');
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https://\\S+oauth/authorize\\S+").matcher(l);
                        if (!sent && m.find()) { sent = true; auth.onAuth(m.group(), null); }
                        // A wrong code re-prompts forever: end this attempt (/login starts afresh).
                        if (l.contains("Invalid code")) { proc.destroy(); break; }
                    }
                } catch (Exception ignored) {}
                int code;
                try { code = proc.waitFor(); } catch (InterruptedException e) { code = -1; }
                String out = all.toString();
                boolean failed = code != 0 || out.toLowerCase(java.util.Locale.ROOT).contains("invalid") || out.toLowerCase(java.util.Locale.ROOT).contains("error");
                String tail = "";   // the last line that carries no URL: never echo sign-in parameters
                for (String l : out.split("\n")) if (!l.trim().isEmpty() && !l.contains("://") && !l.contains("oauth")) tail = l.trim();
                done.onDone(!failed, tail.length() > 300 ? tail.substring(0, 300) : tail);
            } });
        }

        /** The code from the sign-in page. Written to the login process only. */
        public void code(String code) {
            try { in.write((code.trim() + "\n").getBytes(StandardCharsets.UTF_8)); in.flush(); in.close(); } catch (Exception ignored) {}
        }

        public void cancel() { proc.destroy(); }
    }

    private static void thread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }
}
