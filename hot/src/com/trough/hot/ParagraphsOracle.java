package com.trough.hot;

import java.util.*;

/** android.paragraphs: paragraph identity (docs/VIEW.md "Paragraph identity"). Random inserts,
 * removals and edits, mostly around the viewport, are applied to the deriver and to a Java
 * list. After each one the scene must equal the flat reference for the list at the anchor the
 * scroll-anchoring rule gives (an insertion at or above the anchor moves it down one, a removal
 * above moves it up, removing the anchor shows the next paragraph from its top), and the
 * layouts run must be only the touched paragraph's: an insertion or removal never lays out
 * another paragraph again. Afterwards a jump must equal the estimate walk over the list and
 * scrolls the exact walk. Offscreen. */
final class ParagraphsOracle {
    /** The paragraphs shown, by identity token (the corpus repeats String objects: every 8th
     * paragraph is the same object as one 5 earlier, so strings cannot stand for paragraphs). */
    static Set<Object> shown(List<String> doc, List<Object> ident, int anchor, int offset) {
        Set<Object> out = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        float y = -offset;
        for (int i = anchor; i < doc.size() && y < ViewOracle.VIEW_H; i++) {
            out.add(ident.get(i));
            y += ViewOracle.height(DeriveLayout.layout(doc.get(i), ViewOracle.WIDTH));
        }
        return out;
    }
    static Map<String, Object> run() {
        List<String> corpus = LayoutDeriveOracle.corpus(600);
        List<String> doc = new ArrayList<>(corpus);
        List<Object> ident = new ArrayList<>();
        for (int i = 0; i < doc.size(); i++) ident.add(new Object());
        int H = ViewOracle.VIEW_H;
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> failures = new ArrayList<>();
        Random rnd = new Random(41);
        long d = NativeCore.deriveOpen(2, doc.size());
        if (d < 0) return Json.map("ok", false, "error", "deriveOpen " + d);
        try {
            NativeCore.deriveWidth(d, ViewOracle.WIDTH);
            for (int i = 0; i < doc.size(); i++) NativeCore.deriveText(d, i, doc.get(i));
            int anchor = NativeCore.deriveJump(d, 500), offset = 25;
            NativeCore.deriveViewport(d, anchor, offset, H);
            NativeCore.deriveDemandScene(d, true);
            NativeCore.deriveSettle(d);
            int[] kinds = new int[3], sceneBad = {0}; long layoutTotal = 0, worstLayout = 0;
            List<Object> log = new ArrayList<>();
            for (int k = 0; k < 90; k++) {
                int kind = rnd.nextInt(10) < 5 ? 0 : rnd.nextInt(10) < 6 ? 1 : 2;   /* insert, remove, edit */
                int pos = rnd.nextInt(3) == 0 ? rnd.nextInt(doc.size()) : Math.max(0, Math.min(doc.size() - 1, anchor - 4 + rnd.nextInt(16)));
                long[] b0 = NativeCore.deriveStats(d);
                boolean visible = pos >= anchor && pos <= anchor + 12;
                Set<Object> cleanBefore = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
                for (int i = Math.max(0, anchor - 4); i < Math.min(doc.size(), anchor + 40); i++)
                    if (NativeCore.deriveValue(d, i) >= 0) cleanBefore.add(ident.get(i));   /* layouts already derived */
                if (kind == 0) {
                    String text = corpus.get(rnd.nextInt(corpus.size())) + " #" + k;
                    int id = NativeCore.deriveInsert(d, pos, text);
                    if (id < 0) { failures.add(Json.map("insert_failed", pos)); break; }
                    doc.add(pos, text); ident.add(pos, new Object());
                    if (pos <= anchor) anchor++;
                } else if (kind == 1) {
                    if (NativeCore.deriveRemove(d, pos) < 0) { failures.add(Json.map("remove_failed", pos)); break; }
                    doc.remove(pos); ident.remove(pos);
                    if (pos < anchor) anchor--;
                    else if (pos == anchor) offset = 0;
                    if (anchor >= doc.size()) anchor = doc.size() - 1;
                } else {
                    String text = doc.get(pos) + " edited" + k;
                    NativeCore.deriveText(d, pos, text);
                    doc.set(pos, text); ident.set(pos, new Object());
                }
                kinds[kind]++;
                NativeCore.deriveSettle(d);
                long[] b1 = NativeCore.deriveStats(d);
                long layouts = b1[11] - b0[11];
                layoutTotal += layouts; worstLayout = Math.max(worstLayout, layouts);
                byte[] got = NativeCore.deriveBytes(d, -1);
                boolean same = got != null && Arrays.equals(got, ViewOracle.reference(doc, anchor, offset));
                if (!same) sceneBad[0]++;
                if (NativeCore.deriveCount(d) != doc.size()) failures.add(Json.map("count", NativeCore.deriveCount(d), "want", doc.size()));
                /* Only one paragraph is laid out per op: the touched one, or the one a removal pulls
                 * into view (2 runs for a fresh id: one restart). Above the anchor: none. */
                /* Layouts run only for shown paragraphs whose layout was not already derived (at
                 * most 2 runs each: a fresh id restarts once); an edit makes a new string. */
                int fresh = 0;
                for (Object t : shown(doc, ident, anchor, offset)) if (!cleanBefore.contains(t)) fresh++;
                if (layouts > 2L * fresh)
                {
                    long[] tr = NativeCore.deriveTrace(d);              /* (kind, key, outcome, worker) rows */
                    List<Object> jobs = new ArrayList<>();
                    long opJobs = b1[0] - b0[0];                         /* this op's jobs: the newest rows */
                    for (int r = (int) Math.max(0, tr.length / 4 - opJobs); r < tr.length / 4; r++)
                        jobs.add(Arrays.asList(tr[4 * r], tr[4 * r + 1], tr[4 * r + 2]));
                    List<Object> shownIdx = new ArrayList<>();
                    for (Object t : shown(doc, ident, anchor, offset)) shownIdx.add(ident.indexOf(t));
                    failures.add(Json.map("op", k, "kind", kind, "pos", pos, "anchor", anchor, "layout_runs", layouts, "newly_shown", fresh,
                        "op_jobs_kind_key_outcome", jobs, "shown_positions", shownIdx));
                }
                if (log.size() < 10 || !same) log.add(Json.map("op", k, "kind", kind, "pos", pos, "anchor", anchor, "visible", visible, "newly_shown", fresh,
                    "layout_runs", layouts, "scene_runs", b1[13] - b0[13], "equal", same));
            }
            out.put("ops", Json.map("insert", kinds[0], "remove", kinds[1], "edit", kinds[2], "layout_runs", layoutTotal, "worst_layout_runs", worstLayout));
            out.put("log", log.subList(0, Math.min(16, log.size())));
            if (sceneBad[0] != 0) failures.add(Json.map("scenes_differ", sceneBad[0]));

            /* The summaries follow the order: a jump equals the estimate walk over the list. */
            int jumped = NativeCore.deriveJump(d, 300), want = ViewOracle.estimateAnchor(doc, 300);
            out.put("jump", Json.map("got", jumped, "want", want));
            if (jumped != want) failures.add(Json.map("jump", jumped, "want", want));
            NativeCore.deriveViewport(d, jumped, 0, H);
            NativeCore.deriveSettle(d);
            int ra = jumped, ro = 0, scrollBad = 0;
            for (int dy : new int[] {900, -1500, 2200}) {
                long pos = NativeCore.deriveScroll(d, dy);
                NativeCore.deriveSettle(d);
                int[] w = ViewOracle.scrollReference(doc, ra, ro, dy);
                ra = w[0]; ro = w[1];
                if ((int) (pos & 0xffffffffL) != ra || (int) (pos >>> 32) != ro || !Arrays.equals(NativeCore.deriveBytes(d, -1), ViewOracle.reference(doc, ra, ro))) scrollBad++;
            }
            out.put("scrolls_differ", scrollBad);
            if (scrollBad != 0) failures.add(Json.map("scrolls_differ", scrollBad));
            long[] s = NativeCore.deriveStats(d);
            if (s[3] != 0) failures.add(Json.map("error", s[3]));
        } finally {
            NativeCore.deriveClose(d);
        }
        out.put("failures", failures.subList(0, Math.min(8, failures.size())));
        out.put("ok", failures.isEmpty());
        return out;
    }
}
