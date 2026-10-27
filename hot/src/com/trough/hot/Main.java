package com.trough.hot;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

/** Bundle entry point (see app/.../HotLoader.java for the contract). */
public final class Main {
    private static volatile boolean stopped;

    public static void start(final Activity activity, FrameLayout root, final File dir) {
        stopped = false;
        final TextView out = new TextView(activity);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(12);
        out.setTextColor(0xFFE8EFE6);
        out.setPadding(32, 96, 32, 64);
        out.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.addView(out);
        root.addView(scroll);
        out.setText("hot bundle " + dir.getName().substring(0, 8) + "\nrunning probe + bench (~15s)...");
        new Thread(new Runnable() {
            @Override public void run() {
                final String result = Bench.run(activity, new File(dir, "bin"));
                if (stopped) return;
                activity.runOnUiThread(new Runnable() { @Override public void run() { out.setText(result); } });
            }
        }, "trough-bench").start();
    }

    public static void stop() { stopped = true; }
}
