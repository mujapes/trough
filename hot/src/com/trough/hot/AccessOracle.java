package com.trough.hot;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import java.util.*;

/** android.access: accessibility of the derived document (docs/ACCESSIBILITY.md). The host node's
 * children must be the shown paragraphs by id, in order; each paragraph node's text and bounds
 * must equal the corpus and a flat walk of StaticLayout heights from the scrolled anchor. Then
 * TextView is the reference for behaviour: per paragraph, an EditText holding the same text,
 * laid out at the same width and text size, receives the same random accessibility actions
 * (selection, and next/previous at character, word, line and paragraph granularity, extending
 * or not) as the paragraph's virtual node; return values and the resulting selection must
 * match after every action. Runs on the UI thread. Offscreen. */
final class AccessOracle {
    static Map<String, Object> run(Activity activity) {
        List<String> corpus = LayoutDeriveOracle.corpus(120);
        int n = corpus.size(), W = ViewOracle.WIDTH, H = ViewOracle.VIEW_H;
        List<Object> failures = new ArrayList<>();
        long d = NativeCore.deriveOpen(1, n);
        if (d < 0) return Json.map("ok", false, "error", "deriveOpen " + d);
        int nodes = 0, actions = 0, lineMismatch = 0;
        int[] byGranularity = new int[5];
        try {
            NativeCore.deriveWidth(d, W);
            for (int i = 0; i < n; i++) NativeCore.deriveText(d, i, corpus.get(i));
            int anchor = NativeCore.deriveJump(d, 300);
            NativeCore.deriveViewport(d, anchor, 37, H);
            NativeCore.deriveDemandScene(d, true);
            NativeCore.deriveSettle(d);
            DerivedView host = new DerivedView(activity, d);
            DocumentAccessibility provider = (DocumentAccessibility) host.getAccessibilityNodeProvider();

            /* Structure: children by id, text and bounds. */
            AccessibilityNodeInfo root = provider.createAccessibilityNodeInfo(View.NO_ID);
            List<Integer> positions = new ArrayList<>();
            float top = -37;
            for (int i = anchor; i < n && top < H; i++) {
                positions.add(i);
                StaticLayout l = CaretOracle.platform(corpus.get(i));
                float h = l.getLineBottom(l.getLineCount() - 1);
                if (positions.size() <= root.getChildCount()) {
                    AccessibilityNodeInfo child = provider.createAccessibilityNodeInfo(idOf(provider, i));
                    Rect r = new Rect();
                    if (child != null) child.getBoundsInParent(r);
                    nodes++;
                    if (child == null || !corpus.get(i).contentEquals(child.getText()) || r.top != (int) top || r.bottom != (int) (top + h)
                            || r.left != 0 || r.right != W || child.getMovementGranularities() != DocumentAccessibility.GRANULARITIES)
                        failures.add(Json.map("node", i, "bounds", Arrays.asList(r.top, r.bottom), "want", Arrays.asList((int) top, (int) (top + h))));
                }
                top += h;
            }
            if (root.getChildCount() != positions.size()) failures.add(Json.map("children", root.getChildCount(), "want", positions.size()));

            /* Behaviour against EditText, paragraph by paragraph. */
            Random r = new Random(11);
            int[] granularities = {AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD,
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH};
            for (int p : positions) {
                String text = corpus.get(p);
                EditText ref = reference(activity, text, W);
                if (!sameLines(ref.getLayout(), text, W)) { lineMismatch++; continue; }   /* line granularity needs equal lines */
                int id = idOf(provider, p);
                int start0 = r.nextInt(text.length() + 1);
                ref.setSelection(start0);
                NativeCore.deriveSelect(d, p, start0);
                Deque<String> recent = new ArrayDeque<>();
                for (int k = 0; k < 60; k++) {
                    int action; Bundle args = new Bundle(); String op;
                    if (r.nextInt(6) == 0) {
                        action = AccessibilityNodeInfo.ACTION_SET_SELECTION;
                        int a = r.nextInt(text.length() + 3) - 1, b = r.nextInt(5) == 0 ? a : r.nextInt(text.length() + 3) - 1;
                        if (r.nextInt(8) == 0) { a = -1; b = -1; }
                        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, a);
                        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, b);
                        op = "select(" + a + "," + b + ")";
                    } else {
                        boolean forward = r.nextInt(3) != 0, extend = r.nextInt(4) == 0;
                        int g = granularities[r.nextInt(4)];
                        action = forward ? AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY : AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY;
                        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, g);
                        args.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, extend);
                        op = (forward ? "next(" : "prev(") + g + (extend ? ",extend)" : ")");
                        byGranularity[Integer.numberOfTrailingZeros(g)]++;
                    }
                    boolean want = ref.performAccessibilityAction(action, args);
                    boolean got = provider.performAction(id, action, args);
                    actions++;
                    recent.addLast(op); if (recent.size() > 5) recent.removeFirst();
                    int[] sel = provider.selection(p, text.length());
                    int gs = sel == null ? -1 : sel[0], ge = sel == null ? -1 : sel[1];
                    if (want != got || ref.getSelectionStart() != gs || ref.getSelectionEnd() != ge) {
                        if (failures.size() < 10) failures.add(Json.map("para", p, "ops", new ArrayList<>(recent), "returned", Arrays.asList(got, want),
                            "selection", Arrays.asList(gs, ge), "want", Arrays.asList(ref.getSelectionStart(), ref.getSelectionEnd()),
                            "text", text.length() > 80 ? text.substring(0, 80) : text));
                        break;
                    }
                }
            }
        } finally {
            NativeCore.deriveClose(d);
        }
        return Json.map("ok", failures.isEmpty() && nodes > 0 && actions > 0 && lineMismatch == 0, "nodes", nodes, "actions", actions,
            "by_granularity", Arrays.asList(byGranularity[0], byGranularity[1], byGranularity[2], byGranularity[3]),
            "line_mismatch_paragraphs", lineMismatch, "failures", failures);
    }

    private static int idOf(DocumentAccessibility provider, int position) {
        for (DocumentAccessibility.Shown s : provider.shown(null)) if (s.position == position) return s.id;
        return -1;
    }
    /** An EditText laid out like the derived view's paragraphs: same width, size and breaking. */
    private static EditText reference(Activity activity, String text, int width) {
        EditText e = DocumentKeys.laidOut(activity);
        e.setText(text);
        DocumentKeys.layout(e, width);
        return e;
    }
    private static boolean sameLines(Layout l, String text, int width) {
        if (l == null) return false;
        int[] ours = DeriveLayout.lines(text, width);
        if (l.getLineCount() != ours.length / 3) return false;
        for (int i = 0; i < l.getLineCount(); i++)
            if (l.getLineStart(i) != ours[3 * i] || l.getLineEnd(i) != ours[3 * i + 1] || l.getParagraphDirection(i) != ours[3 * i + 2]) return false;
        return true;
    }
}
