package com.trough.hot;

import android.app.Activity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.SurroundingText;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** android.editor: EditorView's EditorInfo, the IME connection it returns, and the notify path:
 * an external (agent) edit merged by the keeper must reach the main thread through the eventfd
 * and restart input, without polling. Runs from the development worker; UI work is posted. */
final class EditorOracle {
    static Map<String, Object> run(final Activity activity, File bundle) throws Exception {
        NativeCore.load(bundle);
        File dir = new File(activity.getCacheDir(), "scenario-android-editor");
        Main.deleteTree(dir);
        final long k = NativeCore.start(dir.getPath());
        if (k < 0) throw new IllegalStateException("start " + (-k));
        final long doc = NativeCore.create(k), q = NativeCore.attach(k, doc);
        final Map<String, Object> out = new LinkedHashMap<>();
        final EditorView[] view = new EditorView[1];
        try {
            ui(activity, new Callable<Void>() { public Void call() {
                view[0] = new EditorView(activity);
                view[0].bind(k, q);
                EditorInfo first = new EditorInfo();
                InputConnection ic = view[0].onCreateInputConnection(first);
                out.put("text_class", (first.inputType & android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_TEXT);
                out.put("initial_empty", first.initialSelStart == 0 && first.initialSelEnd == 0);
                ic.commitText("hello world", 1);
                ic.setSelection(5, 5);
                EditorInfo second = new EditorInfo();
                view[0].onCreateInputConnection(second);
                SurroundingText st = second.getInitialSurroundingText(100, 100, 0);
                out.put("initial_selection", second.initialSelStart == 5 && second.initialSelEnd == 5);
                out.put("surrounding", st != null && "hello world".contentEquals(st.getText()) && st.getOffset() == 0);
                return null;
            }});
            long local = NativeCore.value(q)[5];
            if (NativeCore.flush(k, q, local, 20000) < 0) throw new IllegalStateException("flush");
            int before = view[0].restarts;
            long st = NativeCore.externalEdit(k, doc, 11, 11, "!");      // an agent appends
            if (st < 0) throw new IllegalStateException("external edit " + (-st));
            long until = System.nanoTime() + 3_000_000_000L;
            while (view[0].restarts == before && System.nanoTime() < until) Thread.sleep(5);
            out.put("restarted_after_external", view[0].restarts == before + 1);
            out.put("live_merged", "hello world!".equals(NativeCore.live(q)));
            long[] v = NativeCore.value(q);
            out.put("caret_kept", v[1] == 5 && v[2] == 5);
            out.put("events", view[0].events);
            boolean ok = true;
            for (Object x : out.values()) if (Boolean.FALSE.equals(x)) ok = false;
            out.put("ok", ok);
            return out;
        } finally {
            ui(activity, new Callable<Void>() { public Void call() { if (view[0] != null) view[0].unbind(); return null; }});
            NativeCore.detach(k, q);
            NativeCore.stop(k);
            Main.deleteTree(dir);
        }
    }
    private static <T> T ui(Activity a, Callable<T> c) throws Exception {
        FutureTask<T> t = new FutureTask<>(c);
        a.runOnUiThread(t);
        return t.get(20, TimeUnit.SECONDS);
    }
}
