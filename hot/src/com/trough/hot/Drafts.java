package com.trough.hot;

import android.content.Context;
import java.io.File;

/** The app's draft store owner: one keeper over files/drafts for this bundle's lifetime
 * (docs/KEEPER.md, docs/BIONIC.md). Hot replacement retires it from Main.stop, before the next
 * bundle starts: tk_stop saves pending drafts and releases the store's single-owner lock, so
 * the successor's keeper can open the same store. In-memory queues do not cross bundles;
 * drafts cross durably and are reattached. */
final class Drafts {
    private static long keeper;
    private static long[] retired;   // keeper stats at retirement, for diagnostics

    static synchronized long keeper(Context context, File bundle) {
        if (keeper > 0) return keeper;
        NativeCore.load(bundle);
        File dir = new File(context.getFilesDir(), "drafts");
        long k = NativeCore.start(dir.getPath());
        if (k < 0) throw new IllegalStateException("draft store: ts_status " + (-k));
        keeper = k;
        return k;
    }

    /** Retirement obligation of this bundle: save every attached draft, then close the store. */
    static synchronized void retire() {
        if (keeper <= 0) return;
        retired = NativeCore.stats(keeper);
        NativeCore.stop(keeper);
        keeper = 0;
    }

    static synchronized boolean running() { return keeper > 0; }
}
