package com.trough.hot;

import android.content.Context;
import android.os.Environment;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Iterator;

/**
 * Files too big for a bundle (claude is 294 MB; HotLoader reads bundles into memory).
 * tools/hot.sh copies each to Download/trough/payload/<sha256> and lists name -> sha256 in the
 * bundle's manifest.json. The manifest is inside the signed bundle, so the hash is trusted:
 * stream the file into app storage, hash on the way, keep it only if it matches.
 */
final class Payloads {
    private Payloads() {}

    static File dir(Context ctx) { return new File(ctx.getFilesDir(), "payload"); }

    /** Installs every payload in manifest; returns name -> local path, or throws on mismatch. */
    static JSONObject ensure(Context ctx, File bundleDir) throws Exception { return ensure(ctx, bundleDir, null); }

    /** As ensure, leaving out the payloads named in skip (not fetched, not listed). */
    static JSONObject ensure(Context ctx, File bundleDir, java.util.Set<String> skip) throws Exception {
        JSONObject manifest = new JSONObject(new String(readSmall(new File(bundleDir, "manifest.json")), "UTF-8"));
        JSONObject wanted = manifest.optJSONObject("payloads");
        JSONObject out = new JSONObject();
        if (wanted == null) return out;
        File shared = new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "trough"), "payload");
        dir(ctx).mkdirs();
        for (Iterator<String> it = wanted.keys(); it.hasNext(); ) {
            String name = it.next(), sha = wanted.getString(name);
            if (skip != null && skip.contains(name)) continue;
            File local = new File(dir(ctx), sha);
            if (!local.exists()) {
                File tmp = new File(dir(ctx), sha + ".tmp");
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (InputStream in = new FileInputStream(new File(shared, sha)); OutputStream o = new FileOutputStream(tmp)) {
                    byte[] b = new byte[1 << 20];
                    for (int n; (n = in.read(b)) > 0; ) { md.update(b, 0, n); o.write(b, 0, n); }
                }
                String got = hex(md.digest());
                if (!got.equals(sha)) { tmp.delete(); throw new SecurityException(name + ": sha256 " + got + " != " + sha); }
                if (!tmp.renameTo(local)) throw new IllegalStateException("rename " + local);
                local.setExecutable(true, false);
            }
            out.put(name, local.getPath());
        }
        return out;
    }

    static void writeSmall(File f, String text) throws Exception {
        File tmp = new File(f.getPath() + ".new");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(text.getBytes("UTF-8")); }
        if (!tmp.renameTo(f)) throw new IllegalStateException("rename " + f);
    }

    static byte[] readSmall(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            for (int n; off < b.length && (n = in.read(b, off, b.length - off)) > 0; ) off += n;
            return b;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
