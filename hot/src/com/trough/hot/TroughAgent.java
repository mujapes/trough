package com.trough.hot;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Trough's own agent loop, for any model with an API (Provider): the model streams a reply,
 * every tool call it makes goes to the Listener for approval, approved calls run here (bash in
 * Trough's root, file read/write/edit), results go back, until the model stops calling tools.
 * Conversations are Trough's own JSON (home/.trough/conversations/<id>.json), so a reload or a
 * restart resumes them, independent of any vendor's session store.
 */
public final class TroughAgent implements Agent {
    static final List<Map<String, Object>> TOOLS = new ArrayList<>();
    static {
        TOOLS.add(tool("bash", "Run a bash command in the working directory (Trough's Debian userland: git, gcc, java on PATH). Returns stdout+stderr and the exit code.",
            "command", "string", "The command"));
        TOOLS.add(tool("read_file", "Read a text file. Lines are numbered.", "path", "string", "Absolute, or relative to the working directory"));
        Map<String, Object> w = tool("write_file", "Create or overwrite a file with content.", "path", "string", "File path");
        props(w).put("content", Json.map("type", "string"));
        required(w).add("content");
        TOOLS.add(w);
        Map<String, Object> e = tool("edit_file", "Replace old_string (which must occur exactly once) with new_string in a file.", "path", "string", "File path");
        props(e).put("old_string", Json.map("type", "string"));
        props(e).put("new_string", Json.map("type", "string"));
        required(e).add("old_string");
        required(e).add("new_string");
        TOOLS.add(e);
    }

    private final Provider provider;
    private final File cwd, store;
    private final Map<String, String> env;
    private final Agent.Listener listener;
    private final String id, label;
    private final List<Map<String, Object>> messages = new ArrayList<>();
    private final BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
    private final Map<String, BlockingQueue<Boolean>> asks = new java.util.HashMap<>();
    private volatile boolean closed, interrupted;
    private final Thread worker;

    /** store: the conversations dir; resumeId: a conversation to continue, or null/empty. */
    public TroughAgent(Provider provider, String label, File cwd, Map<String, String> env, File store, String resumeId,
                       Agent.Listener listener) throws Exception {
        this.provider = provider;
        this.label = label;
        this.cwd = cwd;
        this.env = env;
        this.store = store;
        this.listener = listener;
        store.mkdirs();
        String rid = resumeId == null ? "" : resumeId.replaceAll("[^A-Za-z0-9_-]", "");
        File saved = new File(store, rid + ".json");
        if (!rid.isEmpty() && saved.exists()) {
            Map<String, Object> conv = Json.obj(Json.parse(new String(Payloads.readSmall(saved), "UTF-8")));
            List<Object> ms = conv == null ? null : Json.arr(conv.get("messages"));
            if (ms != null) for (Object o : ms) messages.add(Json.obj(o));
            id = rid;
        } else {
            id = "t" + Long.toString(System.currentTimeMillis(), 36);
        }
        worker = new Thread(new Runnable() { @Override public void run() { loop(); } }, "trough-agent");
        worker.setDaemon(true);
        worker.start();
        listener.onInit(id, label);
    }

    @Override public void send(String text) { inbox.add(text); }

    @Override public void allow(String requestId, Map<String, Object> input) { answer(requestId, true); }

    @Override public void deny(String requestId, String message) { answer(requestId, false); }

    @Override public void interrupt() {
        interrupted = true;
        provider.cancel();
        synchronized (asks) { for (BlockingQueue<Boolean> q : asks.values()) q.offer(false); }
    }

    @Override public void close() {
        closed = true;
        interrupt();
        worker.interrupt();
    }

    private void answer(String requestId, boolean ok) {
        BlockingQueue<Boolean> q;
        synchronized (asks) { q = asks.get(requestId); }
        if (q != null) q.offer(ok);
    }

    private void loop() {
        while (!closed) {
            String text;
            try { text = inbox.take(); } catch (InterruptedException e) { break; }
            interrupted = false;
            long t0 = System.currentTimeMillis();
            messages.add(Json.map("role", "user", "text", text));
            String error = null;
            try {
                error = turn();
            } catch (Exception e) {
                error = String.valueOf(e);
            }
            save();
            listener.onResult(error == null, 0, System.currentTimeMillis() - t0, error);
        }
        listener.onExit(0, "");
    }

    /** One user turn: model, tools, model, ... until it stops. Returns null or an error. */
    private String turn() throws Exception {
        for (int step = 0; step < 200 && !closed; step++) {
            if (interrupted) return "interrupted";
            Provider.Reply r = provider.send(system(), messages, TOOLS, new Provider.Sink() {
                @Override public void onTextDelta(String t) { listener.onTextDelta(t); }
            });
            if (interrupted) return "interrupted";
            if (r.error != null) return r.error;
            List<Object> calls = new ArrayList<>();
            for (Map<String, Object> c : r.calls) calls.add(c);
            messages.add(Json.map("role", "assistant", "text", r.text.toString(), "calls", calls));
            if (r.text.length() > 0) listener.onAssistantText(r.text.toString());
            if (r.calls.isEmpty()) return null;
            for (Map<String, Object> call : r.calls) {
                String cid = Json.str(call, "id"), name = Json.str(call, "name");
                Map<String, Object> input = Json.obj(call.get("input"));
                if (input == null) input = Json.map();
                listener.onToolUse(cid, name, input);
                String result;
                boolean err;
                if (ask(cid, name, input)) {
                    try { result = run(name, input); err = result.startsWith("ERROR"); }
                    catch (Exception e) { result = "ERROR: " + e; err = true; }
                } else {
                    result = "The user denied this tool call in Trough.";
                    err = true;
                }
                if (result.length() > 30000) result = result.substring(0, 15000) + "\n... (" + (result.length() - 30000) + " chars cut) ...\n" + result.substring(result.length() - 15000);
                listener.onToolResult(cid, result, err);
                messages.add(Json.map("role", "tool", "id", cid, "text", result, "error", err));
            }
        }
        return closed ? "closed" : "stopped after 200 steps";
    }

    private boolean ask(String cid, String name, Map<String, Object> input) throws InterruptedException {
        String rid = "trough_" + cid;
        BlockingQueue<Boolean> q = new ArrayBlockingQueue<>(1);
        synchronized (asks) { asks.put(rid, q); }
        try {
            listener.onPermission(rid, name, input);
            Boolean ok = q.take();
            return ok && !interrupted;
        } finally {
            synchronized (asks) { asks.remove(rid); }
        }
    }

    // --- tools ---

    private String run(String name, Map<String, Object> in) throws Exception {
        if ("bash".equals(name)) return bash(Json.str(in, "command"));
        File f = path(Json.str(in, "path"));
        if ("read_file".equals(name)) {
            if (!f.isFile()) return "ERROR: no such file " + f;
            String[] lines = new String(Payloads.readSmall(f), "UTF-8").split("\n", -1);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < lines.length && i < 4000; i++) b.append(String.format("%6d\t", i + 1)).append(lines[i]).append('\n');
            if (lines.length > 4000) b.append("... (").append(lines.length - 4000).append(" more lines)\n");
            return b.toString();
        }
        if ("write_file".equals(name)) {
            String content = Json.str(in, "content");
            if (content == null) return "ERROR: content missing";
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            Payloads.writeSmall(f, content);
            return "wrote " + content.length() + " chars to " + f;
        }
        if ("edit_file".equals(name)) {
            String old = Json.str(in, "old_string"), nu = Json.str(in, "new_string");
            if (old == null || nu == null) return "ERROR: old_string and new_string are required";
            if (!f.isFile()) return "ERROR: no such file " + f;
            String text = new String(Payloads.readSmall(f), "UTF-8");
            int at = text.indexOf(old);
            if (at < 0) return "ERROR: old_string not found in " + f;
            if (text.indexOf(old, at + 1) >= 0) return "ERROR: old_string occurs more than once in " + f;
            Payloads.writeSmall(f, text.substring(0, at) + nu + text.substring(at + old.length()));
            return "edited " + f;
        }
        return "ERROR: unknown tool " + name;
    }

    private File path(String p) {
        if (p == null) p = "";
        File f = new File(p);
        return f.isAbsolute() ? f : new File(cwd, p);
    }

    private String bash(String command) throws Exception {
        if (command == null) return "ERROR: command missing";
        String shell = env == null ? null : env.get("SHELL");
        ProcessBuilder pb = new ProcessBuilder(shell != null ? shell : "/bin/bash", "-c", command).directory(cwd)
            .redirectErrorStream(true).redirectInput(new File("/dev/null"));
        if (env != null) { pb.environment().clear(); pb.environment().putAll(env); }
        final Process p = pb.start();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(new Runnable() { @Override public void run() {
            try (InputStream in = p.getInputStream()) {
                byte[] b = new byte[8192];
                for (int n; (n = in.read(b)) > 0; ) synchronized (out) { out.write(b, 0, n); }
            } catch (Exception ignored) {}
        } }, "trough-agent-bash");
        reader.setDaemon(true);
        reader.start();
        boolean done = false;
        for (int waited = 0; waited < 600 && !done; waited++) {   // 10 min, interruptible
            done = p.waitFor(1, TimeUnit.SECONDS);
            if (interrupted || closed) break;
        }
        if (!done) p.destroyForcibly();
        reader.join(2000);
        String text;
        synchronized (out) { text = out.toString("UTF-8"); }
        if (!done) return "ERROR: " + (interrupted ? "interrupted" : "timed out after 600 s") + "\n" + text;
        int exit = p.exitValue();
        return (exit == 0 ? "" : "ERROR: exit " + exit + "\n") + text;
    }

    private String system() {
        StringBuilder s = new StringBuilder();
        s.append("You are an agent running inside Trough, an Android app, on the user's phone. You are ")
         .append(label).append(". Working directory: ").append(cwd).append(". Use the tools to act; the user ")
         .append("approves each tool call in Trough. Be concise.\n");
        File notes = new File(cwd, "AGENTS.md");
        try { if (notes.isFile()) s.append("\n").append(new String(Payloads.readSmall(notes), "UTF-8")); } catch (Exception ignored) {}
        return s.toString();
    }

    private void save() {
        try { Payloads.writeSmall(new File(store, id + ".json"), Json.write(Json.map("model", label, "messages", new ArrayList<Object>(messages)))); }
        catch (Exception ignored) {}
    }

    // --- tool schema helpers ---

    private static Map<String, Object> tool(String name, String description, String arg, String type, String argDescription) {
        Map<String, Object> props = Json.map(arg, Json.map("type", type, "description", argDescription));
        List<Object> req = new ArrayList<>();
        req.add(arg);
        return Json.map("name", name, "description", description, "schema", Json.map("type", "object", "properties", props, "required", req));
    }

    private static Map<String, Object> props(Map<String, Object> t) { return Json.obj(Json.obj(t.get("schema")).get("properties")); }

    private static List<Object> required(Map<String, Object> t) { return Json.arr(Json.obj(t.get("schema")).get("required")); }
}
