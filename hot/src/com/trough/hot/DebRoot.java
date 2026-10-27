package com.trough.hot;

import android.content.Context;
import android.system.Os;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.json.JSONObject;

/**
 * Installs Trough's Debian root (tools/root.sh: the self-hosting toolchain) at
 * /data/data/t.t/d. Regular files come from the zip; empty dirs, symlinks and exec bits from
 * dirs.txt, links.txt and exec.txt, since ZipInputStream creates none of them. The zip only
 * BOOTSTRAPS a phone with no root: once d/ has a dpkg database, dpkg owns it (packages agents
 * install must survive), and later bundles converge it instead (converge, packages).
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
        for (String rel : lines(new File(fresh, "dirs.txt"))) new File(fresh, rel).mkdirs();   // apt needs its empty dirs
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

    /** A root dpkg owns: from here on it is never replaced, only converged. */
    static boolean present(Context ctx) { return new File(ctx.getDataDir(), "d/var/lib/dpkg/status").exists(); }

    /**
     * Trough's own files in d/ (tools/root.sh OWNED: apt.conf, sources, wrappers, tfix, the
     * ldconfig note, GCC specs, glibc link names, merged-/usr links, CA bundles), from the signed
     * bundle's rootfiles/, each replaced by rename. Nothing dpkg owns is touched.
     */
    static String converge(Context ctx, File bundle) throws Exception {
        File src = new File(bundle, "rootfiles"), d = new File(ctx.getDataDir(), "d");
        if (!new File(src, "files").isDirectory()) return "bundle has no rootfiles";
        int files = copy(new File(src, "files"), d, "");
        int links = 0;
        for (String line : lines(new File(src, "rootfiles-links.txt"))) {
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            File l = new File(d, line.substring(0, tab));
            String target = line.substring(tab + 1);
            try { if (target.equals(Os.readlink(l.getPath()))) continue; } catch (Exception absent) {}
            l.getParentFile().mkdirs();
            File tmp = new File(l.getPath() + ".trough-new");
            tmp.delete();
            Os.symlink(target, tmp.getPath());
            Os.rename(tmp.getPath(), l.getPath());
            links++;
        }
        for (String rel : lines(new File(src, "rootfiles-exec.txt"))) new File(d, rel).setExecutable(true, false);
        return files + " files, " + links + " links updated";
    }

    private static int copy(File from, File d, String rel) throws Exception {
        File[] kids = from.listFiles();
        int n = 0;
        if (kids == null) return 0;
        for (File k : kids) {
            String r = rel.isEmpty() ? k.getName() : rel + "/" + k.getName();
            if (k.isDirectory()) { n += copy(k, d, r); continue; }
            File to = new File(d, r);
            byte[] want = Payloads.readSmall(k);
            if (to.isFile() && java.util.Arrays.equals(want, Payloads.readSmall(to))) continue;
            to.getParentFile().mkdirs();
            File tmp = new File(to.getPath() + ".trough-new");
            try (OutputStream o = new FileOutputStream(tmp)) { o.write(want); }
            if (!tmp.renameTo(to)) throw new IllegalStateException("rename " + to);
            n++;
        }
        return n;
    }

    /**
     * The bundle's package list, installed by the phone's own apt (verified, tfix after) when it
     * changed since the last success. Idempotent: what is installed stays; nothing is removed.
     */
    static JSONObject packages(Context ctx, File bundle) throws Exception {
        File list = new File(bundle, "rootfiles/packages.txt"), d = new File(ctx.getDataDir(), "d");
        JSONObject r = new JSONObject();
        if (!list.exists()) return r.put("skipped", "bundle has no package list");
        String want = new String(Payloads.readSmall(list), "UTF-8").trim();
        File mark = new File(d, ".trough-packages");
        if (mark.exists() && want.equals(new String(Payloads.readSmall(mark), "UTF-8").trim())) return r.put("current", true);
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(d + "/usr/local/bin/apt-get");
        cmd.add("install");
        cmd.add("-y");
        cmd.add("--no-install-recommends");
        for (String p : want.split("\n")) if (!p.trim().isEmpty()) cmd.add(p.trim());
        File w = new File(ctx.getCacheDir(), "rootpkgs");
        w.mkdirs();
        JSONObject update = SelfTest.exec(w, ctx, null, 600, d + "/usr/local/bin/apt-get", "update");
        JSONObject install = SelfTest.exec(w, ctx, null, 1800, cmd.toArray(new String[0]));
        r.put("update", update).put("install", install);
        if (install.getInt("exit") == 0) Payloads.writeSmall(mark, want);
        return r.put("ok", install.getInt("exit") == 0);
    }

    private static java.util.List<String> lines(File f) throws Exception {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (!f.exists()) return out;
        for (String s : new String(Payloads.readSmall(f), "UTF-8").split("\n")) if (!s.isEmpty()) out.add(s);
        return out;
    }
}
