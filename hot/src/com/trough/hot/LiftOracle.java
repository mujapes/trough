package com.trough.hot;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;

/**
 * docs/LIFT.md step 2 (scenario lift.slot): Trough's own APK lifted by the root's apktool (baksmali
 * -> smali), then the LIFTED code runs in slot process :p0. Its HotLoader, loaded from the rebuilt
 * dex under the boot class path only (no host class can leak in), verifies the running bundle's
 * signature and rejects a one-byte flip, the same as the host's own HotLoader. The real loader
 * then boots under GuestContext; lift.guest also activates its lifted bundle (step 4).
 */
final class LiftOracle {
    private LiftOracle() {}

    static Map<String, Object> run(Context ctx) throws Exception {
        return run(ctx, false);
    }

    static Map<String, Object> run(Context ctx, boolean activate) throws Exception {
        Map<String, Object> r = new LinkedHashMap<>();
        android.content.ContentProviderClient old = ctx.getContentResolver().acquireUnstableContentProviderClient("t.t.slot0");
        try { if (old != null) old.call("lift.guest.stop", null, null); }
        finally { if (old != null) old.close(); }
        File d = new File(ctx.getDataDir(), "d"), w = new File(ctx.getCacheDir(), "lift");
        SelfTest.exec(ctx.getCacheDir(), ctx, null, 60, "/system/bin/rm", "-rf", w.getPath());
        w.mkdirs();
        String apk = ctx.getPackageCodePath();
        long t0 = System.nanoTime();
        JSONObject dec = SelfTest.exec(w, ctx, null, 600, d + "/usr/bin/apktool", "d", "-r", "-f", "-o", "dec", apk);
        int rewritten = dec.getInt("exit") == 0 ? Lift.rewrite(new File(w, "dec/smali")) : -1;
        r.put("rewritten_call_sites", rewritten);
        JSONObject build = SelfTest.exec(w, ctx, null, 600, d + "/usr/bin/apktool", "b", "dec", "-o", "rt.apk");
        r.put("lift_ms", (System.nanoTime() - t0) / 1_000_000);
        r.put("decode_exit", dec.getInt("exit"));
        r.put("build_exit", build.getInt("exit"));
        if (dec.getInt("exit") != 0 || build.getInt("exit") != 0) {
            r.put("ok", false);
            r.put("log", tail(dec.optString("out") + build.optString("out")));
            return r;
        }
        File dex = new File(w, "rt.dex");
        try (ZipFile z = new ZipFile(new File(w, "rt.apk")); InputStream in = z.getInputStream(z.getEntry("classes.dex"));
             FileOutputStream o = new FileOutputStream(dex)) {
            byte[] b = new byte[1 << 16];
            for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
        }
        dex.setReadOnly();   // ART refuses writable dex files for DexClassLoader (Android 14+)
        File shared = new File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS), "trough/hot");
        // The running bundle, lifted for the guest and signed with Trough's own key; the guest trusts
        // that key (provisioned into its files/hot/trusted before its HotLoader is constructed).
        File hz = new File(new File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS), "trough"), "hot/bundle.zip");
        File bw = new File(w, "bundle"); bw.mkdirs();
        File bdex = new File(bw, "classes.dex"), blifted = new File(bw, "lifted.dex");
        try (ZipFile z = new ZipFile(hz); InputStream bi = z.getInputStream(z.getEntry("classes.dex")); FileOutputStream o = new FileOutputStream(bdex)) {
            byte[] b = new byte[1 << 16]; for (int n; (n = bi.read(b)) > 0; ) o.write(b, 0, n);
        }
        long tb = System.nanoTime();
        int bsites = Lift.liftDex(ctx, bdex, blifted, bw, r);
        r.put("bundle_lift_ms", (System.nanoTime() - tb) / 1_000_000);
        r.put("bundle_rewritten_call_sites", bsites);
        java.security.KeyPair key = Lift.key(ctx);
        File gz = new File(bw, "guest-bundle.zip"), gsig = new File(bw, "guest-bundle.sig");
        if (bsites >= 0) Lift.repack(hz, blifted, gz, gsig, key);
        File trusted = new File(ctx.getFilesDir(), "guests/t.t/files/hot/trusted");
        trusted.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(trusted, "trough-lift.der"))) { o.write(key.getPublic().getEncoded()); }
        Bundle in = new Bundle();
        in.putString("dex", dex.getPath());
        in.putString("zip", new File(shared, "bundle.zip").getPath());
        in.putString("sig", new File(shared, "bundle.sig").getPath());
        // UNSTABLE client: with a stable one (ContentResolver.call), Android kills the caller when the
        // provider's process dies, and a slot dying in lifted code took Trough's main process with it
        // ("Trough keeps stopping", 10-30). Now the slot dying only fails this scenario.
        File steps = new File(w, "slot-steps.txt");
        String hostHotBefore = listing(new File(ctx.getFilesDir(), "hot"));
        in.putString("steps", steps.getPath());
        Bundle out = null;
        android.content.ContentProviderClient c = ctx.getContentResolver().acquireUnstableContentProviderClient("t.t.slot0");
        try {
            // Through query, not call: the installed host's SlotProvider.call recurses into itself
            // (Slot.java, fixed for the next APK). query forwards correctly: rows of (key, value).
            if (c != null) {
                android.database.Cursor q = c.query(Uri.parse("content://t.t.slot0/lift.verify"), null, null,
                    new String[]{in.getString("dex"), in.getString("zip"), in.getString("sig"), in.getString("steps"),
                                 new File(w, "rt.apk").getPath(), gz.getPath(), gsig.getPath(), String.valueOf(activate)}, null);
                if (q != null) {
                    out = new Bundle();
                    while (q.moveToNext()) out.putString(q.getString(0), q.getString(1));
                    q.close();
                }
            }
        } catch (android.os.DeadObjectException e) {
            r.put("error", "slot :p0 died");
        } finally {
            if (c != null) c.close();
        }
        if (steps.exists()) r.put("slot_steps", tail(new String(read(steps), "UTF-8")));
        if (out == null) {
            r.put("slot_exit", exits(ctx));
            // An app may read its own uid's log: the slot's FATAL EXCEPTION is there.
            try {
                Process lp = new ProcessBuilder("/system/bin/logcat", "-d", "-t", "400", "-v", "brief", "AndroidRuntime:E", "trough:*", "*:S")
                    .redirectErrorStream(true).start();
                String log = new String(readAll(lp.getInputStream()), "UTF-8");
                lp.waitFor();
                r.put("slot_log", tail(log.length() > 4000 ? log.substring(log.length() - 4000) : log));
            } catch (Exception e) { r.put("slot_log", String.valueOf(e)); }
        }
        if (out == null) { r.put("ok", false); if (!r.containsKey("error")) r.put("error", "slot :p0 answered nothing"); return r; }
        boolean hostUntouched = hostHotBefore.equals(listing(new File(ctx.getFilesDir(), "hot")));
        r.put("host_hot_untouched", hostUntouched);
        // The installed host's SlotProvider.call no longer recurses (APK fix, 10-30): ping it.
        android.content.ContentProviderClient pc = ctx.getContentResolver().acquireUnstableContentProviderClient("t.t.slot0");
        try { Bundle pong = pc == null ? null : pc.call("lift.ping", null, null); r.put("call_ping", pong != null ? pong.getString("pid") : "null"); }
        catch (Exception e) { r.put("call_ping", String.valueOf(e)); }
        finally { if (pc != null) pc.close(); }
        for (String k : out.keySet()) r.put(k, out.get(k));
        boolean ok = yes(out, "lifted_from_dex") && yes(out, "lifted_good") && !yes(out, "lifted_flipped")
            && yes(out, "host_good") && !yes(out, "host_flipped") && yes(out, "distinct_classes")
            && !String.valueOf(android.os.Process.myPid()).equals(out.getString("pid"))
            && yes(out, "guest_boot") && yes(out, "guest_state_in_guest_dir") && hostUntouched
            && yes(out, "guest_shared_is_guest") && yes(out, "guest_rejected_bad_bundle") && yes(out, "guest_crash_in_guest_dir")
            && rewritten > 0 && !newHostCrash(ctx, t0)
            && bsites > 0 && yes(out, "guest_trusts_lifted_bundle") && !yes(out, "guest_trusts_flipped_lifted");
        if (activate) ok = ok && yes(out, "guest_accepted_lifted_bundle") && yes(out, "guest_credentials_isolated")
            && yes(out, "host_os_untouched") && yes(out, "guest_rebuilt_lifted") && yes(out, "guest_ready");
        r.put("ok", ok);
        return r;
    }

    private static boolean yes(Bundle b, String k) { return "true".equals(b.getString(k)); }

    /** Real slot window, followed by detach: retain the same process-owned guest throughout. */
    static Map<String, Object> window(final android.app.Activity activity) throws Exception {
        Map<String, Object> r = new LinkedHashMap<>();
        android.content.ContentProviderClient c = activity.getContentResolver().acquireUnstableContentProviderClient("t.t.slot0");
        if (c == null) throw new IllegalStateException("guest slot unavailable");
        try {
            Bundle before = c.call("lift.guest.state", null, null);
            if (before == null || !before.getBoolean("running")) throw new IllegalStateException("run lift.guest first");
            final android.content.Intent intent = new android.content.Intent().setClassName(activity, "com.trough.app.Slot$A0");
            GuestRuntime.onMain(new java.util.concurrent.Callable<Object>() { public Object call() {
                activity.startActivity(intent); return null;
            } });
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            Bundle shown = null;
            do {
                Thread.sleep(250);
                shown = c.call("lift.guest.state", null, null);
            } while (System.nanoTime() < until && (shown == null || !shown.getBoolean("attached") || shown.getInt("width") < 1));
            Bundle captured = c.call("lift.guest.capture", null, null);
            r.put("window", shown == null ? "null" : shown.toString());
            r.put("screenshot", captured == null ? "" : captured.getString("screenshot", ""));
            r.put("attached", shown != null && shown.getBoolean("attached") && shown.getBoolean("shown")
                && shown.getInt("width") > 0 && shown.getInt("height") > 0 && shown.getInt("children") > 0
                && before.getString("bundle").equals(shown.getString("bundle")));
            c.call("lift.guest.hide", null, null);
            until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            Bundle after;
            do {
                Thread.sleep(250); after = c.call("lift.guest.state", null, null);
            } while (System.nanoTime() < until && (after == null || after.getBoolean("attached")));
            r.put("detached_alive", after != null && after.getBoolean("running") && !after.getBoolean("attached")
                && before.getString("bundle").equals(after.getString("bundle")));
            r.put("ok", Boolean.TRUE.equals(r.get("attached")) && Boolean.TRUE.equals(r.get("detached_alive"))
                && !String.valueOf(r.get("screenshot")).isEmpty());
            return r;
        } finally { try { c.call("lift.guest.hide", null, null); } finally { c.close(); } }
    }

    /** Slot side of the query: selection args are dex, zip, sig, steps; rows are (key, value). */
    static android.database.Cursor slotQuery(Context ctx, String[] a) {
        Bundle in = new Bundle();
        in.putString("dex", a[0]); in.putString("zip", a[1]); in.putString("sig", a[2]); in.putString("steps", a[3]);
        if (a.length > 4) in.putString("apk", a[4]);
        if (a.length > 6) { in.putString("gzip", a[5]); in.putString("gsig", a[6]); }
        if (a.length > 7) in.putBoolean("activate", Boolean.parseBoolean(a[7]));
        Bundle out = slotVerify(ctx, in);
        android.database.MatrixCursor m = new android.database.MatrixCursor(new String[]{"key", "value"});
        for (String k : out.keySet()) m.addRow(new Object[]{k, String.valueOf(out.get(k))});
        return m;
    }

    /** In slot process :p0 (Main.slotProvider). */
    static Bundle slotVerify(Context ctx, Bundle in) {
        final File steps = new File(in.getString("steps"));
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                step(steps, "UNCAUGHT on " + t.getName() + ": " + sw);
            }
        });
        Bundle out = new Bundle();
        step(steps, "slot " + android.os.Process.myPid() + " started");
        out.putString("pid", String.valueOf(android.os.Process.myPid()));
        out.putString("process", android.app.Application.getProcessName());
        try {
            byte[] zip = read(new File(in.getString("zip"))), sig = read(new File(in.getString("sig")));
            step(steps, "read bundle");
            byte[] flipped = zip.clone();
            flipped[flipped.length / 2] ^= 1;
            ArrayList<PublicKey> keys = trusted(ctx);
            ClassLoader boot = ClassLoader.getSystemClassLoader().getParent();
            File cache = new File(ctx.getCodeCacheDir(), "lift-guest");
            cache.mkdirs();
            step(steps, "keys loaded");
            DexClassLoader lifted = new DexClassLoader(in.getString("dex"), cache.getPath(), null, boot);
            step(steps, "class loader made");
            Class<?> lh = lifted.loadClass("com.trough.app.HotLoader");
            out.putBoolean("lifted_from_dex", lh.getClassLoader() == lifted);
            step(steps, "lifted HotLoader loaded");
            out.putBoolean("lifted_good", verified(lh, keys, zip, sig));
            out.putBoolean("lifted_flipped", verified(lh, keys, flipped, sig));
            step(steps, "lifted verified");
            Class<?> hh = Class.forName("com.trough.app.HotLoader", true, LiftOracle.class.getClassLoader());
            out.putBoolean("host_good", verified(hh, keys, zip, sig));
            out.putBoolean("host_flipped", verified(hh, keys, flipped, sig));
            step(steps, "host verified");
            out.putBoolean("distinct_classes", lh != hh);
            if (in.getString("apk") != null) guestBoot(ctx, lh, in.getString("apk"), out, steps, in);
        } catch (Throwable t) {
            out.putString("error", String.valueOf(t) + (t.getCause() != null ? " <- " + t.getCause() : ""));
        }
        return out;
    }

    /**
     * Step 3: the lifted HotLoader's real constructor, given the guest's identity. It reads its
     * signing key from the guest's own assets (the lifted APK) and keeps its state in the guest's
     * files dir: the host's files/hot must not change (checked by the caller).
     */
    private static final Thread.UncaughtExceptionHandler[] guestHandler = new Thread.UncaughtExceptionHandler[1];

    private static void copy(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from); FileOutputStream o = new FileOutputStream(to)) {
            byte[] b = new byte[1 << 16];
            for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
        }
    }

    private static void guestBoot(Context host, Class<?> hotLoader, String apk, Bundle out, File steps, Bundle in) {
        try {
            final GuestContext g = new GuestContext(host, "t.t", apk);
            File guestShared = new File(new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "trough"), "guests/t.t");
            hotLoader.getClassLoader().loadClass("com.trough.guest.Shim").getField("root").set(null, guestShared.getPath());
            step(steps, "guest context " + g.getFilesDir());
            final java.lang.reflect.Constructor<?> c = hotLoader.getDeclaredConstructor(Context.class);
            c.setAccessible(true);
            final Object[] made = new Object[1];
            final Throwable[] err = new Throwable[1];
            // Views need a Looper: construct on the slot's main thread.
            GuestRuntime.onMain(new java.util.concurrent.Callable<Object>() { public Object call() throws Exception {
                try { made[0] = c.newInstance(g); } catch (Throwable t) { err[0] = t.getCause() != null ? t.getCause() : t; }
                return null;
            } });
            guestHandler[0] = Thread.getDefaultUncaughtExceptionHandler();   // the guest's own (chains to ours)
            Thread.setDefaultUncaughtExceptionHandler(null);
            if (err[0] != null) throw err[0];
            Field k = hotLoader.getDeclaredField("keys");
            k.setAccessible(true);
            out.putInt("guest_keys", ((java.util.List<?>) k.get(made[0])).size());
            Field st = hotLoader.getDeclaredField("state");
            st.setAccessible(true);
            String state = String.valueOf(st.get(made[0]));
            out.putString("guest_state", state);
            out.putBoolean("guest_state_in_guest_dir", state.startsWith(g.getFilesDir().getPath()));
            out.putBoolean("guest_boot", made[0] != null && ((java.util.List<?>) k.get(made[0])).size() >= 1);
            step(steps, "guest HotLoader constructed, state " + state);
            // (a) the lifted sharedDir() sees the guest's storage (Lift's rewrite), the host's is unchanged
            Method sd = hotLoader.getDeclaredMethod("sharedDir");
            sd.setAccessible(true);
            File gs = (File) sd.invoke(null);
            out.putString("guest_shared", gs.getPath());
            out.putBoolean("guest_shared_is_guest", gs.getPath().startsWith(guestShared.getPath()));
            // (b) the guest's own check() on a bundle with a broken signature in ITS Download path
            gs.mkdirs();
            File hz = new File(new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "trough"), "hot/bundle.zip");
            copy(hz, new File(gs, "bundle.zip"));
            byte[] badSig = read(new File(hz.getParentFile(), "bundle.sig"));
            badSig[badSig.length - 1] ^= 1;
            try (FileOutputStream o = new FileOutputStream(new File(gs, "bundle.sig"))) { o.write(badSig); }
            Method check = hotLoader.getDeclaredMethod("check");
            check.setAccessible(true);
            boolean accepted = (Boolean) check.invoke(made[0]);
            Field status = hotLoader.getDeclaredField("status");
            status.setAccessible(true);
            String st2 = String.valueOf(status.get(made[0]));
            out.putString("guest_status", st2);
            out.putBoolean("guest_rejected_bad_bundle", !accepted && st2.contains("bad signature"));
            step(steps, "guest check: " + st2);
            // (c) the guest's crash handler writes into the guest's storage
            Thread.UncaughtExceptionHandler gh = guestHandler[0];
            File[] before = gs.getParentFile().listFiles();
            if (gh != null) gh.uncaughtException(Thread.currentThread(), new RuntimeException("lift.slot: guest crash handler test"));
            boolean crashed = false;
            File[] after = gs.getParentFile().listFiles();
            if (after != null) for (File f : after) if (f.getName().startsWith("crash-")) crashed = true;
            out.putBoolean("guest_crash_in_guest_dir", crashed && (before == null || after.length > before.length));
            step(steps, "guest crash handler wrote into " + gs.getParentFile());
            // The guest, with ITS keys (assets + its trusted dir), on the bundle Trough lifted for it.
            if (in.getString("gzip") != null) {
                byte[] gzip = read(new File(in.getString("gzip"))), gsig = read(new File(in.getString("gsig")));
                Method v = hotLoader.getDeclaredMethod("verified", byte[].class, byte[].class);
                v.setAccessible(true);
                out.putBoolean("guest_trusts_lifted_bundle", (Boolean) v.invoke(made[0], gzip, gsig));
                gzip[gzip.length / 3] ^= 1;
                out.putBoolean("guest_trusts_flipped_lifted", (Boolean) v.invoke(made[0], gzip, gsig));
                step(steps, "guest verified the lifted bundle");
                if (in.getBoolean("activate")) activateGuest(host, g, hotLoader, made[0], gs, in, out, steps);
            }
        } catch (Throwable t) {
            out.putString("guest_error", String.valueOf(t));
            step(steps, "guest boot failed: " + t);
        }
    }

    private static void activateGuest(Context host, GuestContext guest, Class<?> loaderClass, Object loader,
                                      File inbox, Bundle in, Bundle out, File steps) throws Exception {
        // The distribution and immutable compiler/CLI artifacts are shared, credentials are not.
        // Link only manifest-addressed payloads, never the host's home, source or keys.
        File bundle = new File(in.getString("gzip"));
        try (ZipFile z = new ZipFile(bundle); InputStream m = z.getInputStream(z.getEntry("manifest.json"))) {
            JSONObject payloads = new JSONObject(new String(readAll(m), "UTF-8")).getJSONObject("payloads");
            File target = new File(guest.getFilesDir(), "payload"); target.mkdirs();
            for (java.util.Iterator<String> names = payloads.keys(); names.hasNext(); ) {
                String name = names.next(), sha = payloads.getString(name);
                if ("root".equals(name)) continue;
                if (!sha.matches("[0-9a-f]{64}")) throw new SecurityException("invalid payload identity");
                File source = new File(host.getFilesDir(), "payload/" + sha), dest = new File(target, sha);
                // Android's app SELinux domain denies hard links even within this uid's files.
                // Symbolic links share these immutable tool artifacts without copying the CLIs.
                if (!dest.exists()) java.nio.file.Files.createSymbolicLink(dest.toPath(), source.toPath());
            }
        }
        File[] osFiles = new File[]{new File(Root.os(), "x"), new File(Root.os(), "l/libc.so.6"),
            new File(Root.os(), "d/var/lib/dpkg/status"), new File(host.getFilesDir(), "keys/trough_rsa.p8")};
        String before = fingerprints(osFiles);
        copy(new File(in.getString("gsig")), new File(inbox, "bundle.sig"));
        copy(bundle, new File(inbox, "bundle.zip"));
        new File(inbox, "bundle.zip").setLastModified(System.currentTimeMillis());
        long activatedAt = System.currentTimeMillis();
        out.putBoolean("guest_accepted_lifted_bundle", GuestRuntime.activate(host, loader));
        step(steps, "guest bundle accepted, waiting for its self build and own control key");
        String guestHash = ScenarioProtocol.sha(read(bundle));
        out.putString("guest_bundle", guestHash);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(240);
        File report = null;
        JSONObject boot = null;
        while (System.nanoTime() < deadline) {
            File[] reports = inbox.getParentFile().listFiles();
            if (reports != null) for (File f : reports)
                if (f.getName().startsWith("status-") && f.getName().endsWith(".json")
                    && f.lastModified() >= activatedAt
                    && (report == null || f.lastModified() > report.lastModified())) report = f;
            if (report != null) try {
                boot = new JSONObject(new String(read(report), "UTF-8"));
                if (boot.has("self_error") || boot.has("enter_error")) break;
                if ("done".equals(boot.optString("step")) && new File(guest.getFilesDir(), "keys/trough_rsa.p8").isFile()) break;
            } catch (Exception transientWrite) { /* atomic reports may be replaced while reading */ }
            Thread.sleep(250);
        }
        out.putString("guest_report", boot == null ? "no report" : boot.toString());
        out.putBoolean("guest_ready", boot != null && "done".equals(boot.optString("step"))
            && !boot.has("self_error") && !boot.has("enter_error"));
        // Check the actual running self build, including the cached-build path, rather than
        // treating a build log as proof that runtime storage stayed isolated.
        Field current = loaderClass.getDeclaredField("current"); current.setAccessible(true);
        Class<?> entry = (Class<?>) current.get(loader);
        boolean mapped = false;
        if (entry != null) {
            Field stop = entry.getDeclaredField("runningStop"); stop.setAccessible(true);
            Method running = (Method) stop.get(null);
            if (running != null) {
                ClassLoader code = running.getDeclaringClass().getClassLoader();
                Class<?> root = code.loadClass("com.trough.hot.Root"), creds = code.loadClass("com.trough.hot.Creds");
                Method isGuest = root.getDeclaredMethod("guest"), shared = creds.getDeclaredMethod("shared");
                isGuest.setAccessible(true); shared.setAccessible(true);
                mapped = Boolean.TRUE.equals(isGuest.invoke(null))
                    && inbox.getParentFile().equals(shared.invoke(null)) && boot != null
                    && boot.optString("running").startsWith("self ");
            }
        }
        out.putBoolean("guest_rebuilt_lifted", mapped);
        File hostKey = new File(host.getFilesDir(), "keys/trough_rsa.p8"), guestKey = new File(guest.getFilesDir(), "keys/trough_rsa.p8");
        out.putBoolean("guest_credentials_isolated", guestKey.isFile() && !java.util.Arrays.equals(read(hostKey), read(guestKey))
            && !new File(guest.getFilesDir(), "home/.claude/.credentials.json").exists()
            && !new File(guest.getFilesDir(), "home/.codex/auth.json").exists());
        out.putBoolean("host_os_untouched", before.equals(fingerprints(osFiles)));
        step(steps, "guest runtime " + GuestRuntime.state());
    }

    private static String fingerprints(File[] files) throws Exception {
        StringBuilder b = new StringBuilder();
        for (File f : files) b.append(f.getPath()).append(':').append(f.lastModified()).append(':')
            .append(ScenarioProtocol.sha(read(f))).append('\n');
        return b.toString();
    }

    /** A crash file the guest's handler may have written into the HOST's Download/trough since t0. */
    private static boolean newHostCrash(Context ctx, long t0Nanos) {
        long since = System.currentTimeMillis() - (System.nanoTime() - t0Nanos) / 1_000_000;
        File[] fs = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "trough").listFiles();
        if (fs != null) for (File f : fs) if (f.getName().startsWith("crash-") && f.lastModified() >= since) return true;
        return false;
    }

    private static String listing(File dir) {
        StringBuilder b = new StringBuilder();
        File[] fs = dir.listFiles();
        if (fs != null) {
            java.util.Arrays.sort(fs);
            for (File f : fs) b.append(f.getName()).append(' ').append(f.lastModified()).append(' ').append(f.length()).append('\n');
            for (String sub : new String[]{"bad", "trusted"}) b.append(sub).append(':').append(listing(new File(dir, sub)));
        }
        return b.toString();
    }

    /** HotLoader.verified(bytes, sig) on an instance allocated without the constructor. */
    private static boolean verified(Class<?> hotLoader, ArrayList<PublicKey> keys, byte[] zip, byte[] sig) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Object loader = unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, hotLoader);
        Field k = hotLoader.getDeclaredField("keys");
        k.setAccessible(true);
        k.set(loader, new ArrayList<>(keys));
        Method v = hotLoader.getDeclaredMethod("verified", byte[].class, byte[].class);
        v.setAccessible(true);
        return (Boolean) v.invoke(loader, zip, sig);
    }

    /** The keys the host trusts: the APK's asset plus files/hot/trusted/*.der. */
    private static ArrayList<PublicKey> trusted(Context ctx) throws Exception {
        ArrayList<PublicKey> keys = new ArrayList<>();
        KeyFactory f = KeyFactory.getInstance("EC");
        try (InputStream a = ctx.getAssets().open("hot-pubkey.der")) {
            keys.add(f.generatePublic(new X509EncodedKeySpec(readAll(a))));
        }
        File[] extra = new File(new File(ctx.getFilesDir(), "hot"), "trusted").listFiles();
        if (extra != null) for (File x : extra)
            if (x.getName().endsWith(".der")) keys.add(f.generatePublic(new X509EncodedKeySpec(read(x))));
        return keys;
    }

    private static byte[] read(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) { return readAll(in); }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toByteArray();
    }

    /** Why slot processes died (Android records it for our own package: ApplicationExitInfo). */
    static java.util.List<Object> exits(Context ctx) {
        java.util.List<Object> l = new java.util.ArrayList<>();
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            for (android.app.ApplicationExitInfo e : am.getHistoricalProcessExitReasons(null, 0, 8)) {
                if (!e.getProcessName().contains(":")) continue;   // slot and isolated processes only
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("process", e.getProcessName());
                m.put("pid", e.getPid());
                m.put("reason", e.getReason());
                m.put("status", e.getStatus());
                m.put("description", String.valueOf(e.getDescription()));
                m.put("ago_ms", System.currentTimeMillis() - e.getTimestamp());
                try (InputStream t = e.getTraceInputStream()) {
                    if (t != null) m.put("trace", tail(new String(readAll(t), "UTF-8").replaceAll("[^\\x20-\\x7e\\n]", ".")));
                } catch (Exception x) { m.put("trace_error", String.valueOf(x)); }
                l.add(m);
            }
        } catch (Throwable t) { l.add(String.valueOf(t)); }
        return l;
    }

    private static void step(File f, String what) {
        try (FileOutputStream o = new FileOutputStream(f, true)) { o.write((what + "\n").getBytes("UTF-8")); o.getFD().sync(); }
        catch (Exception e) { /* best effort */ }
    }

    private static String tail(String s) { return s.length() > 3500 ? s.substring(s.length() - 3500) : s; }
}
