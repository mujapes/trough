/*
 * Copyright 2021 Google LLC. Licensed under the Apache License, Version 2.0.
 * Quantized pretrained rule from google-research/self-organising-systems,
 * notebooks/μNCA_pytorch.ipynb (exported GLSL cell 12, 588 coefficients).
 * Java CPU evaluator and interaction/render helpers adapted for Trough, 2026.
 * See third_party/munca/PROVENANCE.txt and LICENSE for source and attribution.
 */
package com.trough.hot;

/** Persistent micro neural cellular automaton, with no Android dependency.
 * Own each instance on one worker thread. The learned update is independent of
 * text and render controls; pixels() applies those controls only to RGB output.
 */
final class MuNca {
    static final String MODEL_ID = "google-munca-588-int8";
    static final int PARAMETER_COUNT = 588;
    static final int QUANTIZED_BYTES = 588;
    static final int DEQUANTIZED_WEIGHT_BYTES = 2352;
    private static final int CHANNELS = 12;
    // Output-channel major: 24 signed features, 24 absolute features, bias.
    // Each stored integer is divided by 500, exactly as in the pretrained GLSL.
    private static final byte[] QUANTIZED = {
        -25,-2,-3,-2,-6,0,3,-1,2,2,3,-1,26,3,-7,-1,6,-11,2,7,10,-3,1,6,19,-5,-1,-3,6,-1,-4,4,1,-3,7,-4,-3,3,3,-5,4,6,3,-8,-6,7,-4,2,-18,
        4,-42,-1,-3,-10,-2,4,5,4,-1,7,-5,2,20,2,-5,11,-18,2,11,17,-3,2,9,-3,9,-7,-5,8,-2,-7,4,2,-8,13,-3,-2,0,4,-7,7,8,5,-11,-10,10,-7,3,-8,
        3,-5,-45,-4,-9,0,2,4,4,-2,5,-5,3,0,21,-5,11,-19,3,11,18,-4,2,9,-5,-7,8,-6,7,-2,-8,5,1,-8,11,-3,-3,5,0,-7,6,9,5,-11,-10,10,-7,3,-5,
        4,-1,-2,-24,21,10,23,-16,-11,-10,-12,-8,13,10,19,43,-6,-11,25,15,10,-2,-16,1,-4,-7,-10,-4,22,-3,16,7,26,3,4,-2,2,3,2,-15,2,-2,-4,-17,4,16,-4,14,-7,
        0,16,16,25,-50,8,-9,-12,-10,6,-18,1,8,8,9,24,-12,-9,15,17,2,-2,-5,5,-3,-36,-32,-9,25,14,-21,2,-5,-2,4,-3,1,5,7,-7,-7,0,-9,-8,8,11,-9,2,22,
        4,15,21,-4,2,-73,11,7,-3,-15,-5,-1,6,4,5,15,-5,16,6,3,3,-3,-6,-1,-1,-5,1,-32,-39,-6,-4,-3,14,8,9,-7,3,2,4,1,-2,-1,5,-10,16,3,2,-11,18,
        -7,-23,-20,-10,5,10,-54,-25,-21,4,-1,4,-4,-7,-6,3,3,4,22,-4,-6,-4,2,-4,11,3,-14,-11,-14,5,-15,2,-3,37,-35,-9,-2,-3,-4,20,-22,7,3,14,5,-9,1,-8,1,
        0,-17,-14,19,14,2,30,-83,-22,-13,-8,4,-8,-5,-9,-10,20,6,-9,-6,-4,-8,10,7,4,-16,-31,-8,35,1,1,-16,-1,-5,-25,-15,4,2,2,9,0,3,13,2,-9,-6,14,-9,-1,
        3,20,17,-1,-22,-22,10,12,-79,-21,-16,-25,5,8,9,-26,5,-19,-1,6,29,-7,3,1,-2,7,14,-1,1,-18,11,8,47,6,-31,-28,1,-3,-5,-4,-2,10,-3,5,-13,-1,-2,9,-6,
        4,8,10,0,14,1,-14,-13,24,-67,-6,-2,3,2,1,8,-1,1,-4,0,-1,17,-6,-5,-10,3,11,18,22,0,-5,6,3,-3,5,7,-4,1,1,-9,12,-9,9,-1,3,-7,0,12,-11,
        1,2,4,-2,13,-7,-1,3,16,-8,-92,16,4,3,4,-17,13,-9,-15,3,10,-10,13,5,1,-25,-30,-3,-8,20,-23,14,10,-23,-3,5,3,0,1,2,2,-7,11,-11,2,-2,9,-1,3,
        -3,-8,-9,35,5,6,17,-1,7,5,-24,-61,-1,-1,-1,8,-2,5,2,0,-4,-3,-2,3,2,-6,-20,4,3,-3,0,-17,8,10,-28,-6,-1,0,1,1,-8,-5,0,10,4,-2,2,9,-7,
    };
    // Slice once at class load; the cell update only addresses a small weight row.
    private static final float[][] WEIGHTS = new float[CHANNELS][49];
    static {
        for (int c = 0; c < CHANNELS; c++)
            for (int j = 0; j < 49; j++) WEIGHTS[c][j] = QUANTIZED[c * 49 + j] / 500f;
    }

    private final int width, height;
    private final int[] left, right, above, below;
    private float[] field, next;
    private final float[] features = new float[48];
    private int randomState;
    private long updates, numericalResets;

    MuNca(int width, int height, long seed) {
        if (width < 2 || height < 2 || (long) width * height > 65536)
            throw new IllegalArgumentException("MuNca grid must contain 4..65536 cells");
        this.width = width;
        this.height = height;
        field = new float[width * height * CHANNELS];
        next = new float[field.length];
        left = new int[width]; right = new int[width];
        above = new int[height]; below = new int[height];
        for (int x = 0; x < width; x++) {
            left[x] = (x == 0 ? width - 1 : x - 1) * CHANNELS;
            right[x] = (x + 1 == width ? 0 : x + 1) * CHANNELS;
        }
        for (int y = 0; y < height; y++) {
            above[y] = (y == 0 ? height - 1 : y - 1) * width * CHANNELS;
            below[y] = (y + 1 == height ? 0 : y + 1) * width * CHANNELS;
        }
        randomState = (int) seed;
        for (int i = 0; i < field.length; i++) field[i] = random();
    }

    int width() { return width; }
    int height() { return height; }
    long steps() { return updates; }
    long numericalResets() { return numericalResets; }
    int stateBytes() {
        return (field.length + next.length + features.length) * 4
            + (left.length + right.length + above.length + below.length) * 4;
    }

    /** Synchronous trained residual evolution, toroidal edges, update rate 1. */
    void step(int count) {
        if (count < 0) throw new IllegalArgumentException("Negative update count");
        for (int update = 0; update < count; update++) {
            float[] a = field, b = next, f = features;
            for (int y = 0; y < height; y++) {
                int row = y * width * CHANNELS, top = above[y], bottom = below[y];
                for (int x = 0; x < width; x++) {
                    int centre = row + x * CHANNELS;
                    int nw = top + left[x], n = top + x * CHANNELS, ne = top + right[x];
                    int w = row + left[x], e = row + right[x];
                    int sw = bottom + left[x], s = bottom + x * CHANNELS, se = bottom + right[x];
                    for (int c = 0; c < CHANNELS; c++) {
                        float perception = 0f;
                        if (c < 4) {
                            perception += a[nw + c];
                            perception += a[n + c] * 2f;
                            perception += a[ne + c];
                            perception += a[w + c] * 2f;
                            perception += a[centre + c] * -12f;
                            perception += a[e + c] * 2f;
                            perception += a[sw + c];
                            perception += a[s + c] * 2f;
                            perception += a[se + c];
                        } else if (c < 8) {
                            perception += -a[nw + c];
                            perception += a[ne + c];
                            perception += a[w + c] * -2f;
                            perception += a[e + c] * 2f;
                            perception += -a[sw + c];
                            perception += a[se + c];
                        } else {
                            perception += -a[nw + c];
                            perception += a[n + c] * -2f;
                            perception += -a[ne + c];
                            perception += a[sw + c];
                            perception += a[s + c] * 2f;
                            perception += a[se + c];
                        }
                        f[c] = a[centre + c];
                        f[CHANNELS + c] = perception;
                    }
                    for (int j = 0; j < 24; j++) f[24 + j] = Math.abs(f[j]);
                    for (int c = 0; c < CHANNELS; c++) {
                        float[] weights = WEIGHTS[c];
                        float delta = weights[48];
                        // Fixed trained topology: avoid a 48-iteration interpreter loop.
                        // Keep every += separate and ordered for float32 state parity.
                        delta += weights[0] * f[0];
                        delta += weights[1] * f[1];
                        delta += weights[2] * f[2];
                        delta += weights[3] * f[3];
                        delta += weights[4] * f[4];
                        delta += weights[5] * f[5];
                        delta += weights[6] * f[6];
                        delta += weights[7] * f[7];
                        delta += weights[8] * f[8];
                        delta += weights[9] * f[9];
                        delta += weights[10] * f[10];
                        delta += weights[11] * f[11];
                        delta += weights[12] * f[12];
                        delta += weights[13] * f[13];
                        delta += weights[14] * f[14];
                        delta += weights[15] * f[15];
                        delta += weights[16] * f[16];
                        delta += weights[17] * f[17];
                        delta += weights[18] * f[18];
                        delta += weights[19] * f[19];
                        delta += weights[20] * f[20];
                        delta += weights[21] * f[21];
                        delta += weights[22] * f[22];
                        delta += weights[23] * f[23];
                        delta += weights[24] * f[24];
                        delta += weights[25] * f[25];
                        delta += weights[26] * f[26];
                        delta += weights[27] * f[27];
                        delta += weights[28] * f[28];
                        delta += weights[29] * f[29];
                        delta += weights[30] * f[30];
                        delta += weights[31] * f[31];
                        delta += weights[32] * f[32];
                        delta += weights[33] * f[33];
                        delta += weights[34] * f[34];
                        delta += weights[35] * f[35];
                        delta += weights[36] * f[36];
                        delta += weights[37] * f[37];
                        delta += weights[38] * f[38];
                        delta += weights[39] * f[39];
                        delta += weights[40] * f[40];
                        delta += weights[41] * f[41];
                        delta += weights[42] * f[42];
                        delta += weights[43] * f[43];
                        delta += weights[44] * f[44];
                        delta += weights[45] * f[45];
                        delta += weights[46] * f[46];
                        delta += weights[47] * f[47];
                        float value = a[centre + c] + delta;
                        // Do not clamp finite learned states. Recover only a numerical failure.
                        if (Float.isNaN(value) || Float.isInfinite(value)) {
                            value = 0f; numericalResets++;
                        }
                        b[centre + c] = value;
                    }
                }
            }
            field = b; next = a; updates++;
        }
    }

    /** Blend a small patch toward the rule's random initial distribution. */
    void perturb(float normalizedX, float normalizedY, float strength) {
        if (!finite(normalizedX) || !finite(normalizedY) || !finite(strength)) return;
        float amount = unit(strength);
        if (amount == 0f) return;
        float cx = unit(normalizedX) * (width - 1), cy = unit(normalizedY) * (height - 1);
        float radius = 2f + amount * Math.min(width, height) * .15f;
        float radius2 = radius * radius;
        for (int y = 0; y < height; y++) {
            float dy = Math.abs(y - cy); dy = Math.min(dy, height - dy);
            for (int x = 0; x < width; x++) {
                float dx = Math.abs(x - cx); dx = Math.min(dx, width - dx);
                float d2 = dx * dx + dy * dy;
                if (d2 >= radius2) continue;
                float edge = 1f - d2 / radius2;
                float blend = amount * edge * edge;
                int base = (y * width + x) * CHANNELS;
                for (int c = 0; c < CHANNELS; c++) {
                    float old = field[base + c];
                    field[base + c] = old + (random() - old) * blend;
                }
            }
        }
    }

    /** First three trained channels become RGB (+.5, clamped to display range).
     * Controls 0..1 are render only; .5/.5/.5 preserves the original palette.
     */
    void pixels(int[] argb, float warmth, float focus, float depth) {
        if (argb == null || argb.length < width * height)
            throw new IllegalArgumentException("Pixel buffer too small");
        float tint = (safeUnit(warmth) - .5f) * .12f;
        float contrast = .82f + safeUnit(focus) * .36f;
        float brightness = .90f + safeUnit(depth) * .20f;
        for (int i = 0; i < width * height; i++) {
            int base = i * CHANNELS;
            float r = (field[base] * contrast + .5f + tint) * brightness;
            float g = (field[base + 1] * contrast + .5f) * brightness;
            float b = (field[base + 2] * contrast + .5f - tint) * brightness;
            argb[i] = 0xff000000 | ((int) (unit(r) * 255f) << 16)
                | ((int) (unit(g) * 255f) << 8) | (int) (unit(b) * 255f);
        }
    }

    // Diagnostics/testing: a caller-provided buffer keeps the hot path allocation free.
    void copyState(float[] out) {
        if (out.length != field.length) throw new IllegalArgumentException("State buffer size");
        System.arraycopy(field, 0, out, 0, field.length);
    }
    int randomState() { return randomState; }

    /** Restore one owned worker's cellular state; validate everything before changing it.
     * Scratch features and the alternate field are completely overwritten by the next step.
     */
    void restoreState(float[] cells, int random, long steps, long resets) {
        if (cells == null || cells.length != field.length || steps < 0 || resets < 0)
            throw new IllegalArgumentException("Invalid MuNca state shape or counters");
        for (float value : cells) if (!finite(value))
            throw new IllegalArgumentException("Non-finite MuNca state");
        System.arraycopy(cells, 0, field, 0, field.length);
        randomState = random; updates = steps; numericalResets = resets;
    }
    private float random() {
        int r = randomState;
        r ^= r << 13; r ^= r >>> 17; r ^= r << 5;
        randomState = r;
        return (r & 0xffffff) / 16777216f - .5f;
    }
    private static boolean finite(float v) { return !Float.isNaN(v) && !Float.isInfinite(v); }
    private static float unit(float v) { return v < 0f ? 0f : v > 1f ? 1f : v; }
    private static float safeUnit(float v) { return finite(v) ? unit(v) : .5f; }
}
