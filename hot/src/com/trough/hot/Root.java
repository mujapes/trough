package com.trough.hot;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Lays out Trough's root in the app data dir, at the short paths stock binaries were patched
 * to use (native/texec, tools/set_interp.py, tools/patch_paths.py):
 *     /data/data/t.t/x    texec (PT_INTERP of every installed binary)
 *     /data/data/t.t/l/   patched glibc (texec's TROUGH_LD and LD_LIBRARY_PATH)
 *     /data/data/t.t/r    resolv.conf
 * Files are replaced by rename, so running processes keep the version they started with.
 */
final class Root {
    private Root() {}

    static final String DIR = "/data/data/t.t";

    static File install(Context ctx, File bundleDir) throws Exception {
        File root = ctx.getDataDir();  // /data/user/0/t.t, which /data/data/t.t reaches
        if (!new File(DIR).getCanonicalPath().equals(root.getCanonicalPath()))
            throw new IllegalStateException("data dir " + root + " is not " + DIR + " (package must be t.t)");
        replace(new File(bundleDir, "bin/texec"), new File(root, "x"), true);
        File lib = new File(root, "l");
        lib.mkdirs();
        File[] libs = new File(bundleDir, "lib").listFiles();
        if (libs != null) for (File f : libs) replace(f, new File(lib, f.getName()), false);
        writeText(new File(root, "r"), "nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 2606:4700:4700::1111\n");
        return root;
    }

    private static void replace(File from, File to, boolean exec) throws Exception {
        File tmp = new File(to.getPath() + ".new");
        try (InputStream in = new FileInputStream(from); OutputStream o = new FileOutputStream(tmp)) {
            byte[] b = new byte[1 << 16];
            for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
        }
        tmp.setReadable(true, false);
        if (exec) tmp.setExecutable(true, false);
        if (!tmp.renameTo(to)) throw new IllegalStateException("rename " + to);
    }

    private static void writeText(File to, String text) throws Exception {
        File tmp = new File(to.getPath() + ".new");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(text.getBytes("UTF-8")); }
        if (!tmp.renameTo(to)) throw new IllegalStateException("rename " + to);
    }
}
