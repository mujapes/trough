package com.trough.hot;

import java.io.File;
import java.text.Bidi;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Compare native paragraph/line bidi with the Java API used by TextLayout. */
final class BidiOracle {
    private int cases, units, runs, failures;
    private final List<String> details = new ArrayList<>();
    private void fail(String message) { failures++; if (details.size() < 16) details.add(message); }
    private void check(String text, int start, int end, String label) {
        check(text, start, end, label, NativeCore.bidiLine(text.toCharArray(), start, end));
    }
    private void check(String text, int start, int end, String label, int[] got) {
        cases++;
        Bidi paragraph = new Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
        if (got == null || got.length < 3 || got[0] != paragraph.getBaseLevel() || got[2] != end - start) {
            fail(label + " header"); return;
        }
        if (start == end) { if (got.length != 3 || got[1] != 0) fail(label + " empty line"); return; }
        Bidi line = paragraph.createLineBidi(start, end);
        int count = line.getRunCount();
        if (got[1] != count || got.length != 3 + end - start + count * 3) {
            fail(label + " run count native=" + got[1] + " java=" + count); return;
        }
        for (int i = 0; i < end - start; i++) {
            units++;
            if (got[3 + i] != line.getLevelAt(i)) fail(label + " level@" + i);
        }
        byte[] levels = new byte[count]; Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) { levels[i] = (byte)line.getRunLevel(i); order[i] = i; }
        Bidi.reorderVisually(levels, 0, order, 0, count);
        int at = 3 + end - start;
        for (int i : order) {
            runs++;
            int rs = got[at++], re = got[at++], level = got[at++];
            if (rs != start + line.getRunStart(i) || re != start + line.getRunLimit(i) || level != line.getRunLevel(i))
                fail(label + " visual run got=" + rs + ":" + re + "/" + level + " expected="
                    + (start + line.getRunStart(i)) + ":" + (start + line.getRunLimit(i)) + "/" + line.getRunLevel(i));
        }
    }
    static Map<String, Object> run(File bundle) throws Exception {
        NativeCore.load(bundle);
        BidiOracle o = new BidiOracle();
        List<String> texts = new ArrayList<>();
        for (String text : ShapeOracle.TEXT) texts.add(text);
        texts.add("  \t"); texts.add("אב 123 (abc)  \t");
        texts.add("a\u202bאב\u202a12 x\u202cגד\u202c z");
        texts.add("\u2067אב \u2066abc 12\u2069 גד\u2069   ");
        texts.add("\u202eabc 123\u202c x\u200e\u200f");
        for (int t = 0; t < texts.size(); t++) {
            String text = texts.get(t); int n = text.length();
            int[] ranges = new int[(n+1)*4];
            for (int i = 0; i <= n; i++) {
                ranges[i*4] = 0; ranges[i*4+1] = i; ranges[i*4+2] = i; ranges[i*4+3] = n;
            }
            int[][] batch = NativeCore.bidiBatch(text.toCharArray(), ranges);
            if (batch == null || batch.length != ranges.length/2+1) o.fail("text=" + t + " retained batch");
            else {
                Bidi para = new Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
                int count = n == 0 ? 0 : para.getRunCount();
                if (batch[0].length != count*3) o.fail("text=" + t + " logical count");
                else for (int r = 0; r < count; r++) {
                    if (batch[0][r*3] != para.getRunStart(r) || batch[0][r*3+1] != para.getRunLimit(r)
                            || batch[0][r*3+2] != para.getRunLevel(r)) o.fail("text=" + t + " logical run=" + r);
                }
                for (int i = 0; i < ranges.length/2; i++)
                    o.check(text, ranges[i*2], ranges[i*2+1], "text=" + t + " retained=" + i, batch[i+1]);
            }
            o.check(text, 0, n, "text=" + t + " whole");
            for (int i = 0; i <= n; i++) {
                o.check(text, 0, i, "text=" + t + " prefix=" + i);
                o.check(text, i, n, "text=" + t + " suffix=" + i);
            }
        }
        final long seed = 0x62696469L; Random random = new Random(seed);
        String[] pieces = {"אב", "العربية", "abc", "12", " ", "\t", "(42)", "👩🏽‍🚀", "\ud800", "\udc00",
            "\u2066", "\u2067", "\u2068", "\u2069", "\u202a", "\u202b", "\u202c", "\u202d", "\u202e"};
        for (int i = 0; i < 128; i++) {
            StringBuilder s = new StringBuilder();
            for (int j = 0, n = 2 + random.nextInt(10); j < n; j++) s.append(pieces[random.nextInt(pieces.length)]);
            String text = s.toString(); int start = random.nextInt(text.length() + 1);
            int end = start + random.nextInt(text.length() - start + 1);
            o.check(text, 0, text.length(), "sample=" + i + " whole");
            o.check(text, start, end, "sample=" + i + " range=" + start + ":" + end);
        }
        boolean invalid = NativeCore.bidiLine(null, 0, 0) == null
            && NativeCore.bidiLine(new char[]{'a'}, -1, 1) == null
            && NativeCore.bidiLine(new char[]{'a'}, 1, 0) == null
            && NativeCore.bidiLine(new char[]{'a'}, 0, 2) == null;
        invalid &= NativeCore.bidiBatch(null, new int[0]) == null
            && NativeCore.bidiBatch(new char[]{'a'}, new int[]{0}) == null
            && NativeCore.bidiBatch(new char[]{'a'}, new int[]{0, 2}) == null;
        return Json.map("ok", invalid && o.failures == 0, "seed", seed, "cases", o.cases,
            "units", o.units, "runs", o.runs, "mismatches", o.failures,
            "invalid_inputs_rejected", invalid, "first_failures", o.details);
    }
}
