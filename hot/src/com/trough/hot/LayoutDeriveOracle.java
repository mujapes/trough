package com.trough.hot;

import java.io.File;
import java.util.*;

/** android.derive: paragraph layout as a derivation operator on executor workers
 * (docs/LAYOUT_DERIVE.md). Every derived layout must be byte-identical to DeriveLayout.layout
 * run directly on this thread; equal paragraphs share one handle; an edit costs exactly one
 * job, a width change one job per demanded paragraph, an undemanded edit none. Then the same
 * corpus is timed on 1, 2, 4 and 8 workers against a single-thread loop. Offscreen. */
final class LayoutDeriveOracle {
    static Map<String, Object> run(File bundle) {
        NativeCore.load(bundle);
        final List<String> corpus = corpus(240);
        int n = corpus.size();
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> failures = new ArrayList<>();
        /* Warm the JIT and font caches the same way for every configuration below. */
        for (String t : corpus) DeriveLayout.layout(t, 655);
        long d = NativeCore.deriveOpen(4, n);
        if (d < 0) return Json.map("ok", false, "error", "deriveOpen " + d);
        try {
            NativeCore.deriveWidth(d, 655);
            for (int i = 0; i < n; i++) NativeCore.deriveText(d, i, corpus.get(i));
            for (int i = 0; i < n; i++) NativeCore.deriveDemand(d, i, true);
            boolean settled = NativeCore.deriveSettle(d) > 0;
            long[] s0 = NativeCore.deriveStats(d);
            int equal = compare(d, corpus, 655, failures);
            int shared = 0, duplicates = 0;
            for (int i = 0; i < n; i++) for (int j = 0; j < i; j++) if (corpus.get(i).equals(corpus.get(j))) {
                duplicates++;
                if (NativeCore.deriveValue(d, i) == NativeCore.deriveValue(d, j)) shared++;
                break;
            }
            /* One edit: one job. */
            corpus.set(17, corpus.get(17) + " edited");
            NativeCore.deriveText(d, 17, corpus.get(17));
            NativeCore.deriveSettle(d);
            long[] s1 = NativeCore.deriveStats(d);
            int equalEdit = compare(d, corpus, 655, failures);
            /* Width: one job per demanded paragraph. */
            NativeCore.deriveWidth(d, 410);
            NativeCore.deriveSettle(d);
            long[] s2 = NativeCore.deriveStats(d);
            int equalWidth = compare(d, corpus, 410, failures);
            /* Undemanded paragraphs cost nothing when edited; demanding them again recomputes. */
            for (int i = 0; i < n; i += 2) NativeCore.deriveDemand(d, i, false);
            for (int i = 0; i < n; i += 2) { corpus.set(i, corpus.get(i) + "!"); NativeCore.deriveText(d, i, corpus.get(i)); }
            NativeCore.deriveSettle(d);
            long[] s3 = NativeCore.deriveStats(d);
            for (int i = 0; i < n; i += 2) NativeCore.deriveDemand(d, i, true);
            NativeCore.deriveSettle(d);
            long[] s4 = NativeCore.deriveStats(d);
            int equalAfter = compare(d, corpus, 410, failures);
            int half = (n + 1) / 2;
            boolean ok = settled && s0[3] == 0 && s4[3] == 0 && equal == n && equalEdit == n && equalWidth == n && equalAfter == n
                && shared == duplicates && duplicates > 0
                && s0[0] == 2L * n && s1[0] - s0[0] == 1 && s2[0] - s1[0] == n && s3[0] == s2[0] && s4[0] - s3[0] == half;
            out.put("ok", ok);
            out.put("paragraphs", n);
            out.put("identical", Arrays.asList(equal, equalEdit, equalWidth, equalAfter));
            out.put("duplicates", duplicates); out.put("shared_handles", shared);
            out.put("jobs", Json.map("first", s0[0], "edit", s1[0] - s0[0], "width", s2[0] - s1[0],
                "undemanded_edits", s3[0] - s2[0], "redemand", s4[0] - s3[0]));
            out.put("blobs", Json.map("interned", s4[4], "deduplicated", s4[5], "live", s4[6], "bytes", s4[7]));
            out.put("error", s4[3]);
            out.put("failures", failures.subList(0, Math.min(6, failures.size())));
        } finally {
            NativeCore.deriveClose(d);
        }
        out.put("font_probe", fontProbe());
        /* Timing: the whole corpus, cold graph, warm caches. */
        long t0 = System.nanoTime();
        for (String t : corpus) DeriveLayout.layout(t, 655);
        out.put("direct_ms", (System.nanoTime() - t0) / 1e6);
        List<Object> timing = new ArrayList<>();
        for (int w : new int[]{1, 2, 4, 8}) {
            long h = NativeCore.deriveOpen(w, n);
            NativeCore.deriveWidth(h, 655);
            for (int i = 0; i < n; i++) NativeCore.deriveText(h, i, corpus.get(i));
            long start = System.nanoTime();
            for (int i = 0; i < n; i++) NativeCore.deriveDemand(h, i, true);
            NativeCore.deriveSettle(h);
            double ms = (System.nanoTime() - start) / 1e6;
            long[] s = NativeCore.deriveStats(h);
            timing.add(Json.map("workers", w, "ms", ms, "operator_cpu_ms", s[8] / 1e6, "queue_wait_ms", s[9] / 1e6, "traced", s[10]));
            NativeCore.deriveClose(h);
        }
        out.put("timing", timing);
        /* The same corpus on plain Java threads, no executor: separates executor cost from the
         * platform text stack's own scaling. */
        List<Object> threads = new ArrayList<>();
        for (int w : new int[]{1, 2, 4, 8}) {
            final int workers = w;
            Thread[] pool = new Thread[w];
            final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
            long start = System.nanoTime();
            for (int k = 0; k < w; k++) {
                pool[k] = new Thread(new Runnable() { public void run() {
                    for (int i; (i = next.getAndIncrement()) < corpus.size();) DeriveLayout.layout(corpus.get(i), 655);
                }});
                pool[k].start();
            }
            for (Thread t : pool) { try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
            threads.add(Json.map("threads", workers, "ms", (System.nanoTime() - start) / 1e6));
        }
        out.put("java_threads", threads);
        out.put("stage_scaling", stageScaling(corpus));
        return out;
    }

    /** Each platform stage alone over the corpus on 1 and 4 threads (ms): where layout's
     * multi-thread ceiling sits. */
    static Map<String, Object> stageScaling(final List<String> corpus) {
        Map<String, Object> r = new LinkedHashMap<>();
        String[] stages = {"shape", "break", "bidi"};
        for (final String stage : stages) {
            List<Object> row = new ArrayList<>();
            for (int w : new int[]{1, 4}) {
                Thread[] pool = new Thread[w];
                final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
                long start = System.nanoTime();
                for (int k = 0; k < w; k++) {
                    pool[k] = new Thread(new Runnable() { public void run() {
                        android.graphics.Paint p = new android.graphics.Paint();
                        p.setTextSize(DeriveLayout.TEXT_SIZE);
                        for (int i; (i = next.getAndIncrement()) < corpus.size();) {
                            String t = corpus.get(i); char[] c = t.toCharArray();
                            if (stage.equals("shape")) android.graphics.text.TextRunShaper.shapeTextRun(c, 0, c.length, 0, c.length, 0, 0, false, p);
                            else if (stage.equals("break")) {
                                android.graphics.text.MeasuredText m = new android.graphics.text.MeasuredText.Builder(c).appendStyleRun(p, c.length, false).build();
                                android.graphics.text.LineBreaker.ParagraphConstraints pc = new android.graphics.text.LineBreaker.ParagraphConstraints();
                                pc.setWidth(655);
                                new android.graphics.text.LineBreaker.Builder().setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
                                    .build().computeLineBreaks(m, pc, 0);
                            } else new java.text.Bidi(c, 0, null, 0, c.length, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
                        }
                    }});
                    pool[k].start();
                }
                for (Thread t : pool) { try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
                row.add((System.nanoTime() - start) / 1e6);
            }
            r.put(stage, row);
        }
        return r;
    }

    /** Per-call cost (us) of ways to name the face behind a fresh PositionedGlyphs Font. */
    static Map<String, Object> fontProbe() {
        android.graphics.Paint p = new android.graphics.Paint();
        p.setTextSize(36);
        String t = "Trough fjord office";
        int n = 2000;
        android.graphics.fonts.Font[] fs = new android.graphics.fonts.Font[n];
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) fs[i] = android.graphics.text.TextRunShaper.shapeTextRun(t, 0, t.length(), 0, t.length(), 0, 0, false, p).getFont(0);
        long shape = System.nanoTime() - t0;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("shape_us", shape / 1e3 / n);
        r.put("distinct_objects", new java.util.IdentityHashMap<Object, Object>() {{ for (Object f : fs) put(f, f); }}.size());
        t0 = System.nanoTime(); int h = 0; for (android.graphics.fonts.Font f : fs) h += f.hashCode(); r.put("hashCode_us", (System.nanoTime() - t0) / 1e3 / n);
        t0 = System.nanoTime(); int eq = 0; for (int i = 1; i < n; i++) if (fs[i].equals(fs[0])) eq++; r.put("equals_us", (System.nanoTime() - t0) / 1e3 / n);
        t0 = System.nanoTime(); for (android.graphics.fonts.Font f : fs) h += f.getFile() == null ? 0 : 1; r.put("getFile_us", (System.nanoTime() - t0) / 1e3 / n);
        t0 = System.nanoTime(); for (android.graphics.fonts.Font f : fs) h += f.getBuffer().capacity(); r.put("getBuffer_us", (System.nanoTime() - t0) / 1e3 / n);
        t0 = System.nanoTime(); for (android.graphics.fonts.Font f : fs) h += f.getStyle().getWeight(); r.put("getStyle_us", (System.nanoTime() - t0) / 1e3 / n);
        t0 = System.nanoTime(); for (android.graphics.fonts.Font f : fs) h += (int) f.getSourceIdentifier(); r.put("sourceIdentifier_us", (System.nanoTime() - t0) / 1e3 / n);
        t0 = System.nanoTime(); for (android.graphics.fonts.Font f : fs) h += System.identityHashCode(f.getBuffer()); r.put("bufferIdentity_us", (System.nanoTime() - t0) / 1e3 / n);
        r.put("equal_to_first", eq);
        r.put("same_source_identifier", fs[0].getSourceIdentifier() == fs[n - 1].getSourceIdentifier());
        r.put("sink", h);
        return r;
    }

    private static int compare(long d, List<String> corpus, int width, List<Object> failures) {
        int equal = 0;
        for (int i = 0; i < corpus.size(); i++) {
            byte[] got = NativeCore.deriveBytes(d, i), want = DeriveLayout.layout(corpus.get(i), width);
            if (Arrays.equals(got, want)) equal++;
            else if (failures.size() < 6) failures.add(Json.map("paragraph", i, "width", width,
                "got", got == null ? -1 : got.length, "want", want.length));
        }
        return equal;
    }

    /** Mixed-script paragraphs of varying length; every 8th repeats an earlier one exactly. */
    static List<String> corpus(int n) {
        String[] words = {"layout", "derivation", "Trough", "office", "affluent", "שלום", "עברית", "مرحبا", "بالعالم",
            "हिन्दी", "पाठ", "中文", "日本語", "12345", "Ελληνικά", "fjord", "quick", "brown", "fox", "—", "text,", "lines."};
        Random r = new Random(42);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (i % 8 == 7) { out.add(out.get(i - 5)); continue; }
            StringBuilder b = new StringBuilder();
            int count = 4 + r.nextInt(60);
            for (int k = 0; k < count; k++) { if (k > 0) b.append(' '); b.append(words[r.nextInt(words.length)]); }
            out.add(b.toString());
        }
        return out;
    }
}
