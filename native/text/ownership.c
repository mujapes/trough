/* Device lifetime oracle: compare owned output to the independently verified shaping path,
 * then exercise retirement, failed admission, capacity retries and concurrent borrowers. */
#include "shape.h"
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <uchar.h>

static const char16_t text[] = u"AV ffi العربية क्षि 👩🏽‍🚀 🇬🇧";
#define UNITS (sizeof text / sizeof text[0] - 1)
#define CAP 128

typedef struct { sh_context *context; int failures, runs; } worker;
static void *work(void *data) {
    worker *w = data;
    for (int i = 0; i < 40; i++) {
        sh_glyph glyphs[CAP]; float advances[UNITS];
        sh_run r = {.glyphs = glyphs, .capacity = CAP, .advances = advances, .advance_capacity = UNITS};
        int rc = sh_shape_owned(w->context, text, UNITS, 0, UNITS, i & 1, 17, &r);
        if (rc || !r.count || r.count > CAP || !r.owner) w->failures++;
        sh_run_release(&r); w->runs++;
    }
    return NULL;
}

void sh_ownership_oracle(const char *locales, char *out, size_t capacity) {
    int failures = 0, comparisons = 0, cycles = 0, threaded = 0;
    sh_init(locales);
    /* Warm the legacy reference before counting mappings. */
    sh_glyph baseline[2][CAP]; float widths[2][UNITS];
    sh_run refs[2] = {{.glyphs = baseline[0], .capacity = CAP, .advances = widths[0], .advance_capacity = UNITS},
                     {.glyphs = baseline[1], .capacity = CAP, .advances = widths[1], .advance_capacity = UNITS}};
    for (int rtl = 0; rtl < 2; rtl++)
        if (sh_shape(text, UNITS, 0, UNITS, rtl, 17, &refs[rtl]) || refs[rtl].count > CAP) failures++;
    size_t before = sh_context_live(), maps_before = sh_mapping_live();
    for (int cycle = 0; cycle < 12 && !failures; cycle++) {
        sh_context *ctx = sh_context_create(locales);
        if (!ctx) { failures++; break; }
        sh_glyph glyphs[2][CAP]; float advances[2][UNITS];
        sh_run runs[2] = {{.glyphs = glyphs[0], .capacity = CAP, .advances = advances[0], .advance_capacity = UNITS},
                         {.glyphs = glyphs[1], .capacity = CAP, .advances = advances[1], .advance_capacity = UNITS}};
        /* Undersized advances must fail without acquiring an output lease. */
        runs[0].advance_capacity = UNITS - 1;
        if (sh_shape_owned(ctx, text, UNITS, 0, UNITS, 0, 17, &runs[0]) == 0 || runs[0].owner) failures++;
        runs[0].advance_capacity = UNITS;
        /* Capacity probes also own their fonts until explicitly released. */
        sh_run probe = {0};
        if (sh_shape_owned(ctx, text, UNITS, 0, UNITS, 0, 17, &probe) || !probe.count || !probe.owner) failures++;
        sh_run_release(&probe);
        for (int rtl = 0; rtl < 2; rtl++) {
            sh_run *r = &runs[rtl], *ref = &refs[rtl];
            if (sh_shape_owned(ctx, text, UNITS, 0, UNITS, rtl, 17, r) || r->count != ref->count
                    || r->count > CAP || r->advance != ref->advance) { failures++; continue; }
            for (size_t i = 0; i < r->count; i++) {
                sh_glyph *g = &r->glyphs[i], *b = &ref->glyphs[i]; char name[512], expected[512];
                if (sh_run_face_name(r, g->face, name, sizeof name) < 0
                        || sh_face_name(b->face, expected, sizeof expected) < 0 || strcmp(name, expected)
                        || g->glyph != b->glyph || g->cluster != b->cluster || g->x != b->x
                        || g->y != b->y || g->advance != b->advance) failures++;
                comparisons++;
            }
            if (memcmp(r->advances, ref->advances, sizeof widths[rtl])) failures++;
            /* Reusing a live output cannot silently replace its lease. */
            if (sh_shape_owned(ctx, text, UNITS, 0, UNITS, rtl, 17, r) == 0) failures++;
        }
        if (cycle == 0) {
            pthread_t threads[4]; worker workers[4]; int launched = 0;
            for (int i = 0; i < 4; i++) {
                workers[i] = (worker){.context = ctx};
                if (pthread_create(&threads[i], NULL, work, &workers[i])) { failures++; break; }
                launched++;
            }
            for (int i = 0; i < launched; i++) {
                pthread_join(threads[i], NULL); failures += workers[i].failures; threaded += workers[i].runs;
            }
        }
        sh_context_retire(ctx);
        if (sh_context_live() != before + 1) failures++;
        /* This raw pointer is still alive because runs own it; retirement denies new work. */
        if (runs[0].owner || runs[1].owner) {
            if (sh_shape_owned(ctx, text, UNITS, 0, UNITS, 0, 17, &probe) == 0) {
                failures++; sh_run_release(&probe);
            }
        }
        for (int rtl = 0; rtl < 2; rtl++) {
            char name[512];
            if (runs[rtl].owner && runs[rtl].count && sh_run_face_name(&runs[rtl], runs[rtl].glyphs[0].face, name, sizeof name) < 0)
                failures++;
            sh_run_release(&runs[rtl]);
            sh_run_release(&runs[rtl]); /* clearing the same lease twice is harmless */
        }
        if (sh_context_live() != before || sh_mapping_live() != maps_before) failures++;
        cycles++;
    }
    snprintf(out, capacity, "{\"ok\":%s,\"failures\":%d,\"cycles\":%d,\"glyph_comparisons\":%d,\"threaded_runs\":%d,\"contexts_before\":%zu,\"contexts_after\":%zu,\"mappings_before\":%zu,\"mappings_after\":%zu}",
        failures ? "false" : "true", failures, cycles, comparisons, threaded,
        before, sh_context_live(), maps_before, sh_mapping_live());
}
