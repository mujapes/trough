package com.trough.hot;

import android.app.Activity;
import android.text.Editable;
import android.text.Selection;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import java.io.File;
import java.util.*;

/** android.docime: the IME over the derived document (docs/INPUT.md "Document"). TextInput with
 * the DocumentText backend, where the text is paragraphs joined by '\n', against Android's own
 * BaseInputConnection over an Editable, with the same random operations as android.ime:
 * commits and composing text including newlines and surrogate pairs, regions, selections and
 * surrounding deletions. After every operation the joined document, the selection and the
 * composing region must equal the Editable's; every seventh operation the cursor queries too.
 * Each seed ends by rendering: the scene of the final document equals the flat reference.
 * Runs on the UI thread, where IMEs call in. */
final class DocImeOracle {
    private static final String[] PIECES = {"", "a", "b", "xy", "\n", "é", "😀", "abc", "ab\n", "\n\n", "שלום"};

    static Map<String, Object> run(Activity activity) {
        List<Object> mismatches = new ArrayList<>();
        int seeds = 16, steps = 300, ops = 0, queries = 0, maxParagraphs = 0, scenesBad = 0;
        String[] start = {"ab", "", "שלום x", "cd"};
        for (int seed = 1; seed <= seeds; seed++) {
            long d = NativeCore.deriveOpen(1, 40);                /* capacity 144 ids */
            if (d < 0) return Json.map("ok", false, "error", "deriveOpen " + d);
            try {
                NativeCore.deriveWidth(d, ViewOracle.WIDTH);
                for (int i = 0; i < 36; i++) NativeCore.deriveRemove(d, 4);
                for (int i = 0; i < 4; i++) NativeCore.deriveText(d, i, start[i]);
                View view = new View(activity);
                BaseInputConnection ref = new BaseInputConnection(view, true);
                ref.getEditable().append(String.join("\n", start));
                DocumentText doc = new DocumentText(d);
                TextInput ours = new TextInput(view, doc);
                ref.setSelection(0, 0); ours.setSelection(0, 0);
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
                    if (diff == null) diff = compare(ref, doc, d);
                    maxParagraphs = Math.max(maxParagraphs, NativeCore.deriveCount(d));
                    if (diff == null && i % 7 == 0) {
                        int n = r.nextInt(5);
                        queries += 3;
                        if (!Objects.equals(str(ref.getTextBeforeCursor(n, 0)), str(ours.getTextBeforeCursor(n, 0)))) diff = "getTextBeforeCursor(" + n + ")";
                        else if (!Objects.equals(str(ref.getTextAfterCursor(n, 0)), str(ours.getTextAfterCursor(n, 0)))) diff = "getTextAfterCursor(" + n + ")";
                        else if (!Objects.equals(str(ref.getSelectedText(0)), str(ours.getSelectedText(0)))) diff = "getSelectedText";
                    }
                }
                if (diff != null && mismatches.size() < 6) mismatches.add(Json.map("seed", seed, "diff", diff, "ops", new ArrayList<>(recent)));
                /* The final document renders as its paragraphs. */
                List<String> paras = Arrays.asList(ref.getEditable().toString().split("\n", -1));
                NativeCore.deriveViewport(d, 0, 0, ViewOracle.VIEW_H);
                NativeCore.deriveDemandScene(d, true);
                NativeCore.deriveSettle(d);
                if (!Arrays.equals(NativeCore.deriveBytes(d, -1), ViewOracle.reference(paras, 0, 0))) scenesBad++;
            } finally {
                NativeCore.deriveClose(d);
            }
        }
        return Json.map("ok", mismatches.isEmpty() && scenesBad == 0, "seeds", seeds, "operations", ops, "queries", queries,
            "max_paragraphs", maxParagraphs, "scenes_differ", scenesBad, "mismatches", mismatches);
    }
    private static String str(CharSequence c) { return c == null ? null : c.toString(); }
    private static String show(String t) { return "\"" + t.replace("\n", "\\n") + "\""; }
    private static String compare(BaseInputConnection ref, DocumentText doc, long d) {
        Editable e = ref.getEditable();
        String rt = e.toString();
        long[] s = NativeCore.deriveDocState(d);
        String ot = NativeCore.deriveSlice(d, 0, s[0]);
        int ra = Selection.getSelectionStart(e), rc = Selection.getSelectionEnd(e);
        int rcs = BaseInputConnection.getComposingSpanStart(e), rce = BaseInputConnection.getComposingSpanEnd(e);
        long[] v = doc.state();
        if (!rt.equals(ot) || ra != v[1] || rc != v[2] || rcs != v[3] || rce != v[4])
            return "ref " + show(rt) + " sel " + ra + "," + rc + " comp " + rcs + "," + rce +
                " | ours " + show(ot) + " sel " + v[1] + "," + v[2] + " comp " + v[3] + "," + v[4];
        return null;
    }
}
