/* Text layout as a derivation operator, in process (docs/LAYOUT_DERIVE.md). A world of
 * paragraph text cells (UTF-16 blobs) and one width cell; node (layout, i) is paragraph i laid
 * out at the width. The operator runs on executor workers attached to the JVM and calls the
 * hot-loaded DeriveLayout.layout(String, int), whose serialized lines and glyphs become a
 * canonical blob. Java drives it through owner calls; no Java code sees jobs or the graph. */
#define _POSIX_C_SOURCE 200809L
#include "exec.h"
#include "order.h"
#include "atlas.h"
#include "scene.h"
#include <android/native_window.h>
#include <android/choreographer.h>
#include <android/looper.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>

#define CLASS(name) Java_com_trough_hot_NativeCore_##name

typedef struct {
    JavaVM *vm; jclass cls; jmethodID layout, mask, caret, offset_at, select_rects;
    tr_id op_mask;
    /* Selection (docs/VIEW.md "Caret"): a cell holding (paragraph << 32 | offset) + 1, 0 = none;
     * caret nodes (paragraph << 32 | offset) give its x and line box, and the overlay node turns
     * selection + viewport into rectangles drawn under the glyphs. */
    tr_id selection, op_caret, op_overlay; uint32_t overlay;
    /* Range selection: sel_anchor is the other end, same encoding (0 or equal: a caret).
     * highlight(paragraph << 42 | flags << 40 | start << 20 | end) gives a paragraph's rects. */
    tr_id sel_anchor, op_highlight;
    /* The view's frames (deriveFrameOpen): GPU frame ring and atlas residency, used only by the
     * thread that calls deriveFrame. */
    ps_gpu *gpu; ANativeWindow *window; at_atlas atlas; at_cell *atlas_cells; uint32_t *atlas_index; int32_t atlas_pages[64];
    uint32_t fw, fh; uint64_t frame_uploads, frame_hits;
    ps_present_time present_times[1024]; size_t npresent; uint64_t refresh_ns;   /* derivePresentTimes */
    /* Frame pacing (deriveFrameBegin): the newest displayed frame, each frame's start time (when
     * its input was sampled) by number, and the work estimate from start to present. */
    int pace; uint64_t last_number, shown_frame, shown_ns, frame_start[256], work_ns, work_dev, slept_ns;
    /* The vsync each paced frame aimed at, and the slack beyond the CPU work estimate that GPU
     * and compositor latch need, learned from display timing: +2 ms per miss, -0.25 ms while
     * frames are more than 4 ms early. */
    uint64_t target_ns[256], extra_ns, misses;
    uint64_t input_ns[256];                           /* per frame: the oldest input event it consumed */
    struct vsync_src *vsync;                          /* derivePace mode 2: platform frame timelines */
    tr_world world; tr_cell *cells; tr_id driver, width, op, viewport, op_sum, op_scene; tr_id *text; int paragraphs;
    /* Paragraph identity (docs/VIEW.md "Paragraph identity"): `paragraphs` is the capacity of
     * ids; text cells and layout nodes are per id. The order of ids is a B+tree on the owner
     * (docs/ORDER.md); each tree slot's published form lives in ocell[slot], and operators walk
     * those from slot 0. Inserting or removing republishes O(log n) slots, never a layout.
     * count mirrors the number of paragraphs (written by the owner thread only). */
    or_tree order; or_node *onodes; uint32_t *ofree, *ochanged; uint8_t *omarked, *oused; tr_id *ocell; uint32_t oslots;
    atomic_int count; uint32_t *free_ids; int nfree;
    uint32_t scene;                                   /* the scene node */
    atomic_ullong runs[8];                            /* invocations: layout, summary, scene, mask; [4] paragraphs estimated; caret, overlay */
    /* Prefetch (derivePrefetch): layouts of paragraphs [anchor - before, anchor + after] are
     * demanded at DV_BACKGROUND, owned as [pf_lo, pf_hi); kept on the owner thread. */
    int pf_before, pf_after, pf_lo, pf_hi; uint64_t scroll_pauses;
    uint32_t *pf_ids; int npf;                        /* layout nodes the prefetch demands, by id */
    dv_graph graph; dv_node *nodes; dv_block *blocks; uint32_t *table; dv_frame *path; uint32_t *node;
    bl_store blobs; bl_entry *entries; uint32_t *index;
    ex_exec ex;
} deriver;

static uint64_t mono_ns(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (uint64_t)t.tv_sec * 1000000000u + (uint64_t)t.tv_nsec; }
/* Display timing as it arrives; the newest displayed frame anchors the vsync grid. */
static void collect_timing(deriver *d) {
    if (!d->window || d->npresent >= 1024) return;
    int got = ps_present_timing(d->gpu, d->present_times + d->npresent, 1024 - d->npresent, &d->refresh_ns);
    for (int i = 0; i < got; i++) {
        const ps_present_time *t = &d->present_times[d->npresent + (size_t)i];
        if (t->actual_ns && t->frame >= d->shown_frame) { d->shown_frame = t->frame; d->shown_ns = t->actual_ns; }
        uint64_t target = d->target_ns[t->frame % 256];
        if (target && t->actual_ns && d->refresh_ns) {
            if (d->pace == 2) {                        /* the deadline already includes the latch */
                if (t->actual_ns > target + d->refresh_ns / 2) { d->extra_ns += 1000000; d->misses++; }
                else if (d->extra_ns > 500000) d->extra_ns -= 50000;
            } else if (t->actual_ns > target + d->refresh_ns / 2) { d->extra_ns += 2000000; d->misses++; }
            else if (t->margin_ns > 4000000) d->extra_ns -= d->extra_ns < 250000 ? d->extra_ns : 250000;
            if (d->extra_ns > 2 * d->refresh_ns) d->extra_ns = 2 * d->refresh_ns;
            d->target_ns[t->frame % 256] = 0;
        }
    }
    if (got > 0) d->npresent += (size_t)got;
}
/* ---- platform frame timelines (Android 13+): a looper thread keeps the latest choreographer
 * callback's timelines, each a compositor latch deadline and the expected present time. The
 * API is newer than the library's target, so it is resolved at run time. ---- */
typedef struct { int64_t present, deadline; } vs_timeline;
struct vsync_src {
    pthread_t thread; ALooper *looper; AChoreographer *choreographer; int run, ready;
    pthread_mutex_t lock; pthread_cond_t started; vs_timeline tl[16]; size_t n; uint64_t callbacks;
    void (*post)(AChoreographer *, AChoreographer_vsyncCallback, void *);
    size_t (*length)(const AChoreographerFrameCallbackData *);
    int64_t (*present)(const AChoreographerFrameCallbackData *, size_t), (*deadline)(const AChoreographerFrameCallbackData *, size_t);
};
static void vs_callback(const AChoreographerFrameCallbackData *data, void *arg) {
    struct vsync_src *v = arg;
    size_t n = v->length(data); if (n > 16) n = 16;
    pthread_mutex_lock(&v->lock);
    for (size_t i = 0; i < n; i++) { v->tl[i].present = v->present(data, i); v->tl[i].deadline = v->deadline(data, i); }
    v->n = n; v->callbacks++;
    pthread_mutex_unlock(&v->lock);
    if (v->run) v->post(v->choreographer, vs_callback, v);
}
static void *vs_main(void *arg) {
    struct vsync_src *v = arg;
    v->looper = ALooper_prepare(0);
    v->choreographer = AChoreographer_getInstance();
    pthread_mutex_lock(&v->lock); v->ready = v->choreographer ? 1 : -1; pthread_cond_signal(&v->started); pthread_mutex_unlock(&v->lock);
    if (!v->choreographer) return NULL;
    v->post(v->choreographer, vs_callback, v);
    while (v->run) ALooper_pollOnce(-1, NULL, NULL, NULL);
    return NULL;
}
static struct vsync_src *vs_open(void) {
    void *lib = dlopen("libandroid.so", RTLD_NOW);
    if (!lib) return NULL;
    struct vsync_src *v = calloc(1, sizeof *v);
    if (!v) return NULL;
    v->post = (void (*)(AChoreographer *, AChoreographer_vsyncCallback, void *))dlsym(lib, "AChoreographer_postVsyncCallback");
    v->length = (size_t (*)(const AChoreographerFrameCallbackData *))dlsym(lib, "AChoreographerFrameCallbackData_getFrameTimelinesLength");
    v->present = (int64_t (*)(const AChoreographerFrameCallbackData *, size_t))dlsym(lib, "AChoreographerFrameCallbackData_getFrameTimelineExpectedPresentationTimeNanos");
    v->deadline = (int64_t (*)(const AChoreographerFrameCallbackData *, size_t))dlsym(lib, "AChoreographerFrameCallbackData_getFrameTimelineDeadlineNanos");
    if (!v->post || !v->length || !v->present || !v->deadline) { free(v); return NULL; }
    pthread_mutex_init(&v->lock, NULL); pthread_cond_init(&v->started, NULL);
    v->run = 1;
    if (pthread_create(&v->thread, NULL, vs_main, v)) { free(v); return NULL; }
    pthread_mutex_lock(&v->lock);
    while (!v->ready) pthread_cond_wait(&v->started, &v->lock);
    pthread_mutex_unlock(&v->lock);
    if (v->ready < 0) { pthread_join(v->thread, NULL); free(v); return NULL; }
    return v;
}
static void vs_close(struct vsync_src *v) {
    if (!v) return;
    v->run = 0;
    ALooper_wake(v->looper);
    pthread_join(v->thread, NULL);
    free(v);
}

static size_t pow2(size_t n) { size_t p = 1; while (p < n) p <<= 1; return p; }

static void attach(void *ctx) { deriver *d = ctx; JNIEnv *env; (*d->vm)->AttachCurrentThread(d->vm, (void *)&env, NULL); }
static void detach(void *ctx) { deriver *d = ctx; (*d->vm)->DetachCurrentThread(d->vm); }

/* Worker thread: text and width from the job, layout through Java, bytes out. */
static int layout_op(void *ctx, const dv_job *job, ex_out *out) {
    deriver *d = ctx;
    atomic_fetch_add(&d->runs[0], 1);
    dv_ref rt = {DV_CELL, d->text[job->key], 0}, rw = {DV_CELL, d->width, 0};
    uint64_t th, w;
    int have_t = ex_read(job, out, rt, &th, NULL), have_w = ex_read(job, out, rw, &w, NULL);
    if (!have_t || !have_w) {                         /* ask for both at once: one restart */
        if (!have_t) ex_need(out, rt);
        if (!have_w) ex_need(out, rw);
        return 0;
    }
    const void *units = NULL; size_t length = 0;
    if (bl_is(th) && bl_get(&d->blobs, th, &units, &length) != TR_OK) { units = NULL; length = 0; }
    JNIEnv *env;
    if ((*d->vm)->GetEnv(d->vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) { out->value = 0; return 0; }
    jstring s = (*env)->NewString(env, units, (jsize)(length / 2));
    jbyteArray r = s ? (*env)->CallStaticObjectMethod(env, d->cls, d->layout, s, (jint)w) : NULL;
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); r = NULL; }
    if (r) {
        jsize n = (*env)->GetArrayLength(env, r);
        out->blob = malloc(n ? (size_t)n : 1);
        if (out->blob) { (*env)->GetByteArrayRegion(env, r, 0, n, out->blob); out->blob_length = (size_t)n; }
        (*env)->DeleteLocalRef(env, r);
    } else out->value = 0;                            /* layout failed: a scalar error value */
    if (s) (*env)->DeleteLocalRef(env, s);
    return 0;
}

/* Mask (face << 32 | glyph): the platform's coverage for one glyph. No dependencies: valid until
 * the operator changes. */
static int mask_op(void *ctx, const dv_job *job, ex_out *out) {
    deriver *d = ctx;
    atomic_fetch_add(&d->runs[3], 1);
    JNIEnv *env;
    if ((*d->vm)->GetEnv(d->vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) { out->value = 0; return 0; }
    jbyteArray r = (*env)->CallStaticObjectMethod(env, d->cls, d->mask, (jint)(job->key >> 32), (jint)(job->key & 0xffffffffu));
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); r = NULL; }
    if (!r) { out->value = 0; return 0; }
    jsize n = (*env)->GetArrayLength(env, r);
    out->blob = malloc(n ? (size_t)n : 1);
    if (out->blob) { (*env)->GetByteArrayRegion(env, r, 0, n, out->blob); out->blob_length = (size_t)n; }
    (*env)->DeleteLocalRef(env, r);
    return 0;
}

/* ---- height summaries and the scene (docs/VIEW.md) ---- */
#define FAN 16
static uint32_t be32(const unsigned char *p) { return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3]; }
static float bef(const unsigned char *p) { uint32_t u = be32(p); float f; memcpy(&f, &u, 4); return f; }
#define LINE_BYTES 25                                 /* start, end, baseline, top, bottom, width, rtl */
/* A layout blob's height: its last line's bottom (whole pixels by construction). */
static uint64_t layout_height(const unsigned char *b, size_t n) {
    if (n < 4) return 0;
    uint32_t lines = be32(b);
    if (!lines || n < 4 + (size_t)lines * LINE_BYTES) return 0;
    float bottom = bef(b + 4 + (size_t)(lines - 1) * LINE_BYTES + 16);
    return bottom > 0 ? (uint64_t)(bottom + 0.5f) : 0;
}
/* Operators: *id1 = id + 1 at position i, walking the published order tree from slot 0's cell
 * (docs/ORDER.md); 0 past the end. Returns 0 when a node cell is not provided (asked for). */
static int id_at(deriver *d, const dv_job *job, ex_out *out, uint64_t i, uint64_t *id1) {
    uint64_t pos = i; uint32_t slot = 0;
    *id1 = 0;
    for (int depth = 0; depth < 32; depth++) {
        dv_ref r = {DV_CELL, d->ocell[slot], 0}; uint64_t v; const void *p = NULL; size_t n = 0;
        if (!ex_read(job, out, r, &v, NULL)) { ex_need(out, r); return 0; }
        if (bl_is(v)) bl_get(&d->blobs, v, &p, &n);
        uint32_t child; int leaf;
        if (or_step(p, n, &pos, &child, &leaf) != TR_OK) return 1;   /* past the end */
        if (leaf) { *id1 = (uint64_t)child + 1; return 1; }
        if (child >= d->oslots) return 1;
        slot = child;
    }
    return 1;
}
/* Owner thread: id + 1 at position i (0 past the end). */
static uint64_t owner_id(deriver *d, tr_world *w, int64_t i) {
    (void)w; uint32_t id;
    if (i < 0 || or_at(&d->order, (uint64_t)i, &id, NULL) != TR_OK) return 0;
    return (uint64_t)id + 1;
}
/* The height a ref stands for: a layout blob's height or a summary's scalar. 0 = missing. */
static int height_of(deriver *d, const dv_job *job, ex_out *out, dv_ref r, int is_layout, uint64_t *h,
                     const unsigned char **bytes, size_t *length) {
    uint64_t v;
    if (!ex_read(job, out, r, &v, NULL)) return 0;
    if (!is_layout) { *h = v; return 1; }
    const void *p = NULL; size_t n = 0;
    if (bl_is(v)) bl_get(&d->blobs, v, &p, &n);
    if (bytes) { *bytes = p; *length = n; }
    *h = p ? layout_height(p, n) : 0;
    return 1;
}
/* Estimated paragraph height: pure and cheap, for the scrollbar and jumps only (nothing is
 * drawn at estimated positions). Lines = ceil(units * EST_ADVANCE / width), at least 1. */
#define EST_ADVANCE 17                                /* px per UTF-16 unit at TEXT_SIZE 36 */
#define EST_LINE 48
static uint64_t estimate(uint64_t units, uint64_t width) {
    uint64_t lines = width ? (units * EST_ADVANCE + width - 1) / width : 1;
    return (lines ? lines : 1) * EST_LINE;
}
/* Summary (slot): the total estimated height of an order-tree node (docs/ORDER.md). It reads the
 * node's cell first, so a republished node re-derives. A leaf reads its paragraphs' text cells
 * and the width and estimates inline (fused: no node per estimate); an internal node sums its
 * children's summaries. All missing inputs are asked for at once, after the reads. */
static int sum_op(void *ctx, const dv_job *job, ex_out *out) {
    deriver *d = ctx;
    atomic_fetch_add(&d->runs[1], 1);
    uint32_t slot = (uint32_t)job->key;
    if (slot >= d->oslots) { out->value = 0; return 0; }
    dv_ref rn = {DV_CELL, d->ocell[slot], 0}, rw = {DV_CELL, d->width, 0};
    uint64_t nv, total = 0, width = 0; const void *p = NULL; size_t n = 0;
    if (!ex_read(job, out, rn, &nv, NULL)) { ex_need(out, rn); return 0; }
    if (bl_is(nv)) bl_get(&d->blobs, nv, &p, &n);
    if (!p || n < 4) { out->value = 0; return 0; }   /* an empty document */
    const unsigned char *b = p; uint32_t head = be32(b), count = head & 0x7fffffffu; int leaf = (int)(head >> 31);
    if (count > OR_FAN || n < 4 + 8 * (size_t)count) { out->value = 0; return 0; }
    dv_ref need[OR_FAN + 1]; size_t nn = 0; int estimated = 0;
    if (leaf && !ex_read(job, out, rw, &width, NULL)) need[nn++] = rw;
    for (uint32_t k = 0; k < count; k++) {
        uint32_t child = be32(b + 4 + 8 * (size_t)k); uint64_t v;
        if (leaf) {
            if (child >= (uint32_t)d->paragraphs) continue;
            dv_ref rt = {DV_CELL, d->text[child], 0}; const void *q = NULL; size_t m = 0;
            if (!ex_read(job, out, rt, &v, NULL)) { need[nn++] = rt; continue; }
            if (bl_is(v)) bl_get(&d->blobs, v, &q, &m);
            total += estimate(m / 2, width); estimated++;
        } else {
            dv_ref rc = {DV_NODE, d->op_sum, child};
            if (!ex_read(job, out, rc, &v, NULL)) { need[nn++] = rc; continue; }
            total += v;
        }
    }
    if (nn) { for (size_t k = 0; k < nn; k++) ex_need(out, need[k]); return 0; }   /* needs last: reads stay valid */
    atomic_fetch_add(&d->runs[4], (unsigned long long)estimated);
    out->value = total;
    return 0;
}

typedef struct { unsigned char *b; size_t n, cap; uint32_t glyphs; int failed; } sink;
static void put(sink *s, const void *p, size_t n) {
    if (s->failed) return;
    if (s->n + n > s->cap) {
        size_t cap = s->cap ? s->cap * 2 : 4096;
        while (cap < s->n + n) cap *= 2;
        unsigned char *b = realloc(s->b, cap);
        if (!b) { s->failed = 1; return; }
        s->b = b; s->cap = cap;
    }
    memcpy(s->b + s->n, p, n); s->n += n;
}
static void put32(sink *s, uint32_t v) { unsigned char b[4] = {v >> 24, v >> 16, v >> 8, v}; put(s, b, 4); }
static void putf(sink *s, float f) { uint32_t u; memcpy(&u, &f, 4); put32(s, u); }

/* Emits the glyphs of one layout whose line intersects the viewport, the paragraph's top at
 * screen y = top (negative above the viewport). */
static void emit_paragraph(sink *s, const unsigned char *b, size_t n, int64_t top, uint64_t height) {
    if (n < 4) return;
    uint32_t lines = be32(b);
    size_t g = 4 + (size_t)lines * LINE_BYTES;
    if (n < g + 4) return;
    uint32_t glyphs = be32(b + g);
    if (n < g + 4 + (size_t)glyphs * 16) return;
    /* Glyph y is a baseline: a glyph belongs to the line whose [top, bottom) holds it. */
    for (uint32_t k = 0; k < glyphs; k++) {
        const unsigned char *q = b + g + 4 + (size_t)k * 16;
        float x = bef(q + 8), y = bef(q + 12);
        int visible = 0;
        for (uint32_t l = 0; l < lines; l++) {
            const unsigned char *L = b + 4 + (size_t)l * LINE_BYTES;
            float lt = bef(L + 12), lb = bef(L + 16);
            if (y >= lt && y < lb) { visible = (double)top + lb > 0 && (double)top + lt < (double)height; break; }
        }
        if (!visible) continue;
        put32(s, be32(q)); put32(s, be32(q + 4));
        putf(s, x); putf(s, (float)((double)top + y));
        s->glyphs++;
    }
}
/* Scene: the viewport cell is (anchor paragraph, offset into it, height). Paragraphs are
 * stacked with exact heights from the anchor, whose top is at -offset, until the viewport is
 * full: nothing above the anchor is read, so edits there cannot move what is shown. Output:
 * count, then (face, glyph, x, y) in viewport coordinates. */
static int scene_op(void *ctx, const dv_job *job, ex_out *out) {
    deriver *d = ctx;
    atomic_fetch_add(&d->runs[2], 1);
    dv_ref rv = {DV_CELL, d->viewport, 0};
    uint64_t v;
    if (!ex_read(job, out, rv, &v, NULL)) { ex_need(out, rv); return 0; }
    uint64_t anchor = v & 0xffffffffu, offset = (v >> 32) & 0xffff, height = v >> 48;
    int64_t y = -(int64_t)offset;
    sink s = {0};
    put32(&s, 0);
    for (uint64_t i = anchor; y < (int64_t)height; i++) {
        uint64_t id1;
        if (!id_at(d, job, out, i, &id1)) { free(s.b); return 0; }
        if (!id1) break;                               /* past the last paragraph */
        dv_ref r = {DV_NODE, d->op, id1 - 1}; uint64_t h; const unsigned char *bytes = NULL; size_t n = 0;
        if (!height_of(d, job, out, r, 1, &h, &bytes, &n)) { ex_need(out, r); free(s.b); return 0; }
        emit_paragraph(&s, bytes, n, y, height);
        y += (int64_t)h;
    }
    if (s.failed) { free(s.b); out->value = 0; return 0; }
    unsigned char c[4] = {s.glyphs >> 24, s.glyphs >> 16, s.glyphs >> 8, s.glyphs};
    memcpy(s.b, c, 4);
    out->blob = s.b; out->blob_length = s.n;
    return 0;
}

/* Caret (paragraph << 32 | offset): x, line top and line bottom in the paragraph's layout, by
 * TextLayout's caret rules (Layout.getPrimaryHorizontal), through Java on a worker. */
static int caret_op(void *ctx, const dv_job *job, ex_out *out) {
    deriver *d = ctx;
    atomic_fetch_add(&d->runs[5], 1);
    uint64_t para = job->key >> 32, offset = job->key & 0xffffffffu;
    if (para >= (uint64_t)d->paragraphs) { out->value = 0; return 0; }
    dv_ref rt = {DV_CELL, d->text[para], 0}, rw = {DV_CELL, d->width, 0};
    uint64_t th, w;
    int have_t = ex_read(job, out, rt, &th, NULL), have_w = ex_read(job, out, rw, &w, NULL);
    if (!have_t || !have_w) { if (!have_t) ex_need(out, rt); if (!have_w) ex_need(out, rw); return 0; }
    const void *units = NULL; size_t length = 0;
    if (bl_is(th) && bl_get(&d->blobs, th, &units, &length) != TR_OK) { units = NULL; length = 0; }
    JNIEnv *env;
    if ((*d->vm)->GetEnv(d->vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) { out->value = 0; return 0; }
    jstring s = (*env)->NewString(env, units, (jsize)(length / 2));
    jbyteArray r = s ? (*env)->CallStaticObjectMethod(env, d->cls, d->caret, s, (jint)w, (jint)offset) : NULL;
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); r = NULL; }
    if (r) {
        jsize n = (*env)->GetArrayLength(env, r);
        out->blob = malloc(n ? (size_t)n : 1);
        if (out->blob) { (*env)->GetByteArrayRegion(env, r, 0, n, out->blob); out->blob_length = (size_t)n; }
        (*env)->DeleteLocalRef(env, r);
    } else out->value = 0;
    if (s) (*env)->DeleteLocalRef(env, s);
    return 0;
}
/* Highlight: one paragraph's selection rectangles (Layout.getSelection's rules, through Java),
 * in its own coordinates: count, then left, top, right, bottom floats. */
static int highlight_op(void *ctx, const dv_job *job, ex_out *out) {
    deriver *d = ctx;
    atomic_fetch_add(&d->runs[7], 1);
    uint64_t para = job->key >> 42, flags = (job->key >> 40) & 3, start = (job->key >> 20) & 0xfffff, end = job->key & 0xfffff;
    if (para >= (uint64_t)d->paragraphs) { out->value = 0; return 0; }
    dv_ref rt = {DV_CELL, d->text[para], 0}, rw = {DV_CELL, d->width, 0};
    uint64_t th, w;
    int have_t = ex_read(job, out, rt, &th, NULL), have_w = ex_read(job, out, rw, &w, NULL);
    if (!have_t || !have_w) { if (!have_t) ex_need(out, rt); if (!have_w) ex_need(out, rw); return 0; }
    const void *units = NULL; size_t length = 0;
    if (bl_is(th) && bl_get(&d->blobs, th, &units, &length) != TR_OK) { units = NULL; length = 0; }
    JNIEnv *env;
    if ((*d->vm)->GetEnv(d->vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) { out->value = 0; return 0; }
    jstring s = (*env)->NewString(env, units, (jsize)(length / 2));
    jbyteArray r = s ? (*env)->CallStaticObjectMethod(env, d->cls, d->select_rects, s, (jint)w, (jint)start, (jint)end, (jint)flags) : NULL;
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); r = NULL; }
    if (r) {
        jsize n = (*env)->GetArrayLength(env, r);
        out->blob = malloc(n ? (size_t)n : 1);
        if (out->blob) { (*env)->GetByteArrayRegion(env, r, 0, n, out->blob); out->blob_length = (size_t)n; }
        (*env)->DeleteLocalRef(env, r);
    } else out->value = 0;
    if (s) (*env)->DeleteLocalRef(env, s);
    return 0;
}
static void put_rect(sink *o, float x0, float y0, float x1, float y1, uint32_t kind, uint32_t *count) {
    float r[4] = {x0, y0, x1, y1};
    for (int k = 0; k < 4; k++) { uint32_t u; memcpy(&u, &r[k], 4); put32(o, u); }
    put32(o, kind); (*count)++;
}
/* Overlay: the selection's rectangles in viewport coordinates. Walks exact heights from the
 * anchor like the scene, but only to the selected paragraph, and reads its caret node. Output:
 * count, then (x0, y0, x1, y1) floats. A caret is 2 px wide, centred on its x. */
#define CARET_HALF 1.0f
#define CARET_R (26 / 255.0f)                          /* opaque, so premultiplied is the colour */
#define CARET_G (115 / 255.0f)
#define CARET_B (230 / 255.0f)
#define HIGHLIGHT_A (89 / 255.0f)                      /* selection highlight: the caret colour at ~35% */
static int overlay_op(void *ctx, const dv_job *job, ex_out *out) {
    deriver *d = ctx;
    atomic_fetch_add(&d->runs[6], 1);
    dv_ref rv = {DV_CELL, d->viewport, 0}, rs = {DV_CELL, d->selection, 0}, ra = {DV_CELL, d->sel_anchor, 0};
    uint64_t v, sel, anc;
    int have_v = ex_read(job, out, rv, &v, NULL), have_s = ex_read(job, out, rs, &sel, NULL), have_a = ex_read(job, out, ra, &anc, NULL);
    if (!have_v || !have_s || !have_a) {
        if (!have_v) ex_need(out, rv);
        if (!have_s) ex_need(out, rs);
        if (!have_a) ex_need(out, ra);
        return 0;
    }
    sink o = {0};
    uint32_t count = 0;
    put32(&o, 0);
    uint64_t anchor = v & 0xffffffffu, offset = (v >> 32) & 0xffff, height = v >> 48;
    int range = sel && anc && anc != sel;
    uint64_t lo = sel - 1, hi = sel - 1;
    if (range) { lo = anc - 1 < sel - 1 ? anc - 1 : sel - 1; hi = anc - 1 < sel - 1 ? sel - 1 : anc - 1; }
    uint64_t lop = lo >> 32, hip = hi >> 32;
    if (sel) {
        int64_t y = -(int64_t)offset;
        for (uint64_t i = anchor; i <= hip && y < (int64_t)height; i++) {
            uint64_t id1;
            if (!id_at(d, job, out, i, &id1)) { free(o.b); return 0; }
            if (!id1) break;
            uint64_t id = id1 - 1;                    /* caret and highlight nodes are per id */
            if (!range && i == lop) {
                dv_ref rc = {DV_NODE, d->op_caret, id << 32 | (lo & 0xffffffffu)}; uint64_t cv; const void *p = NULL; size_t n = 0;
                if (!ex_read(job, out, rc, &cv, NULL)) { ex_need(out, rc); free(o.b); return 0; }
                if (bl_is(cv)) bl_get(&d->blobs, cv, &p, &n);
                if (p && n >= 12) {
                    const unsigned char *c = p;
                    float x = bef(c), yy = (float)y;
                    put_rect(&o, x - CARET_HALF, yy + bef(c + 4), x + CARET_HALF, yy + bef(c + 8), 0, &count);
                }
                break;
            }
            if (range && i >= lop) {
                uint64_t flags = (i > lop ? 1u : 0u) | (i < hip ? 2u : 0u);
                uint64_t st = i == lop ? lo & 0xfffff : 0, en = i == hip ? hi & 0xfffff : 0;
                dv_ref rh = {DV_NODE, d->op_highlight, id << 42 | flags << 40 | st << 20 | en}; uint64_t hv; const void *p = NULL; size_t n = 0;
                if (!ex_read(job, out, rh, &hv, NULL)) { ex_need(out, rh); free(o.b); return 0; }
                if (bl_is(hv)) bl_get(&d->blobs, hv, &p, &n);
                if (p && n >= 4) {
                    const unsigned char *b = p; uint32_t k = be32(b);
                    for (uint32_t j = 0; j < k && 4 + (size_t)(j + 1) * 16 <= n; j++) {
                        const unsigned char *q = b + 4 + (size_t)j * 16; float yy = (float)y;
                        put_rect(&o, bef(q), yy + bef(q + 4), bef(q + 8), yy + bef(q + 12), 1, &count);
                    }
                }
            }
            dv_ref r = {DV_NODE, d->op, id}; uint64_t h;
            if (!height_of(d, job, out, r, 1, &h, NULL, NULL)) { ex_need(out, r); free(o.b); return 0; }
            y += (int64_t)h;
        }
    }
    if (o.failed) { free(o.b); out->value = 0; return 0; }
    unsigned char c[4] = {count >> 24, count >> 16, count >> 8, count};
    memcpy(o.b, c, 4);
    out->blob = o.b; out->blob_length = o.n;
    return 0;
}

/* Jump: the paragraph at a fraction of the estimated document height, through the estimate
 * summaries (all clean: the root is demanded). */
typedef struct { deriver *d; int64_t permille, anchor, total; } jump_call;
static void demand_root(void *arg, tr_world *w, dv_graph *g) {
    (void)w; jump_call *j = arg; uint32_t root;
    if (dv_intern(g, j->d->op_sum, 0, &root) == TR_OK && !g->nodes[root].demand) dv_demand(g, root);
}
static int64_t node_value(deriver *d, dv_graph *g, dv_ref r) {
    uint32_t n; uint64_t v;
    if (dv_intern(g, r.id, r.key, &n) || dv_get(g, n, &v)) return -1;
    (void)d; return (int64_t)v;
}
/* Descends the owner's order tree by the node summaries (leaves: the same estimate, from the
 * cells) to the paragraph holding the target height; past the end, the last one. */
static void find_anchor(void *arg, tr_world *w, dv_graph *g) {
    jump_call *j = arg; deriver *d = j->d; tr_read wd;
    int64_t total = node_value(d, g, (dv_ref){DV_NODE, d->op_sum, 0});
    j->total = total; j->anchor = -1;
    if (total < 0 || tr_current(w, d->width, &wd)) return;
    int64_t target = total * j->permille / 1000, y = 0; uint64_t base = 0;
    const or_node *x = &d->order.nodes[0];
    for (;;) {
        if (!x->n) return;
        uint32_t k = 0;
        for (; k < x->n; k++) {
            int64_t h;
            if (x->leaf) {
                tr_read t; const void *p = NULL; size_t n = 0;
                if (tr_current(w, d->text[x->child[k]], &t)) return;
                if (bl_is(t.value)) bl_get(&d->blobs, t.value, &p, &n);
                h = (int64_t)estimate(n / 2, wd.value);
            } else h = node_value(d, g, (dv_ref){DV_NODE, d->op_sum, x->child[k]});
            if (h < 0) return;
            if (y + h > target) break;
            y += h;
        }
        if (k == x->n) k = x->n - 1;                     /* past the end: the last child */
        if (x->leaf) { base += k; break; }
        for (uint32_t i = 0; i < k; i++) base += x->count[i];
        x = &d->order.nodes[x->child[k]];
    }
    int count = atomic_load(&d->count);
    j->anchor = (int64_t)base < count ? (int64_t)base : count - 1;
}

/* ---- owner calls ---- */
static void write_cell(deriver *d, tr_id cell, uint64_t value) {
    tr_transaction t; tr_read r[4]; tr_write w[1]; tr_receipt rc;
    tr_read old;
    tr_current(&d->world, cell, &old);
    if (tr_begin(&t, &d->world, d->driver, r, 4, w, 1) || tr_write_value(&t, cell, value) || tr_commit(&t, &rc)) return;
    if (bl_is(old.value)) bl_release(&d->blobs, old.value);   /* the cell held it */
}
/* Republishes the order-tree slots the last mutation changed (docs/ORDER.md "Publication"). */
static void publish_order(deriver *d) {
    uint32_t slots[64], k;
    while ((k = or_take_changed(&d->order, slots, 64)))
        for (uint32_t i = 0; i < k; i++) {
            unsigned char b[OR_ENCODED_MAX]; uint64_t h = 0;
            size_t n = or_encode(&d->order, slots[i], b);
            if (n && bl_intern(&d->blobs, b, n, 0, &h)) { d->ex.error = TR_CAPACITY; continue; }
            write_cell(d, d->ocell[slots[i]], n ? h : 0);
        }
}
/* Moves the background prefetch window to the viewport cell's anchor: demands what entered,
 * releases what left. */
static void prefetch_update(deriver *d, tr_world *w, dv_graph *g) {
    tr_read vp; int lo = 0, hi = 0;
    if ((d->pf_before || d->pf_after) && tr_current(w, d->viewport, &vp) == TR_OK) {
        int anchor = (int)(vp.value & 0xffffffffu);
        lo = anchor - d->pf_before; if (lo < 0) lo = 0;
        hi = anchor + d->pf_after + 1; if (hi > atomic_load(&d->count)) hi = atomic_load(&d->count);
        if (lo > hi) lo = hi;
    }
    /* By id: positions move when paragraphs are inserted. Released and demanded in one owner
     * call, so nodes kept in the window never see a poll without their demand. */
    for (int k = 0; k < d->npf; k++) dv_release_at(g, d->node[d->pf_ids[k]], DV_BACKGROUND);
    d->npf = 0;
    for (int i = lo; i < hi; i++) {
        uint64_t id1 = owner_id(d, w, i);
        if (!id1) break;
        d->pf_ids[d->npf++] = (uint32_t)(id1 - 1);
        dv_demand_at(g, d->node[id1 - 1], DV_BACKGROUND);
    }
    d->pf_lo = lo; d->pf_hi = lo + d->npf;
}
typedef struct { deriver *d; int i; const jchar *units; jsize length; int64_t result; } call;
static void set_text(void *arg, tr_world *w, dv_graph *g) {
    (void)w; (void)g;
    call *c = arg; uint64_t h;
    c->result = bl_intern(&c->d->blobs, (void *)c->units, (size_t)c->length * 2, 0, &h);
    uint64_t id1 = owner_id(c->d, w, c->i);
    if (!c->result && !id1) { bl_release(&c->d->blobs, h); c->result = TR_INVALID; }
    if (!c->result) { write_cell(c->d, c->d->text[id1 - 1], h); or_set_units(&c->d->order, (uint64_t)c->i, (uint64_t)c->length); }
}
static void set_width(void *arg, tr_world *w, dv_graph *g) { (void)w; (void)g; call *c = arg; write_cell(c->d, c->d->width, (uint64_t)c->i); }
static void set_viewport(void *arg, tr_world *w, dv_graph *g) {     /* i = anchor, length = offset | height << 16 */
    call *c = arg;
    write_cell(c->d, c->d->viewport, (uint64_t)(uint32_t)c->i | ((uint64_t)(uint32_t)c->length & 0xffff) << 32
                                     | ((uint64_t)(uint32_t)c->length >> 16) << 48);
    prefetch_update(c->d, w, g);
}
static void set_prefetch(void *arg, tr_world *w, dv_graph *g) {   /* i = before, length = after */
    call *c = arg; c->d->pf_before = c->i; c->d->pf_after = (int)c->length;
    prefetch_update(c->d, w, g);
}
static void demand_scene(void *arg, tr_world *w, dv_graph *g) {
    (void)w; call *c = arg; c->result = c->length ? dv_demand(g, c->d->scene) : dv_release(g, c->d->scene);
    if (!c->result) c->length ? dv_demand(g, c->d->overlay) : dv_release(g, c->d->overlay);
}
static void demand(void *arg, tr_world *w, dv_graph *g) {
    call *c = arg; uint64_t id1 = owner_id(c->d, w, c->i);
    if (!id1) { c->result = TR_INVALID; return; }
    c->result = c->length ? dv_demand(g, c->d->node[id1 - 1]) : dv_release(g, c->d->node[id1 - 1]);
}
static void value(void *arg, tr_world *w, dv_graph *g) {
    call *c = arg; uint64_t v, id1 = owner_id(c->d, w, c->i);
    c->result = id1 && dv_get(g, c->d->node[id1 - 1], &v) == TR_OK ? (int64_t)(v & ~BL_TAG) : -1;
}
typedef struct { deriver *d; int i; void *bytes; size_t length; int ok; } copy;
static void bytes(void *arg, tr_world *w, dv_graph *g) {
    copy *c = arg; uint64_t v; const void *p; size_t n;
    uint64_t id1 = c->i >= 0 ? owner_id(c->d, w, c->i) : 0;
    if (c->i >= 0 && !id1) return;
    uint32_t node = c->i == -2 ? c->d->overlay : c->i < 0 ? c->d->scene : c->d->node[id1 - 1];   /* -1 scene, -2 overlay */
    if (dv_get(g, node, &v) || !bl_is(v) || bl_get(&c->d->blobs, v, &p, &n)) return;
    c->bytes = malloc(n ? n : 1);
    if (c->bytes) { memcpy(c->bytes, p, n); c->length = n; c->ok = 1; }
}
static void stats(void *arg, tr_world *w, dv_graph *g) {
    (void)w; (void)g; deriver *d = ((call *)arg)->d; int64_t *s = (int64_t *)((call *)arg)->units;
    s[0] = (int64_t)d->ex.jobs; s[1] = (int64_t)d->ex.cancelled; s[2] = (int64_t)d->ex.stale; s[3] = d->ex.error;
    s[4] = (int64_t)d->blobs.interned; s[5] = (int64_t)d->blobs.deduplicated; s[6] = (int64_t)d->blobs.live; s[7] = (int64_t)d->blobs.bytes;
    uint64_t busy = 0, wait = 0; ex_trace t[EX_TRACE];
    size_t n = ex_trace_copy(&d->ex, t, EX_TRACE);
    for (size_t k = 0; k < n; k++) { busy += t[k].end_ns - t[k].start_ns; wait += t[k].start_ns - t[k].queued_ns; }
    s[8] = (int64_t)busy; s[9] = (int64_t)wait; s[10] = (int64_t)n;
    for (int k = 0; k < 3; k++) s[11 + k] = (int64_t)atomic_load(&d->runs[k]);
    s[14] = (int64_t)atomic_load(&d->runs[3]); s[15] = (int64_t)d->frame_uploads; s[16] = (int64_t)d->frame_hits;
    s[17] = (int64_t)d->atlas.evictions; s[18] = (int64_t)d->atlas.refusals; s[19] = (int64_t)atomic_load(&d->runs[4]);
    s[20] = (int64_t)d->scroll_pauses; s[21] = d->pf_hi - d->pf_lo;
    s[22] = (int64_t)d->extra_ns; s[23] = (int64_t)d->misses;
    s[24] = (int64_t)atomic_load(&d->runs[5]); s[25] = (int64_t)atomic_load(&d->runs[6]); s[26] = (int64_t)atomic_load(&d->runs[7]);
}

/* The newest trace records: (operator 0 layout / 1 summary / 2 scene, key, outcome, worker). */
typedef struct { deriver *d; int64_t *rows; size_t n; } traced;
static void trace_rows(void *arg, tr_world *w, dv_graph *g) {
    (void)w; (void)g; traced *t = arg; ex_trace r[EX_TRACE];
    size_t n = ex_trace_copy(&t->d->ex, r, EX_TRACE);
    for (size_t k = 0; k < n; k++) {
        tr_id op = r[k].op; int kind = op.slot == t->d->op.slot ? 0 : op.slot == t->d->op_sum.slot ? 1 : 2;
        t->rows[4 * k] = kind; t->rows[4 * k + 1] = (int64_t)t->d->nodes[r[k].node].key;
        t->rows[4 * k + 2] = r[k].outcome; t->rows[4 * k + 3] = r[k].worker;
    }
    t->n = n;
}

static void destroy(deriver *d) {
    vs_close(d->vsync);
    if (d->gpu) ps_close(d->gpu);
    if (d->window) ANativeWindow_release(d->window);
    free(d->atlas_cells); free(d->atlas_index);
    free(d->onodes); free(d->ofree); free(d->ochanged); free(d->omarked); free(d->oused); free(d->ocell);
    free(d->free_ids); free(d->pf_ids);
    free(d->cells); free(d->text); free(d->nodes); free(d->blocks); free(d->table); free(d->path); free(d->node);
    free(d->entries); free(d->index); free(d);
}

JNIEXPORT jlong JNICALL CLASS(deriveOpen)(JNIEnv *env, jclass c, jint workers, jint paragraphs) {
    (void)c;
    if (paragraphs <= 0 || paragraphs > 100000 || workers <= 0 || workers > EX_WORKERS) return -1;
    deriver *d = calloc(1, sizeof *d);
    if (!d) return -1;
    /* Ids for twice the initial paragraphs (insertions); a text cell per id, an order cell and a
     * summary node per order-tree slot (at most 2n / (OR_FAN / 2) + 8 slots for n ids). */
    size_t n = 2 * (size_t)paragraphs + 64, slots = n / 4 + 16;
    size_t cellc = n + slots + 32, nodec = n + slots + 16 + 4096, blobc = 2 * n + slots + 64 + 4096;   /* + masks */
    d->paragraphs = (int)n; d->oslots = (uint32_t)slots;
    atomic_store(&d->count, paragraphs);
    d->cells = calloc(cellc, sizeof *d->cells); d->text = calloc(n, sizeof *d->text);
    d->onodes = calloc(slots, sizeof *d->onodes); d->ofree = calloc(slots, sizeof *d->ofree); d->ochanged = calloc(slots, sizeof *d->ochanged);
    d->omarked = calloc(slots, 1); d->oused = calloc(slots, 1); d->ocell = calloc(slots, sizeof *d->ocell);
    d->free_ids = calloc(n, sizeof *d->free_ids); d->pf_ids = calloc(n, sizeof *d->pf_ids);
    d->nodes = calloc(nodec, sizeof *d->nodes); d->blocks = calloc(nodec, sizeof *d->blocks);
    d->table = calloc(pow2(2 * nodec), sizeof *d->table); d->path = calloc(nodec, sizeof *d->path);
    d->node = calloc(n, sizeof *d->node);
    d->entries = calloc(blobc, sizeof *d->entries); d->index = calloc(pow2(2 * blobc), sizeof *d->index);
    jclass cls = (*env)->FindClass(env, "com/trough/hot/DeriveLayout");
    if (!d->onodes || !d->ofree || !d->ochanged || !d->omarked || !d->oused || !d->ocell || !d->free_ids || !d->pf_ids || !d->cells || !d->text || !d->nodes || !d->blocks || !d->table || !d->path || !d->node || !d->entries || !d->index || !cls) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        destroy(d); return -2;
    }
    (*env)->GetJavaVM(env, &d->vm);
    d->cls = (*env)->NewGlobalRef(env, cls);
    d->layout = (*env)->GetStaticMethodID(env, cls, "layout", "(Ljava/lang/String;I)[B");
    d->mask = d->layout ? (*env)->GetStaticMethodID(env, cls, "mask", "(II)[B") : NULL;
    d->caret = d->mask ? (*env)->GetStaticMethodID(env, cls, "caret", "(Ljava/lang/String;II)[B") : NULL;
    d->offset_at = d->caret ? (*env)->GetStaticMethodID(env, cls, "offsetAt", "(Ljava/lang/String;IIF)I") : NULL;
    d->select_rects = d->offset_at ? (*env)->GetStaticMethodID(env, cls, "selection", "(Ljava/lang/String;IIII)[B") : NULL;
    if (!d->layout || !d->mask || !d->caret || !d->offset_at || !d->select_rects) { (*env)->ExceptionClear(env); (*env)->DeleteGlobalRef(env, d->cls); destroy(d); return -3; }
    tr_world_init(&d->world, d->cells, cellc);
    tr_create(&d->world, 1, &d->driver); tr_create(&d->world, 1, &d->op); tr_create(&d->world, 0, &d->width);
    tr_create(&d->world, 1, &d->op_sum); tr_create(&d->world, 1, &d->op_scene); tr_create(&d->world, 0, &d->viewport);
    tr_create(&d->world, 1, &d->op_mask);
    tr_create(&d->world, 0, &d->selection); tr_create(&d->world, 1, &d->op_caret); tr_create(&d->world, 1, &d->op_overlay);
    tr_create(&d->world, 0, &d->sel_anchor); tr_create(&d->world, 1, &d->op_highlight);
    for (size_t i = 0; i < n; i++) tr_create(&d->world, 0, &d->text[i]);
    for (size_t i = 0; i < slots; i++) tr_create(&d->world, 0, &d->ocell[i]);
    for (size_t i = n; i > (size_t)paragraphs; i--) d->free_ids[d->nfree++] = (uint32_t)(i - 1);
    bl_init(&d->blobs, d->entries, blobc, d->index, pow2(2 * blobc), 1ULL << 30);
    or_init(&d->order, d->onodes, (uint32_t)slots, d->ofree, d->ochanged, d->omarked, d->oused);
    for (int i = 0; i < paragraphs; i++) or_insert(&d->order, (uint64_t)i, (uint32_t)i, 0);   /* id i at position i, empty */
    publish_order(d);
    dv_init(&d->graph, &d->world, d->nodes, nodec, d->blocks, nodec, d->table, pow2(2 * nodec), d->path);
    for (size_t i = 0; i < n; i++) dv_intern(&d->graph, d->op, i, &d->node[i]);
    dv_intern(&d->graph, d->op_scene, 0, &d->scene);
    dv_intern(&d->graph, d->op_overlay, 0, &d->overlay);
    ex_init(&d->ex, &d->world, &d->graph, (size_t)workers);
    ex_use_blobs(&d->ex, &d->blobs);
    ex_on_worker(&d->ex, attach, detach, d);
    dv_set_static(&d->graph, d->op);                  /* layout reads text and width (a summary's inputs follow its node) */
    ex_register(&d->ex, d->op, layout_op, d);
    ex_register(&d->ex, d->op_sum, sum_op, d);
    ex_register(&d->ex, d->op_scene, scene_op, d);
    ex_register(&d->ex, d->op_mask, mask_op, d);
    dv_set_static(&d->graph, d->op_caret);            /* text and width */
    ex_register(&d->ex, d->op_caret, caret_op, d);
    ex_register(&d->ex, d->op_overlay, overlay_op, d);
    dv_set_static(&d->graph, d->op_highlight);
    ex_register(&d->ex, d->op_highlight, highlight_op, d);

    if (ex_start(&d->ex)) { (*env)->DeleteGlobalRef(env, d->cls); destroy(d); return -4; }
    return (jlong)(intptr_t)d;
}
JNIEXPORT jint JNICALL CLASS(deriveText)(JNIEnv *env, jclass c, jlong h, jint i, jstring s) {
    (void)c; deriver *d = (deriver *)(intptr_t)h;
    if (i < 0 || i >= atomic_load(&d->count)) return -1;
    call k = {d, i, (*env)->GetStringChars(env, s, NULL), (*env)->GetStringLength(env, s), 0};
    if (!k.units) return -1;
    ex_call(&d->ex, set_text, &k);
    (*env)->ReleaseStringChars(env, s, k.units);
    return (jint)k.result;
}
JNIEXPORT void JNICALL CLASS(deriveWidth)(JNIEnv *env, jclass c, jlong h, jint w) {
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h; call k = {d, w, NULL, 0, 0}; ex_call(&d->ex, set_width, &k);
}
JNIEXPORT void JNICALL CLASS(deriveViewport)(JNIEnv *env, jclass c, jlong h, jint anchor, jint offset, jint height) {
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h;
    if (anchor < 0) anchor = 0;
    if (anchor >= atomic_load(&d->count)) anchor = atomic_load(&d->count) - 1;   /* a paragraph past the end has no node */
    call k = {d, anchor, NULL, (jsize)((offset & 0xffff) | (height & 0xffff) << 16), 0}; ex_call(&d->ex, set_viewport, &k);
}
/* Scroll by dy pixels: move the anchor across exact heights. A paragraph whose layout is not
 * clean yet (crossed upward, or beyond the viewport) is demanded, settled and released. */
/* The scroll's position lives in the call until it is complete, so pausing to lay out a crossed
 * paragraph does not re-derive the scene; the viewport cell is written once. */
typedef struct { deriver *d; int64_t dy, anchor, offset; uint64_t height; uint32_t need; int done, started; } scroll_call;
static void scroll_step(void *arg, tr_world *w, dv_graph *g) {
    scroll_call *sc = arg; deriver *d = sc->d; tr_read vp;
    sc->need = UINT32_MAX; sc->done = 0;
    if (!sc->started) {
        if (tr_current(w, d->viewport, &vp)) { sc->done = 1; return; }
        sc->anchor = (int64_t)(vp.value & 0xffffffffu); sc->offset = (int64_t)((vp.value >> 32) & 0xffff) + sc->dy;
        sc->height = vp.value >> 48; sc->started = 1;
    }
    int64_t anchor = sc->anchor, offset = sc->offset;
    uint64_t height = sc->height, v; const void *p; size_t n;
    for (;;) {
        int64_t i = offset < 0 ? anchor - 1 : anchor;    /* the paragraph whose height decides */
        if (offset < 0 && anchor == 0) { offset = 0; break; }
        if (offset >= 0 && anchor >= atomic_load(&d->count) - 1) break;
        uint64_t id1 = owner_id(d, w, i);
        if (!id1) break;
        if (dv_get(g, d->node[id1 - 1], &v) || !bl_is(v) || bl_get(&d->blobs, v, &p, &n)) { sc->need = (uint32_t)(id1 - 1); break; }
        int64_t h = (int64_t)layout_height(p, n);
        if (offset < 0) { anchor--; offset += h; continue; }
        if (offset >= h && h > 0) { offset -= h; anchor++; continue; }
        break;
    }
    sc->anchor = anchor; sc->offset = offset;
    if (sc->need != UINT32_MAX) { d->scroll_pauses++; return; }   /* paused: lay out id sc->need, then continue */
    if (offset > 0xffff) offset = 0xffff;
    write_cell(d, d->viewport, (uint64_t)anchor | (uint64_t)offset << 32 | height << 48);
    prefetch_update(d, w, g);
    sc->done = 1;
}
/* A scroll waits for a crossed paragraph: urgent, so it runs ahead of prefetching. */
static void demand_layout(void *arg, tr_world *w, dv_graph *g) {
    (void)w; call *c = arg;
    c->result = c->length ? dv_demand_at(g, c->d->node[c->i], DV_URGENT) : dv_release_at(g, c->d->node[c->i], DV_URGENT);   /* i: an id */
}
JNIEXPORT jlong JNICALL CLASS(deriveScroll)(JNIEnv *env, jclass c, jlong h, jint dy) {
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h;
    scroll_call sc = {d, dy, 0, 0, 0, UINT32_MAX, 0, 0};
    for (int guard = 0; guard < 100000; guard++) {
        ex_call(&d->ex, scroll_step, &sc);
        if (sc.done) break;
        call k = {d, (int)sc.need, NULL, 1, 0};            /* lay out the paragraph crossed, then continue */
        ex_call(&d->ex, demand_layout, &k);
        if (!ex_settle_at(&d->ex, DV_URGENT)) return -1;
        k.length = 0; ex_call(&d->ex, demand_layout, &k);
    }
    tr_read vp; uint64_t v = 0;
    if (tr_current(&d->world, d->viewport, &vp) == TR_OK) v = vp.value;   /* read after the calls: owner idle */
    return (jlong)(v & 0xffffffffffffULL);           /* anchor | offset << 32 */
}

/* Anchor at a fraction (per mille) of the estimated height; returns the paragraph, or -1. */
JNIEXPORT jint JNICALL CLASS(deriveJump)(JNIEnv *env, jclass c, jlong h, jint permille) {
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h;
    jump_call j = {d, permille, -1, -1};
    ex_call(&d->ex, demand_root, &j);
    if (!ex_settle_at(&d->ex, DV_VISIBLE)) return -1;
    ex_call(&d->ex, find_anchor, &j);
    return (jint)j.anchor;
}
JNIEXPORT jint JNICALL CLASS(deriveDemandScene)(JNIEnv *env, jclass c, jlong h, jboolean on) {
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h; call k = {d, 0, NULL, on ? 1 : 0, 0}; ex_call(&d->ex, demand_scene, &k); return (jint)k.result;
}
JNIEXPORT jint JNICALL CLASS(deriveDemand)(JNIEnv *env, jclass c, jlong h, jint i, jboolean on) {
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h;
    if (i < 0 || i >= atomic_load(&d->count)) return -1;
    call k = {d, i, NULL, on ? 1 : 0, 0}; ex_call(&d->ex, demand, &k); return (jint)k.result;
}
JNIEXPORT jlong JNICALL CLASS(deriveSettle)(JNIEnv *env, jclass c, jlong h) {
    (void)env; (void)c; return (jlong)ex_settle(&((deriver *)(intptr_t)h)->ex);
}
/* Display timing collected since the last call: refresh period, then (frame, desired, actual,
 * earliest, margin) per presented frame, in ns. Empty without VK_GOOGLE_display_timing. */
JNIEXPORT jlongArray JNICALL CLASS(derivePresentTimes)(JNIEnv *env, jclass c, jlong h) {
    (void)c; deriver *d = (deriver *)(intptr_t)h;
    collect_timing(d);
    jsize n = (jsize)(1 + 7 * d->npresent);
    jlong *v = malloc(sizeof *v * (size_t)n);
    if (!v) return NULL;
    v[0] = (jlong)d->refresh_ns;
    for (size_t i = 0; i < d->npresent; i++) {
        const ps_present_time *t = &d->present_times[i];
        jlong *e = v + 1 + 7 * i;
        e[0] = (jlong)t->frame; e[1] = (jlong)t->desired_ns; e[2] = (jlong)t->actual_ns; e[3] = (jlong)t->earliest_ns;
        e[4] = (jlong)t->margin_ns; e[5] = (jlong)d->frame_start[t->frame % 256]; e[6] = (jlong)d->input_ns[t->frame % 256];
    }
    jlongArray r = (*env)->NewLongArray(env, n);
    if (r) (*env)->SetLongArrayRegion(env, r, 0, n, v);
    free(v);
    d->npresent = 0;
    return r;
}
/* The oldest input event (CLOCK_MONOTONIC ns) the frame begun by deriveFrameBegin consumes. */
JNIEXPORT void JNICALL CLASS(deriveFrameInput)(JNIEnv *env, jclass c, jlong h, jlong event_ns) {
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h;
    d->input_ns[(d->last_number + 1) % 256] = (uint64_t)event_ns;
}
/* Pacing on or off: with it, deriveFrameBegin delays a frame's start (and the input it samples)
 * until its work estimate plus 2 ms before the vsync after the previous frame's. */
/* Mode 0 off, 1 learned grid (display timing), 2 platform deadlines (choreographer frame
 * timelines). Returns the mode in effect: 2 falls back to 0 without the platform API. */
JNIEXPORT jint JNICALL CLASS(derivePace)(JNIEnv *env, jclass c, jlong h, jint mode) {
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h;
    if (mode == 2 && !d->vsync && !(d->vsync = vs_open())) mode = 0;
    /* Mode 2 starts at 1.5 refreshes, measured on the Pixel's Vulkan swapchain (VIEW.md): the
     * platform deadline does not cover the GPU fence and BufferQueue; on-time frames trim it. */
    uint64_t r = d->refresh_ns ? d->refresh_ns : 16666667;
    if (mode != d->pace) d->extra_ns = mode == 1 ? 2000000 : mode == 2 ? r * 3 / 2 : 0;
    d->pace = mode;
    return mode;
}
/* Called before a frame samples input. Returns the ns slept. The vsync grid comes from the
 * newest displayed frame, extrapolated one refresh per frame presented since (no skips). */
JNIEXPORT jlong JNICALL CLASS(deriveFrameBegin)(JNIEnv *env, jclass c, jlong h) {
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h;
    collect_timing(d);
    uint64_t next = d->last_number + 1, now = mono_ns(), slept = 0, r = d->refresh_ns;
    if (d->pace == 2 && d->vsync) {                       /* the earliest deadline the work can meet */
        uint64_t budget = (d->work_ns ? d->work_ns + 2 * d->work_dev : 4000000) + d->extra_ns, until = 0, present = 0;
        pthread_mutex_lock(&d->vsync->lock);
        for (size_t i = 0; i < d->vsync->n; i++) {
            const vs_timeline *t = &d->vsync->tl[i];
            if (t->deadline > 0 && (uint64_t)t->deadline > now + budget && (!until || (uint64_t)t->deadline - budget < until)) {
                until = (uint64_t)t->deadline - budget; present = (uint64_t)t->present;
            }
        }
        pthread_mutex_unlock(&d->vsync->lock);
        if (until && until < now + 4 * (r ? r : 16666667)) {
            struct timespec ts = {(time_t)(until / 1000000000u), (long)(until % 1000000000u)};
            clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, NULL);
            slept = until - now;
            d->target_ns[next % 256] = present;
        }
    } else if (d->pace == 1 && r && d->shown_ns && d->work_ns && d->last_number >= d->shown_frame) {
        uint64_t vsync = d->shown_ns + (next - d->shown_frame) * r;   /* the previous frame's, plus one */
        uint64_t budget = d->work_ns + 2 * d->work_dev + d->extra_ns;
        while (vsync > now + 4 * r) vsync -= r;            /* a stale grid never sleeps more than ~3 frames */
        d->target_ns[next % 256] = vsync;
        if (vsync > budget && vsync - budget > now) {
            uint64_t until = vsync - budget;
            struct timespec ts = {(time_t)(until / 1000000000u), (long)(until % 1000000000u)};
            clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, NULL);
            slept = until - now;
        }
    }
    d->frame_start[next % 256] = mono_ns();
    d->input_ns[next % 256] = 0;
    d->slept_ns += slept;
    return (jlong)slept;
}
/* Settles what is on screen (DV_VISIBLE and urgent), not the prefetch. */
JNIEXPORT jlong JNICALL CLASS(deriveSettleVisible)(JNIEnv *env, jclass c, jlong h) {
    (void)env; (void)c; return (jlong)ex_settle_at(&((deriver *)(intptr_t)h)->ex, DV_VISIBLE);
}
/* Inserting and removing paragraphs (docs/VIEW.md "Paragraph identity"): order cells move,
 * text cells and layouts stay with their ids. Positions held elsewhere move like an Editable's
 * spans: the viewport anchor keeps showing the same paragraph, and selection ends follow
 * their paragraphs. */
static uint64_t shift_position(uint64_t cell_value, int64_t index, int insert) {   /* (pos << 32 | off) + 1 */
    if (!cell_value) return 0;
    int64_t pos = (int64_t)((cell_value - 1) >> 32); uint64_t off = (cell_value - 1) & 0xffffffffu;
    if (insert) { if (pos >= index) pos++; }
    else if (pos > index) pos--;
    else if (pos == index) off = 0;                  /* its paragraph is gone: the start of the next */
    return ((uint64_t)pos << 32 | off) + 1;
}
static void move_spans(deriver *d, tr_world *w, int64_t index, int insert) {
    tr_read vp, sel, anc;
    int count = atomic_load(&d->count);
    if (tr_current(w, d->viewport, &vp) == TR_OK) {
        int64_t anchor = (int64_t)(vp.value & 0xffffffffu); uint64_t rest = vp.value & ~0xffffffffULL;
        if (insert && index <= anchor) anchor++;
        else if (!insert && index < anchor) anchor--;
        else if (!insert && index == anchor) rest &= ~(0xffffULL << 32);   /* shown from the next one's top */
        if (anchor >= count) anchor = count - 1;
        if (anchor < 0) anchor = 0;
        uint64_t v = (uint64_t)anchor | rest;
        if (v != vp.value) write_cell(d, d->viewport, v);
    }
    if (tr_current(w, d->selection, &sel) == TR_OK) {
        uint64_t v = shift_position(sel.value, index, insert);
        if (v && ((v - 1) >> 32) >= (uint64_t)count) v = 0;
        if (v != sel.value) write_cell(d, d->selection, v);
    }
    if (tr_current(w, d->sel_anchor, &anc) == TR_OK) {
        uint64_t v = shift_position(anc.value, index, insert);
        if (v && ((v - 1) >> 32) >= (uint64_t)count) v = 0;
        if (v != anc.value) write_cell(d, d->sel_anchor, v);
    }
}
static void insert_paragraph(void *arg, tr_world *w, dv_graph *g) {   /* i = position; result = id or -1 */
    call *c = arg; deriver *d = c->d; uint64_t h;
    int count = atomic_load(&d->count);
    c->result = -1;
    if (c->i < 0 || c->i > count || !d->nfree) return;
    if (bl_intern(&d->blobs, (void *)c->units, (size_t)c->length * 2, 0, &h)) return;
    uint32_t id = d->free_ids[d->nfree - 1];
    if (or_insert(&d->order, (uint64_t)c->i, id, (uint64_t)c->length)) { bl_release(&d->blobs, h); return; }
    d->nfree--;
    write_cell(d, d->text[id], h);
    publish_order(d);
    atomic_store(&d->count, count + 1);
    move_spans(d, w, c->i, 1);
    prefetch_update(d, w, g);
    c->result = id;
}
static void remove_paragraph(void *arg, tr_world *w, dv_graph *g) {   /* i = position; result = id or -1 */
    call *c = arg; deriver *d = c->d;
    int count = atomic_load(&d->count);
    c->result = -1;
    if (c->i < 0 || c->i >= count || count <= 1) return;   /* a document keeps one paragraph */
    uint32_t id;
    if (or_remove(&d->order, (uint64_t)c->i, &id)) return;
    uint64_t id1 = (uint64_t)id + 1;
    publish_order(d);
    write_cell(d, d->text[id1 - 1], 0);                  /* the id's text blob is released */
    d->free_ids[d->nfree++] = (uint32_t)(id1 - 1);
    atomic_store(&d->count, count - 1);
    move_spans(d, w, c->i, 0);
    prefetch_update(d, w, g);
    c->result = (int64_t)(id1 - 1);
}
JNIEXPORT jint JNICALL CLASS(deriveInsert)(JNIEnv *env, jclass c, jlong h, jint i, jstring s) {
    (void)c; deriver *d = (deriver *)(intptr_t)h;
    call k = {d, i, (*env)->GetStringChars(env, s, NULL), (*env)->GetStringLength(env, s), -1};
    if (!k.units) return -1;
    ex_call(&d->ex, insert_paragraph, &k);
    (*env)->ReleaseStringChars(env, s, k.units);
    return (jint)k.result;
}
JNIEXPORT jint JNICALL CLASS(deriveRemove)(JNIEnv *env, jclass c, jlong h, jint i) {
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h;
    call k = {d, i, NULL, 0, -1};
    ex_call(&d->ex, remove_paragraph, &k);
    return (jint)k.result;
}
JNIEXPORT jint JNICALL CLASS(deriveCount)(JNIEnv *env, jclass c, jlong h) {
    (void)env; (void)c; return atomic_load(&((deriver *)(intptr_t)h)->count);
}
/* The document as one text, paragraphs joined by '\n' (the IME's view, docs/INPUT.md
 * "Document"). Offsets come from the order tree's unit totals (docs/ORDER.md): O(log n). */
static size_t para_units(deriver *d, tr_world *w, int64_t i, const uint16_t **units) {
    uint64_t id1 = owner_id(d, w, i); tr_read t; const void *p = NULL; size_t n = 0;
    if (id1 && tr_current(w, d->text[id1 - 1], &t) == TR_OK && bl_is(t.value)) bl_get(&d->blobs, t.value, &p, &n);
    if (units) *units = p;
    return n / 2;
}
typedef struct { deriver *d; int64_t global, para, off; int64_t state[3]; uint16_t *out; int64_t a, b, n; } doc_call;
static int64_t to_global(deriver *d, tr_world *w, uint64_t cell) {   /* (pos << 32 | off) + 1 -> offset */
    (void)w; uint64_t pos = (cell - 1) >> 32, off = (cell - 1) & 0xffffffffu, g, units;
    if (or_offset(&d->order, pos, &g) || or_at(&d->order, pos, NULL, &units)) return 0;
    return (int64_t)(g + (off < units ? off : units));
}
static void doc_state(void *arg, tr_world *w, dv_graph *g) {   /* length, anchor, caret */
    (void)g; doc_call *c = arg; deriver *d = c->d; tr_read sel, anc;
    c->state[0] = (int64_t)or_units(&d->order);
    uint64_t sv = tr_current(w, d->selection, &sel) == TR_OK ? sel.value : 0, av = tr_current(w, d->sel_anchor, &anc) == TR_OK ? anc.value : 0;
    c->state[2] = sv ? to_global(d, w, sv) : 0;
    c->state[1] = av ? to_global(d, w, av) : c->state[2];
}
static void doc_locate(void *arg, tr_world *w, dv_graph *g) {   /* global -> (para, off), clamped */
    (void)w; (void)g; doc_call *c = arg; uint64_t pos = 0, off = 0;
    if (or_locate(&c->d->order, c->global < 0 ? 0 : (uint64_t)c->global, &pos, &off)) { pos = 0; off = 0; }
    c->para = (int64_t)pos; c->off = (int64_t)off;
}
static void doc_slice(void *arg, tr_world *w, dv_graph *g) {   /* units [a, b) of the joined text */
    (void)g; doc_call *c = arg; deriver *d = c->d; uint64_t pos, off, start;
    c->n = 0;
    if (c->a >= c->b || or_locate(&d->order, (uint64_t)c->a, &pos, &off) || or_offset(&d->order, pos, &start)) return;
    int count = atomic_load(&d->count); int64_t at = (int64_t)start;
    for (int64_t i = (int64_t)pos; i < count && at < c->b; i++) {
        if (i > (int64_t)pos) { if (at >= c->a && at < c->b) c->out[c->n++] = '\n'; at++; }
        const uint16_t *u; int64_t n = (int64_t)para_units(d, w, i, &u);
        for (int64_t k = 0; k < n && at < c->b; k++, at++) if (at >= c->a) c->out[c->n++] = u[k];
    }
}
static void leaf_size(void *arg, tr_world *w, dv_graph *g) {
    (void)w; (void)g; doc_call *c = arg; uint32_t e = 0;
    c->n = c->global < 0 || or_leaf_entries(&c->d->order, (uint64_t)c->global, &e) ? -1 : (int64_t)e;
}
static void doc_paragraph(void *arg, tr_world *w, dv_graph *g) {   /* para -> its units, copied */
    (void)g; doc_call *c = arg; const uint16_t *u = NULL;
    c->n = -1;
    if (c->para < 0 || c->para >= atomic_load(&c->d->count)) return;
    int64_t n = (int64_t)para_units(c->d, w, c->para, &u);
    c->out = malloc(sizeof(uint16_t) * (size_t)(n + 1));
    if (!c->out) return;
    if (n) memcpy(c->out, u, sizeof(uint16_t) * (size_t)n);
    c->n = n;
}
JNIEXPORT jint JNICALL CLASS(deriveLeafSize)(JNIEnv *env, jclass c, jlong h, jint position) {   /* paragraphs in its leaf */
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h; doc_call k = {d, position, 0, 0, {0}, NULL, 0, 0, 0};
    ex_call(&d->ex, leaf_size, &k);
    return (jint)k.n;
}
JNIEXPORT jstring JNICALL CLASS(deriveParagraph)(JNIEnv *env, jclass c, jlong h, jint para) {
    (void)c; doc_call k = {(deriver *)(intptr_t)h, 0, para, 0, {0}, NULL, 0, 0, 0};
    ex_call(&k.d->ex, doc_paragraph, &k);
    jstring r = k.n >= 0 ? (*env)->NewString(env, k.out, (jsize)k.n) : NULL;
    free(k.out);
    return r;
}
JNIEXPORT jlongArray JNICALL CLASS(deriveDocState)(JNIEnv *env, jclass c, jlong h) {
    (void)c; doc_call k = {(deriver *)(intptr_t)h, 0, 0, 0, {0}, NULL, 0, 0, 0};
    ex_call(&k.d->ex, doc_state, &k);
    jlongArray r = (*env)->NewLongArray(env, 3);
    if (r) (*env)->SetLongArrayRegion(env, r, 0, 3, (const jlong *)k.state);
    return r;
}
JNIEXPORT jlong JNICALL CLASS(deriveLocate)(JNIEnv *env, jclass c, jlong h, jlong global) {
    (void)env; (void)c; doc_call k = {(deriver *)(intptr_t)h, global, 0, 0, {0}, NULL, 0, 0, 0};
    ex_call(&k.d->ex, doc_locate, &k);
    return k.para << 32 | k.off;
}
JNIEXPORT jstring JNICALL CLASS(deriveSlice)(JNIEnv *env, jclass c, jlong h, jlong a, jlong b) {
    (void)c;
    if (a < 0 || b < a) return NULL;
    doc_call k = {(deriver *)(intptr_t)h, 0, 0, 0, {0}, malloc(sizeof(uint16_t) * (size_t)(b - a + 1)), a, b, 0};
    if (!k.out) return NULL;
    ex_call(&k.d->ex, doc_slice, &k);
    jstring r = (*env)->NewString(env, k.out, (jsize)k.n);
    free(k.out);
    return r;
}
/* The insertion marker for CursorAnchorInfo (docs/INPUT.md "Cursor anchor"): the overlay's caret
 * rectangle gives the paragraph's y in the viewport, its caret node the line box, baseline and
 * direction. Read on the owner from settled nodes; none when the selection is a range or the
 * caret lies outside the drawn paragraphs. Out: x, top, baseline, bottom (viewport), rtl, the
 * paragraph's viewport top, the layout width. */
typedef struct { deriver *d; int ok; float m[7]; } marker_call;
static void cursor_anchor(void *arg, tr_world *w, dv_graph *g) {
    marker_call *c = arg; deriver *d = c->d; tr_read sel, anc; uint64_t ov, cv; const void *p = NULL, *q = NULL; size_t n = 0, m = 0;
    c->ok = 0;
    if (tr_current(w, d->selection, &sel) != TR_OK || !sel.value) return;
    if (tr_current(w, d->sel_anchor, &anc) == TR_OK && anc.value && anc.value != sel.value) return;
    if (dv_get(g, d->overlay, &ov) || !bl_is(ov) || bl_get(&d->blobs, ov, &p, &n) || n < 4) return;
    const unsigned char *b = p; uint32_t count = be32(b);
    for (uint32_t k = 0; k < count && 4 + (size_t)(k + 1) * 20 <= n; k++) {
        const unsigned char *r = b + 4 + (size_t)k * 20;
        if (be32(r + 16) != 0) continue;                  /* kind 0: the caret */
        uint64_t pos = (sel.value - 1) >> 32, id1 = owner_id(d, w, (int64_t)pos); uint32_t node;
        if (!id1 || dv_intern(g, d->op_caret, (id1 - 1) << 32 | ((sel.value - 1) & 0xffffffffu), &node)) return;
        if (dv_get(g, node, &cv) || !bl_is(cv) || bl_get(&d->blobs, cv, &q, &m) || m < 20) return;
        const unsigned char *t = q; float dy = bef(r + 4) - bef(t + 4);   /* viewport y of the paragraph top */
        c->m[0] = bef(t); c->m[1] = dy + bef(t + 4); c->m[2] = dy + bef(t + 12); c->m[3] = dy + bef(t + 8);
        c->m[4] = (float)(be32(t + 16) & 1); c->m[5] = dy;
        tr_read wr; c->m[6] = tr_current(w, d->width, &wr) == TR_OK ? (float)wr.value : 0;
        c->ok = 1;
        return;
    }
}
JNIEXPORT jfloatArray JNICALL CLASS(deriveCursorAnchor)(JNIEnv *env, jclass c, jlong h) {
    (void)c; marker_call k = {(deriver *)(intptr_t)h, 0, {0}};
    ex_call(&k.d->ex, cursor_anchor, &k);
    if (!k.ok) return NULL;
    jfloatArray r = (*env)->NewFloatArray(env, 7);
    if (r) (*env)->SetFloatArrayRegion(env, r, 0, 7, k.m);
    return r;
}
/* Tap at viewport (x, y): the paragraph and line under it, from the clean layouts the scene
 * read (exact heights from the anchor), then the offset by TextLayout's tap rules
 * (Layout.getOffsetForHorizontal) through Java on the calling thread. The selection becomes a
 * caret there. Returns paragraph << 32 | offset, or -1 (layouts not settled). */
typedef struct { deriver *d; float x, y; int64_t para; int line; void *text; size_t length; uint64_t width; int ok; } tap_call;
static void tap_find(void *arg, tr_world *w, dv_graph *g) {
    tap_call *t = arg; deriver *d = t->d; tr_read vp, wd, tx; uint64_t v; const void *p; size_t n;
    t->ok = 0;
    if (tr_current(w, d->viewport, &vp) || tr_current(w, d->width, &wd)) return;
    uint64_t anchor = vp.value & 0xffffffffu; float y = -(float)((vp.value >> 32) & 0xffff);
    uint64_t count = (uint64_t)atomic_load(&d->count);
    for (uint64_t i = anchor; i < count; i++) {
        uint64_t id = owner_id(d, w, (int64_t)i) - 1;
        if (dv_get(g, d->node[id], &v) || !bl_is(v) || bl_get(&d->blobs, v, &p, &n)) return;
        float h = (float)layout_height(p, n);
        if (t->y < y + h || i == count - 1) {
            const unsigned char *b = p; uint32_t lines = be32(b); float local = t->y - y;
            int line = (int)lines - 1;
            for (uint32_t l = 0; l < lines; l++) if (local < bef(b + 4 + (size_t)l * LINE_BYTES + 16)) { line = (int)l; break; }
            if (tr_current(w, d->text[id], &tx)) return;
            const void *u = NULL; size_t un = 0;
            if (bl_is(tx.value)) bl_get(&d->blobs, tx.value, &u, &un);
            t->text = malloc(un ? un : 1);
            if (!t->text) return;
            if (un) memcpy(t->text, u, un);
            t->length = un; t->para = (int64_t)i; t->line = line < 0 ? 0 : line; t->width = wd.value; t->ok = 1;
            return;
        }
        y += h;
    }
}
static void set_selection(void *arg, tr_world *w, dv_graph *g) {    /* i = paragraph, length = offset (< 0: none) */
    (void)w; (void)g; call *c = arg;
    tr_read a;
    if (tr_current(&c->d->world, c->d->sel_anchor, &a) == TR_OK && a.value) write_cell(c->d, c->d->sel_anchor, 0);   /* a caret */
    write_cell(c->d, c->d->selection, c->length < 0 ? 0 : ((uint64_t)(uint32_t)c->i << 32 | (uint32_t)c->length) + 1);
}
typedef struct { deriver *d; uint64_t anchor, focus; } range_call;
static void set_range(void *arg, tr_world *w, dv_graph *g) {
    (void)w; (void)g; range_call *r = arg;
    write_cell(r->d, r->d->sel_anchor, r->anchor);
    write_cell(r->d, r->d->selection, r->focus);
}
/* A range from (pa, oa) to (pb, ob), either order; the focus (pb, ob) is where a caret would be. */
JNIEXPORT void JNICALL CLASS(deriveSelectRange)(JNIEnv *env, jclass c, jlong h, jint pa, jint oa, jint pb, jint ob) {
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h;
    if (pa < 0 || pb < 0 || pa >= atomic_load(&d->count) || pb >= atomic_load(&d->count) || oa < 0 || ob < 0 || oa > 0xfffff || ob > 0xfffff) return;
    range_call r = {d, ((uint64_t)(uint32_t)pa << 32 | (uint32_t)oa) + 1, ((uint64_t)(uint32_t)pb << 32 | (uint32_t)ob) + 1};
    ex_call(&d->ex, set_range, &r);
}
JNIEXPORT jlong JNICALL CLASS(deriveTap)(JNIEnv *env, jclass c, jlong h, jfloat x, jfloat y) {
    (void)c; deriver *d = (deriver *)(intptr_t)h;
    tap_call t = {d, x, y, -1, 0, NULL, 0, 0, 0};
    ex_call(&d->ex, tap_find, &t);
    if (!t.ok) return -1;
    jstring s = (*env)->NewString(env, t.text, (jsize)(t.length / 2));
    free(t.text);
    if (!s) return -1;
    jint offset = (*env)->CallStaticIntMethod(env, d->cls, d->offset_at, s, (jint)t.width, (jint)t.line, x);
    (*env)->DeleteLocalRef(env, s);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return -1; }
    call k = {d, (int)t.para, NULL, offset, 0};
    ex_call(&d->ex, set_selection, &k);
    return t.para << 32 | (int64_t)(uint32_t)offset;
}
JNIEXPORT void JNICALL CLASS(deriveSelect)(JNIEnv *env, jclass c, jlong h, jint paragraph, jint offset) {
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h;
    if (paragraph < 0 || paragraph >= atomic_load(&d->count)) offset = -1;
    call k = {d, paragraph, NULL, offset, 0};
    ex_call(&d->ex, set_selection, &k);
}
/* Lays out `before` paragraphs above the anchor and `after` below it in the background, and
 * keeps that window around the anchor as the viewport moves. 0, 0 turns it off. */
JNIEXPORT void JNICALL CLASS(derivePrefetch)(JNIEnv *env, jclass c, jlong h, jint before, jint after) {
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h;
    call k = {d, before < 0 ? 0 : before, NULL, after < 0 ? 0 : after, 0}; ex_call(&d->ex, set_prefetch, &k);
}
JNIEXPORT jlong JNICALL CLASS(deriveValue)(JNIEnv *env, jclass c, jlong h, jint i) {
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h;
    if (i < 0 || i >= atomic_load(&d->count)) return -1;
    call k = {d, i, NULL, 0, -1}; ex_call(&d->ex, value, &k); return k.result;
}
JNIEXPORT jbyteArray JNICALL CLASS(deriveBytes)(JNIEnv *env, jclass c, jlong h, jint i) {
    (void)c; deriver *d = (deriver *)(intptr_t)h;
    if (i < -2 || i >= atomic_load(&d->count)) return NULL;
    copy k = {d, i, NULL, 0, 0};
    ex_call(&d->ex, bytes, &k);
    jbyteArray r = NULL;
    if (k.ok && (r = (*env)->NewByteArray(env, (jsize)k.length))) (*env)->SetByteArrayRegion(env, r, 0, (jsize)k.length, k.bytes);
    free(k.bytes);
    return r;
}
JNIEXPORT jlongArray JNICALL CLASS(deriveStats)(JNIEnv *env, jclass c, jlong h) {
    (void)c; deriver *d = (deriver *)(intptr_t)h;
    int64_t s[28] = {0};
    call k = {d, 0, (const jchar *)(void *)s, 0, 0};
    ex_call(&d->ex, stats, &k);
    jlongArray r = (*env)->NewLongArray(env, 28);
    if (r) (*env)->SetLongArrayRegion(env, r, 0, 28, (const jlong *)s);
    return r;
}
JNIEXPORT jlongArray JNICALL CLASS(deriveTrace)(JNIEnv *env, jclass c, jlong h) {
    (void)c; deriver *d = (deriver *)(intptr_t)h;
    int64_t *rows = malloc(sizeof(int64_t) * 4 * EX_TRACE);
    if (!rows) return NULL;
    traced t = {d, rows, 0};
    ex_call(&d->ex, trace_rows, &t);
    jlongArray r = (*env)->NewLongArray(env, (jsize)(4 * t.n));
    if (r) (*env)->SetLongArrayRegion(env, r, 0, (jsize)(4 * t.n), (const jlong *)rows);
    free(rows);
    return r;
}
JNIEXPORT void JNICALL CLASS(deriveClose)(JNIEnv *env, jclass c, jlong h) {
    (void)c; deriver *d = (deriver *)(intptr_t)h;
    ex_stop(&d->ex);
    (*env)->DeleteGlobalRef(env, d->cls);
    destroy(d);
}

/* ---- the view's frames ---- */
typedef struct { deriver *d; const unsigned char *scene; size_t n; uint32_t *nodes; size_t count; int64_t result; } masks_call;
static void scene_copy(void *arg, tr_world *w, dv_graph *g) {
    (void)w; copy *c = arg; uint64_t v; const void *p; size_t n;
    if (dv_get(g, c->d->scene, &v) || !bl_is(v) || bl_get(&c->d->blobs, v, &p, &n)) return;
    c->bytes = malloc(n ? n : 1);
    if (c->bytes) { memcpy(c->bytes, p, n); c->length = n; c->ok = 1; }
}
/* Interns and demands the mask node of every glyph in the scene (once per distinct glyph). */
static void demand_masks(void *arg, tr_world *w, dv_graph *g) {
    (void)w; masks_call *m = arg; uint32_t count = be32(m->scene);
    m->count = 0; m->result = 0;
    for (uint32_t k = 0; k < count; k++) {
        const unsigned char *q = m->scene + 4 + (size_t)k * 16;
        uint32_t node;
        if (dv_intern(g, m->d->op_mask, (uint64_t)be32(q) << 32 | be32(q + 4), &node)) { m->result = -1; return; }
        m->nodes[k] = node;
        if (!g->nodes[node].demand) { dv_demand(g, node); m->count++; }
    }
}
typedef struct { deriver *d; const uint32_t *nodes; uint32_t count; uint64_t *handles; unsigned char **bytes; size_t *lengths; int ok; } mask_bytes;
static void collect_masks(void *arg, tr_world *w, dv_graph *g) {
    (void)w; mask_bytes *m = arg; m->ok = 1;
    for (uint32_t k = 0; k < m->count; k++) {
        uint64_t v; const void *p; size_t n;
        if (dv_get(g, m->nodes[k], &v) || !bl_is(v) || bl_get(&m->d->blobs, v, &p, &n) || n < 16) { m->ok = 0; m->handles[k] = 0; continue; }
        m->handles[k] = v;
        m->bytes[k] = malloc(n); if (m->bytes[k]) memcpy(m->bytes[k], p, n); m->lengths[k] = n;
    }
}

int trough_surface_gpu(JNIEnv *env, jobject surface, uint32_t w, uint32_t h, ps_gpu **out, ANativeWindow **window);   /* present.c */
static int atlas_open(deriver *d, jint aw, jint ah) {
    size_t cells = (size_t)(aw / 16) * (ah / 16), index = 1; while (index < 2 * cells) index <<= 1;
    if ((size_t)(aw / 256) * (ah / 256) > 64) return -1;
    d->atlas_cells = calloc(cells, sizeof *d->atlas_cells); d->atlas_index = calloc(index, sizeof *d->atlas_index);
    if (!d->atlas_cells || !d->atlas_index) return -2;
    return at_init(&d->atlas, (uint32_t)aw, (uint32_t)ah, 256, d->atlas_cells, cells, d->atlas_index, index, d->atlas_pages) ? -3 : 0;
}
/* Frames presented to an Android Surface instead of offscreen targets. */
JNIEXPORT jint JNICALL CLASS(deriveSurfaceOpen)(JNIEnv *env, jclass c, jlong h, jobject surface, jint w, jint hh, jint aw, jint ah) {
    (void)c; deriver *d = (deriver *)(intptr_t)h;
    int st = atlas_open(d, aw, ah);
    if (!st) st = trough_surface_gpu(env, surface, (uint32_t)w, (uint32_t)hh, &d->gpu, &d->window);
    if (!st) st = ps_frames_init(d->gpu, (uint32_t)w, (uint32_t)hh, (uint32_t)aw, (uint32_t)ah);
    d->fw = (uint32_t)w; d->fh = (uint32_t)hh;
    return st;
}
JNIEXPORT jint JNICALL CLASS(deriveFrameOpen)(JNIEnv *env, jclass c, jlong h, jint w, jint hh, jint aw, jint ah) {
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h;
    char info[256];
    int st = atlas_open(d, aw, ah);
    if (!st) st = ps_open(&d->gpu, info, sizeof info);
    if (!st) st = ps_frames_init(d->gpu, (uint32_t)w, (uint32_t)hh, (uint32_t)aw, (uint32_t)ah);
    d->fw = (uint32_t)w; d->fh = (uint32_t)hh;
    return st;
}
/* Builds and submits the frame for the current scene; with `pixels`, waits and returns them.
 * timing: [mask settle ms, build ms, gpu wait ms, glyphs, new masks]. */
JNIEXPORT jbyteArray JNICALL CLASS(deriveFrame)(JNIEnv *env, jclass c, jlong h, jboolean pixels, jdoubleArray timing) {
    (void)c; deriver *d = (deriver *)(intptr_t)h;
    struct timespec t0, t1, t2, t3;
    copy sc = {d, -1, NULL, 0, 0};
    ex_call(&d->ex, scene_copy, &sc);
    if (!sc.ok || sc.length < 4) { free(sc.bytes); return NULL; }
    uint32_t count = be32(sc.bytes);
    /* The overlay's rectangles (the caret), drawn under the glyphs. */
    ps_rect *rects = NULL; size_t nrect = 0;
    copy oc = {d, -2, NULL, 0, 0};
    ex_call(&d->ex, bytes, &oc);
    if (oc.ok && oc.length >= 4) {
        uint32_t nr = be32(oc.bytes);
        rects = malloc(sizeof *rects * (nr ? nr : 1));
        for (uint32_t k = 0; rects && k < nr && 4 + (size_t)(k + 1) * 20 <= oc.length; k++) {
            const unsigned char *q = oc.bytes + 4 + (size_t)k * 20;
            ps_rect *r = &rects[nrect++];
            float a = be32(q + 16) ? HIGHLIGHT_A : 1;    /* kind 1: highlight, translucent */
            r->x0 = bef(q); r->y0 = bef(q + 4); r->x1 = bef(q + 8); r->y1 = bef(q + 12);
            r->cx0 = 0; r->cy0 = 0; r->cx1 = (float)d->fw; r->cy1 = (float)d->fh;
            r->r = CARET_R * a; r->g = CARET_G * a; r->b = CARET_B * a; r->a = a;   /* premultiplied */
        }
    }
    free(oc.bytes);
    uint32_t *nodes = malloc(sizeof *nodes * (count ? count : 1));
    uint64_t *handles = calloc(count ? count : 1, sizeof *handles);
    unsigned char **bytes = calloc(count ? count : 1, sizeof *bytes);
    size_t *lengths = calloc(count ? count : 1, sizeof *lengths);
    ps_glyph *glyphs = malloc(sizeof *glyphs * (count ? count : 1));
    ps_upload *ups = malloc(sizeof *ups * (count ? count : 1));
    unsigned char *staging = NULL; size_t staged = 0, cap = 0;
    jbyteArray result = NULL;
    double ms[5] = {0};
    if (!nodes || !handles || !bytes || !lengths || !glyphs || !ups) goto done;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    masks_call mc = {d, sc.bytes, sc.length, nodes, 0, 0};
    ex_call(&d->ex, demand_masks, &mc);
    if (mc.result || !ex_settle_at(&d->ex, DV_VISIBLE)) goto done;   /* not the prefetch */
    clock_gettime(CLOCK_MONOTONIC, &t1);
    mask_bytes mb = {d, nodes, count, handles, bytes, lengths, 0};
    ex_call(&d->ex, collect_masks, &mb);
    if (!mb.ok) goto done;
    size_t nglyph = 0, nup = 0;
    for (uint32_t k = 0; k < count; k++) {
        const unsigned char *q = sc.bytes + 4 + (size_t)k * 16, *m = bytes[k];
        uint32_t w = be32(m), hh = be32(m + 4); int32_t left = (int32_t)be32(m + 8), top = (int32_t)be32(m + 12);
        if (!w || !hh) continue;                       /* empty glyph */
        at_rect r;
        tr_status s = at_use(&d->atlas, handles[k], (uint16_t)w, (uint16_t)hh, &r);
        if (s == TR_CAPACITY) continue;                /* refused: drawn next frame (counted) */
        if (s == TR_STALE) {
            if (staged + (size_t)w * hh > cap) {
                cap = (staged + (size_t)w * hh) * 2 + 65536;
                unsigned char *grown = realloc(staging, cap);
                if (!grown) goto done;
                staging = grown;
            }
            memcpy(staging + staged, m + 16, (size_t)w * hh);
            ups[nup++] = (ps_upload){r.x, r.y, (uint16_t)w, (uint16_t)hh, (uint32_t)staged};
            staged += (size_t)w * hh; d->frame_uploads++;
        } else d->frame_hits++;
        float x = floorf(bef(q + 8) + 0.5f) + (float)left, y = floorf(bef(q + 12) + 0.5f) + (float)top;
        glyphs[nglyph++] = (ps_glyph){x, y, x + (float)w, y + (float)hh, r.x, r.y, 0, 0, -1e6f, -1e6f, 1e6f, 1e6f, 0, 0, 0, 1};
    }
    uint64_t number;
    int presented = d->window ? ps_frame_present(d->gpu, ups, nup, staging, staged, rects, nrect, glyphs, nglyph, pixels, &number)
                              : ps_frame_submit(d->gpu, ups, nup, staging, staged, rects, nrect, glyphs, nglyph, &number);
    collect_timing(d);
    if (!presented || presented == 1) {
        d->last_number = number;
        uint64_t start = d->frame_start[number % 256], now = mono_ns();
        if (start && now > start) {                     /* work estimate: mean + 2 deviations, EWMA 1/8 */
            uint64_t w = now - start, dev = w > d->work_ns ? w - d->work_ns : d->work_ns - w;
            if (!d->work_ns) { d->work_ns = w; d->work_dev = w / 4; }
            else { d->work_ns = (7 * d->work_ns + w) / 8; d->work_dev = (7 * d->work_dev + dev) / 8; }
        }
    }
    if (presented) { at_submit(&d->atlas); ms[4] = -presented; goto done; }   /* 1: swapchain out of date */
    at_submit(&d->atlas);
    clock_gettime(CLOCK_MONOTONIC, &t2);
    if (pixels) {
        if (ps_frames_wait(d->gpu, number)) goto done;
        size_t bytes_px = (size_t)d->fw * d->fh * 4;
        unsigned char *rgba = malloc(bytes_px);
        if (rgba && !ps_frame_pixels(d->gpu, number, rgba) && (result = (*env)->NewByteArray(env, (jsize)bytes_px)))
            (*env)->SetByteArrayRegion(env, result, 0, (jsize)bytes_px, (const jbyte *)rgba);
        free(rgba);
    }
    at_retire(&d->atlas, ps_frames_retired(d->gpu));
    clock_gettime(CLOCK_MONOTONIC, &t3);
    ms[0] = (t1.tv_sec - t0.tv_sec) * 1e3 + (t1.tv_nsec - t0.tv_nsec) / 1e6;
    ms[1] = (t2.tv_sec - t1.tv_sec) * 1e3 + (t2.tv_nsec - t1.tv_nsec) / 1e6;
    ms[2] = (t3.tv_sec - t2.tv_sec) * 1e3 + (t3.tv_nsec - t2.tv_nsec) / 1e6;
    ms[3] = (double)nglyph; ms[4] = (double)nup;
done:
    free(rects);
    if (timing) (*env)->SetDoubleArrayRegion(env, timing, 0, 5, ms);
    for (uint32_t k = 0; bytes && k < count; k++) free(bytes[k]);
    free(sc.bytes); free(nodes); free(handles); free(bytes); free(lengths); free(glyphs); free(ups); free(staging);
    return result;
}
