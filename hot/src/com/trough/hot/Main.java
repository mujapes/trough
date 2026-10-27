package com.trough.hot;

import android.app.Activity;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Bundle entry point (contract: app/.../HotLoader.java). Two halves:
 *   start  -- the boot, always the signed bundle's own copy: installs the root and payloads,
 *             seeds files/src, builds it with Trough's own toolchain (Self) and hands off.
 *   run    -- Trough itself, normally the copy Trough just built: its own UI (Chat) over an
 *             agent it runs directly (Agent: claude, codex, ...). No daemon, no server, no WebView.
 * If the self build fails, the boot runs the bundle's own copy of run, so Trough always comes up.
 */
public final class Main {
    private static Chat chat;
    private static Development development;

    // Boot state (signed-bundle copy only): what is running now, so it can be stopped or replaced.
    private static Method runningStop;
    // A string literal is interned once per VM, so this is the same object in every bundle's and
    // every self build's class loader: a lock that spans them.
    private static final Object BOOT_LOCK = "com.trough.hot.boot";
    private static Activity bootActivity;
    private static FrameLayout bootRoot;
    private static File bootDir;
    private static JSONObject bootPayloads;

    public static void start(final Activity activity, final FrameLayout root, final File dir) {
        bootActivity = activity;
        bootRoot = root;
        bootDir = dir;
        final TextView wait = new TextView(activity);
        wait.setText("Trough is starting");
        wait.setPadding(48, 96, 48, 48);
        root.addView(wait);
        new Thread(new Runnable() {
            @Override public void run() {
                final JSONObject boot = new JSONObject();
                Class<?> self = null;
                // A newer bundle can arrive while an older boot still installs; never two at once.
                synchronized (BOOT_LOCK) { try {
                    step(boot, "root");
                    Root.install(activity, dir);      // texec, patched glibc, resolv.conf
                    step(boot, "payloads");
                    // The root zip only bootstraps: once dpkg owns d/, it is never fetched again.
                    bootPayloads = Payloads.ensure(activity, dir, rootSkip(activity));
                    if (bootPayloads.has("root")) {
                        long t = System.nanoTime();
                        boot.put("root_install", DebRoot.install(activity, bootPayloads.getString("root")));
                        boot.put("root_install_ms", (System.nanoTime() - t) / 1_000_000);
                    } else if (DebRoot.present(activity)) boot.put("root_install", "kept: dpkg owns d/");
                    if (DebRoot.present(activity)) boot.put("root_files", DebRoot.converge(activity, dir));
                    step(boot, "seed");
                    boot.put("seed", Self.seed(activity, dir));
                    step(boot, "self_build");
                    JSONObject b = new JSONObject();
                    boot.put("self_build", b);
                    File built = Self.build(activity, Self.src(activity), dir, bootPayloads, Self.builds(activity), b);
                    self = Self.load(activity, built, dir, Main.class.getName());
                    runningKey = built.getName();
                    if (DebRoot.present(activity)) {
                        step(boot, "self_native");
                        JSONObject n = new JSONObject();
                        boot.put("self_native", n);
                        try { native_(activity, dir, n); } catch (Throwable e) { n.put("error", String.valueOf(e)); }
                    }
                    boot.put("running", "self " + built.getName().substring(0, 12));
                } catch (Throwable e) {
                    put(boot, "self_error", String.valueOf(e));
                    put(boot, "running", "bundle");
                } }
                step(boot, "enter");
                final Class<?> entry = self != null ? self : Main.class;
                activity.runOnUiThread(new Runnable() { @Override public void run() {
                    root.removeAllViews();
                    enter(entry, boot);
                } });
            }
        }, "trough-boot").start();
    }

    /**
     * Boot side of reload: called when the running agent's turn ends and by /reload. Rebuilds
     * files/src; swaps only if the build differs from what runs. On failure the old build keeps
     * running and the build log lands in work/.trough/build.log, where the agent can read it.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean reloading = new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile String runningKey = "";

    private static final Runnable RELOAD = new Runnable() {
        @Override public void run() {
            if (bootPayloads == null || !reloading.compareAndSet(false, true)) return;
            new Thread(new Runnable() {
                @Override public void run() {
                    final JSONObject boot = new JSONObject();
                    final JSONObject b = new JSONObject();
                    File log = new File(work(bootActivity), ".trough/build.log");
                    try {
                        boot.put("self_build", b).put("reload", true);
                        step(boot, "reload_build");
                        final File built = Self.build(bootActivity, Self.src(bootActivity), bootDir, bootPayloads, Self.builds(bootActivity), b);
                        log.delete();
                        if (DebRoot.present(bootActivity)) {   // native edits rebuild and install too
                            JSONObject n = new JSONObject();
                            boot.put("self_native", n);
                            try { native_(bootActivity, bootDir, n); } catch (Throwable ne) { n.put("error", String.valueOf(ne)); }
                        }
                        if (built.getName().equals(runningKey)) { reloading.set(false); status(boot); return; }
                        Self.commitEdits(bootActivity, Self.src(bootActivity), "agent edits (built " + built.getName().substring(0, 12) + ")");
                        final Class<?> entry = Self.load(bootActivity, built, bootDir, Main.class.getName());
                        boot.put("running", "self " + built.getName().substring(0, 12));
                        bootActivity.runOnUiThread(new Runnable() { @Override public void run() {
                            stopRunning();
                            bootRoot.removeAllViews();
                            runningKey = built.getName();
                            enter(entry, boot);
                            reloading.set(false);
                        } });
                    } catch (final Throwable e) {
                        put(boot, "reload_error", String.valueOf(e));
                        status(boot);
                        try {
                            log.getParentFile().mkdirs();
                            Payloads.writeSmall(log, e + "\n" + b.optString("log") + "\n");
                        } catch (Exception ignored) {}
                        reloading.set(false);
                        bootActivity.runOnUiThread(new Runnable() { @Override public void run() {
                            Toast.makeText(bootActivity, "Trough build failed, still running the old one (work/.trough/build.log)", Toast.LENGTH_LONG).show();
                        } });
                    }
                }
            }, "trough-reload").start();
        }
    };

    /** Native code from files/src/native: built, smoke-tested, installed only if it differs. */
    private static void native_(Activity activity, File dir, JSONObject n) throws Exception {
        File nb = Self.buildNative(activity, Self.src(activity), n);
        if (nb == null) { n.put("installed", "no native sources"); return; }
        if (Self.sameAsBundle(nb, dir)) { n.put("installed", "bundle's (identical)"); return; }
        Root.installNative(activity, nb);
        n.put("installed", "self-built " + nb.getName());
    }

    private static void enter(Class<?> entry, JSONObject boot) {
        try {
            entry.getMethod("run", Activity.class, FrameLayout.class, File.class, JSONObject.class, Runnable.class)
                .invoke(null, bootActivity, bootRoot, bootDir, boot, RELOAD);
            runningStop = entry.getMethod("stop");
        } catch (Throwable e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            put(boot, "enter_error", String.valueOf(cause));
            if (entry != Main.class) { bootRoot.removeAllViews(); enter(Main.class, boot); }
            else status(boot);
        }
    }

    private static void stopRunning() {
        Method m = runningStop;
        runningStop = null;   // first: when run is the boot's own copy, its stop lands back here
        if (m != null) try { m.invoke(null); } catch (Throwable ignored) {}
    }

    /**
     * Trough's own screen as a PNG in Download/trough/shot-<ms>.png: the view tree drawn into a
     * bitmap by the app itself, so the VM can see the UI with no adb (golden screenshots next).
     */
    static void screenshot(final Activity activity, final android.view.View v, final String tag) {
        try {
            int w = v.getWidth(), h = v.getHeight();
            if (w <= 0 || h <= 0) return;
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            v.draw(new android.graphics.Canvas(bm));
            File f = new File(new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "trough"), "shot-" + System.currentTimeMillis() + "-" + tag + ".png");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o); }
            bm.recycle();
        } catch (Throwable ignored) {}
    }

    /** Trough itself. Entered by the boot, on the UI thread. */
    public static void run(final Activity activity, final FrameLayout root, final File dir, final JSONObject boot, Runnable reload) {
        final File home = new File(activity.getFilesDir(), "home");
        home.mkdirs();
        chat = new Chat(activity, root, home, reload);
        final Chat c = chat;
        JSONObject build = boot.optJSONObject("self_build");
        String identity = build == null || !boot.optString("running").startsWith("self ") ? "bundle" : build.optString("identity", "bundle");
        development = new Development(activity, root, dir, identity);
        if (!boot.optBoolean("reload")) root.postDelayed(new Runnable() { @Override public void run() { screenshot(activity, root, "boot"); } }, 4000);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject payloads = Payloads.ensure(activity, dir, rootSkip(activity));
                    Creds.sync(activity, home);
                    File work = work(activity);
                    agentNotes(activity, work);
                    Map<String, String> env = Root.env(activity, activity.getCacheDir());
                    env.put("CODEX_HOME", new File(home, ".codex").getPath());
                    new File(home, ".codex").mkdirs();
                    Map<String, String> agents = new java.util.LinkedHashMap<>();
                    for (String name : new String[]{"claude", "codex"}) if (payloads.has(name)) agents.put(name, payloads.getString(name));
                    for (String name : Models.names(home)) if (!agents.containsKey(name)) agents.put(name, Models.MARK);
                    c.start(agents, work, env);
                    if (boot.optBoolean("reload")) { status(boot); return; }
                    // Self-hosting proofs, reported to the VM: the toolchain builds C and Java here,
                    // and an edited copy of Trough's own source builds and loads.
                    if (DebRoot.present(activity)) {
                        step(boot, "root_packages");
                        try { boot.put("root_packages", DebRoot.packages(activity, dir)); } catch (Throwable e) { boot.put("root_packages_error", String.valueOf(e)); }
                        // Expensive/networked regression suites are explicit development work,
                        // not competition for foreground interaction after every code change.
                        boolean diagnostics = new JSONObject(new String(Payloads.readSmall(
                            new File(dir, "manifest.json")), "UTF-8")).optBoolean("diagnostics", false);
                        boot.put("diagnostics", diagnostics);
                        if (!diagnostics) { step(boot, "done"); return; }
                        step(boot, "selftest");
                        try { boot.put("selftest", SelfTest.run(activity)); } catch (Throwable e) { boot.put("selftest_error", String.valueOf(e)); }
                        step(boot, "native_repro");
                        try { boot.put("native_repro", SelfTest.nativeRepro(activity, dir)); } catch (Throwable e) { boot.put("native_repro_error", String.valueOf(e)); }
                        step(boot, "native_edit");
                        try { boot.put("native_edit", SelfTest.nativeEdit(activity, dir)); } catch (Throwable e) { boot.put("native_edit_error", String.valueOf(e)); }
                        step(boot, "self_edit");
                        try { boot.put("self_edit", SelfTest.selfEdit(activity, dir, payloads)); } catch (Throwable e) { boot.put("self_edit_error", String.valueOf(e)); }
                        step(boot, "probe");
                        try { boot.put("probe", SelfTest.probe(activity, dir)); } catch (Throwable e) { boot.put("probe_error", String.valueOf(e)); }
                        step(boot, "providers");
                        try { boot.put("providers", SelfTest.providers()); } catch (Throwable e) { boot.put("providers_error", String.valueOf(e)); }
                        step(boot, "apt");
                        try { boot.put("apt", SelfTest.apt(activity)); } catch (Throwable e) { boot.put("apt_error", String.valueOf(e)); }
                        step(boot, "seed_merge");
                        try { boot.put("seed_merge", SelfTest.seedMerge(activity)); } catch (Throwable e) { boot.put("seed_merge_error", String.valueOf(e)); }
                        step(boot, "namespaces");
                        try { boot.put("namespaces", SelfTest.namespaces(activity, dir)); } catch (Throwable e) { boot.put("namespaces_error", String.valueOf(e)); }
                        if (payloads.has("codex")) {
                            step(boot, "codex");
                            try { boot.put("codex", SelfTest.codex(activity, payloads)); } catch (Throwable e) { boot.put("codex_error", String.valueOf(e)); }
                        }
                        step(boot, "done");
                        return;
                    }
                    status(boot);
                } catch (final Throwable e) {
                    activity.runOnUiThread(new Runnable() { @Override public void run() { c.onExit(-1, String.valueOf(e)); } });
                }
            }
        }, "trough-start").start();
    }

    private static java.util.Set<String> rootSkip(android.content.Context ctx) {
        java.util.Set<String> s = new java.util.HashSet<>();
        if (DebRoot.present(ctx)) s.add("root");
        return s;
    }

    static File work(android.content.Context ctx) {
        File w = new File(ctx.getFilesDir(), "work");
        w.mkdirs();
        return w;
    }

    private static final String NOTES_MARK = "<!-- written by Trough; delete this line to keep your own -->";

    /**
     * What the agent running inside Trough needs to know to change Trough. Agent-neutral
     * (AGENTS.md; CLAUDE.md points at it), since Trough runs arbitrary models, not just Claude.
     * Rewritten each start unless someone removed the mark line.
     */
    private static void agentNotes(android.content.Context ctx, File work) throws Exception {
        String src = Self.src(ctx).getPath();
        String notes = NOTES_MARK + "\n# You are running inside Trough\n\n"
            + "Trough is the Android app you are running in. Its own source is at " + src + " (Java,\n"
            + "package com.trough.hot; entry Main.run). Edit it there to change Trough itself.\n\n"
            + "- When your turn ends, Trough rebuilds that source with its own javac + d8 and, if it\n"
            + "  changed, reloads into the new build. Your session resumes after the reload.\n"
            + "- If the build fails, the old build keeps running and the error is in .trough/build.log\n"
            + "  (relative to this directory). Fix it and end your turn again.\n"
            + "- It is a git repo: branch main is yours (Trough commits your edits when it reloads),\n"
            + "  branch bundle is what arrived in signed bundles; bundles are merged into main, and a\n"
            + "  conflicting edit is kept on an agent-* branch. git and the toolchain are on PATH.\n"
            + "- Java 8 source against android.jar, no lambdas (no LambdaMetafactory there).\n"
            + "- Native views only: no WebView, no HTML, no local servers.\n\n"
            + "## Your userland\n"
            + "Debian trixie arm64, running natively from /data/data/t.t/d (no VM, no root). bash, git,\n"
            + "gcc 14, JDK 21 and the Debian essentials are on PATH. `apt-get install <pkg>` works: real\n"
            + "Debian packages, signatures verified, installed into the root (you are not root and need\n"
            + "no sudo). Android has no /bin, /usr, /etc or /tmp: use $TMPDIR, and paths under the root.\n";
        for (String[] f : new String[][]{{"AGENTS.md", notes}, {"CLAUDE.md", NOTES_MARK + "\n@AGENTS.md\n"}}) {
            File file = new File(work, f[0]);
            if (file.exists() && !new String(Payloads.readSmall(file), "UTF-8").startsWith(NOTES_MARK)) continue;
            Payloads.writeSmall(file, f[1]);
        }
    }

    static File statusFile() {
        return new File(new File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS), "trough"), "status-" + System.currentTimeMillis() + ".json");
    }

    /** Writes r to its own report file (r.report, set on first write), replacing what was there. */
    static void status(JSONObject r) {
        try {
            if (!r.has("report")) r.put("report", statusFile().getPath());
            File f = new File(r.getString("report")), tmp = new File(f.getPath() + ".tmp");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(tmp)) { o.write(r.toString(2).getBytes("UTF-8")); }
            tmp.renameTo(f);
        } catch (Exception ignored) {}
    }

    /** Marks the step about to run and rewrites the report: a hang still shows how far it got. */
    private static void step(JSONObject r, String next) {
        try { r.put("step", next).put("step_at_ms", System.currentTimeMillis()); } catch (Exception ignored) {}
        status(r);
    }

    private static void put(JSONObject o, String k, String v) { try { o.put(k, v); } catch (Exception ignored) {} }

    static void deleteTree(File f) {
        File[] kids = f.isDirectory() && !isLink(f) ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private static boolean isLink(File f) {
        try { return android.system.Os.lstat(f.getPath()).st_mode / 4096 == 10; } catch (Exception e) { return false; }
    }

    /** Called by HotLoader on the boot copy (forwards to what runs), and by the boot on run's copy. */
    public static void stop() {
        if (runningStop != null) { stopRunning(); return; }
        if (development != null) development.close();
        development = null;
        if (chat != null) chat.close();
        chat = null;
    }
}
