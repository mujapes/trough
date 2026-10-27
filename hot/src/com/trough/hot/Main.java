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
                } catch (final Throwable e) {
                    activity.runOnUiThread(new Runnable() { @Override public void run() { c.onExit(-1, String.valueOf(e)); } });
                }
            }
        }, "trough-start").start();
    }

    public static void stop() {
        if (chat != null) chat.close();
        chat = null;
    }
}
