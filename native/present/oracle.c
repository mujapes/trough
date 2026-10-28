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
    /* Throughput on a phone-sized target: clear+readback alone, then 20000 UI-sized rectangles
     * (up to 64 px on a side, so overdraw stays like an interface rather than a stress test). */
    double base = 0, draw = 0;
    int stress = 0;
    uint32_t W = 1080, H = 1920;
    ps_rect *many = malloc(20000 * sizeof *many);
    uint8_t *big = malloc((size_t)W * H * 4);
    if (!st && many && big) {
        rng = 42;
        for (int i = 0; i < 20000; i++) {
            random_rect(&many[i], W, H);
            many[i].x1 = many[i].x0 + (float)next(64 * 16) / 16.0f;
            many[i].y1 = many[i].y0 + (float)next(64 * 16) / 16.0f;
        }
        stress = ps_render(g, many, 0, W, H, big, &base);
        if (!stress) stress = ps_render(g, many, 0, W, H, big, &base);
        if (!stress) stress = ps_render(g, many, 20000, W, H, big, &draw);
        if (!stress) stress = ps_render(g, many, 20000, W, H, big, &draw);
    }
    free(many); free(big); free(rects); free(gpu); free(ref);
    ps_close(g);
    for (char *c = info; *c; c++) if (*c == '"' || *c == '\\') *c = '\'';
    snprintf(out, capacity, "{\"ok\":%s,\"device\":\"%s\",\"render_status\":%d,\"scenes\":%d,\"pixels\":%llu,"
        "\"bad_pixels\":%llu,\"coverage_errors\":%llu,\"max_channel_diff\":%d,\"first_bad\":\"%s\","
        "\"stress_status\":%d,\"clear_readback_1080x1920_ms\":%.2f,\"draw_20000_ui_rects_1080x1920_ms\":%.2f}",
        !st && !stress && !bad && pixels ? "true" : "false", info, st, SCENES, (unsigned long long)pixels,
        (unsigned long long)bad, (unsigned long long)coverage, max_diff, first, stress, base, draw);
    return !st && !stress && !bad && pixels;
}

/* Glyph composition oracle: random coverage masks packed in an atlas, drawn at random integer
 * positions with clips and premultiplied colours over a few rectangles. Reference: src-over of
 * colour * coverage/255, the same rule the glyph shader applies to the R8 texel. */
int ps_glyph_oracle(char *out, size_t capacity) {
    ps_gpu *g;
    char info[300] = "";
    int st = ps_open(&g, info, sizeof info);
    if (st) { snprintf(out, capacity, "{\"ok\":false,\"error\":\"open %d\"}", st); return 0; }
    enum { AW = 512, AH = 512, MASKS = 64, SCENES = 40 };
    uint8_t *atlas = malloc(AW * AH), *gpu = malloc(256 * 256 * 4), *ref = malloc(256 * 256 * 4);
    ps_glyph *glyphs = malloc(300 * sizeof *glyphs);
    ps_rect rects[4];
    uint32_t mx[MASKS], my[MASKS], mw[MASKS], mh[MASKS];
    uint64_t pixels = 0, bad = 0, large = 0;
    int max_diff = 0;
    char first[200] = "";
    uint8_t *depth = malloc(256 * 256);
    rng = 7;
    memset(atlas, 0, AW * AH);
    for (uint32_t i = 0, x = 0, y = 0, row = 0; i < MASKS; i++) {      /* shelf packing */
        mw[i] = 1 + next(40); mh[i] = 1 + next(40);
        if (x + mw[i] > AW) { x = 0; y += row; row = 0; }
        mx[i] = x; my[i] = y; x += mw[i]; if (mh[i] > row) row = mh[i];
        for (uint32_t v = 0; v < mh[i]; v++) for (uint32_t u = 0; u < mw[i]; u++)
            atlas[(my[i] + v) * AW + mx[i] + u] = (uint8_t)(next(3) ? next(256) : next(2) * 255);
    }
    if (!st) st = ps_atlas(g, atlas, AW, AH);
    for (int s = 1; s <= SCENES && !st && gpu && ref && glyphs && depth; s++) {
        rng = 0x51ed27ull * (uint64_t)s;
        uint32_t w = 16 + next(240), h = 16 + next(240), n = 1 + next(300), nr = next(4);
        for (uint32_t i = 0; i < nr; i++) random_rect(&rects[i], w, h);
        for (uint32_t i = 0; i < n; i++) {
            uint32_t m = next(MASKS);
            float x0 = (float)next(w + 40) - 20, y0 = (float)next(h + 40) - 20;
            ps_glyph *q = &glyphs[i];
            q->x0 = x0; q->y0 = y0; q->x1 = x0 + (float)mw[m]; q->y1 = y0 + (float)mh[m];
            q->u = (float)mx[m]; q->v = (float)my[m]; q->unused0 = q->unused1 = 0;
            ps_rect c; random_rect(&c, w, h);
            q->cx0 = c.cx0; q->cy0 = c.cy0; q->cx1 = c.cx1; q->cy1 = c.cy1;
            q->r = c.r; q->g = c.g; q->b = c.b; q->a = c.a;
        }
        if ((st = ps_render_text(g, rects, nr, glyphs, n, w, h, gpu, NULL))) break;
        reference(rects, nr, w, h, ref);
        memset(depth, 0, (size_t)w * h);
        for (uint32_t i = 0; i < n; i++) {
            const ps_glyph *q = &glyphs[i];
            for (int y = (int)q->y0; y < (int)q->y1; y++) for (int x = (int)q->x0; x < (int)q->x1; x++) {
                if (x < 0 || y < 0 || x >= (int)w || y >= (int)h) continue;
                float cx = (float)x + 0.5f, cy = (float)y + 0.5f;
                if (cx < q->cx0 || cx >= q->cx1 || cy < q->cy0 || cy >= q->cy1) continue;
                float cov = (float)atlas[((int)q->v + y - (int)q->y0) * AW + (int)q->u + x - (int)q->x0] / 255.0f;
                float src[4] = {q->r * cov, q->g * cov, q->b * cov, q->a * cov};
                uint8_t *p = ref + ((size_t)y * w + x) * 4;
                for (int ch = 0; ch < 4; ch++) p[ch] = unorm(src[ch] + (float)p[ch] / 255.0f * (1.0f - src[3]));
                if (depth[(size_t)y * w + x] < 255) depth[(size_t)y * w + x]++;
            }
        }
        /* Each UNORM blend may round differently by one, and later blends never amplify an
         * earlier error (the factor 1 - alpha is at most 1): allow 1 plus the layers at the pixel,
         * rectangles included. Coverage or texel-addressing errors are far larger (counted apart). */
        for (size_t p = 0; p < (size_t)w * h; p++) {
            int worst = 0;
            for (int ch = 0; ch < 4; ch++) { int d = abs(gpu[p * 4 + ch] - ref[p * 4 + ch]); if (d > worst) worst = d; }
            if (worst > max_diff) max_diff = worst;
            if (worst > 16) large++;
            if (worst > 1 + (int)nr + depth[p] && !bad++)
                snprintf(first, sizeof first, "scene %d pixel %zu,%zu ref %u,%u,%u,%u gpu %u,%u,%u,%u", s, p % w, p / w,
                         ref[p*4], ref[p*4+1], ref[p*4+2], ref[p*4+3], gpu[p*4], gpu[p*4+1], gpu[p*4+2], gpu[p*4+3]);
        }
        pixels += (size_t)w * h;
    }
    free(atlas); free(gpu); free(ref); free(glyphs); free(depth);
    ps_close(g);
    for (char *c = info; *c; c++) if (*c == '"' || *c == '\\') *c = '\'';
    snprintf(out, capacity, "{\"ok\":%s,\"device\":\"%s\",\"status\":%d,\"scenes\":%d,\"pixels\":%llu,\"bad_pixels\":%llu,"
        "\"large_diffs\":%llu,\"max_channel_diff\":%d,\"first_bad\":\"%s\"}", !st && !bad && !large && pixels ? "true" : "false",
        info, st, SCENES, (unsigned long long)pixels, (unsigned long long)bad, (unsigned long long)large, max_diff, first);
    return !st && !bad && !large && pixels;
}

/* Frames in flight over a residency-managed atlas (docs/ATLAS.md). Glyph keys drift so the
 * small atlas evicts constantly; up to PS_FRAMES frames are in flight. Each upload must avoid
 * every rectangle an unretired frame samples (shadow check); each retired frame's pixels must
 * equal a CPU composition from the true masks, so a stale or overwritten atlas texel would
 * show. overlapped counts submissions made while earlier frames were still pending. */
#include "atlas.h"
static void mask_of(uint64_t key, uint8_t *m, uint16_t *w, uint16_t *h) {
    uint64_t s = key * 0x9e3779b97f4a7c15ULL;
    *w = (uint16_t)(4 + (s >> 20) % 37); *h = (uint16_t)(4 + (s >> 30) % 37);
    for (uint32_t i = 0; i < (uint32_t)*w * *h; i++) { s ^= s << 13; s ^= s >> 7; s ^= s << 17; m[i] = (uint8_t)(s >> 24); }
}
int ps_frames_oracle(char *out, size_t capacity) {
    enum { FW = 256, FH = 192, AW = 512, AH = 512, FRAMES = 300, PER = 90, KEYS = 2000 };
    ps_gpu *g;
    char info[300] = "";
    int st = ps_open(&g, info, sizeof info);
    if (st) { snprintf(out, capacity, "{\"ok\":false,\"error\":\"open %d\"}", st); return 0; }
    if (!st) st = ps_frames_init(g, FW, FH, AW, AH);
    at_atlas atlas; static at_cell cells[(AW / 16) * (AH / 16)]; static uint32_t idx[8192]; int32_t pages[(AW / 256) * (AH / 256)];
    if (!st && at_init(&atlas, AW, AH, 256, cells, sizeof cells / sizeof cells[0], idx, 8192, pages)) st = -1;
    typedef struct { ps_glyph q[PER]; uint64_t key[PER]; uint16_t x[PER], y[PER]; int n; uint64_t number; } frame_rec;
    frame_rec *rec = calloc(2 * PS_FRAMES, sizeof *rec);
    /* Frames are built in bursts of PS_FRAMES and then submitted back to back, so later
     * submissions really go in while earlier frames are pending. */
    uint8_t *pixels = malloc((size_t)PS_FRAMES * PER * 40 * 40), *gpu = malloc(FW * FH * 4), *ref = malloc(FW * FH * 4), *depth = malloc(FW * FH);
    uint8_t mask[40 * 40];
    ps_upload up[PS_FRAMES][PER]; size_t nup[PS_FRAMES], bytes[PS_FRAMES]; frame_rec *built[PS_FRAMES];
    uint64_t bad = 0, checked = 0, uploads = 0, refused = 0, overlapped = 0, violations = 0;
    int max_diff = 0; char first[200] = "";
    rng = 99;
    for (int fno = 0; fno < FRAMES && !st && rec && pixels && gpu && ref && depth; ) {
        int burst = 0;
        for (; burst < PS_FRAMES && fno < FRAMES; burst++, fno++) {
            frame_rec *fr = &rec[fno % (2 * PS_FRAMES)];
            fr->n = 0; fr->number = 0;
            uint8_t *px = pixels + (size_t)burst * PER * 40 * 40;
            nup[burst] = 0; bytes[burst] = 0; built[burst] = fr;
            for (int k = 0; k < PER; k++) {
                uint64_t key = next(10) < 8 ? (uint64_t)fno / 6 * 5 + next(60) + 1 : next(KEYS) + 1;   /* drifting hot set */
                uint16_t w, h; mask_of(key, mask, &w, &h);
                at_rect r;
                tr_status sr = at_use(&atlas, key, w, h, &r);
                if (sr == TR_CAPACITY) { refused++; continue; }
                if (sr == TR_STALE) {
                    /* Shadow: no frame still in flight, already built in this burst, or this one,
                     * samples this origin for another key. */
                    uint64_t done = ps_frames_retired(g);
                    for (int o = 0; o < 2 * PS_FRAMES; o++) {
                        frame_rec *x = &rec[o];
                        int live = x == fr || (x->number && x->number > done);
                        for (int b = 0; b < burst; b++) if (built[b] == x) live = 1;
                        for (int j = 0; live && j < x->n; j++) if (x->x[j] == r.x && x->y[j] == r.y && x->key[j] != key) violations++;
                    }
                    memcpy(px + bytes[burst], mask, (size_t)w * h);
                    up[burst][nup[burst]++] = (ps_upload){r.x, r.y, w, h, (uint32_t)bytes[burst]};
                    bytes[burst] += (size_t)w * h; uploads++;
                }
                ps_glyph *q = &fr->q[fr->n];
                float x0 = (float)next(FW + 20) - 10, y0 = (float)next(FH + 20) - 10;
                q->x0 = x0; q->y0 = y0; q->x1 = x0 + w; q->y1 = y0 + h; q->u = r.x; q->v = r.y; q->unused0 = q->unused1 = 0;
                q->cx0 = -1e6f; q->cy0 = -1e6f; q->cx1 = 1e6f; q->cy1 = 1e6f;
                float a = (float)(1 + next(255)) / 255.0f;
                q->r = a * (float)next(256) / 255.0f; q->g = a * (float)next(256) / 255.0f; q->b = a * (float)next(256) / 255.0f; q->a = a;
                fr->key[fr->n] = key; fr->x[fr->n] = r.x; fr->y[fr->n] = r.y; fr->n++;
            }
            at_submit(&atlas);                            /* the atlas's frame order = submission order */
        }
        for (int b = 0; b < burst && !st; b++) {
            if (ps_frames_pending(g) > 0) overlapped++;
            st = ps_frame_submit(g, up[b], nup[b], pixels + (size_t)b * PER * 40 * 40, bytes[b], NULL, 0,
                                 built[b]->q, (size_t)built[b]->n, &built[b]->number);
        }
        if (st) break;
        /* Let the burst retire (its slots are needed next), then check every retired frame. */
        if ((st = ps_frames_wait(g, built[burst - 1]->number))) break;
        uint64_t done = ps_frames_retired(g);
        at_retire(&atlas, done);
        for (int o = 0; o < 2 * PS_FRAMES; o++) {
            frame_rec *x = &rec[o];
            if (!x->number || x->number > done) continue;
            if (ps_frame_pixels(g, x->number, gpu)) { x->number = 0; continue; }
            memset(ref, 0, FW * FH * 4); memset(depth, 0, FW * FH);
            for (int j = 0; j < x->n; j++) {
                const ps_glyph *q = &x->q[j]; uint16_t w, h; mask_of(x->key[j], mask, &w, &h);
                for (int y = (int)q->y0; y < (int)q->y1; y++) for (int xx = (int)q->x0; xx < (int)q->x1; xx++) {
                    if (xx < 0 || y < 0 || xx >= FW || y >= FH) continue;
                    float cov = (float)mask[(y - (int)q->y0) * w + xx - (int)q->x0] / 255.0f;
                    float src[4] = {q->r * cov, q->g * cov, q->b * cov, q->a * cov};
                    uint8_t *p = ref + ((size_t)y * FW + xx) * 4;
                    for (int ch = 0; ch < 4; ch++) p[ch] = unorm(src[ch] + (float)p[ch] / 255.0f * (1.0f - src[3]));
                    if (depth[(size_t)y * FW + xx] < 255) depth[(size_t)y * FW + xx]++;
                }
            }
            for (size_t p = 0; p < (size_t)FW * FH; p++) {
                int worst = 0;
                for (int ch = 0; ch < 4; ch++) { int d = abs(gpu[p * 4 + ch] - ref[p * 4 + ch]); if (d > worst) worst = d; }
                if (worst > max_diff) max_diff = worst;
                if (worst > 1 + depth[p] && !bad++)
                    snprintf(first, sizeof first, "frame %llu pixel %zu,%zu ref %u,%u,%u,%u gpu %u,%u,%u,%u", (unsigned long long)x->number,
                             p % FW, p / FW, ref[p*4], ref[p*4+1], ref[p*4+2], ref[p*4+3], gpu[p*4], gpu[p*4+1], gpu[p*4+2], gpu[p*4+3]);
            }
            checked++;
            x->number = 0;
        }
    }
    ps_close(g);
    free(rec); free(pixels); free(gpu); free(ref); free(depth);
    for (char *c = info; *c; c++) if (*c == '"' || *c == '\\') *c = '\'';
    int ok = !st && !bad && !violations && checked == FRAMES && overlapped >= FRAMES / 2;
    snprintf(out, capacity, "{\"ok\":%s,\"device\":\"%s\",\"status\":%d,\"frames\":%d,\"checked\":%llu,\"overlapped\":%llu,"
        "\"uploads\":%llu,\"evictions\":%llu,\"refused\":%llu,\"violations\":%llu,\"bad_pixels\":%llu,\"max_channel_diff\":%d,\"first_bad\":\"%s\"}",
        ok ? "true" : "false", info, st, FRAMES, (unsigned long long)checked, (unsigned long long)overlapped,
        (unsigned long long)uploads, (unsigned long long)atlas.evictions, (unsigned long long)refused,
        (unsigned long long)violations, (unsigned long long)bad, max_diff, first);
    return ok;
}
