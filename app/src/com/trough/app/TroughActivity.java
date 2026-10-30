package com.trough.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Host only: shows the process's root view (HotLoader.process) and tells the loader when this
 * window comes and goes. Every visible behaviour comes from the bundle. Every Intent Trough is
 * declared to handle (launcher, HOME, links, shares, files, calls, USB, NFC, ...) lands here
 * and goes to the bundle's intent(Intent). singleTask: at most one Activity owns root.
 */
public class TroughActivity extends Activity {
    private HotLoader loader;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        startForegroundService(new Intent(this, TroughService.class));
        HotLoader.init(getApplicationContext());
        loader = HotLoader.process;
        if (loader == null) { finish(); return; }
        FrameLayout root = loader.root();
        if (root.getParent() != null) ((ViewGroup) root.getParent()).removeView(root);
        setContentView(root);
        loader.attachActivity(this);
        if (state == null) loader.intent(getIntent());
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (loader != null) loader.intent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        if (loader != null) loader.visible(true);
    }

    @Override protected void onPause() {
        if (loader != null) loader.visible(false);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (loader != null) loader.detachActivity(this);
        super.onDestroy();
    }
}
