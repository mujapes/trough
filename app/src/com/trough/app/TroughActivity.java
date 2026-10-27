package com.trough.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

/** First Trough screen: runs the on-device bench and shows its JSON. Plain Views, no WebView. */
public class TroughActivity extends Activity {
    private TextView out;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        out = new TextView(this);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(12);
        out.setTextColor(0xFFE8EFE6);
        out.setPadding(32, 96, 32, 64);
        out.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.addView(out);
        setContentView(scroll);

        startForegroundService(new Intent(this, TroughService.class));
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        } else {
            runBench();
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        runBench();  // the bench still runs and shows on screen if storage was refused
    }

    private void runBench() {
        out.setText("Running bench on every core (~10s)...\n");
        final Activity self = this;
        new Thread(new Runnable() {
            @Override public void run() {
                final String result = Bench.run(self);
                runOnUiThread(new Runnable() { @Override public void run() { out.setText(result); } });
            }
        }, "trough-bench").start();
    }
}
