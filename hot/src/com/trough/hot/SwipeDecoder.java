package com.trough.hot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

/** Small offline geometric recogniser. Call from one worker, never from onDraw. */
final class SwipeDecoder {
    private static final int LETTERS = 26;
    private static final int POINTS = 32;
    private static final int MAX_WORDS = 24000;
    private static final int MAX_PATH_POINTS = 2048;
    private static final int SHORTLIST = 48;
    private static final int MAX_RESULTS = 16;
    private static final String[] EMPTY = new String[0];
    private static final Candidate[] NO_CANDIDATES = new Candidate[0];

    /** Geometric evidence for the shared personalised ranker; lower values are better. */
    static final class Candidate {
        final String word;
        final float shape, endpoints, frequency, lengthError, score;

        Candidate(String word, float shape, float endpoints, float frequency,
                  float lengthError, float score) {
            this.word = word;
            this.shape = shape;
            this.endpoints = endpoints;
            this.frequency = frequency;
            this.lengthError = lengthError;
            this.score = score;
        }
    }

    private final String[] words;
    private final byte[] first;
    private final byte[] last;
    private final float[] prior;
    private final float[] keyX = new float[LETTERS];
    private final float[] keyY = new float[LETTERS];
    private float[] templates;
    private float[] lengths;
    private float originX, originY, pitch;
    private float maxX, maxY;
    private boolean ready;

    /** Words must be ordered most common first; malformed and duplicate words are ignored. */
    SwipeDecoder(String[] rankedWords) {
        ArrayList<String> valid = new ArrayList<String>();
        HashSet<String> seen = new HashSet<String>();
        if (rankedWords != null) {
            int bound = Math.min(rankedWords.length, MAX_WORDS * 4);
            for (int i = 0; i < bound && valid.size() < MAX_WORDS; i++) {
                String word = rankedWords[i];
                if (validWord(word) && seen.add(word)) valid.add(word);
            }
        }
        words = valid.toArray(new String[valid.size()]);
        first = new byte[words.length];
        last = new byte[words.length];
        prior = new float[words.length];
        for (int i = 0; i < words.length; i++) {
            first[i] = (byte) (words[i].charAt(0) - 'a');
            last[i] = (byte) (words[i].charAt(words[i].length() - 1) - 'a');
            // Frequency breaks geometric ties; it cannot outweigh a clearly better path.
            prior[i] = (float) (0.007 * Math.log1p(i));
        }
    }

    /** Alphabetical a-z centres in the same pixel space as the captured gesture. */
    void setLayout(float[] xs, float[] ys) {
        ready = false;
        if (xs == null || ys == null || xs.length < LETTERS || ys.length < LETTERS) return;
        float[] x = new float[LETTERS];
        float[] y = new float[LETTERS];
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float hiX = -Float.MAX_VALUE, hiY = -Float.MAX_VALUE;
        for (int i = 0; i < LETTERS; i++) {
            if (!finite(xs[i]) || !finite(ys[i]) || Math.abs(xs[i]) > 1000000f
                    || Math.abs(ys[i]) > 1000000f) return;
            x[i] = xs[i];
            y[i] = ys[i];
            minX = Math.min(minX, x[i]);
            minY = Math.min(minY, y[i]);
            hiX = Math.max(hiX, x[i]);
            hiY = Math.max(hiY, y[i]);
        }
        float[] nearest = new float[LETTERS];
        for (int i = 0; i < LETTERS; i++) {
            float best = Float.MAX_VALUE;
            for (int j = 0; j < LETTERS; j++) {
                if (j != i) best = Math.min(best, distance2(x[i], y[i], x[j], y[j]));
            }
            nearest[i] = (float) Math.sqrt(best);
        }
        Arrays.sort(nearest);
        float nextPitch = (nearest[12] + nearest[13]) * 0.5f;
        if (!finite(nextPitch) || nextPitch < 0.01f || nearest[0] < nextPitch * 0.2f) return;
        originX = minX;
        originY = minY;
        pitch = nextPitch;
        maxX = (hiX - minX) / pitch;
        maxY = (hiY - minY) / pitch;
        for (int i = 0; i < LETTERS; i++) {
            keyX[i] = (x[i] - minX) / pitch;
            keyY[i] = (y[i] - minY) / pitch;
        }
        float[] nextTemplates = new float[words.length * POINTS * 2];
        float[] nextLengths = new float[words.length];
        float[] wx = new float[32];
        float[] wy = new float[32];
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            int n = 0;
            char previous = 0;
            for (int j = 0; j < word.length(); j++) {
                char letter = word.charAt(j);
                // A repeated letter does not require lifting or drawing a tiny artificial loop.
                if (letter == previous) continue;
                int key = letter - 'a';
                wx[n] = keyX[key];
                wy[n++] = keyY[key];
                previous = letter;
            }
            nextLengths[i] = resample(wx, wy, n, nextTemplates, i * POINTS * 2);
        }
        templates = nextTemplates;
        lengths = nextLengths;
        ready = true;
    }

    /** Compatibility API; candidate geometry is computed only once. */
    String[] decode(float[] xs, float[] ys, int count, int limit) {
        Candidate[] ranked = candidates(xs, ys, count, limit);
        if (ranked.length == 0) return EMPTY;
        String[] result = new String[ranked.length];
        for (int i = 0; i < ranked.length; i++) result[i] = ranked[i].word;
        return result;
    }

    /** Fresh ranked evidence, or none for an invalid/unrecognisable path. Max 16. */
    Candidate[] candidates(float[] xs, float[] ys, int count, int limit) {
        if (!ready || words.length == 0 || xs == null || ys == null || count < 2
                || count > MAX_PATH_POINTS || count > xs.length || count > ys.length || limit < 1) {
            return NO_CANDIDATES;
        }
        int resultLimit = Math.min(limit, MAX_RESULTS);
        // Own the normalised samples for this call; no caller arrays are retained or modified.
        float[] x = new float[count];
        float[] y = new float[count];
        int n = 0;
        for (int i = 0; i < count; i++) {
            if (!finite(xs[i]) || !finite(ys[i])) return NO_CANDIDATES;
            float px = (xs[i] - originX) / pitch;
            float py = (ys[i] - originY) / pitch;
            if (!finite(px) || !finite(py) || px < -3f || py < -3f
                    || px > maxX + 3f || py > maxY + 3f) return NO_CANDIDATES;
            // Remove sampling duplicates and sub-pixel jitter, including stationary key holds.
            if (n == 0 || distance2(px, py, x[n - 1], y[n - 1]) > 0.0001f) {
                x[n] = px;
                y[n++] = py;
            }
        }
        if (n < 2) return NO_CANDIDATES;
        float[] path = new float[POINTS * 2];
        float length = resample(x, y, n, path, 0);
        if (length < 0.65f || length > 100f) return NO_CANDIDATES;
        float sx = path[0], sy = path[1];
        float ex = path[(POINTS - 1) * 2], ey = path[(POINTS - 1) * 2 + 1];
        int[] candidates = new int[SHORTLIST];
        float[] coarse = new float[SHORTLIST];
        Arrays.fill(coarse, Float.MAX_VALUE);
        int used = 0;
        for (int i = 0; i < words.length; i++) {
            int fk = first[i], lk = last[i];
            float startError = distance2(sx, sy, keyX[fk], keyY[fk]);
            float endError = distance2(ex, ey, keyX[lk], keyY[lk]);
            if (startError > 2.4f || endError > 2.4f || lengths[i] < 0.5f) continue;
            float ratio = length / lengths[i];
            if (ratio < 0.42f || ratio > 2.5f) continue;
            int off = i * POINTS * 2;
            float aligned = 0;
            for (int p = 0; p < POINTS * 2; p += 2) {
                aligned += distance2(path[p], path[p + 1], templates[off + p], templates[off + p + 1]);
            }
            float score = aligned / POINTS + 0.22f * (startError + endError)
                    + 0.07f * Math.abs((float) Math.log(ratio)) + prior[i];
            if (used == SHORTLIST && score >= coarse[used - 1]) continue;
            int at = Math.min(used, SHORTLIST - 1);
            while (at > 0 && score < coarse[at - 1]) {
                if (at < SHORTLIST) {
                    coarse[at] = coarse[at - 1];
                    candidates[at] = candidates[at - 1];
                }
                at--;
            }
            coarse[at] = score;
            candidates[at] = i;
            if (used < SHORTLIST) used++;
        }
        if (used == 0) return NO_CANDIDATES;
        Candidate[] best = new Candidate[resultLimit];
        float[] scores = new float[resultLimit];
        Arrays.fill(scores, Float.MAX_VALUE);
        int found = 0;
        float[] prev = new float[POINTS + 1];
        float[] next = new float[POINTS + 1];
        for (int c = 0; c < used; c++) {
            int i = candidates[c];
            int fk = first[i], lk = last[i];
            float endpoints = distance2(sx, sy, keyX[fk], keyY[fk])
                    + distance2(ex, ey, keyX[lk], keyY[lk]);
            float shape = warpedDistance(path, templates, i * POINTS * 2, prev, next);
            float score = shape + 0.12f * coarse[c] + 0.18f * endpoints + prior[i];
            // Do not force a dictionary word onto a scribble or a gesture outside the keys.
            if (score > 1.35f || (found == resultLimit && score >= scores[found - 1])) continue;
            int at = Math.min(found, resultLimit - 1);
            while (at > 0 && score < scores[at - 1]) {
                if (at < resultLimit) {
                    scores[at] = scores[at - 1];
                    best[at] = best[at - 1];
                }
                at--;
            }
            scores[at] = score;
            best[at] = new Candidate(words[i], shape, endpoints, prior[i],
                    Math.abs((float) Math.log(length / lengths[i])), score);
            if (found < resultLimit) found++;
        }
        if (found == 0) return NO_CANDIDATES;
        return Arrays.copyOf(best, found);
    }

    private static float warpedDistance(float[] path, float[] template, int off,
                                        float[] prev, float[] next) {
        Arrays.fill(prev, Float.MAX_VALUE);
        prev[0] = 0;
        for (int i = 1; i <= POINTS; i++) {
            Arrays.fill(next, Float.MAX_VALUE);
            int lo = Math.max(1, i - 6), hi = Math.min(POINTS, i + 6);
            int p = (i - 1) * 2;
            for (int j = lo; j <= hi; j++) {
                int q = off + (j - 1) * 2;
                float cost = distance2(path[p], path[p + 1], template[q], template[q + 1]);
                next[j] = cost + Math.min(prev[j - 1], Math.min(prev[j], next[j - 1]));
            }
            float[] swap = prev;
            prev = next;
            next = swap;
        }
        return prev[POINTS] / POINTS;
    }

    /** Arc-length resampling removes touch event rate and typing speed from the match. */
    private static float resample(float[] x, float[] y, int n, float[] out, int off) {
        if (n == 0) return 0;
        float length = 0;
        for (int i = 1; i < n; i++) length += (float) Math.sqrt(distance2(x[i - 1], y[i - 1], x[i], y[i]));
        int segment = 1;
        float before = 0;
        float segmentLength = n > 1 ? (float) Math.sqrt(distance2(x[0], y[0], x[1], y[1])) : 0;
        for (int p = 0; p < POINTS; p++) {
            float target = length * p / (POINTS - 1);
            while (segment < n - 1 && target > before + segmentLength) {
                before += segmentLength;
                segment++;
                segmentLength = (float) Math.sqrt(distance2(x[segment - 1], y[segment - 1], x[segment], y[segment]));
            }
            int low = Math.max(0, segment - 1), high = Math.min(n - 1, segment);
            float t = segmentLength > 0.000001f ? Math.max(0, Math.min(1, (target - before) / segmentLength)) : 0;
            out[off + p * 2] = x[low] + (x[high] - x[low]) * t;
            out[off + p * 2 + 1] = y[low] + (y[high] - y[low]) * t;
        }
        return length;
    }

    private static boolean validWord(String word) {
        if (word == null || word.length() < 2 || word.length() > 32) return false;
        for (int i = 0; i < word.length(); i++) {
            if (word.charAt(i) < 'a' || word.charAt(i) > 'z') return false;
        }
        return true;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static float distance2(float ax, float ay, float bx, float by) {
        float x = ax - bx, y = ay - by;
        return x * x + y * y;
    }
}
