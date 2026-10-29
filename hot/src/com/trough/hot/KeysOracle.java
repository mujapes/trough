package com.trough.hot;

import android.app.Activity;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.EditText;
import java.util.*;

/** android.keys: key events on the derived document (docs/INPUT.md "Keys") against an EditText
 * with the same inputType holding the joined text. The same random KeyEvents (backspace and
 * forward delete over emoji sequences, flags, keycaps and combining marks; Enter; letters with
 * and without shift after sentence ends; space, period, digits; arrows and Home/End, with and
 * without shift, through bidi text and across paragraphs) and selection changes go to
 * both: DerivedView.onKeyDown/onKeyUp and EditText.onKeyDown/onKeyUp. After every event the
 * joined document and the selection must equal the EditText's. Runs on the UI thread. */
final class KeysOracle {
    private static final String[] START = {"Hello there. a👍🏽b", "", "family 👨‍👩‍👧 ok", "🇯🇵🇺🇸 flags", "key 1️⃣ é é", "שלום עולם", "", "last line."};

    static Map<String, Object> run(Activity activity) {
        List<Object> mismatches = new ArrayList<>();
        int seeds = 12, steps = 150, events = 0, maxParagraphs = 0;
        int[] codes = {KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_PERIOD,
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_COMMA,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END};
        int[] weights = {22, 6, 6, 6, 5, 9, 6, 5, 4, 3, 8, 8, 4, 4, 2, 2};
        for (int seed = 1; seed <= seeds; seed++) {
            long d = NativeCore.deriveOpen(1, 60);
            if (d < 0) return Json.map("ok", false, "error", "deriveOpen " + d);
            try {
                NativeCore.deriveWidth(d, ViewOracle.WIDTH);
                for (int i = 0; i < 60 - START.length; i++) NativeCore.deriveRemove(d, START.length);
                for (int i = 0; i < START.length; i++) NativeCore.deriveText(d, i, START[i]);
                DerivedView view = new DerivedView(activity, d);
                EditText ref = DocumentKeys.laidOut(activity);      /* laid out: arrow keys need a Layout */
                ref.setText(String.join("\n", START));
                DocumentKeys.layout(ref, ViewOracle.WIDTH);
                Random r = new Random(seed);
                Deque<String> recent = new ArrayDeque<>();
                String diff = null;
                for (int i = 0; i < steps && diff == null; i++) {
                    int len = ref.getText().length();
                    String op;
                    if (i == 0 || r.nextInt(10) == 0) {
                        int a = r.nextInt(len + 1), b = r.nextInt(5) < 4 ? a : r.nextInt(len + 1);
                        ref.setSelection(a, b);
                        long pa = NativeCore.deriveLocate(d, a), pb = NativeCore.deriveLocate(d, b);
                        if (a == b) NativeCore.deriveSelect(d, (int) (pa >> 32), (int) (pa & 0xffffffffL));
                        else NativeCore.deriveSelectRange(d, (int) (pa >> 32), (int) (pa & 0xffffffffL), (int) (pb >> 32), (int) (pb & 0xffffffffL));
                        op = "select(" + a + "," + b + ")";
                    } else {
                        int pick = r.nextInt(100), k = 0;
                        for (int acc = weights[0]; pick >= acc; acc += weights[++k]) {}
                        int code = codes[k];
                        int meta = r.nextInt(5) == 0 ? KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON : 0;
                        long t = SystemClock.uptimeMillis();
                        KeyEvent down = new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta), up = new KeyEvent(t, t + 1, KeyEvent.ACTION_UP, code, 0, meta);
                        boolean x = ref.onKeyDown(code, down), y = view.onKeyDown(code, down);
                        ref.onKeyUp(code, up); view.onKeyUp(code, up);
                        DocumentKeys.layout(ref, ViewOracle.WIDTH);   /* the reference's layout follows its edits */
                        op = KeyEvent.keyCodeToString(code) + (meta != 0 ? "+shift" : "");
                        if (x != y) diff = "handled " + x + " vs " + y;
                        events++;
                    }
                    recent.addLast(op); if (recent.size() > 6) recent.removeFirst();
                    if (diff == null) diff = compare(ref, d);
                    maxParagraphs = Math.max(maxParagraphs, NativeCore.deriveCount(d));
                }
                if (diff != null && mismatches.size() < 6) mismatches.add(Json.map("seed", seed, "diff", diff, "ops", new ArrayList<>(recent)));
            } finally {
                NativeCore.deriveClose(d);
            }
        }
        return Json.map("ok", mismatches.isEmpty(), "seeds", seeds, "key_events", events, "max_paragraphs", maxParagraphs, "mismatches", mismatches);
    }

    private static String compare(EditText ref, long d) {
        String rt = ref.getText().toString();
        long[] s = NativeCore.deriveDocState(d);
        String ot = NativeCore.deriveSlice(d, 0, s[0]);
        if (!rt.equals(ot) || ref.getSelectionStart() != s[1] || ref.getSelectionEnd() != s[2])
            return "ref " + show(rt) + " sel " + ref.getSelectionStart() + "," + ref.getSelectionEnd() + " | ours " + show(ot) + " sel " + s[1] + "," + s[2];
        return null;
    }
    private static String show(String t) { return "\"" + (t.length() > 120 ? t.substring(0, 120) + "..." : t).replace("\n", "\\n") + "\""; }
}
