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
 * Process slots, declared up front so they never need an APK change: sixteen processes
 * (":p0".."p15"), each with Activities in every launch mode (A standard, T singleTop, K
 * singleTask, I singleInstance, L translucent), a Service (S) and a ContentProvider (P,
 * authority t.t.slot<n>). Enough stubs for a guest app's components to map onto (runtime
 * virtualisation or recompiled guests, DESIGN.md "Android apps inside Trough"). They hold no
 * policy. Each hands itself to the last settled bundle's entry class, which decides what runs
 * there, or finishes / returns nothing when the bundle has no use for it:
 *     public static void slotActivity(Activity, Bundle saved)   (owns the Activity from then on)
 *     public static IBinder slotBind(Service, Intent)
 *     public static Object slotProvider(ContentProvider, String event, Object[] args)
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

    public static class SlotProvider extends android.content.ContentProvider {
        private Object call(String event, Object... args) {
            try { return entry(getContext()).getMethod("slotProvider", android.content.ContentProvider.class, String.class, Object[].class).invoke(null, this, event, args); }
            catch (Throwable t) { return null; }
        }
        @Override public boolean onCreate() { return true; }
        @Override public android.database.Cursor query(android.net.Uri u, String[] p, String s, String[] a, String o) { Object r = call("query", u, p, s, a, o); return r instanceof android.database.Cursor ? (android.database.Cursor) r : null; }
        @Override public String getType(android.net.Uri u) { Object r = call("type", u); return r instanceof String ? (String) r : null; }
        @Override public android.net.Uri insert(android.net.Uri u, android.content.ContentValues v) { Object r = call("insert", u, v); return r instanceof android.net.Uri ? (android.net.Uri) r : null; }
        @Override public int delete(android.net.Uri u, String s, String[] a) { Object r = call("delete", u, s, a); return r instanceof Integer ? (Integer) r : 0; }
        @Override public int update(android.net.Uri u, android.content.ContentValues v, String s, String[] a) { Object r = call("update", u, v, s, a); return r instanceof Integer ? (Integer) r : 0; }
        @Override public Bundle call(String method, String arg, Bundle extras) { Object r = call("call", method, arg, extras); return r instanceof Bundle ? (Bundle) r : null; }
        @Override public android.os.ParcelFileDescriptor openFile(android.net.Uri u, String mode) throws java.io.FileNotFoundException {
            Object r = call("open", u, mode);
            if (r instanceof android.os.ParcelFileDescriptor) return (android.os.ParcelFileDescriptor) r;
            throw new java.io.FileNotFoundException(u.toString());
        }
    }

    public static final class A0 extends SlotActivity {} public static final class T0 extends SlotActivity {} public static final class K0 extends SlotActivity {}
    public static final class I0 extends SlotActivity {} public static final class L0 extends SlotActivity {}
    public static final class S0 extends SlotService {} public static final class P0 extends SlotProvider {}
    public static final class A1 extends SlotActivity {} public static final class T1 extends SlotActivity {} public static final class K1 extends SlotActivity {}
    public static final class I1 extends SlotActivity {} public static final class L1 extends SlotActivity {}
    public static final class S1 extends SlotService {} public static final class P1 extends SlotProvider {}
    public static final class A2 extends SlotActivity {} public static final class T2 extends SlotActivity {} public static final class K2 extends SlotActivity {}
    public static final class I2 extends SlotActivity {} public static final class L2 extends SlotActivity {}
    public static final class S2 extends SlotService {} public static final class P2 extends SlotProvider {}
    public static final class A3 extends SlotActivity {} public static final class T3 extends SlotActivity {} public static final class K3 extends SlotActivity {}
    public static final class I3 extends SlotActivity {} public static final class L3 extends SlotActivity {}
    public static final class S3 extends SlotService {} public static final class P3 extends SlotProvider {}
    public static final class A4 extends SlotActivity {} public static final class T4 extends SlotActivity {} public static final class K4 extends SlotActivity {}
    public static final class I4 extends SlotActivity {} public static final class L4 extends SlotActivity {}
    public static final class S4 extends SlotService {} public static final class P4 extends SlotProvider {}
    public static final class A5 extends SlotActivity {} public static final class T5 extends SlotActivity {} public static final class K5 extends SlotActivity {}
    public static final class I5 extends SlotActivity {} public static final class L5 extends SlotActivity {}
    public static final class S5 extends SlotService {} public static final class P5 extends SlotProvider {}
    public static final class A6 extends SlotActivity {} public static final class T6 extends SlotActivity {} public static final class K6 extends SlotActivity {}
    public static final class I6 extends SlotActivity {} public static final class L6 extends SlotActivity {}
    public static final class S6 extends SlotService {} public static final class P6 extends SlotProvider {}
    public static final class A7 extends SlotActivity {} public static final class T7 extends SlotActivity {} public static final class K7 extends SlotActivity {}
    public static final class I7 extends SlotActivity {} public static final class L7 extends SlotActivity {}
    public static final class S7 extends SlotService {} public static final class P7 extends SlotProvider {}
    public static final class A8 extends SlotActivity {} public static final class T8 extends SlotActivity {} public static final class K8 extends SlotActivity {}
    public static final class I8 extends SlotActivity {} public static final class L8 extends SlotActivity {}
    public static final class S8 extends SlotService {} public static final class P8 extends SlotProvider {}
    public static final class A9 extends SlotActivity {} public static final class T9 extends SlotActivity {} public static final class K9 extends SlotActivity {}
    public static final class I9 extends SlotActivity {} public static final class L9 extends SlotActivity {}
    public static final class S9 extends SlotService {} public static final class P9 extends SlotProvider {}
    public static final class A10 extends SlotActivity {} public static final class T10 extends SlotActivity {} public static final class K10 extends SlotActivity {}
    public static final class I10 extends SlotActivity {} public static final class L10 extends SlotActivity {}
    public static final class S10 extends SlotService {} public static final class P10 extends SlotProvider {}
    public static final class A11 extends SlotActivity {} public static final class T11 extends SlotActivity {} public static final class K11 extends SlotActivity {}
    public static final class I11 extends SlotActivity {} public static final class L11 extends SlotActivity {}
    public static final class S11 extends SlotService {} public static final class P11 extends SlotProvider {}
    public static final class A12 extends SlotActivity {} public static final class T12 extends SlotActivity {} public static final class K12 extends SlotActivity {}
    public static final class I12 extends SlotActivity {} public static final class L12 extends SlotActivity {}
    public static final class S12 extends SlotService {} public static final class P12 extends SlotProvider {}
    public static final class A13 extends SlotActivity {} public static final class T13 extends SlotActivity {} public static final class K13 extends SlotActivity {}
    public static final class I13 extends SlotActivity {} public static final class L13 extends SlotActivity {}
    public static final class S13 extends SlotService {} public static final class P13 extends SlotProvider {}
    public static final class A14 extends SlotActivity {} public static final class T14 extends SlotActivity {} public static final class K14 extends SlotActivity {}
    public static final class I14 extends SlotActivity {} public static final class L14 extends SlotActivity {}
    public static final class S14 extends SlotService {} public static final class P14 extends SlotProvider {}
    public static final class A15 extends SlotActivity {} public static final class T15 extends SlotActivity {} public static final class K15 extends SlotActivity {}
    public static final class I15 extends SlotActivity {} public static final class L15 extends SlotActivity {}
    public static final class S15 extends SlotService {} public static final class P15 extends SlotProvider {}
}
