package com.trough.hot;

import android.app.Activity;
import android.graphics.Color;
import android.os.Environment;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Bundle entry point (contract: app/.../HotLoader.java).
 *
 * 10-27: an attempt to run Forge's daemon + WebView client inside Trough was reverted (see
 * DESIGN.md "Direction"). This bundle stops any daemon that attempt left running, keeps the
 * root and payloads current, and shows a status line. The native Trough UI replaces it.
 */
public final class Main {
    public static void start(final Activity activity, FrameLayout root, final File dir) {
        final TextView status = new TextView(activity);
        status.setTextColor(0xFFE8EFE6);
        status.setBackgroundColor(Color.BLACK);
        status.setPadding(32, 96, 32, 32);
        status.setText("Trough " + dir.getName().substring(0, 8) + "\nnative UI in progress");
        root.addView(status);
        new Thread(new Runnable() {
            @Override public void run() {
                JSONObject r = new JSONObject();
                try {
                    r.put("bundle", dir.getName().substring(0, 8));
                    Process p = new ProcessBuilder("/system/bin/pkill", "-f", "forged").redirectErrorStream(true).start();
                    r.put("stopped_forged_exit", p.waitFor());  // 0 = killed something, 1 = none running
                    r.put("root", Root.install(activity, dir).getPath());
                    r.put("payloads", Payloads.ensure(activity, dir));
                } catch (Throwable e) {
                    try { r.put("error", String.valueOf(e)); } catch (Exception ignored) {}
                }
                try {
                    File f = new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "trough"),
                        "status-" + System.currentTimeMillis() + ".json");
                    try (FileOutputStream o = new FileOutputStream(f)) { o.write(r.toString(2).getBytes("UTF-8")); }
                } catch (Exception ignored) {}
            }
        }, "trough-status").start();
    }

    public static void stop() {}
}
