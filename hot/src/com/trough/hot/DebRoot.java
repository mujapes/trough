package com.trough.hot;

import android.content.Context;
import android.system.Os;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs Trough's Debian root (tools/root.sh: the self-hosting toolchain) at
 * /data/data/t.t/d. Regular files come from the zip; symlinks and exec bits from links.txt and
 * exec.txt, since ZipInputStream can create neither. Installed once per payload sha; a new
 * payload is unpacked beside the old root and swapped in by rename.
 */
final class DebRoot {
    private DebRoot() {}

    static String install(Context ctx, String zipPath) throws Exception {
        File data = ctx.getDataDir(), d = new File(data, "d");
        String sha = new File(zipPath).getName();
        File marker = new File(d, ".payload-" + sha);
        if (marker.exists()) return "current";
        File fresh = new File(data, "d.new");
        Main.deleteTree(fresh);
        fresh.mkdirs();
        String base = fresh.getCanonicalPath() + File.separator;
        byte[] b = new byte[1 << 16];
        int files = 0;
        try (ZipInputStream z = new ZipInputStream(new FileInputStream(zipPath))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                File f = new File(fresh, e.getName());
                if (!f.getCanonicalPath().startsWith(base)) throw new SecurityException("zip path " + e.getName());
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(f)) { for (int n; (n = z.read(b)) > 0; ) o.write(b, 0, n); }
                files++;
            }
        }
        int links = 0;
        for (String line : lines(new File(fresh, "links.txt"))) {
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            File l = new File(fresh, line.substring(0, tab));
            l.getParentFile().mkdirs();
            Os.symlink(line.substring(tab + 1), l.getPath());
            links++;
        }
        for (String rel : lines(new File(fresh, "exec.txt"))) new File(fresh, rel).setExecutable(true, false);
        Payloads.writeSmall(new File(fresh, ".payload-" + sha), "ok");
        File old = new File(data, "d.old");
        Main.deleteTree(old);
        if (d.exists() && !d.renameTo(old)) throw new IllegalStateException("rename " + d);
        if (!fresh.renameTo(d)) throw new IllegalStateException("rename " + fresh);
        Main.deleteTree(old);
        return "installed " + files + " files, " + links + " links";
    }

    private static java.util.List<String> lines(File f) throws Exception {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (!f.exists()) return out;
        for (String s : new String(Payloads.readSmall(f), "UTF-8").split("\n")) if (!s.isEmpty()) out.add(s);
        return out;
    }
}
