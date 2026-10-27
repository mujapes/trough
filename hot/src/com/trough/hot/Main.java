package com.trough.hot;

import android.app.Activity;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Bundle entry point (contract: app/.../HotLoader.java). Two halves:
 *   start  -- the boot, always the signed bundle's own copy: installs the root and payloads,
 *             seeds files/src, builds it with Trough's own toolchain (Self) and hands off.
 *   run    -- Trough itself, normally the copy Trough just built: its own UI (Chat) over its
 *             own claude session (Session). No daemon, no server, no WebView.
 * If the self build fails, the boot runs the bundle's own copy of run, so Trough always comes up.
 */
public final class Main {
    private static Chat chat;

    // Boot state (signed-bundle copy only): what is running now, so it can be stopped or replaced.
    private static Method runningStop;
    private static Activity bootActivity;
    private static FrameLayout bootRoot;
    private static File bootDir;
    private static JSONObject bootPayloads;

    public static void start(final Activity activity, final FrameLayout root, final File dir) {
        bootActivity = activity;
        bootRoot = root;
        bootDir = dir;
        final TextView wait = new TextView(activity);
        wait.setText("Trough is starting");
        wait.setPadding(48, 96, 48, 48);
        root.addView(wait);
        new Thread(new Runnable() {
            @Override public void run() {
                final JSONObject boot = new JSONObject();
                Class<?> self = null;
                try {
                    Root.install(activity, dir);      // texec, patched glibc, resolv.conf
                    bootPayloads = Payloads.ensure(activity, dir);
                    if (bootPayloads.has("root")) {
                        long t = System.nanoTime();
                        boot.put("root_install", DebRoot.install(activity, bootPayloads.getString("root")));
                        boot.put("root_install_ms", (System.nanoTime() - t) / 1_000_000);
                    }
                    boot.put("seed", Self.seed(activity, dir));
                    JSONObject b = new JSONObject();
                    boot.put("self_build", b);
                    File built = Self.build(activity, Self.src(activity), dir, bootPayloads, Self.builds(activity), b);
                    self = Self.load(activity, built, dir, Main.class.getName());
                    boot.put("running", "self " + built.getName().substring(0, 12));
                } catch (Throwable e) {
                    put(boot, "self_error", String.valueOf(e));
                    put(boot, "running", "bundle");
                }
                final Class<?> entry = self != null ? self : Main.class;
                activity.runOnUiThread(new Runnable() { @Override public void run() {
                    root.removeAllViews();
                    enter(entry, boot);
                } });
            }
        }, "trough-boot").start();
    }

    /** Boot side of /reload: rebuild files/src and swap in the result, or keep what runs. */
    private static final Runnable RELOAD = new Runnable() {
        @Override public void run() {
            new Thread(new Runnable() {
                @Override public void run() {
                    final JSONObject boot = new JSONObject();
                    try {
                        JSONObject b = new JSONObject();
                        boot.put("self_build", b).put("reload", true);
                        File built = Self.build(bootActivity, Self.src(bootActivity), bootDir, bootPayloads, Self.builds(bootActivity), b);
                        final Class<?> entry = Self.load(bootActivity, built, bootDir, Main.class.getName());
                        boot.put("running", "self " + built.getName().substring(0, 12));
                        bootActivity.runOnUiThread(new Runnable() { @Override public void run() {
                            stopRunning();
                            bootRoot.removeAllViews();
                            enter(entry, boot);
                        } });
                    } catch (final Throwable e) {
                        put(boot, "reload_error", String.valueOf(e));
                        status(boot);
                        bootActivity.runOnUiThread(new Runnable() { @Override public void run() {
                            Toast.makeText(bootActivity, "reload failed, still running the old build: " + e, Toast.LENGTH_LONG).show();
                        } });
                    }
                }
            }, "trough-reload").start();
        }
    };

    private static void enter(Class<?> entry, JSONObject boot) {
        try {
            entry.getMethod("run", Activity.class, FrameLayout.class, File.class, JSONObject.class, Runnable.class)
                .invoke(null, bootActivity, bootRoot, bootDir, boot, RELOAD);
            runningStop = entry.getMethod("stop");
        } catch (Throwable e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            put(boot, "enter_error", String.valueOf(cause));
            if (entry != Main.class) { bootRoot.removeAllViews(); enter(Main.class, boot); }
            else status(boot);
        }
    }

    private static void stopRunning() {
        Method m = runningStop;
        runningStop = null;   // first: when run is the boot's own copy, its stop lands back here
        if (m != null) try { m.invoke(null); } catch (Throwable ignored) {}
    }

    /** Trough itself. Entered by the boot, on the UI thread. */
    public static void run(final Activity activity, FrameLayout root, final File dir, final JSONObject boot, Runnable reload) {
        final File home = new File(activity.getFilesDir(), "home");
        home.mkdirs();
        chat = new Chat(activity, root, home, reload);
        final Chat c = chat;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject payloads = Payloads.ensure(activity, dir);
                    Creds.sync(activity, home);
                    File work = new File(activity.getFilesDir(), "work");
                    work.mkdirs();
                    Map<String, String> env = new HashMap<>();
                    env.put("HOME", home.getPath());
                    env.put("TMPDIR", activity.getCacheDir().getPath());   // Android has no /tmp
                    env.put("SHELL", "/system/bin/sh");
                    env.put("PATH", new File(activity.getDataDir(), "bin").getPath() + ":/system/bin");
                    c.start(payloads.getString("claude"), work, env);
                    if (boot.optBoolean("reload")) { status(boot); return; }
                    // Self-hosting proofs, reported to the VM: the toolchain builds C and Java here,
                    // and an edited copy of Trough's own source builds and loads.
                    if (payloads.has("root")) {
                        try { boot.put("selftest", SelfTest.run(activity)); } catch (Throwable e) { boot.put("selftest_error", String.valueOf(e)); }
                        try { boot.put("self_edit", SelfTest.selfEdit(activity, dir, payloads)); } catch (Throwable e) { boot.put("self_edit_error", String.valueOf(e)); }
                        try { boot.put("namespaces", SelfTest.namespaces(activity, dir)); } catch (Throwable e) { boot.put("namespaces_error", String.valueOf(e)); }
                    }
                    status(boot);
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

    private static void put(JSONObject o, String k, String v) { try { o.put(k, v); } catch (Exception ignored) {} }

    static void deleteTree(File f) {
        File[] kids = f.isDirectory() && !isLink(f) ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private static boolean isLink(File f) {
        try { return android.system.Os.lstat(f.getPath()).st_mode / 4096 == 10; } catch (Exception e) { return false; }
    }

    /** Called by HotLoader on the boot copy (forwards to what runs), and by the boot on run's copy. */
    public static void stop() {
        if (runningStop != null) { stopRunning(); return; }
        if (chat != null) chat.close();
        chat = null;
    }
}
