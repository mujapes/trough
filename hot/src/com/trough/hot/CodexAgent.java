package com.trough.hot;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Codex as a Trough agent: `codex app-server`, JSON-RPC 2.0 as JSON lines over its pipes. Same
 * shape as ClaudeAgent: one child process, nothing in between.
 *
 * Protocol (codex-cli 0.162.1, traced under the simulated phone filter 10-27):
 *   -> initialize {clientInfo}  <- result;  -> initialized (notification)
 *   -> thread/start {cwd, approvalPolicy, sandbox, model} | thread/resume {threadId}
 *        <- result {thread:{id}, model}
 *   -> turn/start {threadId, input:[{type:text,text}]}   -> turn/interrupt {threadId, turnId}
 *   <- turn/started, item/started|completed {item:{type: agentMessage|commandExecution|
 *      fileChange|mcpToolCall|webSearch|..}}, item/agentMessage/delta {delta}, turn/completed
 *   <- (server requests) item/commandExecution/requestApproval, item/fileChange/requestApproval
 *        -> result {decision: accept | decline}
 * Sign-in: account/read; if signed out, account/login/start {type: chatgptDeviceCode} -> the
 * Listener shows verificationUrl + userCode, and account/login/completed starts the thread. So
 * the phone gets its own Codex login, never a copy of another machine's rotating tokens.
 * approvalPolicy "untrusted": anything codex does not itself classify as a safe read asks first,
 * and every ask goes to the Listener. Server requests Trough does not implement get a JSON-RPC
 * error, so codex never hangs waiting on Trough.
 */
public final class CodexAgent implements Agent {
    private final Process proc;
    private final OutputStream in;
    private final Agent.Listener listener;
    private final File cwd;
    private final String model, sandbox;
    private String resume;
    private final StringBuilder stderr = new StringBuilder();
    private final Map<Integer, String> calls = new HashMap<>();          // our request id -> method
    private final Map<String, Object> asks = new HashMap<>();             // our ask id -> codex request id
    private final List<String> queued = new ArrayList<>();               // sends before the thread exists
    private int counter;
    private volatile String threadId, turnId;

    /**
     * sandbox: codex's own command sandbox ("read-only", "workspace-write") needs user
     * namespaces (bubblewrap), which the phone's kernel lacks (DESIGN.md "Namespace
     * investigation"); there Trough passes "danger-full-access" and the Android app sandbox
     * plus Trough's approval cards are the boundary, as for every agent Trough runs.
     */
    public CodexAgent(String codex, File cwd, Map<String, String> env, String resumeThread, String model,
                      String sandbox, Agent.Listener listener) throws Exception {
        this.listener = listener;
        this.cwd = cwd;
        this.model = model;
        this.sandbox = sandbox;
        this.resume = resumeThread;
        ProcessBuilder pb = new ProcessBuilder(codex, "app-server").directory(cwd);
        if (env != null) pb.environment().putAll(env);
        proc = pb.start();
        in = proc.getOutputStream();
        thread("trough-codex-out", new Runnable() { @Override public void run() { readOut(); } });
        thread("trough-codex-err", new Runnable() { @Override public void run() { readErr(); } });
        call("initialize", Json.map("clientInfo", Json.map("name", "trough", "title", "Trough", "version", "1")));
    }

    @Override public void send(String text) {
        synchronized (queued) {
            if (threadId == null) { queued.add(text); return; }
        }
        call("turn/start", Json.map("threadId", threadId,
            "input", Json.list(Json.map("type", "text", "text", text))));
    }

    @Override public void allow(String requestId, Map<String, Object> input) { answer(requestId, "accept"); }

    @Override public void deny(String requestId, String message) { answer(requestId, "decline"); }

    @Override public void interrupt() {
        if (threadId != null && turnId != null) call("turn/interrupt", Json.map("threadId", threadId, "turnId", turnId));
    }

    @Override public void close() {
        try { in.close(); } catch (Exception ignored) {}
        proc.destroy();
    }

    // --- wire ---

    private synchronized void write(Map<String, Object> msg) {
        try {
            msg.put("jsonrpc", "2.0");
            in.write((Json.write(msg) + "\n").getBytes(StandardCharsets.UTF_8));
            in.flush();
        } catch (Exception e) {
            // the reader sees the exit and reports it
        }
    }

    private void call(String method, Map<String, Object> params) {
        int id;
        synchronized (calls) { id = ++counter; calls.put(id, method); }
        write(Json.map("id", id, "method", method, "params", params));
    }

    private void answer(String askId, String decision) {
        Object id;
        synchronized (asks) { id = asks.remove(askId); }
        if (id != null) write(Json.map("id", id, "result", Json.map("decision", decision)));
    }

    private void startThread() {
        if (resume != null && !resume.isEmpty()) {
            call("thread/resume", Json.map("threadId", resume, "cwd", cwd.getPath(), "approvalPolicy", "untrusted", "sandbox", sandbox));
            return;
        }
        Map<String, Object> p = Json.map("cwd", cwd.getPath(), "approvalPolicy", "untrusted", "sandbox", sandbox);
        if (model != null && !model.isEmpty()) p.put("model", model);
        call("thread/start", p);
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
        String method = Json.str(m, "method");
        Object id = m.get("id");
        if (method == null && id instanceof Number) { response(((Number) id).intValue(), m); return; }
        Map<String, Object> p = Json.obj(m.get("params"));
        if (method == null || p == null) return;
        if (id != null) { request(id, method, p); return; }
        switch (method) {
            case "account/login/completed":
                if (Boolean.TRUE.equals(p.get("success"))) startThread();
                else listener.onExit(-1, "codex sign-in failed: " + Json.str(p, "error"));
                break;
            case "turn/started": {
                Map<String, Object> t = Json.obj(p.get("turn"));
                if (t != null) turnId = Json.str(t, "id");
                break;
            }
            case "item/agentMessage/delta":
                listener.onTextDelta(Json.str(p, "delta"));
                break;
            case "item/started": {
                Map<String, Object> item = Json.obj(p.get("item"));
                String type = item == null ? null : Json.str(item, "type");
                if ("commandExecution".equals(type))
                    listener.onToolUse(Json.str(item, "id"), "Bash", Json.map("command", Json.str(item, "command")));
                else if ("fileChange".equals(type))
                    listener.onToolUse(Json.str(item, "id"), "Edit", Json.map("file_path", paths(item)));
                else if ("mcpToolCall".equals(type))
                    listener.onToolUse(Json.str(item, "id"), Json.str(item, "server") + "/" + Json.str(item, "tool"), Json.obj(item.get("arguments")));
                else if ("webSearch".equals(type))
                    listener.onToolUse(Json.str(item, "id"), "WebSearch", Json.map("description", Json.str(item, "query")));
                break;
            }
            case "item/completed": {
                Map<String, Object> item = Json.obj(p.get("item"));
                String type = item == null ? null : Json.str(item, "type");
                if ("agentMessage".equals(type)) listener.onAssistantText(Json.str(item, "text"));
                else if ("commandExecution".equals(type)) {
                    Object exit = item.get("exitCode");
                    String out = Json.str(item, "aggregatedOutput");
                    boolean failed = !"completed".equals(Json.str(item, "status")) || (exit instanceof Number && ((Number) exit).intValue() != 0);
                    listener.onToolResult(Json.str(item, "id"), (out == null ? "" : out) + (exit instanceof Number && ((Number) exit).intValue() != 0 ? "\n(exit " + exit + ")" : ""), failed);
                } else if (type != null && !"userMessage".equals(type) && !"reasoning".equals(type))
                    listener.onToolResult(Json.str(item, "id"), String.valueOf(Json.str(item, "status")), !"completed".equals(Json.str(item, "status")));
                break;
            }
            case "turn/completed": {
                Map<String, Object> t = Json.obj(p.get("turn"));
                Map<String, Object> err = t == null ? null : Json.obj(t.get("error"));
                Object dur = t == null ? null : t.get("durationMs");
                turnId = null;
                listener.onResult(t != null && "completed".equals(Json.str(t, "status")), 0,
                    dur instanceof Number ? ((Number) dur).longValue() : 0, err == null ? null : Json.str(err, "message"));
                break;
            }
            default:
                break;
        }
    }

    private void response(int id, Map<String, Object> m) {
        String method;
        synchronized (calls) { method = calls.remove(id); }
        Map<String, Object> result = Json.obj(m.get("result"));
        Map<String, Object> error = Json.obj(m.get("error"));
        if ("initialize".equals(method) && result != null) {
            write(Json.map("method", "initialized"));
            call("account/read", Json.map());
        } else if ("account/read".equals(method)) {
            boolean signedOut = result != null && result.get("account") == null && Boolean.TRUE.equals(result.get("requiresOpenaiAuth"));
            if (signedOut) call("account/login/start", Json.map("type", "chatgptDeviceCode"));
            else startThread();
        } else if ("account/login/start".equals(method)) {
            if (result == null) { listener.onExit(-1, "codex sign-in failed to start: " + (error == null ? "" : Json.str(error, "message"))); return; }
            listener.onAuth(Json.str(result, "verificationUrl"), Json.str(result, "userCode"));
        } else if (("thread/start".equals(method) || "thread/resume".equals(method))) {
            if (result == null && "thread/resume".equals(method)) { resume = null; startThread(); return; }  // gone: start fresh
            Map<String, Object> t = result == null ? null : Json.obj(result.get("thread"));
            if (t == null) { listener.onExit(-1, "codex " + method + " failed: " + (error == null ? "" : Json.str(error, "message"))); return; }
            List<String> pending;
            synchronized (queued) { threadId = Json.str(t, "id"); pending = new ArrayList<>(queued); queued.clear(); }
            listener.onInit(threadId, Json.str(result, "model"));
            for (String text : pending) send(text);
        } else if ("turn/start".equals(method) && error != null) {
            listener.onResult(false, 0, 0, Json.str(error, "message"));
        }
    }

    private void request(Object id, String method, Map<String, Object> p) {
        String tool = "item/commandExecution/requestApproval".equals(method) ? "Bash"
            : "item/fileChange/requestApproval".equals(method) ? "Edit" : null;
        if (tool == null) {
            write(Json.map("id", id, "error", Json.map("code", -32601, "message", "Trough does not implement " + method)));
            return;
        }
        String ask = "codex_" + id;
        synchronized (asks) { asks.put(ask, id); }
        Map<String, Object> input = new HashMap<>();
        for (String k : new String[]{"command", "cwd", "reason", "grantRoot"}) if (p.get(k) != null) input.put(k, p.get(k));
        listener.onPermission(ask, tool, input);
    }

    private static String paths(Map<String, Object> item) {
        List<Object> changes = Json.arr(item.get("changes"));
        if (changes == null) return "";
        StringBuilder b = new StringBuilder();
        for (Object c : changes) {
            String path = Json.str(Json.obj(c), "path");
            if (path != null) b.append(b.length() == 0 ? "" : ", ").append(path);
        }
        return b.toString();
    }

    private static void thread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }
}
