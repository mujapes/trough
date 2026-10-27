package com.trough.hot;

import android.app.Activity;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Bundle entry point (contract: app/.../HotLoader.java). Trough's own UI (Chat) over its own
 * claude session (Session): no daemon, no server, no WebView (DESIGN.md "Direction").
 */
public final class Main {
    private static Chat chat;

    public static void start(final Activity activity, FrameLayout root, final File dir) {
        final File home = new File(activity.getFilesDir(), "home");
        home.mkdirs();
        chat = new Chat(activity, root, home);
        final Chat c = chat;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File troot = Root.install(activity, dir);      // texec, patched glibc, resolv.conf
                    JSONObject payloads = Payloads.ensure(activity, dir);
                    Creds.sync(activity, home);
                    File work = new File(activity.getFilesDir(), "work");
                    work.mkdirs();
                    Map<String, String> env = new HashMap<>();
                    env.put("HOME", home.getPath());
                    env.put("TMPDIR", activity.getCacheDir().getPath());   // Android has no /tmp
                    env.put("SHELL", "/system/bin/sh");
                    env.put("PATH", new File(troot, "bin").getPath() + ":/system/bin");
                    c.start(payloads.getString("claude"), work, env);
                    // Self-hosting: install the Debian root (toolchain) and prove it builds here.
                    if (payloads.has("root")) {
                        JSONObject r = new JSONObject();
                        long t = System.nanoTime();
                        r.put("root_install", DebRoot.install(activity, payloads.getString("root")));
                        r.put("root_install_ms", (System.nanoTime() - t) / 1_000_000);
                        try { r.put("selftest", SelfTest.run(activity)); } catch (Throwable e) { r.put("selftest_error", String.valueOf(e)); }
                        try { r.put("namespaces", SelfTest.namespaces(activity, dir)); } catch (Throwable e) { r.put("namespaces_error", String.valueOf(e)); }
                        status(r);
                    }
                } catch (final Throwable e) {
                    activity.runOnUiThread(new Runnable() { @Override public void run() { c.onExit(-1, String.valueOf(e)); } });
                }
            }
        }, "trough-start").start();
    }

    static void status(JSONObject r) {
        try {
            File f = new File(new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "trough"), "status-" + System.currentTimeMillis() + ".json");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(r.toString(2).getBytes("UTF-8")); }
        } catch (Exception ignored) {}
    }

    static void deleteTree(File f) {
        File[] kids = f.isDirectory() && !isLink(f) ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private static boolean isLink(File f) {
        try { return android.system.Os.lstat(f.getPath()).st_mode / 4096 == 10; } catch (Exception e) { return false; }
    }

    public static void stop() {
        if (chat != null) chat.close();
        chat = null;
    }
}
