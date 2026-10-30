package com.trough.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
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

    // Callbacks Android gives only to the Activity: forwarded so a bundle can use any API that
    // reports back here (VPN/screen-capture/role/install consent, file pickers, permissions, ...).
    private void host(String event, Object... args) { if (loader != null) loader.host(this, "activity." + event, args); }
    @Override protected void onActivityResult(int request, int result, Intent data) { host("result", request, result, data); }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) { host("permissions", request, permissions, grants); }
    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); host("configuration", c); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); host("focus", focus); }
    @Override public void onMultiWindowModeChanged(boolean multi, Configuration c) { super.onMultiWindowModeChanged(multi, c); host("multiWindow", multi, c); }
    @Override public void onPictureInPictureModeChanged(boolean pip, Configuration c) { super.onPictureInPictureModeChanged(pip, c); host("pip", pip, c); }
    @Override protected void onUserLeaveHint() { super.onUserLeaveHint(); host("userLeave"); }
    @Override protected void onStart() { super.onStart(); host("start"); }
    @Override protected void onStop() { host("stop"); super.onStop(); }
    @Override public void onTrimMemory(int level) { super.onTrimMemory(level); host("trimMemory", level); }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); host("save", out); }
    @Override public void onTopResumedActivityChanged(boolean top) { super.onTopResumedActivityChanged(top); host("topResumed", top); }

    @Override protected void onDestroy() {
        if (loader != null) loader.detachActivity(this);
        super.onDestroy();
    }
}
