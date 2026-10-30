package com.trough.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import dalvik.system.DexClassLoader;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.lang.reflect.Method;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The bootstrap/recovery lifetime (docs/HOT_RELOAD.md "Three lifetimes"). Everything Trough
 * does arrives as a signed bundle; this class verifies, activates and, when a bundle cannot
 * stay up, recovers without the replaceable program being healthy. One per main process
 * (HotLoader.process, created by TroughApp), so a bundle outlives every Activity.
 *
 * Bundle: Download/trough/hot/bundle.zip + bundle.sig (ECDSA P-256 over the zip, key held in
 * the VM, public half in assets/hot-pubkey.der -- shared storage is writable by other apps, so
 * nothing unsigned ever runs). The zip holds classes.dex, bin/* (native executables) and
 * manifest.json {"entry": "<class>"}. The entry class exposes (docs/HOST.md, all optional but one start):
 *     public static void boot(Context app, FrameLayout root, File bundleDir)   v2: process-level, no Activity needed
 *     public static void start(Activity, FrameLayout root, File bundleDir)     v1: waits for an Activity
 *     public static void attach(Activity)     a new Activity shows root (the bundle keeps running)
 *     public static void detach()             no Activity now (v1 bundles without attach are stopped)
 *     public static void intent(Intent)       an Intent delivered to Trough (VIEW, SEND, ...); queued until running
 *     public static Object dispatch(Object host, String event, Object[] args)   every declared component
 *     public static void stop()
 *
 * Recovery state, app-private under files/hot/:
 *   good      sha of the last bundle that stayed up for SETTLE_MS; cold start runs it directly
 *   starting  "<sha> <pid>" being activated; found by a LATER process = it died before settling
 *   bad/<sha> bundles that died before settling; never activated again (a new publish has a new sha)
 * Crashes are also copied to Download/trough/crash-<ms>.txt so the VM can observe them.
 * Knobs, never policy: the loader does not know what a bundle does.
 */
final class HotLoader {
    static final long SETTLE_MS = 15000, POLL_MS = 1000, HIDDEN_POLL_MS = 5000;
    static HotLoader process;
    private final Context app;
    private final FrameLayout root;
    private final PublicKey key;
    private final File state;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Intent> intents = new ArrayList<>();   // before a bundle runs; bounded
    private Activity activity;          // the one showing root, or null
    private boolean visible;
    private volatile Class<?> current;
    private volatile Method dispatch;
    private Class<?> deferred;          // a v1 bundle waiting for an Activity
    private File deferredDir;
    private String currentSha = "";
    private long seenModified = -1;
    String status = "no bundle yet";

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            check();
            if (current == null && deferred == null) showStatus();
            main.postDelayed(this, visible ? POLL_MS : HIDDEN_POLL_MS);
        }
    };

    /** Main process only. Starts the newest verified bundle, else the last good one. */
    static void init(Context app) {
        if (process != null) return;
        try {
            process = new HotLoader(app);
            // Never both: a bundle replaced mid-boot cannot be relied on to stay stopped.
            if (!process.check()) process.resume();
            process.main.post(process.poll);
        } catch (Exception e) { android.util.Log.e("trough", "loader", e); }
    }

    private HotLoader(Context app) throws Exception {
        this.app = app;
        root = new FrameLayout(app);
        root.setBackgroundColor(Color.BLACK);
        byte[] der = readAll(app.getAssets().open("hot-pubkey.der"));
        key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        state = new File(app.getFilesDir(), "hot");
        String[] unsettled = read(new File(state, "starting")).split(" ");
        if (unsettled.length == 2 && !unsettled[1].equals(String.valueOf(android.os.Process.myPid())))
            write(new File(new File(state, "bad"), unsettled[0]), "died before settling");
        new File(state, "starting").delete();
        final Thread.UncaughtExceptionHandler next = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter s = new StringWriter();
                    e.printStackTrace(new PrintWriter(s));
                    write(new File(sharedDir().getParentFile(), "crash-" + System.currentTimeMillis() + ".txt"),
                        "bundle " + currentSha + "\nthread " + t.getName() + "\n" + s);
                } catch (Throwable ignored) {}
                if (next != null) next.uncaughtException(t, e);
            }
        });
    }

    static File sharedDir() {
        return new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "trough"), "hot");
    }

    FrameLayout root() { return root; }

    /** Cold start fallback: the last settled bundle from app storage, with no shared storage. */
    boolean resume() {
        String sha = read(new File(state, "good"));
        File dir = new File(state, sha);
        if (sha.isEmpty() || !new File(dir, "manifest.json").exists() || new File(new File(state, "bad"), sha).exists()) return false;
        try { activate(sha, dir); return true; }
        catch (Throwable t) { status = "last good bundle failed: " + cause(t); return false; }
    }

    /** Cheap when nothing changed: one stat. Returns true if a new bundle was started. */
    boolean check() {
        File zip = new File(sharedDir(), "bundle.zip"), sig = new File(sharedDir(), "bundle.sig");
        long modified = Math.max(zip.lastModified(), sig.lastModified());
        if (modified == 0 || modified == seenModified) return false;
        try {
            byte[] bytes = readAll(new FileInputStream(zip));
            String sha = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (sha.equals(currentSha)) { seenModified = modified; return false; }
            if (new File(new File(state, "bad"), sha).exists()) {
                seenModified = modified;
                status = "bundle " + sha.substring(0, 8) + " died before settling; waiting for a new one";
                return false;
            }
            Signature v = Signature.getInstance("SHA256withECDSA");
            v.initVerify(key);
            v.update(bytes);
            if (!v.verify(readAll(new FileInputStream(sig)))) {
                status = "bundle " + sha.substring(0, 8) + ": bad signature (or still being written)";
                return false;
            }
            seenModified = modified;
            File dir = new File(state, sha);
            if (!new File(dir, "manifest.json").exists()) extract(bytes, dir);
            activate(sha, dir);
            return true;
        } catch (Throwable t) {
            status = "bundle failed: " + cause(t);
            return false;
        }
    }

    private void activate(String sha, File dir) throws Exception {
        JSONObject manifest = new JSONObject(new String(readAll(new FileInputStream(new File(dir, "manifest.json"))), "UTF-8"));
        DexClassLoader loader = new DexClassLoader(new File(dir, "classes.dex").getPath(),
            app.getCodeCacheDir().getPath(), new File(dir, "bin").getPath(), getClass().getClassLoader());
        Class<?> entry = loader.loadClass(manifest.getString("entry"));
        stopCurrent();
        root.removeAllViews();
        currentSha = sha;
        Method boot = method(entry, "boot", Context.class, FrameLayout.class, File.class);
        if (boot == null && activity == null) {   // v1: needs an Activity; attachActivity starts it
            deferred = entry; deferredDir = dir;
            status = "bundle " + sha.substring(0, 8) + " waits for Trough's window";
            return;
        }
        write(new File(state, "starting"), sha + " " + android.os.Process.myPid());
        current = entry;
        dispatch = method(entry, "dispatch", Object.class, String.class, Object[].class);
        if (boot != null) {
            boot.invoke(null, app, root, dir);
            if (activity != null) attachCurrent();
        } else entry.getMethod("start", Activity.class, FrameLayout.class, File.class).invoke(null, activity, root, dir);
        status = "running bundle " + sha.substring(0, 8);
        main.postDelayed(new Runnable() { @Override public void run() { settle(sha); } }, SETTLE_MS);
        flushIntents();
    }

    /** a now shows root (the caller set it as content view). */
    void attachActivity(Activity a) {
        activity = a;
        if (deferred != null) {
            Class<?> e = deferred; File dir = deferredDir;
            deferred = null;
            try { activate(currentSha, dir); } catch (Throwable t) { status = "bundle failed: " + cause(t); showStatus(); }
            return;
        }
        if (current != null) { attachCurrent(); return; }
        if (!check() && !resume()) showStatus();
    }

    private void attachCurrent() {
        Method m = method(current, "attach", Activity.class);
        if (m == null) {   // v1 without attach was started on an earlier Activity: start it again here
            stopCurrent(); root.removeAllViews();
            if (!resume()) showStatus();
            return;
        }
        try { m.invoke(null, activity); }
        catch (Throwable t) { status = "attach failed: " + cause(t); stopCurrent(); root.removeAllViews(); if (!resume()) showStatus(); }
    }

    /** a no longer shows root (destroyed: Recents swipe, finish, recreation). */
    void detachActivity(Activity a) {
        if (activity != a) return;
        activity = null;
        visible = false;
        if (current != null) {
            if (method(current, "attach", Activity.class) == null) stopCurrent();   // v1 lives and dies with its Activity
            else { Method d = method(current, "detach"); if (d != null) try { d.invoke(null); } catch (Throwable ignored) {} }
        }
        if (root.getParent() != null) ((ViewGroup) root.getParent()).removeView(root);
    }

    void visible(boolean v) {
        visible = v;
        main.removeCallbacks(poll);
        main.post(poll);
    }

    /** Intents for Trough itself (VIEW, SEND, HOME, ...); held until a bundle runs. */
    void intent(Intent i) {
        if (i == null) return;
        if (current == null) { if (intents.size() < 16) intents.add(i); return; }
        Method m = method(current, "intent", Intent.class);
        if (m != null) try { m.invoke(null, i); } catch (Throwable t) { android.util.Log.e("trough", "intent", t); }
    }

    private void flushIntents() {
        ArrayList<Intent> held = new ArrayList<>(intents);
        intents.clear();
        for (Intent i : held) intent(i);
    }

    /** From any thread: a declared component's event (Components.java). Null when nothing handles it. */
    Object dispatch(Object host, String event, Object[] args) {
        Method m = dispatch;
        if (m == null) return null;
        try { return m.invoke(null, host, event, args); }
        catch (Throwable t) { android.util.Log.e("trough", "dispatch " + event, cause(t)); return null; }
    }

    private void showStatus() {
        TextView t = (TextView) root.findViewWithTag("trough-status");
        if (t == null) {
            if (root.getChildCount() != 0) return;
            t = new TextView(root.getContext());
            t.setTag("trough-status");
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextColor(0xFFE8EFE6);
            t.setPadding(32, 96, 32, 32);
            root.addView(t);
        }
        t.setText("Trough loader\n\n" + status + "\n\nWaiting for Download/trough/hot/bundle.zip");
    }

    private static Method method(Class<?> c, String name, Class<?>... types) {
        try { return c.getMethod(name, types); } catch (Throwable t) { return null; }
    }

    /** Called SETTLE_MS after an activation: the bundle stayed up, so it becomes last-known-good. */
    void settle(String sha) {
        if (!sha.equals(currentSha) || !read(new File(state, "starting")).startsWith(sha + " ")) return;
        try {
            write(new File(state, "good"), sha);
            new File(state, "starting").delete();
            File[] old = state.listFiles();
            if (old != null) for (File f : old)  // keep the running bundle and nothing else extracted
                if (f.isDirectory() && f.getName().matches("[0-9a-f]{64}(\\.tmp)?") && !f.getName().equals(sha)) deleteTree(f);
        } catch (Throwable ignored) {}
    }

    String currentSha() { return currentSha; }

    void stopCurrent() {
        deferred = null;
        Class<?> c = current;
        if (c == null) return;
        current = null;
        dispatch = null;
        try { Method stop = c.getMethod("stop"); stop.invoke(null); } catch (Throwable ignored) {}
    }

    private static Throwable cause(Throwable t) { return t.getCause() != null ? t.getCause() : t; }

    private static void extract(byte[] zipBytes, File dir) throws Exception {
        File tmp = new File(dir.getPath() + ".tmp");
        deleteTree(tmp);
        tmp.mkdirs();
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                File f = new File(tmp, e.getName());
                if (!f.getCanonicalPath().startsWith(tmp.getCanonicalPath() + File.separator)) throw new SecurityException("zip path " + e.getName());
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(readAll(z, false)); }
                if (e.getName().startsWith("bin/")) f.setExecutable(true, false);
                if (e.getName().endsWith(".dex")) f.setReadOnly();  // ART refuses writable dex
            }
        }
        if (!tmp.renameTo(dir)) throw new IllegalStateException("rename " + dir);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private static String read(File f) {
        try { return f.isFile() ? new String(readAll(new FileInputStream(f)), "UTF-8").trim() : ""; }
        catch (Throwable t) { return ""; }
    }

    /** Durable before return: the marker must survive the crash it exists to detect. */
    private static void write(File f, String text) throws Exception {
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".new");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(text.getBytes("UTF-8")); o.getFD().sync(); }
        if (!tmp.renameTo(f)) throw new IllegalStateException("rename " + f);
    }

    static byte[] readAll(InputStream in) throws Exception { return readAll(in, true); }

    private static byte[] readAll(InputStream in, boolean close) throws Exception {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            return buf.toByteArray();
        } finally { if (close) in.close(); }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
