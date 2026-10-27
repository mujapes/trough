package com.trough.hot;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import dalvik.system.DexClassLoader;

/**
 * Trough builds and reloads itself (DESIGN.md "Self-hosted reload"). It holds its own Java
 * source in files/src, compiles it with the root's javac + d8 (hot/build.sh, the same script
 * tools/hot.sh uses) and runs the result. The signed bundle is only the seed and the fallback:
 * its Main boots, seeds files/src when the bundle carries new source, builds, and hands off.
 *
 * Trust: files/src and files/self are app-private, so nothing outside Trough (and the
 * processes it runs) can write them. Builds are cached by the hash of the source tree plus the
 * build inputs, so an unchanged tree reloads without compiling.
 */
final class Self {
    private Self() {}

    static File src(Context ctx) { return new File(ctx.getFilesDir(), "src"); }

    /**
     * Makes files/src the bundle's source if the bundle's differs from what was last seeded.
     * Local edits made since the last seed are moved aside to src.<time>, never deleted.
     */
    static String seed(Context ctx, File bundle) throws Exception {
        File from = new File(bundle, "src"), to = src(ctx), mark = new File(ctx.getFilesDir(), "src.seed");
        if (!from.isDirectory()) return "bundle has no src";
        String want = treeSha(from);
        String had = mark.exists() ? new String(Payloads.readSmall(mark), "UTF-8").trim() : "";
        if (want.equals(had) && to.isDirectory()) return "current";
        String note = "seeded " + want.substring(0, 8);
        if (to.isDirectory()) {
            if (!treeSha(to).equals(had)) {
                File aside = new File(ctx.getFilesDir(), "src." + System.currentTimeMillis());
                if (!to.renameTo(aside)) throw new IllegalStateException("rename " + to);
                note += ", local edits kept in " + aside.getName();
            } else Main.deleteTree(to);
        }
        File tmp = new File(ctx.getFilesDir(), "src.new");
        Main.deleteTree(tmp);
        copyTree(from, tmp);
        if (!tmp.renameTo(to)) throw new IllegalStateException("rename " + to);
        Payloads.writeSmall(mark, want);
        return note;
    }

    static File builds(Context ctx) { return new File(ctx.getFilesDir(), "self"); }

    /** Builds src into <self>/<key>/classes.dex, or reuses it. Fills r with what happened. */
    static File build(Context ctx, File src, File bundle, JSONObject payloads, File self, JSONObject r) throws Exception {
        File script = new File(bundle, "build.sh");
        String aj = payloads.getString("android.jar"), d8 = payloads.getString("d8.jar");
        String key = sha((treeSha(src) + treeSha(script) + new File(aj).getName() + new File(d8).getName()).getBytes("UTF-8"));
        File out = new File(self, key);
        r.put("key", key.substring(0, 12));
        if (new File(out, "classes.dex").exists()) { r.put("cached", true); return out; }
        File tmp = new File(self, key + ".tmp");
        Main.deleteTree(tmp);
        tmp.mkdirs();
        File cache = new File(ctx.getCacheDir(), "selfbuild-" + key.substring(0, 12));
        Main.deleteTree(cache);
        cache.mkdirs();
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", script.getPath(), src.getPath(), tmp.getPath(), aj, d8)
            .redirectErrorStream(true).redirectInput(new File("/dev/null"));
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("HOME", new File(ctx.getFilesDir(), "home").getPath());
        env.put("TMPDIR", cache.getPath());
        env.put("PATH", new File(ctx.getDataDir(), "d/usr/bin").getPath() + ":/system/bin");
        long t0 = System.nanoTime();
        Process p = pb.start();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) log.write(b, 0, n);
        }
        int exit = p.waitFor();
        r.put("cached", false).put("build_ms", (System.nanoTime() - t0) / 1_000_000).put("exit", exit);
        String text = log.toString("UTF-8").trim();
        if (!text.isEmpty()) r.put("log", text.length() > 1500 ? text.substring(text.length() - 1500) : text);
        if (exit != 0 || !new File(tmp, "classes.dex").exists()) { Main.deleteTree(tmp); throw new IllegalStateException("self build failed (" + exit + ")"); }
        new File(tmp, "classes.dex").setReadOnly();   // ART refuses writable dex
        Main.deleteTree(cache);
        if (!tmp.renameTo(out)) throw new IllegalStateException("rename " + out);
        File[] old = self.listFiles();                 // keep this build and the one before it
        if (old != null && old.length > 2) {
            List<File> all = new ArrayList<>();
            Collections.addAll(all, old);
            Collections.sort(all, new java.util.Comparator<File>() {
                @Override public int compare(File x, File y) { return Long.compare(y.lastModified(), x.lastModified()); }
            });
            for (File f : all.subList(2, all.size())) if (!f.equals(out)) Main.deleteTree(f);
        }
        return out;
    }

    /** Loads a self-built dex beside, not under, the bundle: its classes replace the bundle's. */
    static Class<?> load(Context ctx, File built, File bundle, String name) throws Exception {
        ClassLoader apk = Self.class.getClassLoader().getParent();
        // optimizedDirectory is ignored since API 26: ART keeps the oat beside the dex.
        DexClassLoader loader = new DexClassLoader(new File(built, "classes.dex").getPath(), null,
            new File(bundle, "bin").getPath(), apk);
        return loader.loadClass(name);
    }

    static String treeSha(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        List<String> rel = new ArrayList<>();
        list(f, "", rel);
        Collections.sort(rel);
        byte[] b = new byte[1 << 16];
        for (String p : rel) {
            md.update(p.getBytes("UTF-8"));
            md.update((byte) 0);
            try (InputStream in = new FileInputStream(p.isEmpty() ? f : new File(f, p))) {
                for (int n; (n = in.read(b)) > 0; ) md.update(b, 0, n);
            }
            md.update((byte) 0);
        }
        return hex(md.digest());
    }

    private static void list(File f, String rel, List<String> out) {
        if (!f.isDirectory()) { out.add(rel); return; }
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) list(k, rel.isEmpty() ? k.getName() : rel + "/" + k.getName(), out);
    }

    private static void copyTree(File from, File to) throws Exception {
        if (from.isDirectory()) {
            to.mkdirs();
            File[] kids = from.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(to, k.getName()));
            return;
        }
        byte[] b = new byte[1 << 16];
        try (InputStream in = new FileInputStream(from); OutputStream o = new FileOutputStream(to)) {
            for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
        }
    }

    private static String sha(byte[] b) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(b)); }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
