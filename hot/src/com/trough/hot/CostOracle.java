package com.trough.hot;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.*;

/** android.cost: what the derived view's platform operators cost in memory, measured on this
 * thread so executor admission (docs/EXEC.md "Admission") can charge real estimates.
 * Layout: paragraphs of 16 to 16384 UTF-16 units in the shared corpus's scripts; masks: every
 * distinct glyph the corpus lays out, mono and colour. Per call: bytes the Java heap allocated
 * on this thread (ART's per-thread allocation counter, an upper bound on Java live bytes), the
 * native heap's growth, and the operator's output size. The executor's model
 * (NativeCore.deriveCost, native/android/derive.c) must cover every sample. Then the real layout
 * operator runs under a budget of 2.5 large paragraphs: the charged peak stays within it and
 * the layouts equal direct ones. Offscreen. */
final class CostOracle {
    static Map<String, Object> run(File bundle) {
        NativeCore.load(bundle);
        Map<String, Object> out = new LinkedHashMap<>();
        boolean counting = startCounting();
        out.put("thread_alloc_counter", counting);
        List<String> corpus = LayoutDeriveOracle.corpus(240);
        StringBuilder all = new StringBuilder();
        for (String t : corpus) { if (all.length() > 0) all.append(' '); all.append(t); }
        while (all.length() < 16384) all.append(' ').append(all);
        for (String t : corpus) DeriveLayout.layout(t, 655);   /* warm JIT and font caches */
        List<Object> layouts = new ArrayList<>();
        long layoutUnder = Long.MIN_VALUE; double layoutOver = 0;
        int[] lengths = {16, 64, 256, 1024, 4096, 16384};
        Random r = new Random(7);
        for (int length : lengths) for (int s = 0; s < 4; s++) {
            int start = r.nextInt(all.length() - length);
            if (Character.isLowSurrogate(all.charAt(start))) start++;
            String text = all.substring(start, start + length);
            if (Character.isHighSurrogate(text.charAt(text.length() - 1))) text = text.substring(0, text.length() - 1);
            DeriveLayout.layout(text, 655);   /* this text's runs warm in the shaping cache, as on a re-layout */
            long[] m = measure(counting, new LayoutCall(text, 655));
            long used = m[0] + Math.max(0, m[1]), model = NativeCore.deriveCost(0, text.length());
            layoutUnder = Math.max(layoutUnder, used - model);
            layoutOver = Math.max(layoutOver, model / (double) Math.max(1, used));
            layouts.add(Json.map("units", text.length(), "java", m[0], "native", m[1], "output", m[2], "us", m[3] / 1000, "model", model));
        }
        out.put("layout", layouts);
        out.put("layout_model", Json.map("under_bytes", layoutUnder, "worst_over_ratio", layoutOver));
        /* Masks: every distinct glyph of the corpus. */
        Set<Long> glyphs = new LinkedHashSet<>();
        for (String t : corpus) {
            ByteBuffer b = ByteBuffer.wrap(DeriveLayout.layout(t, 655));
            int lines = b.getInt();
            b.position(b.position() + lines * 25);
            int n = b.getInt();
            for (int g = 0; g < n; g++) { int face = b.getInt(), id = b.getInt(); b.getFloat(); b.getFloat(); glyphs.add((long) face << 32 | (id & 0xffffffffL)); }
        }
        long maskMax = 0, maskModel = NativeCore.deriveCost(1, 0);
        int mono = 0, colour = 0;
        List<Long> monoBytes = new ArrayList<>(), colourBytes = new ArrayList<>();
        for (long k : glyphs) {
            int face = (int) (k >>> 32), id = (int) k;
            DeriveLayout.mask(face, id);
            long[] m = measure(counting, new MaskCall(face, id));
            boolean c = DeriveLayout.colorFace(face);
            long used = m[0] + Math.max(0, m[1]);
            if (c) { colour++; colourBytes.add(used); } else { mono++; monoBytes.add(used); }
            maskMax = Math.max(maskMax, used);
        }
        out.put("mask", Json.map("glyphs", glyphs.size(), "mono", mono, "colour", colour,
            "mono", summary(monoBytes), "colour", summary(colourBytes), "max", maskMax, "model", maskModel));
        Map<String, Object> admission = admission(all.toString());
        out.put("admission", admission);
        out.put("ok", counting && layoutUnder <= 0 && maskMax <= maskModel && Boolean.TRUE.equals(admission.get("ok")));
        return out;
    }

    /** 12 paragraphs of 16000 units on 4 workers, with a budget of 2.5 of their layouts and
     * without admission. (The process native heap is not a usable peak signal here: the GC's
     * frees of earlier garbage dominate it, so the charged peak is the measure.) */
    static Map<String, Object> admission(String all) {
        List<String> big = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            int start = (i * 4099) % (all.length() - 16384);
            if (Character.isLowSurrogate(all.charAt(start))) start++;
            String t = all.substring(start, start + 16000) + " #" + i;
            big.add(t);
        }
        long cost = NativeCore.deriveCost(0, big.get(0).length() + 8);
        long budget = cost * 5 / 2;
        Map<String, Object> r = new LinkedHashMap<>();
        List<Object> failures = new ArrayList<>();
        boolean ok = true;
        for (int pass = 0; pass < 2; pass++) {
            NativeCore.deriveAdmission(pass == 0 ? budget : 0, 0);
            long d = NativeCore.deriveOpen(4, big.size());
            NativeCore.deriveAdmission(NativeCore.admissionBudget(), NativeCore.admissionBudget() / 4);
            if (d < 0) { r.put("error", "deriveOpen " + d); r.put("ok", false); return r; }
            try {
                NativeCore.deriveWidth(d, 655);
                for (int i = 0; i < big.size(); i++) NativeCore.deriveText(d, i, big.get(i));
                long t0 = System.nanoTime();
                for (int i = 0; i < big.size(); i++) NativeCore.deriveDemand(d, i, true);
                boolean settled = NativeCore.deriveSettle(d) > 0;
                long ms = (System.nanoTime() - t0) / 1000000;
                long[] st = NativeCore.deriveStats(d);
                int equal = LayoutDeriveOracle.compare(d, big, 655, failures);
                Map<String, Object> row = Json.map("budget", st[29], "peak_charged", st[27], "deferred", st[28],
                    "ms", ms, "identical", equal, "error", st[3]);
                r.put(pass == 0 ? "admitted" : "unlimited", row);
                ok &= settled && st[3] == 0 && equal == big.size();
                if (pass == 0) ok &= st[29] == budget && st[27] <= budget && st[27] >= 2 * NativeCore.deriveCost(0, 16000) && st[28] > 0;
            } finally {
                NativeCore.deriveClose(d);
            }
        }
        r.put("layout_cost", cost);
        r.put("failures", failures.subList(0, Math.min(4, failures.size())));
        r.put("ok", ok);
        return r;
    }
    interface Call { int run(); }
    static final class LayoutCall implements Call {
        final String text; final int width;
        LayoutCall(String text, int width) { this.text = text; this.width = width; }
        public int run() { return DeriveLayout.layout(text, width).length; }
    }
    static final class MaskCall implements Call {
        final int face, glyph;
        MaskCall(int face, int glyph) { this.face = face; this.glyph = glyph; }
        public int run() { return DeriveLayout.mask(face, glyph).length; }
    }

    /** {java bytes, native growth, output bytes, ns}: medians of 5 calls, each after a GC so the
     * collector's own frees (native peers of earlier garbage) do not land inside the call. */
    static long[] measure(boolean counting, Call c) {
        long[][] runs = new long[4][5];
        for (int i = 0; i < 5; i++) {
            Runtime.getRuntime().gc();
            long j0 = counting ? android.os.Debug.getThreadAllocSize() : 0;
            long n0 = android.os.Debug.getNativeHeapAllocatedSize();
            long t0 = System.nanoTime();
            int output = c.run();
            long t1 = System.nanoTime();
            long n1 = android.os.Debug.getNativeHeapAllocatedSize();
            long j1 = counting ? android.os.Debug.getThreadAllocSize() : 0;
            runs[0][i] = j1 - j0; runs[1][i] = n1 - n0; runs[2][i] = output; runs[3][i] = t1 - t0;
        }
        long[] m = new long[4];
        for (int k = 0; k < 4; k++) { Arrays.sort(runs[k]); m[k] = runs[k][2]; }
        return m;
    }

    @SuppressWarnings("deprecation")
    static boolean startCounting() {
        try {
            android.os.Debug.resetThreadAllocSize();
            android.os.Debug.startAllocCounting();
            new byte[4096].hashCode();
            return android.os.Debug.getThreadAllocSize() >= 4096;
        } catch (Throwable t) {
            return false;
        }
    }

    static Map<String, Object> summary(List<Long> v) {
        if (v.isEmpty()) return Json.map("n", 0);
        List<Long> s = new ArrayList<>(v);
        Collections.sort(s);
        return Json.map("n", s.size(), "min", s.get(0), "p50", s.get(s.size() / 2), "p90", s.get(s.size() * 9 / 10), "max", s.get(s.size() - 1));
    }
}
