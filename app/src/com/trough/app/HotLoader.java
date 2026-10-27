package com.trough.app;

import android.app.Activity;
import android.os.Environment;
import android.view.View;
import android.widget.FrameLayout;

import dalvik.system.DexClassLoader;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Everything Trough does arrives as a signed bundle and swaps in live (DESIGN.md "Hot reload").
 * This class is the only behaviour compiled into the APK, so it should never need to change.
 *
 * Bundle: Download/trough/hot/bundle.zip + bundle.sig (ECDSA P-256 over the zip, key held in
 * the VM, public half in assets/hot-pubkey.der -- shared storage is writable by other apps, so
 * nothing unsigned ever runs). The zip holds classes.dex, bin/* (native executables) and
 * manifest.json {"entry": "<class>"}. The entry class exposes
 *     public static void start(Activity, FrameLayout root, File bundleDir)
 *     public static void stop()            (optional)
 * Knobs, never policy: the loader does not know what a bundle does.
 */
final class HotLoader {
    private final Activity activity;
    private final FrameLayout root;
    private final PublicKey key;
    private Class<?> current;
    private String currentSha = "";
    private long seenModified = -1;
    String status = "no bundle yet";

    HotLoader(Activity activity, FrameLayout root) throws Exception {
        this.activity = activity;
        this.root = root;
        byte[] der = readAll(activity.getAssets().open("hot-pubkey.der"));
        key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
    }

    static File sharedDir() {
        return new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "trough"), "hot");
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
            Signature v = Signature.getInstance("SHA256withECDSA");
            v.initVerify(key);
            v.update(bytes);
            if (!v.verify(readAll(new FileInputStream(sig)))) {
                status = "bundle " + sha.substring(0, 8) + ": bad signature (or still being written)";
                return false;
            }
            seenModified = modified;
            File dir = new File(new File(activity.getFilesDir(), "hot"), sha);
            if (!new File(dir, "manifest.json").exists()) extract(bytes, dir);
            JSONObject manifest = new JSONObject(new String(readAll(new FileInputStream(new File(dir, "manifest.json"))), "UTF-8"));
            DexClassLoader loader = new DexClassLoader(new File(dir, "classes.dex").getPath(),
                activity.getCodeCacheDir().getPath(), new File(dir, "bin").getPath(), getClass().getClassLoader());
            Class<?> entry = loader.loadClass(manifest.getString("entry"));
            stopCurrent();
            root.removeAllViews();
            current = entry;
            currentSha = sha;
            entry.getMethod("start", Activity.class, FrameLayout.class, File.class).invoke(null, activity, root, dir);
            status = "running bundle " + sha.substring(0, 8);
            return true;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            status = "bundle failed: " + cause;
            return false;
        }
    }

    void stopCurrent() {
        if (current == null) return;
        try { Method stop = current.getMethod("stop"); stop.invoke(null); } catch (Throwable ignored) {}
        current = null;
    }

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
