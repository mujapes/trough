package com.trough.hot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;

/** One explicitly signed, session-scoped bridge for a predecessor whose Stop can get stuck. */
final class LegacyChatRecovery {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = "com.trough.hot.legacy-chat-recovery";
    private LegacyChatRecovery() {}

    /** Called only with the verified bundle directory supplied by HotLoader.replace. */
    static void request(final Context context, final File bundle, final Class<?> previous) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(new Runnable() { @Override public void run() { request(context, bundle, previous); } });
            return;
        }
        File receipt = null;
        JSONObject report = new JSONObject();
        try {
            JSONObject manifest = new JSONObject(readBounded(new File(bundle, "manifest.json"), 131072));
            JSONObject directive = manifest.optJSONObject("chat_recovery");
            if (directive == null) return;
            String id = directive.optString("id", ""), expected = directive.optString("session_id", "");
            if (!id.matches("[0-9a-f]{32}") || !expected.matches("[0-9a-fA-F-]{32,64}")
                    || directive.toString().length() > 4096) return;
            File directory = new File(context.getFilesDir(), "home/.trough/recovery");
            if (!directory.isDirectory() && !directory.mkdirs()) return;
            receipt = new File(directory, id + ".json");
            synchronized (LOCK) {
                // Reserve before touching the old runtime. A process death consumes the
                // authorization rather than repeating cancellation on a later launch.
                if (!receipt.createNewFile()) return;
            }
            report.put("id", id);
            report.put("session_id", expected);
            report.put("started_ms", System.currentTimeMillis());
            report.put("action", "interrupt_then_reconnect_same_conversation");
            final Object chat = findChat(previous);
            if (chat == null) { finish(receipt, report, "no_live_chat"); return; }
            report.put("before", snapshot(chat));
            if (!eligible(chat, expected)) { finish(receipt, report, "guard_rejected"); return; }
            final int generation = integer(chat, "generation");
            final Object transport = field(chat, "session");
            if (transport == null) { finish(receipt, report, "no_transport"); return; }
            declared(chat.getClass(), "stopTurn").invoke(chat);
            report.put("status", "interrupt_requested");
            write(receipt, report);
            final File target = receipt;
            final JSONObject result = report;
            final String thread = expected;
            MAIN.postDelayed(new Runnable() { @Override public void run() {
                try {
                    if (findChat(previous) != chat) { finish(target, result, "runtime_changed"); return; }
                    if (!truth(chat, "busy")) { finish(target, result, "interrupt_completed", chat); return; }
                    if (integer(chat, "generation") != generation || field(chat, "session") != transport
                            || !eligible(chat, thread)) {
                        finish(target, result, "timeout_guard_rejected", chat);
                        return;
                    }
                    // The legacy listener checks its generation before queuing callbacks.
                    // Revoke it before close(), which connect() performs synchronously.
                    Field epoch = declaredField(chat.getClass(), "generation");
                    epoch.setInt(chat, generation + 1);
                    declared(chat.getClass(), "connect").invoke(chat);
                    result.put("status", "reconnect_requested");
                    result.put("after_reconnect", snapshot(chat));
                    write(target, result);
                    MAIN.postDelayed(new Runnable() { @Override public void run() {
                        try {
                            finish(target, result, findChat(previous) == chat ? "reconnect_observed" : "runtime_replaced", chat);
                        } catch (Throwable error) { failure(target, result, error); }
                    } }, 1000);
                } catch (Throwable error) { failure(target, result, error); }
            } }, 6000);
        } catch (Throwable error) {
            if (receipt != null) failure(receipt, report, error);
        }
    }

    private static boolean eligible(Object chat, String expected) throws Exception {
        // Cancellation also covers a turn waiting for approval. Interrupt/close never
        // answers that approval and must remain available when its card is off-screen.
        return "codex".equals(field(chat, "agent")) && expected.equals(field(chat, "sessionId"))
            && truth(chat, "busy") && !truth(chat, "closed") && !truth(chat, "authRequired") && field(chat, "login") == null;
    }

    /** Only the current signed directive can select the bounded, app-private receipt. */
    static java.util.Map<String,Object> receipt(Context context, File bundle) {
        try {
            JSONObject manifest = new JSONObject(readBounded(new File(bundle, "manifest.json"), 131072));
            JSONObject directive = manifest.optJSONObject("chat_recovery");
            if (directive == null) return Json.map("present", false);
            String id = directive.optString("id", "");
            if (!id.matches("[0-9a-f]{32}")) return Json.map("present", false, "error", "invalid recovery id");
            File path = new File(context.getFilesDir(), "home/.trough/recovery/" + id + ".json");
            if (!path.isFile()) return Json.map("present", false, "id", id);
            if (path.length() == 0) return Json.map("present", true, "id", id, "status", "reserved");
            java.util.Map<String,Object> result = Json.obj(Json.parse(readBounded(path, 16384)));
            return result == null ? Json.map("present", true, "id", id, "status", "invalid_receipt") : result;
        } catch (Throwable error) {
            return Json.map("present", false, "error_type", error.getClass().getName());
        }
    }

    /** Follow only known Main boot/run links, bounded and cycle-safe across class loaders. */
    private static Object findChat(Class<?> previous) throws Exception {
        if (previous == null) return null;
        ArrayList<Class<?>> queue = new ArrayList<Class<?>>();
        IdentityHashMap<Class<?>, Boolean> seen = new IdentityHashMap<Class<?>, Boolean>();
        queue.add(previous);
        for (int at = 0; at < queue.size() && at < 12; at++) {
            Class<?> owner = queue.get(at);
            if (owner == null || seen.put(owner, Boolean.TRUE) != null
                    || !"com.trough.hot.Main".equals(owner.getName())) continue;
            Object chat = staticField(owner, "chat");
            if (chat != null && "com.trough.hot.Chat".equals(chat.getClass().getName())) return chat;
            Object predecessor = staticField(owner, "predecessor");
            if (predecessor instanceof Class) queue.add((Class<?>) predecessor);
            Object handoff = staticField(owner, "runningHandoff");
            if (handoff instanceof Method) queue.add(((Method) handoff).getDeclaringClass());
        }
        return null;
    }

    private static Object staticField(Class<?> owner, String name) throws Exception {
        Field value = declaredField(owner, name);
        return Modifier.isStatic(value.getModifiers()) ? value.get(null) : null;
    }
    private static Field declaredField(Class<?> owner, String name) throws Exception {
        Field value = owner.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }
    private static Method declared(Class<?> owner, String name) throws Exception {
        Method method = owner.getDeclaredMethod(name);
        method.setAccessible(true);
        return method;
    }
    private static Object field(Object owner, String name) throws Exception {
        return declaredField(owner.getClass(), name).get(owner);
    }
    private static boolean truth(Object owner, String name) throws Exception {
        return Boolean.TRUE.equals(field(owner, name));
    }
    private static int integer(Object owner, String name) throws Exception {
        return ((Number) field(owner, name)).intValue();
    }

    private static JSONObject snapshot(Object chat) throws Exception {
        JSONObject result = new JSONObject();
        result.put("busy", truth(chat, "busy"));
        result.put("initialized", truth(chat, "initialized"));
        result.put("generation", integer(chat, "generation"));
        result.put("session_id", field(chat, "sessionId"));
        Object input = field(chat, "input");
        if (!(input instanceof TextView)) throw new IllegalStateException("draft view unavailable");
        String draft = ((TextView) input).getText().toString();
        result.put("draft_length", draft.length());
        result.put("draft_sha256", hex(MessageDigest.getInstance("SHA-256").digest(draft.getBytes("UTF-8"))));
        Object log = field(chat, "log");
        if (log instanceof Collection) result.put("feed_entries", ((Collection<?>) log).size());
        Object approvals = field(chat, "pendingPermissions");
        if (approvals instanceof Collection) result.put("pending_approval_count", ((Collection<?>) approvals).size());
        Object transport = field(chat, "session");
        result.put("transport_present", transport != null);
        if (transport != null && "com.trough.hot.CodexAgent".equals(transport.getClass().getName())) {
            Object turn = field(transport, "turnId");
            result.put("turn_id_present", turn instanceof String && ((String) turn).length() > 0);
            Object calls = field(transport, "calls");
            if (calls instanceof java.util.Map) {
                synchronized (calls) { result.put("pending_rpc_count", ((java.util.Map<?, ?>) calls).size()); }
            }
        }
        return result;
    }

    private static void finish(File receipt, JSONObject result, String status) throws Exception {
        result.put("status", status);
        result.put("finished_ms", System.currentTimeMillis());
        write(receipt, result);
    }
    private static void finish(File receipt, JSONObject result, String status, Object chat) throws Exception {
        JSONObject after = snapshot(chat);
        result.put("after", after);
        JSONObject before = result.optJSONObject("before");
        result.put("draft_preserved", before != null && before.optString("draft_sha256", "!")
            .equals(after.optString("draft_sha256", "?")));
        finish(receipt, result, status);
    }
    private static void failure(File receipt, JSONObject result, Throwable error) {
        try {
            result.put("error_type", error.getClass().getName());
            if (error.getCause() != null) result.put("cause_type", error.getCause().getClass().getName());
            finish(receipt, result, "failed");
        } catch (Throwable ignored) {}
    }
    private static String readBounded(File path, int limit) throws Exception {
        if (!path.isFile() || path.length() > limit) throw new IllegalStateException("manifest size");
        byte[] bytes = new byte[(int) path.length()];
        try (FileInputStream in = new FileInputStream(path)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = in.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IllegalStateException("short manifest");
                offset += count;
            }
        }
        return new String(bytes, "UTF-8");
    }
    private static void write(File receipt, JSONObject result) throws Exception {
        byte[] bytes = result.toString().getBytes("UTF-8");
        if (bytes.length > 16384) throw new IllegalStateException("receipt size");
        File temporary = new File(receipt.getPath() + ".new");
        try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(bytes); out.getFD().sync(); }
        if (!temporary.renameTo(receipt)) throw new IllegalStateException("receipt rename");
        // The predecessor can block takeover, so its Development channel cannot expose
        // this new class's private receipt yet. Mirror the content-free diagnostics;
        // the later signed scenario still reads the authoritative private copy.
        try {
            String id = result.optString("id", "");
            if (id.matches("[0-9a-f]{32}")) {
                File shared = Creds.shared();
                if (shared.isDirectory() || shared.mkdirs()) {
                    File visible = new File(shared, "recovery-" + id + ".json");
                    File pending = new File(visible.getPath() + ".new");
                    try (FileOutputStream out = new FileOutputStream(pending)) { out.write(bytes); }
                    pending.renameTo(visible);
                }
            }
        } catch (Throwable ignored) {}
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
        return result.toString();
    }
}
