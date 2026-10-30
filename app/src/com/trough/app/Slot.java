package com.trough.app;

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

import dalvik.system.DexClassLoader;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;

/**
 * Process slots, declared up front so they never need an APK change: eight Activities and eight
 * Services, each pair in its own process (":p0".."p7"). They hold no policy. Each hands itself
 * to the last settled bundle's entry class, which decides what runs there (a guest app
 * recompiled to run inside Trough, isolated work), or finishes when the bundle has no use for it:
 *     public static void slotActivity(Activity, Bundle saved)   (owns the Activity from then on)
 *     public static IBinder slotBind(Service, Intent)
 * Only bundles HotLoader verified and settled run here (files/hot/good, app-private).
 */
public final class Slot {
    private Slot() {}

    static Class<?> entry(Context c) throws Exception {
        File state = new File(c.getFilesDir(), "hot");
        String sha = new String(HotLoader.readAll(new FileInputStream(new File(state, "good"))), "UTF-8").trim();
        File dir = new File(state, sha);
        JSONObject m = new JSONObject(new String(HotLoader.readAll(new FileInputStream(new File(dir, "manifest.json"))), "UTF-8"));
        DexClassLoader l = new DexClassLoader(new File(dir, "classes.dex").getPath(), c.getCodeCacheDir().getPath(),
            new File(dir, "bin").getPath(), Slot.class.getClassLoader());
        return l.loadClass(m.getString("entry"));
    }

    public static class SlotActivity extends Activity {
        @Override protected void onCreate(Bundle saved) {
            super.onCreate(saved);
            try { entry(this).getMethod("slotActivity", Activity.class, Bundle.class).invoke(null, this, saved); }
            catch (Throwable t) { finish(); }
        }
    }

    public static class SlotService extends Service {
        @Override public IBinder onBind(Intent intent) {
            try { return (IBinder) entry(this).getMethod("slotBind", Service.class, Intent.class).invoke(null, this, intent); }
            catch (Throwable t) { return null; }
        }
    }

    public static final class A0 extends SlotActivity {} public static final class S0 extends SlotService {}
    public static final class A1 extends SlotActivity {} public static final class S1 extends SlotService {}
    public static final class A2 extends SlotActivity {} public static final class S2 extends SlotService {}
    public static final class A3 extends SlotActivity {} public static final class S3 extends SlotService {}
    public static final class A4 extends SlotActivity {} public static final class S4 extends SlotService {}
    public static final class A5 extends SlotActivity {} public static final class S5 extends SlotService {}
    public static final class A6 extends SlotActivity {} public static final class S6 extends SlotService {}
    public static final class A7 extends SlotActivity {} public static final class S7 extends SlotService {}
}
