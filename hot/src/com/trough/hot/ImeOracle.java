package com.trough.hot;

import android.app.Activity;
import android.text.Editable;
import android.text.Selection;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import java.io.File;
import java.util.*;

/** android.ime: TextInput (native draft queue) against Android's own BaseInputConnection over an
 * Editable, driven by the same random IME operation sequences. After every operation the text,
 * selection (anchor, caret), composing region and cursor queries must agree. Runs on the UI
 * thread, where IMEs call in. Stops a seed at its first divergence and reports the recent ops. */
final class ImeOracle {
    private static final String[] PIECES = {"", "a", "b", "xy", "\n", "é", "😀", "abc", "ab\n"};

    static Map<String, Object> run(Activity activity, File bundle) throws Exception {
        NativeCore.load(bundle);
        File dir = new File(activity.getCacheDir(), "scenario-android-ime");
        Main.deleteTree(dir);
        long k = NativeCore.start(dir.getPath());
        if (k < 0) throw new IllegalStateException("start " + (-k));
        List<Object> mismatches = new ArrayList<>();
        int seeds = 40, steps = 400, ops = 0, queries = 0;
        try {
            for (int seed = 1; seed <= seeds; seed++) {
                long doc = NativeCore.create(k), q = NativeCore.attach(k, doc);
                View view = new View(activity);
                BaseInputConnection ref = new BaseInputConnection(view, true);   // full editor: false is the key-event fallback, which clears its Editable
                TextInput ours = new TextInput(view, k, q);
                Random r = new Random(seed);
                Deque<String> recent = new ArrayDeque<>();
                String diff = null;
                for (int i = 0; i < steps && diff == null; i++) {
                    Editable e = ref.getEditable();
                    int len = e.length();
                    String op;
                    int kind = r.nextInt(100);
                    if (kind < 25) {
                        String t = PIECES[r.nextInt(PIECES.length)]; int c = r.nextInt(9) - 4;
                        op = "commitText(" + show(t) + "," + c + ")"; ref.commitText(t, c); ours.commitText(t, c);
                    } else if (kind < 50) {
                        String t = PIECES[r.nextInt(PIECES.length)]; int c = r.nextInt(9) - 4;
                        op = "setComposingText(" + show(t) + "," + c + ")"; ref.setComposingText(t, c); ours.setComposingText(t, c);
                    } else if (kind < 60) {
                        int a = r.nextInt(len + 5) - 2, b = r.nextInt(len + 5) - 2;
                        op = "setComposingRegion(" + a + "," + b + ")"; ref.setComposingRegion(a, b); ours.setComposingRegion(a, b);
                    } else if (kind < 68) {
                        op = "finishComposingText()"; ref.finishComposingText(); ours.finishComposingText();
                    } else if (kind < 84) {
                        int a = r.nextInt(6) - (r.nextInt(10) == 0 ? 2 : 0), b = r.nextInt(6);
                        op = "deleteSurroundingText(" + a + "," + b + ")";
                        boolean x = ref.deleteSurroundingText(a, b), y = ours.deleteSurroundingText(a, b);
                        if (x != y) diff = "return " + x + " vs " + y;
                    } else {
                        int a = r.nextInt(len + 3) - 1, b = r.nextInt(len + 3) - 1;
                        op = "setSelection(" + a + "," + b + ")"; ref.setSelection(a, b); ours.setSelection(a, b);
                    }
                    ops++;
                    recent.addLast(op); if (recent.size() > 6) recent.removeFirst();
                    if (diff == null) diff = compare(ref, ours, q);
                    if (diff == null && i % 7 == 0) {
                        int n = r.nextInt(5);
                        queries += 3;
                        if (!Objects.equals(str(ref.getTextBeforeCursor(n, 0)), str(ours.getTextBeforeCursor(n, 0)))) diff = "getTextBeforeCursor(" + n + ")";
                        else if (!Objects.equals(str(ref.getTextAfterCursor(n, 0)), str(ours.getTextAfterCursor(n, 0)))) diff = "getTextAfterCursor(" + n + ")";
                        else if (!Objects.equals(str(ref.getSelectedText(0)), str(ours.getSelectedText(0)))) diff = "getSelectedText";
                    }
                }
                if (diff != null && mismatches.size() < 6)
                    mismatches.add(Json.map("seed", seed, "diff", diff, "ops", new ArrayList<>(recent)));
                NativeCore.detach(k, q);
            }
        } finally {
            NativeCore.stop(k);
            Main.deleteTree(dir);
        }
        return Json.map("ok", mismatches.isEmpty(), "seeds", seeds, "operations", ops, "queries", queries,
            "sdk", android.os.Build.VERSION.SDK_INT, "mismatches", mismatches);
    }
    private static String str(CharSequence c) { return c == null ? null : c.toString(); }
    private static String show(String t) { return "\"" + t.replace("\n", "\\n") + "\""; }
    private static String compare(BaseInputConnection ref, TextInput ours, long q) {
        Editable e = ref.getEditable();
        String rt = e.toString(), ot = NativeCore.live(q);
        int ra = Selection.getSelectionStart(e), rc = Selection.getSelectionEnd(e);
        int rcs = BaseInputConnection.getComposingSpanStart(e), rce = BaseInputConnection.getComposingSpanEnd(e);
        long[] v = NativeCore.value(q);
        if (!rt.equals(ot) || ra != v[1] || rc != v[2] || rcs != v[3] || rce != v[4])
            return "ref " + show(rt) + " sel " + ra + "," + rc + " comp " + rcs + "," + rce +
                " | ours " + show(ot) + " sel " + v[1] + "," + v[2] + " comp " + v[3] + "," + v[4];
        return null;
    }
}
