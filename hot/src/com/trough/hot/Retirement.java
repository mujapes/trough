package com.trough.hot;

import android.app.Activity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Hot-replacement retirement of native draft state, across two real bundles
 * (tests/retire.sh): setup types into a draft of the app's store and records what it typed;
 * the next bundle's check reopens the store (only possible if the predecessor's Main.stop
 * released it) and requires every typed edit. */
final class Retirement {
    private static File probe(Activity a) { return new File(a.getFilesDir(), "retire-probe.txt"); }

    static Map<String, Object> setup(Activity activity, File bundle) throws Exception {
        long k = Drafts.keeper(activity, bundle);
        long doc = NativeCore.create(k), q = NativeCore.attach(k, doc);
        if (doc < 0 || q < 0) throw new IllegalStateException("attach");
        StringBuilder model = new StringBuilder();
        Random r = new Random(System.nanoTime());
        for (int j = 0; j < 500; j++) {
            String t = String.valueOf((char) ('a' + r.nextInt(26)));
            int at = r.nextInt(model.length() + 1);
            if (NativeCore.edit(k, q, at, at, t, at + 1, at + 1, -1, -1) < 0) throw new IllegalStateException("edit");
            model.insert(at, t);
        }
        /* No flush and no detach: the queue is left attached with edits the keeper may not
         * have saved yet. Retirement must save them. */
        File tmp = new File(probe(activity).getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(doc + "\n" + bundle.getName() + "\n" + model);
        }
        if (!tmp.renameTo(probe(activity))) throw new IOException("probe");
        return Json.map("ok", true, "document", doc, "length", model.length(), "bundle", bundle.getName());
    }

    static Map<String, Object> check(Activity activity, File bundle) throws Exception {
        String[] p = new String(ScenarioProtocol.read(probe(activity), 1 << 20), StandardCharsets.UTF_8).split("\n", 3);
        long doc = Long.parseLong(p[0]);
        long k = Drafts.keeper(activity, bundle);
        long q = NativeCore.attach(k, doc);
        if (q < 0) throw new IllegalStateException("attach: ts_status " + (-q));
        String live = NativeCore.live(q);
        boolean same = p[2].equals(live), other = !p[1].equals(bundle.getName());
        NativeCore.detach(k, q);
        probe(activity).delete();
        return Json.map("ok", same && other, "durable_after_reload", same, "different_bundle", other,
            "setup_bundle", p[1], "length", live == null ? -1 : live.length());
    }
}
