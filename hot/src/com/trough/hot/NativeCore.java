package com.trough.hot;

import java.io.File;
import java.util.*;

/** In-process binding of the bionic storage owner (native/android/jni.c, docs/BIONIC.md).
 * Each bundle loads its own copy from its own directory: Android ties a loaded library to one
 * ClassLoader, and every hot bundle has a new one. Negative returns are -ts_status. */
final class NativeCore {
    static native long start(String dir);
    static native void stop(long keeper);
    static native long create(long keeper);
    static native long attach(long keeper, long document);
    static native long detach(long keeper, long queue);
    static native long edit(long keeper, long queue, int start, int end, String text, int anchor, int caret, int cs, int ce);
    static native long flush(long keeper, long queue, long local, int timeoutMs);
    static native String live(long queue);
    static native String durable(long keeper, long document, long revision);
    static native long[] stats(long keeper);
    static native long[] queueState(long queue);

    private static boolean loaded;
    static synchronized long load(File bundle) {
        if (loaded) return 0;
        long t = System.nanoTime();
        System.load(new File(bundle, "lib/libtrough-android.so").getAbsolutePath());
        loaded = true;
        return System.nanoTime() - t;
    }
    private static long check(long v, String what) {
        if (v < 0) throw new IllegalStateException(what + " failed: ts_status " + (-v));
        return v;
    }

    /** android.jni: the input adapter's path, end to end, inside the app process. Typing from
     * Java (JNI edit + keeper poke per keystroke), flush to a durable revision, verify the text,
     * restart the keeper and verify recovery. Latencies are per JNI call, measured in Java. */
    static Map<String, Object> scenario(File bundle, File cache) throws Exception {
        long loadNs = load(bundle);
        File dir = new File(cache, "scenario-android-jni");
        Main.deleteTree(dir);
        try {
            long k = check(start(dir.getPath()), "start");
            long doc = check(create(k), "create"), q = check(attach(k, doc), "attach");
            StringBuilder model = new StringBuilder();
            int edits = 20000;
            long[] ns = new long[edits];
            long local = 0;
            for (int j = 0; j < edits; j++) {
                int n = model.length(), at, end; String text;
                if (j % 5 == 4 && n > 0) { at = (j * 3) % n; end = at + 1; text = ""; }
                else { at = (j * 7) % (n + 1); end = at; text = String.valueOf((char) (0x100 + j % 5000)); }
                int caret = at + text.length();
                long t = System.nanoTime();
                local = check(edit(k, q, at, end, text, caret, caret, -1, -1), "edit");
                ns[j] = System.nanoTime() - t;
                model.replace(at, end, text);
            }
            long[] qs = queueState(q);
            int slow = 0;
            for (long v : ns) if (v > 1_000_000) slow++;
            boolean liveOk = model.toString().equals(live(q));
            long t = System.nanoTime();
            long revision = check(flush(k, q, local, 20000), "flush");
            long flushNs = System.nanoTime() - t;
            boolean durableOk = model.toString().equals(durable(k, doc, revision));
            long[] st = stats(k);
            check(detach(k, q), "detach");
            stop(k);
            long k2 = check(start(dir.getPath()), "restart");
            long q2 = check(attach(k2, doc), "reattach");
            boolean recovered = model.toString().equals(live(q2));
            check(detach(k2, q2), "detach");
            stop(k2);
            long[] sorted = ns.clone(); Arrays.sort(sorted);
            boolean ok = liveOk && durableOk && recovered;
            return Json.map("ok", ok, "load_ms", loadNs / 1e6, "edits", edits, "length", model.length(),
                "edit_p50_us", sorted[edits / 2] / 1e3, "edit_p99_us", sorted[edits * 99 / 100] / 1e3,
                "edit_max_us", sorted[edits - 1] / 1e3, "flush_ms", flushNs / 1e6, "saves", st[0],
                "failures", st[1], "slow_edits_over_1ms", slow, "queue_lock_contended", qs[3],
                "poke_lock_contended", st[4], "live_ok", liveOk, "durable_ok", durableOk, "recovered", recovered,
                "revision", revision);
        } finally {
            Main.deleteTree(dir);
        }
    }
}
