package com.trough.hot;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
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
     * files/src is a git repo. The bundle's source is committed on branch "bundle" (straight
     * from the bundle dir, through a separate index, never touching the work tree) and merged
     * into "main", where the agent's edits live, so both kinds of change survive. If the merge
     * conflicts, main is saved as agent-<time> and reset to the bundle: nothing is ever lost.
     */
    static String seed(Context ctx, File bundle) throws Exception {
        File from = new File(bundle, "src"), repo = src(ctx);
        if (!from.isDirectory()) return "bundle has no src";
        // Before files/src was a repo, src.seed held the hash of the last seeded tree.
        File mark = new File(ctx.getFilesDir(), "src.seed");
        if (mark.exists() && !new File(repo, ".git").isDirectory()
                && treeSha(repo).equals(new String(Payloads.readSmall(mark), "UTF-8").trim())) Main.deleteTree(repo);
        mark.delete();
        return seed(ctx, from, repo);
    }

    static String seed(Context ctx, File from, File repo) throws Exception {
        repo.mkdirs();
        if (!new File(repo, ".git").isDirectory()) {
            git(ctx, repo, null, "init", "-q", "-b", "main");
            if (!git(ctx, repo, null, "status", "--porcelain").isEmpty()) {   // a pre-git files/src
                git(ctx, repo, null, "add", "-A");
                git(ctx, repo, null, "commit", "-q", "-m", "agent edits from before files/src was a repo");
            }
        }
        File index = new File(repo.getParentFile(), repo.getName() + ".bundle-index");
        index.delete();
        String tree;
        try {
            git(ctx, from, index, "--git-dir=" + new File(repo, ".git"), "add", "-A", ".");
            tree = git(ctx, from, index, "--git-dir=" + new File(repo, ".git"), "write-tree");
        } finally { index.delete(); }
        String old = gitOr(ctx, repo, "rev-parse", "-q", "--verify", "bundle^{tree}");
        String head = gitOr(ctx, repo, "rev-parse", "-q", "--verify", "main");
        if (tree.equals(old) && !head.isEmpty()) return "current";
        String parent = gitOr(ctx, repo, "rev-parse", "-q", "--verify", "bundle");
        String commit = parent.isEmpty()
            ? git(ctx, repo, null, "commit-tree", tree, "-m", "bundle")
            : git(ctx, repo, null, "commit-tree", tree, "-p", parent, "-m", "bundle");
        git(ctx, repo, null, "update-ref", "refs/heads/bundle", commit);
        if (head.isEmpty()) {
            git(ctx, repo, null, "reset", "-q", "--hard", "bundle");
            return "seeded " + commit.substring(0, 8);
        }
        commitEdits(ctx, repo, "agent edits");
        try {
            git(ctx, repo, null, "merge", "-q", "--no-edit", "--allow-unrelated-histories", "-m", "merge bundle", "bundle");
            return "merged bundle " + commit.substring(0, 8);
        } catch (IllegalStateException conflict) {
            gitOr(ctx, repo, "merge", "--abort");
            String keep = "agent-" + System.currentTimeMillis();
            git(ctx, repo, null, "branch", keep, "main");
            git(ctx, repo, null, "reset", "-q", "--hard", "bundle");
            return "bundle " + commit.substring(0, 8) + " conflicted with local edits; they are on branch " + keep;
        }
    }

    /** Commits whatever the agent changed in files/src; no-op when clean. */
    static void commitEdits(Context ctx, File repo, String message) throws Exception {
        if (!new File(repo, ".git").isDirectory() || git(ctx, repo, null, "status", "--porcelain").isEmpty()) return;
        git(ctx, repo, null, "add", "-A");
        git(ctx, repo, null, "commit", "-q", "-m", message);
    }

    /** Runs the root's git; returns trimmed stdout, throws IllegalStateException on failure. */
    static String git(Context ctx, File cwd, File index, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = new File(ctx.getDataDir(), "d/usr/bin/git").getPath();
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd).redirectInput(new File("/dev/null"));
        Map<String, String> env = pb.environment();
        env.clear();
        env.putAll(Root.env(ctx, ctx.getCacheDir()));
        env.put("GIT_AUTHOR_NAME", "Trough");
        env.put("GIT_AUTHOR_EMAIL", "trough@t.t");
        env.put("GIT_COMMITTER_NAME", "Trough");
        env.put("GIT_COMMITTER_EMAIL", "trough@t.t");
        if (index != null) { env.put("GIT_INDEX_FILE", index.getPath()); env.put("GIT_WORK_TREE", cwd.getPath()); }
        Process p = pb.start();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final InputStream es = p.getErrorStream();
        Thread drain = new Thread(new Runnable() { @Override public void run() {
            byte[] b = new byte[4096];
            try { for (int n; (n = es.read(b)) > 0; ) err.write(b, 0, n); } catch (Exception ignored) {}
        } });
        drain.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        }
        int exit = p.waitFor();
        drain.join();
        if (exit != 0) throw new IllegalStateException("git " + args[0] + " (" + exit + "): " + err.toString("UTF-8").trim());
        return out.toString("UTF-8").trim();
    }

    private static String gitOr(Context ctx, File cwd, String... args) {
        try { return git(ctx, cwd, null, args); } catch (Exception e) { return ""; }
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
        env.putAll(Root.env(ctx, cache));
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

    static final String[] NATIVE = {"bin/bench", "bin/probe", "bin/texec", "bin/threadtest", "bin/dnstest", "bin/nsprobe", "lib/libexecmap.so"};

    /**
     * Trough's native code from files/src/native (native/build.sh with the root's gcc, then tfix),
     * into files/self-native/<key>, cached by source hash. Smoke-tested before it counts as
     * built: the new texec as a launcher runs a stock program and the threaded test, and the new
     * preload library loads into a shell. Returns the dir, or null if src has no native/.
     */
    static File buildNative(Context ctx, File src, JSONObject r) throws Exception {
        File nat = new File(src, "native");
        if (!new File(nat, "build.sh").isFile()) return null;
        String key = treeSha(nat).substring(0, 16);
        File root = new File(ctx.getFilesDir(), "self-native"), out = new File(root, key);
        r.put("key", key);
        if (new File(out, ".ok").exists()) { r.put("cached", true); return out; }
        File tmp = new File(root, key + ".tmp");
        Main.deleteTree(tmp);
        tmp.mkdirs();
        File cwd = ctx.getCacheDir();
        String d = new File(ctx.getDataDir(), "d").getPath();
        long t0 = System.nanoTime();
        JSONObject b = SelfTest.exec(cwd, ctx, null, 600, "/system/bin/sh", new File(nat, "build.sh").getPath(), nat.getPath(), tmp.getPath());
        r.put("build", b).put("build_ms", (System.nanoTime() - t0) / 1_000_000);
        if (b.getInt("exit") != 0) { Main.deleteTree(tmp); throw new IllegalStateException("native build failed"); }
        SelfTest.exec(cwd, ctx, null, 60, d + "/usr/libexec/trough/tfix", tmp.getPath());
        String texec = new File(tmp, "bin/texec").getPath();
        JSONObject s1 = SelfTest.exec(cwd, ctx, null, 60, texec, d + "/usr/bin/true");
        JSONObject s2 = SelfTest.exec(cwd, ctx, null, 60, texec, new File(tmp, "bin/threadtest").getPath());
        java.util.Map<String, String> pre = new java.util.HashMap<>();
        pre.put("LD_PRELOAD", new File(tmp, "lib/libexecmap.so").getPath());
        JSONObject s3 = SelfTest.exec(cwd, ctx, pre, 60, d + "/usr/bin/sh", "-c", "echo execmap-ok");
        boolean ok = s1.getInt("exit") == 0 && s2.getString("out").contains("\"ok\":true")
            && s3.getInt("exit") == 0 && s3.getString("out").contains("execmap-ok") && !s3.getString("out").contains("cannot be preloaded");
        r.put("smoke", new JSONObject().put("texec_true", s1.getInt("exit")).put("texec_threadtest", s2.getString("out").trim())
            .put("execmap_shell", s3.getString("out").trim()).put("ok", ok));
        if (!ok) { Main.deleteTree(tmp); throw new IllegalStateException("native smoke test failed"); }
        Payloads.writeSmall(new File(tmp, ".ok"), "smoke-tested");
        Main.deleteTree(out);
        if (!tmp.renameTo(out)) throw new IllegalStateException("rename " + out);
        return out;
    }

    /** True when every native output of built equals the bundle's: nothing to install. */
    static boolean sameAsBundle(File built, File bundle) throws Exception {
        for (String f : NATIVE) {
            File a = new File(built, f), b = new File(bundle, f);
            if (!a.isFile() || !b.isFile() || !java.util.Arrays.equals(Payloads.readSmall(a), Payloads.readSmall(b))) return false;
        }
        return true;
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
        if (kids != null) for (File k : kids) if (!(rel.isEmpty() && k.getName().equals(".git"))) list(k, rel.isEmpty() ? k.getName() : rel + "/" + k.getName(), out);
    }

    private static String sha(byte[] b) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(b)); }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
