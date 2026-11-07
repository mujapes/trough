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

    /**
     * Native code Trough built itself (Self.buildNative, already smoke-tested) replaces texec and
     * the exec mapper. The previous copies stay as x.prev / libexecmap.so.prev; and every boot
     * first installs the signed bundle's, so a bad self build cannot outlive a restart.
     */
    static void installNative(Context ctx, File built) throws Exception {
        if (guest()) return;   // the OS layer is the host's
        File root = os();
        File x = new File(root, "x"), prev = new File(root, "x.prev");
        File lib = new File(root, "l/libexecmap.so");
        if (x.isFile() && lib.isFile()
                && java.util.Arrays.equals(Payloads.readSmall(x), Payloads.readSmall(new File(built, "bin/texec")))
                && java.util.Arrays.equals(Payloads.readSmall(lib), Payloads.readSmall(new File(built, "lib/libexecmap.so")))) return;
        if (x.exists()) { prev.delete(); java.nio.file.Files.copy(x.toPath(), prev.toPath()); prev.setExecutable(true, false); }
        replace(new File(built, "bin/texec"), x, true);
        File lprev = new File(root, "l/libexecmap.so.prev");
        if (lib.exists()) { lprev.delete(); java.nio.file.Files.copy(lib.toPath(), lprev.toPath()); }
        replace(new File(built, "lib/libexecmap.so"), lib, false);
    }

    /** The OS layer (Debian root d/, texec x, glibc l/, bin/): fixed at /data/data/t.t, shared by guests. */
    static File os() { return new File(DIR); }

    /**
     * Running as a guest inside Trough (docs/LIFT.md)? A lifted bundle's shared storage is its guest
     * root (Lift's Environment shim), so it never installs or converges the OS layer it shares.
     */
    static boolean guest() {
        return android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            .getPath().contains("/trough/guests/");
    }

    static File install(Context ctx, File bundleDir) throws Exception {
        if (guest()) return os();   // a guest bundle must never replace (or downgrade) the OS layer
        File root = ctx.getDataDir();  // /data/user/0/t.t, which /data/data/t.t reaches
        // /data/data is a bind mount of /data/user/0, not a symlink, so canonical paths differ
        // for the same directory (phone, 10-27). Prove identity with a marker file instead.
        String token = Long.toString(System.nanoTime());
        writeText(new File(root, ".same"), token);
        File via = new File(DIR, ".same");
        if (!via.exists() || !token.equals(new String(Payloads.readSmall(via), "UTF-8")))
            throw new IllegalStateException("data dir " + root + " is not reachable as " + DIR + " (package must be t.t)");
        replace(new File(bundleDir, "bin/texec"), new File(root, "x"), true);
        File hostexec = new File(bundleDir, "bin/hostexec");
        if (hostexec.exists()) replace(hostexec, new File(root, "hostexec"), true);
        File lib = new File(root, "l");
        lib.mkdirs();
        File[] libs = new File(bundleDir, "lib").listFiles();
        if (libs != null) for (File f : libs) replace(f, new File(lib, f.getName()), false);
        String resolv = "nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 2606:4700:4700::1111\n";
        writeText(new File(root, "r"), resolv);
        // Virtual FHS (docs/FILESYSTEM.md): Trough's glibc maps /tmp, /etc, ... into d/. The
        // directories Debian's base-files would create, world-writable ones sticky as on Linux.
        File d = new File(root, "d");
        if (new File(d, "etc").isDirectory()) {
            for (String dir : new String[]{"tmp", "var/tmp", "dev/shm", "run/lock"}) {
                File f = new File(d, dir);
                f.mkdirs();
                try { android.system.Os.chmod(f.getPath(), 01777); } catch (Exception e) { f.setWritable(true, false); }
            }
            writeText(new File(d, "etc/resolv.conf"), resolv);
        }
        return root;
    }

    /**
     * The userland environment every process Trough starts gets: the Debian root d/ first on
     * PATH, and the knobs its stock tools offer for their compiled-in /usr and /etc paths
     * (git's exec path and templates, the CA bundle). Clean: nothing inherited from the app process.
     */
    static java.util.Map<String, String> env(Context ctx, File tmp) {
        java.util.Map<String, String> e = new java.util.HashMap<>();
        String d = new File(os(), "d").getPath();
        e.put("HOME", new File(ctx.getFilesDir(), "home").getPath());
        e.put("TMPDIR", tmp.getPath());   // Android has no /tmp
        boolean utf8Locale=new File(d,"usr/lib/locale/C.utf8/LC_CTYPE").isFile();
        e.put("LANG",utf8Locale?"C.UTF-8":"C");e.put("LC_ALL",utf8Locale?"C.UTF-8":"C");
        e.put("PYTHONUTF8","1");
        e.put("SHELL", d + "/usr/bin/bash");   // agents' shell tools expect bash
        e.put("SSL_CERT_FILE", d + "/etc/ssl/certs/ca-certificates.crt");
        e.put("PATH", d + "/usr/local/bin:" + d + "/usr/bin:" + d + "/usr/sbin:" + new File(os(), "bin").getPath() + ":/system/bin");
        e.put("APT_CONFIG", d + "/etc/apt/apt.conf");   // apt rooted in d/ (tools/root.sh)
        e.put("GIT_EXEC_PATH", d + "/usr/lib/git-core");
        e.put("GIT_TEMPLATE_DIR", d + "/usr/share/git-core/templates");
        e.put("GIT_CONFIG_NOSYSTEM", "1");
        e.put("GIT_SSL_CAINFO", d + "/etc/ssl/certs/ca-certificates.crt");
        return e;
    }

    /**
     * Java starts programs through Android's libc, which knows nothing of the virtual FHS: a root
     * script saying #!/bin/bash would fail with ENOENT (Android has no /bin/bash; apktool, 10-30).
     * Resolve a virtual #! interpreter into d/, as Trough's glibc does for glibc callers
     * (docs/FILESYSTEM.md "Exec in the mapper"): interp [optarg] script args.
     */
    static String[] command(Context ctx, String... cmd) {
        if (cmd.length == 0) return cmd;
        String d = new File(os(), "d").getPath();
        byte[] head = new byte[256];
        int n;
        try (InputStream in = new FileInputStream(cmd[0])) { n = in.read(head); } catch (Exception e) { return cmd; }
        if (n < 3 || head[0] != '#' || head[1] != '!') return cmd;
        String line = new String(head, 2, n - 2, java.nio.charset.StandardCharsets.UTF_8);
        int nl = line.indexOf('\n');
        if (nl >= 0) line = line.substring(0, nl);
        line = line.trim();
        if (line.isEmpty() || line.charAt(0) != '/') return cmd;
        int sp = 0;
        while (sp < line.length() && line.charAt(sp) != ' ' && line.charAt(sp) != '\t') sp++;
        String interp = line.substring(0, sp), arg = line.substring(sp).trim();
        for (String host : new String[]{"/data/", "/system/", "/apex/", "/vendor/", "/product/", "/odm/"})
            if (interp.startsWith(host)) return cmd;   // a real host path: the kernel finds it
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add(d + interp);
        if (!arg.isEmpty()) out.add(arg);
        out.addAll(java.util.Arrays.asList(cmd));
        return out.toArray(new String[0]);
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
