package com.trough.hot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny personal swipe policy. Only explicit corrected words update its weights. */
final class PersonalSwipeModel {
    private static final int FEATURES = 12, POINTS = 32, CACHE = 128;
    private static final float OFFSET_BOUND = 0.45f, WEIGHT_BOUND = 3f;
    private final float[] weights = new float[FEATURES];
    private final float[] offsetX = new float[26], offsetY = new float[26];
    private final LinkedHashMap<String, Integer> words = new LinkedHashMap<String, Integer>();
    private final LinkedHashMap<String, Integer> contexts = new LinkedHashMap<String, Integer>();
    private long version, examples, updates, lastFeedbackMs;
    private String lastWord = "", lastSource = "";

    PersonalSwipeModel() { weights[0] = 1f; weights[1] = 1f; weights[2] = 0.08f; weights[3] = 0.04f; }

    /** Geometry and prior are lower-is-better scores; priors may be null. No training here. */
    synchronized String[] rank(String[] candidates, float[] geometry, float[] priors, String context) {
        if (candidates == null || geometry == null || candidates.length > 64
                || geometry.length < candidates.length || (priors != null && priors.length < candidates.length)) {
            return new String[0];
        }
        ArrayList<String> result = new ArrayList<String>();
        ArrayList<Float> scores = new ArrayList<Float>();
        HashSet<String> seen = new HashSet<String>();
        for (int i = 0; i < candidates.length; i++) {
            String word = candidates[i];
            float prior = priors == null ? 0f : priors[i];
            if (!validWord(word) || !validScore(geometry[i]) || !validScore(prior) || !seen.add(word)) continue;
            float score = dot(features(word, geometry[i], prior, context));
            int at = result.size();
            while (at > 0 && score > scores.get(at - 1).floatValue()) at--;
            result.add(at, word);
            scores.add(at, Float.valueOf(score));
        }
        return result.toArray(new String[result.size()]);
    }

    /**
     * A user-selected alternative is the training label. Coordinates use the raw keyboard layout,
     * not an already adjusted layout. A failed geometric calibration still permits a valid rank label.
     */
    synchronized boolean teach(String intended, String original, String[] candidates, float[] geometry,
                               float[] priors, String context, float[] pathX, float[] pathY, int count,
                               float[] keyX, float[] keyY) {
        if (!validWord(intended) || !validWord(original) || intended.equals(original)
                || candidates == null || geometry == null || candidates.length > 64
                || geometry.length < candidates.length || (priors != null && priors.length < candidates.length)
                || version >= 9007199254740990L) return false;
        int positive = index(candidates, intended), negative = index(candidates, original);
        // Explicit alternatives must be present in the scored evidence shown to the user.
        if (positive < 0 || negative < 0 || !validScore(geometry[positive]) || !validScore(geometry[negative])
                || (priors != null && (!validScore(priors[positive]) || !validScore(priors[negative])))) return false;
        float[] a = features(intended, geometry[positive], priors == null ? 0 : priors[positive], context);
        float[] b = features(original, geometry[negative], priors == null ? 0 : priors[negative], context);
        // Pairwise logistic SGD makes the selected candidate more likely for similar future gestures.
        for (int step = 0; step < 8; step++) {
            float margin = 0;
            for (int j = 0; j < FEATURES; j++) margin += weights[j] * (a[j] - b[j]);
            float error = 1f / (1f + (float) Math.exp(Math.max(-20f, Math.min(20f, margin))));
            for (int j = 0; j < FEATURES; j++) {
                weights[j] = clamp(weights[j] + 0.24f * (error * (a[j] - b[j]) - 0.0005f * weights[j]), -WEIGHT_BOUND, WEIGHT_BOUND);
            }
            // Geometry remains an anchor even after many corrections.
            weights[0] = clamp(weights[0], 0.25f, WEIGHT_BOUND);
            updates++;
        }
        calibrate(intended, pathX, pathY, count, keyX, keyY);
        increment(words, intended);
        String before = contextWord(context);
        if (before.length() > 0) increment(contexts, before + " " + intended);
        version++;
        examples++;
        lastWord = intended;
        lastSource = "swipe-correction";
        lastFeedbackMs = System.currentTimeMillis();
        return true;
    }

    /** Caller owns the returned arrays. Offsets are in key-pitch units, so resize is safe. */
    synchronized float[][] adjustedLayout(float[] xs, float[] ys) {
        float[] x = xs == null ? new float[0] : xs.clone();
        float[] y = ys == null ? new float[0] : ys.clone();
        float pitch = layoutPitch(xs, ys);
        if (pitch > 0) for (int i = 0; i < 26; i++) { x[i] += offsetX[i] * pitch; y[i] += offsetY[i] * pitch; }
        return new float[][] {x, y};
    }

    synchronized String[] personalWords() { return words.keySet().toArray(new String[words.size()]); }

    synchronized Map<String, Object> state() {
        double norm = 0;
        for (float w : weights) norm += w * w;
        for (int i = 0; i < 26; i++) norm += offsetX[i] * offsetX[i] + offsetY[i] * offsetY[i];
        return Json.map("model", "personal-swipe-sgd", "parameters", 64, "weights_version", version,
                "examples", examples, "updates", updates, "weight_norm", Math.sqrt(norm),
                "personal_words", words.size(), "contexts", contexts.size(), "last_feedback", lastWord,
                "last_source", lastSource, "last_feedback_ms", lastFeedbackMs);
    }

    synchronized String encode() {
        return Json.write(Json.map("schema", 1, "version", version, "examples", examples, "updates", updates,
                "weights", floats(weights), "offset_x", floats(offsetX), "offset_y", floats(offsetY),
                "words", counts(words), "contexts", counts(contexts), "last_word", lastWord,
                "last_source", lastSource, "last_feedback_ms", lastFeedbackMs));
    }

    /** Corrupt snapshots fail atomically, retaining the current model. */
    synchronized boolean restore(String encoded) {
        if (encoded == null || encoded.length() > 65536) return false;
        try {
            Map<String, Object> m = Json.obj(Json.parse(encoded));
            if (m == null || integer(m.get("schema")) != 1) return false;
            long v = integer(m.get("version")), e = integer(m.get("examples")), u = integer(m.get("updates"));
            long at = integer(m.get("last_feedback_ms"));
            if (v < 0 || e != v || u != e * 8 || at < 0 || e > 1000000000000L) return false;
            float[] w = readFloats(m.get("weights"), FEATURES, WEIGHT_BOUND);
            float[] x = readFloats(m.get("offset_x"), 26, OFFSET_BOUND);
            float[] y = readFloats(m.get("offset_y"), 26, OFFSET_BOUND);
            LinkedHashMap<String, Integer> savedWords = readCounts(m.get("words"), false);
            LinkedHashMap<String, Integer> savedContexts = readCounts(m.get("contexts"), true);
            String last = Json.str(m, "last_word"), source = Json.str(m, "last_source");
            if (w == null || w[0] < 0.25f || x == null || y == null || savedWords == null || savedContexts == null
                    || last == null || (last.length() > 0 && !validWord(last)) || source == null
                    || !(source.length() == 0 || source.equals("swipe-correction"))) return false;
            System.arraycopy(w, 0, weights, 0, FEATURES);
            System.arraycopy(x, 0, offsetX, 0, 26);
            System.arraycopy(y, 0, offsetY, 0, 26);
            words.clear(); words.putAll(savedWords); contexts.clear(); contexts.putAll(savedContexts);
            version = v; examples = e; updates = u; lastFeedbackMs = at; lastWord = last; lastSource = source;
            return true;
        } catch (RuntimeException badSnapshot) { return false; }
    }

    private float[] features(String word, float geometry, float prior, String context) {
        float[] f = new float[FEATURES];
        f[0] = -Math.min(geometry, 4f); f[1] = -Math.min(prior, 1f);
        Integer seen = words.get(word), paired = contexts.get(contextWord(context) + " " + word);
        f[2] = seen == null ? 0 : (float) Math.log1p(seen.intValue()) / 4f;
        f[3] = paired == null ? 0 : (float) Math.log1p(paired.intValue()) / 4f;
        // Bounded signed character features let one correction generalise to similar spellings.
        int previous = 0;
        for (int i = 0; i < word.length(); i++) {
            int c = word.charAt(i) - 'a' + 1;
            int hash = (c * 131 + previous * 31 + i * 17) * 0x45d9f3b;
            f[4 + ((hash >>> 1) & 7)] += (hash & 1) == 0 ? 1f : -1f;
            previous = c;
        }
        float scale = 1f / (float) Math.sqrt(word.length());
        for (int i = 4; i < FEATURES; i++) f[i] = clamp(f[i] * scale, -2f, 2f);
        return f;
    }

    private float dot(float[] f) { float result = 0; for (int j = 0; j < FEATURES; j++) result += weights[j] * f[j]; return result; }

    /** DTW alignment supplies the squared-error gradient of each letter's 2D touch offset. */
    private void calibrate(String word, float[] px, float[] py, int count, float[] kx, float[] ky) {
        float pitch = layoutPitch(kx, ky);
        if (pitch <= 0 || px == null || py == null || count < 2 || count > 2048 || count > px.length || count > py.length) return;
        float[] path = new float[POINTS * 2];
        float[] raw = new float[count * 2];
        for (int i = 0; i < count; i++) {
            if (!finite(px[i]) || !finite(py[i]) || Math.abs(px[i] / pitch) > 10000 || Math.abs(py[i] / pitch) > 10000) return;
            raw[i * 2] = px[i] / pitch; raw[i * 2 + 1] = py[i] / pitch;
        }
        if (resample(raw, count, path) < 0.65f) return;
        int n = 0;
        int[] letters = new int[word.length()];
        for (int i = 0; i < word.length(); i++) {
            int key = word.charAt(i) - 'a';
            if (n == 0 || key != letters[n - 1]) letters[n++] = key;
        }
        if (n < 2) return;
        float[] vertices = new float[n * 2], template = new float[POINTS * 2];
        for (int i = 0; i < n; i++) { int key = letters[i]; vertices[i * 2] = kx[key] / pitch + offsetX[key]; vertices[i * 2 + 1] = ky[key] / pitch + offsetY[key]; }
        float[] lengths = new float[n];
        for (int i = 1; i < n; i++) lengths[i] = lengths[i - 1] + distance(vertices, i - 1, vertices, i);
        float total = lengths[n - 1];
        if (total < 0.65f || total > 100f) return;
        int[] lower = new int[POINTS]; float[] mixes = new float[POINTS];
        int segment = 1;
        for (int p = 0; p < POINTS; p++) {
            float target = total * p / (POINTS - 1);
            while (segment < n - 1 && target > lengths[segment]) segment++;
            float mix = (target - lengths[segment - 1]) / Math.max(0.0001f, lengths[segment] - lengths[segment - 1]);
            lower[p] = segment - 1; mixes[p] = mix;
            template[p * 2] = vertices[(segment - 1) * 2] * (1 - mix) + vertices[segment * 2] * mix;
            template[p * 2 + 1] = vertices[(segment - 1) * 2 + 1] * (1 - mix) + vertices[segment * 2 + 1] * mix;
        }
        float[][] dp = new float[POINTS + 1][POINTS + 1];
        for (float[] row : dp) Arrays.fill(row, Float.MAX_VALUE);
        dp[0][0] = 0;
        for (int i = 1; i <= POINTS; i++) for (int j = Math.max(1, i - 8); j <= Math.min(POINTS, i + 8); j++) {
            float dx = path[(i - 1) * 2] - template[(j - 1) * 2], dy = path[(i - 1) * 2 + 1] - template[(j - 1) * 2 + 1];
            dp[i][j] = dx * dx + dy * dy + Math.min(dp[i - 1][j - 1], Math.min(dp[i - 1][j], dp[i][j - 1]));
        }
        // Ignore wildly incompatible labels for calibration; ranking still learns their explicit preference.
        if (dp[POINTS][POINTS] / POINTS > 1.5f) return;
        float[] gx = new float[26], gy = new float[26], mass = new float[26];
        int i = POINTS, j = POINTS;
        while (i > 0 && j > 0) {
            int p = i - 1, q = j - 1, lo = lower[q];
            float dx = clamp(path[p * 2] - template[q * 2], -0.6f, 0.6f), dy = clamp(path[p * 2 + 1] - template[q * 2 + 1], -0.6f, 0.6f);
            for (int side = 0; side < 2; side++) {
                int key = letters[lo + side]; float weight = side == 0 ? 1 - mixes[q] : mixes[q];
                gx[key] += weight * dx; gy[key] += weight * dy; mass[key] += weight;
            }
            float diagonal = dp[i - 1][j - 1], up = dp[i - 1][j], left = dp[i][j - 1];
            if (diagonal <= up && diagonal <= left) { i--; j--; } else if (up < left) i--; else j--;
        }
        for (int key = 0; key < 26; key++) if (mass[key] > 0) {
            offsetX[key] = clamp(offsetX[key] + 0.16f * gx[key] / mass[key], -OFFSET_BOUND, OFFSET_BOUND);
            offsetY[key] = clamp(offsetY[key] + 0.16f * gy[key] / mass[key], -OFFSET_BOUND, OFFSET_BOUND);
        }
    }

    private static float resample(float[] points, int n, float[] out) {
        float[] lengths = new float[n];
        for (int i = 1; i < n; i++) lengths[i] = lengths[i - 1] + distance(points, i - 1, points, i);
        float total = lengths[n - 1]; int segment = 1;
        for (int p = 0; p < POINTS; p++) {
            float target = total * p / (POINTS - 1);
            while (segment < n - 1 && target > lengths[segment]) segment++;
            float mix = (target - lengths[segment - 1]) / Math.max(0.0001f, lengths[segment] - lengths[segment - 1]);
            out[p * 2] = points[(segment - 1) * 2] * (1 - mix) + points[segment * 2] * mix;
            out[p * 2 + 1] = points[(segment - 1) * 2 + 1] * (1 - mix) + points[segment * 2 + 1] * mix;
        }
        return total;
    }

    private static float layoutPitch(float[] x, float[] y) {
        if (x == null || y == null || x.length < 26 || y.length < 26) return -1;
        float[] distances = new float[26];
        for (int i = 0; i < 26; i++) {
            if (!finite(x[i]) || !finite(y[i]) || Math.abs(x[i]) > 1000000 || Math.abs(y[i]) > 1000000) return -1;
            float best = Float.MAX_VALUE;
            for (int j = 0; j < 26; j++) if (i != j) {
                float dx = x[i] - x[j], dy = y[i] - y[j]; best = Math.min(best, dx * dx + dy * dy);
            }
            distances[i] = (float) Math.sqrt(best);
        }
        Arrays.sort(distances); float pitch = (distances[12] + distances[13]) * 0.5f;
        return finite(pitch) && pitch > 0.01f && distances[0] > pitch * 0.2f ? pitch : -1;
    }

    private static float distance(float[] a, int i, float[] b, int j) { float dx = a[i * 2] - b[j * 2], dy = a[i * 2 + 1] - b[j * 2 + 1]; return (float) Math.sqrt(dx * dx + dy * dy); }
    private static int index(String[] a, String value) { for (int i = 0; i < a.length; i++) if (value.equals(a[i])) return i; return -1; }
    private static boolean finite(float n) { return !Float.isNaN(n) && !Float.isInfinite(n); }
    private static boolean validScore(float n) { return finite(n) && n >= 0 && n <= 20; }
    private static float clamp(float n, float lo, float hi) { return Math.max(lo, Math.min(hi, n)); }
    private static boolean validWord(String word) { if (word == null || word.length() < 1 || word.length() > 32) return false; for (int i = 0; i < word.length(); i++) if (word.charAt(i) < 'a' || word.charAt(i) > 'z') return false; return true; }
    private static String contextWord(String text) {
        if (text == null) return "";
        int end = text.length(); while (end > 0 && !asciiLetter(text.charAt(end - 1))) end--;
        int start = end; while (start > 0 && asciiLetter(text.charAt(start - 1)) && end - start < 32) start--;
        if (start > 0 && asciiLetter(text.charAt(start - 1))) return "";
        return text.substring(start, end).toLowerCase(java.util.Locale.US);
    }
    private static boolean asciiLetter(char c) { return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'; }
    private static void increment(LinkedHashMap<String, Integer> map, String key) { Integer old = map.remove(key); map.put(key, Integer.valueOf(old == null ? 1 : Math.min(1000, old.intValue() + 1))); while (map.size() > CACHE) map.remove(map.keySet().iterator().next()); }
    private static List<Object> floats(float[] values) { ArrayList<Object> a = new ArrayList<Object>(); for (float value : values) a.add(Float.valueOf(value)); return a; }
    private static Map<String, Object> counts(LinkedHashMap<String, Integer> values) { Map<String, Object> result = new LinkedHashMap<String, Object>(); for (Map.Entry<String, Integer> e : values.entrySet()) result.put(e.getKey(), e.getValue()); return result; }
    private static long integer(Object value) { if (!(value instanceof Number)) return -1; double n = ((Number) value).doubleValue(); return !Double.isNaN(n) && !Double.isInfinite(n) && n >= 0 && n <= 9007199254740991d && n == Math.rint(n) ? (long) n : -1; }
    private static float[] readFloats(Object object, int count, float bound) {
        List<Object> a = Json.arr(object); if (a == null || a.size() != count) return null;
        float[] result = new float[count];
        for (int i = 0; i < count; i++) { if (!(a.get(i) instanceof Number)) return null; double n = ((Number) a.get(i)).doubleValue(); if (Double.isNaN(n) || Double.isInfinite(n) || Math.abs(n) > bound) return null; result[i] = (float) n; }
        return result;
    }
    private static LinkedHashMap<String, Integer> readCounts(Object object, boolean paired) {
        Map<String, Object> m = Json.obj(object); if (m == null || m.size() > CACHE) return null;
        LinkedHashMap<String, Integer> result = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            String key = e.getKey(); long n = integer(e.getValue());
            if (n < 1 || n > 1000) return null;
            if (paired) { int split = key.indexOf(' '); if (split < 1 || !validWord(key.substring(0, split)) || !validWord(key.substring(split + 1))) return null; }
            else if (!validWord(key)) return null;
            result.put(key, Integer.valueOf((int) n));
        }
        return result;
    }
}
