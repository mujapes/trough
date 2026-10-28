#define _POSIX_C_SOURCE 200809L
/* Pixel oracle for the rectangle renderer (docs/PRESENT.md), shared by the VM program
 * (native/scenarios/scene.c, lavapipe) and the phone's JNI scenario (device GPU). The
 * reference rasteriser shares no code with the renderer: pixel centres against half-open
 * rectangles and clips, src-over in UNORM8 with round-to-nearest. */
#include "scene.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static uint64_t rng;
static uint32_t next(uint32_t n) { rng ^= rng << 13; rng ^= rng >> 7; rng ^= rng << 17; return (uint32_t)(rng >> 33) % n; }
/* Coordinates on a 1/16 grid (exact under any conforming subpixel precision), sometimes exactly
 * on a pixel centre to exercise the inclusive/exclusive edge rule. */
static float coord(uint32_t extent) {
    float v = (float)next(extent * 16 + 256) / 16.0f - 8.0f;
    return next(4) ? v : floorf(v) + 0.5f;
}
static void random_rect(ps_rect *r, uint32_t w, uint32_t h) {
    float a = coord(w), b = coord(w), c = coord(h), d = coord(h);
    if (next(20) == 0) b = a;   /* zero area */
    r->x0 = fminf(a, b); r->x1 = fmaxf(a, b); r->y0 = fminf(c, d); r->y1 = fmaxf(c, d);
    if (next(3)) { r->cx0 = -1e6f; r->cy0 = -1e6f; r->cx1 = 1e6f; r->cy1 = 1e6f; }
    else {
        float e = coord(w), f = coord(w), g = coord(h), k = coord(h);
        r->cx0 = fminf(e, f); r->cx1 = fmaxf(e, f); r->cy0 = fminf(g, k); r->cy1 = fmaxf(g, k);
    }
    uint32_t alpha = next(4) ? 255 : next(256);
    r->a = (float)alpha / 255.0f;
    r->r = (float)next(alpha + 1) / 255.0f; r->g = (float)next(alpha + 1) / 255.0f; r->b = (float)next(alpha + 1) / 255.0f;
}
static uint8_t unorm(float v) { v = v < 0 ? 0 : v > 1 ? 1 : v; return (uint8_t)lrintf(v * 255.0f); }
static void reference(const ps_rect *rects, size_t n, uint32_t w, uint32_t h, uint8_t *out) {
    memset(out, 0, (size_t)w * h * 4);
    for (size_t i = 0; i < n; i++) {
        const ps_rect *r = &rects[i];
        float src[4] = {r->r, r->g, r->b, r->a};
        for (uint32_t y = 0; y < h; y++) {
            float cy = (float)y + 0.5f;
            if (cy < r->y0 || cy >= r->y1 || cy < r->cy0 || cy >= r->cy1) continue;
            for (uint32_t x = 0; x < w; x++) {
                float cx = (float)x + 0.5f;
                if (cx < r->x0 || cx >= r->x1 || cx < r->cx0 || cx >= r->cx1) continue;
                uint8_t *p = out + ((size_t)y * w + x) * 4;
                for (int ch = 0; ch < 4; ch++) p[ch] = unorm(src[ch] + (float)p[ch] / 255.0f * (1.0f - r->a));
            }
        }
    }
}
static double now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

int ps_oracle(char *out, size_t capacity) {
    char info[300] = "";
    ps_gpu *g;
    int st = ps_open(&g, info, sizeof info);
    if (st) { snprintf(out, capacity, "{\"ok\":false,\"error\":\"open %d\"}", st); return 0; }
    enum { SCENES = 60, LIMIT = 2 };
    uint64_t pixels = 0, bad = 0, coverage = 0;
    int max_diff = 0;
    char first[200] = "";
    ps_rect *rects = malloc(200 * sizeof *rects);
    uint8_t *gpu = malloc(256 * 256 * 4), *ref = malloc(256 * 256 * 4);
    for (int s = 1; s <= SCENES && rects && gpu && ref; s++) {
        rng = 0x9e3779b97f4a7c15ull * (uint64_t)s;
        uint32_t w = 16 + next(240), h = 16 + next(240), n = 1 + next(200);
        for (uint32_t i = 0; i < n; i++) random_rect(&rects[i], w, h);
        double ms;
        if ((st = ps_render(g, rects, n, w, h, gpu, &ms))) break;
        reference(rects, n, w, h, ref);
        for (size_t p = 0; p < (size_t)w * h; p++) {
            int worst = 0;
            for (int ch = 0; ch < 4; ch++) { int d = abs(gpu[p * 4 + ch] - ref[p * 4 + ch]); if (d > worst) worst = d; }
            if (worst > max_diff) max_diff = worst;
            if (worst > LIMIT) {
                if (!bad++) snprintf(first, sizeof first, "scene %d (%ux%u, %u rects) pixel %zu,%zu ref %u,%u,%u,%u gpu %u,%u,%u,%u",
                    s, w, h, n, p % w, p / w, ref[p*4], ref[p*4+1], ref[p*4+2], ref[p*4+3], gpu[p*4], gpu[p*4+1], gpu[p*4+2], gpu[p*4+3]);
                /* A coverage error: one side untouched (transparent) where the other drew. */
                if (!(gpu[p * 4 + 3] && ref[p * 4 + 3])) coverage++;
            }
        }
        pixels += (size_t)w * h;
    }
    /* Throughput on a phone-sized target: clear+readback alone, then 20000 rectangles. */
    double base = 0, draw = 0;
    uint32_t W = 1080, H = 1920;
    ps_rect *many = malloc(20000 * sizeof *many);
    uint8_t *big = malloc((size_t)W * H * 4);
    if (!st && many && big) {
        rng = 42;
        for (int i = 0; i < 20000; i++) random_rect(&many[i], W, H);
        double t0 = now_ms(); (void)t0;
        ps_render(g, many, 0, W, H, big, &base);
        ps_render(g, many, 0, W, H, big, &base);
        ps_render(g, many, 20000, W, H, big, &draw);
        ps_render(g, many, 20000, W, H, big, &draw);
    }
    free(many); free(big); free(rects); free(gpu); free(ref);
    ps_close(g);
    for (char *c = info; *c; c++) if (*c == '"' || *c == '\\') *c = '\'';
    snprintf(out, capacity, "{\"ok\":%s,\"device\":\"%s\",\"render_status\":%d,\"scenes\":%d,\"pixels\":%llu,"
        "\"bad_pixels\":%llu,\"coverage_errors\":%llu,\"max_channel_diff\":%d,\"first_bad\":\"%s\","
        "\"clear_readback_1080x1920_ms\":%.2f,\"draw_20000_rects_1080x1920_ms\":%.2f}",
        !st && !bad && pixels ? "true" : "false", info, st, SCENES, (unsigned long long)pixels,
        (unsigned long long)bad, (unsigned long long)coverage, max_diff, first, base, draw);
    return !st && !bad && pixels;
}
