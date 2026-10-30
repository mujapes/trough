package com.trough.app;

import android.app.Activity;
import android.os.Environment;
import android.widget.FrameLayout;

import dalvik.system.DexClassLoader;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
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
 * stay up, recovers without the replaceable program being healthy.
 *
 * Bundle: Download/trough/hot/bundle.zip + bundle.sig (ECDSA P-256 over the zip, key held in
 * the VM, public half in assets/hot-pubkey.der -- shared storage is writable by other apps, so
 * nothing unsigned ever runs). The zip holds classes.dex, bin/* (native executables) and
 * manifest.json {"entry": "<class>"}. The entry class exposes
 *     public static void start(Activity, FrameLayout root, File bundleDir)
 *     public static void stop()            (optional)
 *
 * Recovery state, app-private under files/hot/:
 *   good      sha of the last bundle that stayed up for SETTLE_MS; cold start runs it directly
 *   starting  "<sha> <pid>" being activated; found by a LATER process = it died before settling
 *             (an activity recreated inside the same process is not a crash)
 *   bad/<sha> bundles that died before settling; never activated again (a new publish has a new sha)
 * Crashes are also copied to Download/trough/crash-<ms>.txt so the VM can observe them.
 * Knobs, never policy: the loader does not know what a bundle does.
 */
final class HotLoader {
    static final long SETTLE_MS = 15000;
    /** One per process: a bundle with attach(Activity) outlives its Activity (TroughService
     *  keeps the process) and is handed each new one with the same root view. */
    static HotLoader process;
    private Activity activity;
    private final FrameLayout root;
    private final PublicKey key;
    private final File state;
    private Class<?> current;
    private String currentSha = "";
    private long seenModified = -1;
    String status = "no bundle yet";

    HotLoader(Activity activity, FrameLayout root) throws Exception {
        this.activity = activity;
        this.root = root;
        byte[] der = readAll(activity.getAssets().open("hot-pubkey.der"));
        key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        state = new File(activity.getFilesDir(), "hot");
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
            activity.getCodeCacheDir().getPath(), new File(dir, "bin").getPath(), getClass().getClassLoader());
        Class<?> entry = loader.loadClass(manifest.getString("entry"));
        stopCurrent();
        root.removeAllViews();
        write(new File(state, "starting"), sha + " " + android.os.Process.myPid());
        current = entry;
        currentSha = sha;
        entry.getMethod("start", Activity.class, FrameLayout.class, File.class).invoke(null, activity, root, dir);
        status = "running bundle " + sha.substring(0, 8);
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

    FrameLayout root() { return root; }

    /** Whether the running bundle can move to a new Activity (public static void attach(Activity)). */
    boolean attachable() {
        if (current == null) return false;
        try { current.getMethod("attach", Activity.class); return true; } catch (Throwable t) { return false; }
    }

    /** Hands the running bundle a new Activity, whose content view is already root. False: it
     *  could not take it and was stopped; the caller starts a bundle the usual way. */
    boolean attach(Activity a) {
        activity = a;
        try { current.getMethod("attach", Activity.class).invoke(null, a); return true; }
        catch (Throwable t) { status = "attach failed: " + cause(t); stopCurrent(); root.removeAllViews(); return false; }
    }

    /** A bundle without attach() stops with its Activity, as before; the next one restarts it here. */
    void rebind(Activity a) { activity = a; }

    void stopCurrent() {
        if (current == null) return;
        try { Method stop = current.getMethod("stop"); stop.invoke(null); } catch (Throwable ignored) {}
        current = null;
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
