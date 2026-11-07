package com.trough.hot;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.File;

/**
 * The host-exec broker (native/hostexec, docs/FILESYSTEM.md): Android's own programs, run outside
 * texec's path filter for processes under it. One per device, outliving bundle reloads: a boot that
 * finds it answering leaves it alone, so reloads neither duplicate nor orphan it.
 */
final class HostExec {
    static final String SOCKET = "/data/data/t.t/hostexec.sock";   // = TROUGH_HOSTEXEC (trough-path.h)

    static String ensure(Context ctx) {
        if (answers()) return "running";
        File bin = new File(Root.os(), "hostexec");
        if (!bin.canExecute()) return "no broker binary";
        try {
            File log = new File(ctx.getCacheDir(), "hostexec.log");
            new ProcessBuilder(bin.getPath(), SOCKET).redirectErrorStream(true)
                .redirectOutput(log).redirectInput(new File("/dev/null")).start();
            for (int i = 0; i < 50; i++) {
                if (answers()) return "started";
                Thread.sleep(20);
            }
            return "started, not answering (see " + log + ")";
        } catch (Exception e) {
            return "failed: " + e;
        }
    }

    private static boolean answers() {
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.FILESYSTEM));
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { s.close(); } catch (Exception e) { /* nothing to do */ }
        }
    }
}
