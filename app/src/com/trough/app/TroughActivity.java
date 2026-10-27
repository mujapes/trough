package com.trough.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Host only: a root view and a HotLoader. Every visible behaviour comes from the bundle.
 * While visible, it checks for a new bundle once a second (one stat when nothing changed).
 */
public class TroughActivity extends Activity {
    private static final long POLL_MS = 1000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private HotLoader loader;
    private boolean visible;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!visible || loader == null) return;
            if (!loader.check() && (root.getChildCount() == 0 || root.findViewWithTag("trough-status") != null))
                showStatus(loader.status);
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        startForegroundService(new Intent(this, TroughService.class));
        try { loader = new HotLoader(this, root); } catch (Exception e) { showStatus("loader: " + e); }
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override protected void onPause() {
        visible = false;
        handler.removeCallbacks(poll);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (loader != null) loader.stopCurrent();
        super.onDestroy();
    }

    private void showStatus(String text) {
        TextView t = (TextView) root.findViewWithTag("trough-status");
        if (t == null) {
            t = new TextView(this);
            t.setTag("trough-status");
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextColor(0xFFE8EFE6);
            t.setPadding(32, 96, 32, 32);
            root.addView(t);
        }
        t.setText("Trough loader\n\n" + text + "\n\nWaiting for Download/trough/hot/bundle.zip");
    }
}
