/* Text layout as a derivation operator, in process (docs/LAYOUT_DERIVE.md). A world of
 * paragraph text cells (UTF-16 blobs) and one width cell; node (layout, i) is paragraph i laid
 * out at the width. The operator runs on executor workers attached to the JVM and calls the
 * hot-loaded DeriveLayout.layout(String, int), whose serialized lines and glyphs become a
 * canonical blob. Java drives it through owner calls; no Java code sees jobs or the graph. */
#define _POSIX_C_SOURCE 200809L
#include "exec.h"
#include "atlas.h"
#include "scene.h"
#include <jni.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>

#define CLASS(name) Java_com_trough_hot_NativeCore_##name

typedef struct {
    JavaVM *vm; jclass cls; jmethodID layout, mask;
    tr_id op_mask;
    /* The view's frames (deriveFrameOpen): GPU frame ring and atlas residency, used only by the
     * thread that calls deriveFrame. */
    ps_gpu *gpu; at_atlas atlas; at_cell *atlas_cells; uint32_t *atlas_index; int32_t atlas_pages[64];
    uint32_t fw, fh; uint64_t frame_uploads, frame_hits;
    tr_world world; tr_cell *cells; tr_id driver, width, op, viewport, op_sum, op_scene; tr_id *text; int paragraphs;
    int levels; uint32_t scene;                       /* summary levels above paragraphs; the scene node */
    atomic_ullong runs[5];                            /* invocations: layout, summary, scene, mask; [4] paragraphs estimated */
    dv_graph graph; dv_node *nodes; dv_block *blocks; uint32_t *table; dv_frame *path; uint32_t *node;
    bl_store blobs; bl_entry *entries; uint32_t *index;
    ex_exec ex;
} deriver;

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
static uint64_t count_at(const deriver *d, int level) {           /* nodes at a summary level */
    uint64_t c = (uint64_t)d->paragraphs;
    for (int l = 0; l <= level; l++) c = (c + FAN - 1) / FAN;
    return c;
}
static dv_ref child_ref(const deriver *d, int level, uint64_t j) {
    if (level < 0) return (dv_ref){DV_CELL, d->text[j], 0};          /* level 0 estimates its texts */
    return (dv_ref){DV_NODE, d->op_sum, (uint64_t)level << 32 | j};
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
/* Summary (level, j): total estimated height of its children. Level 0 reads its paragraphs' text
 * cells and the width and estimates inline (fused: no node per estimate); higher levels sum
 * child summaries. A summary needs every child, so all missing ones are asked for at once. */
static int sum_op(void *ctx, const dv_job *job, ex_out *out) {
    deriver *d = ctx;
    atomic_fetch_add(&d->runs[1], 1);
    int level = (int)(job->key >> 32); uint64_t j = job->key & 0xffffffffu;
    uint64_t below = level == 0 ? (uint64_t)d->paragraphs : count_at(d, level - 1), total = 0, width = 0;
    dv_ref rw = {DV_CELL, d->width, 0};
    int missing = level == 0 && !ex_read(job, out, rw, &width, NULL), estimated = 0;
    for (uint64_t c = j * FAN; c < below && c < j * FAN + FAN; c++) {
        dv_ref r = child_ref(d, level - 1, c); uint64_t v;
        if (!ex_read(job, out, r, &v, NULL)) { missing = 1; continue; }
        if (level > 0) { total += v; continue; }
        const void *p = NULL; size_t n = 0;
        if (bl_is(v)) bl_get(&d->blobs, v, &p, &n);
        total += estimate(n / 2, width); estimated++;
    }
    if (missing) {
        if (level == 0 && !ex_read(job, out, rw, NULL, NULL)) ex_need(out, rw);
        for (uint64_t c = j * FAN; c < below && c < j * FAN + FAN; c++) {
            dv_ref r = child_ref(d, level - 1, c);
            if (!ex_read(job, out, r, NULL, NULL)) ex_need(out, r);
        }
        return 0;
    }
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
    for (uint64_t i = anchor; i < (uint64_t)d->paragraphs && y < (int64_t)height; i++) {
        dv_ref r = {DV_NODE, d->op, i}; uint64_t h; const unsigned char *bytes = NULL; size_t n = 0;
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

/* Jump: the paragraph at a fraction of the estimated document height, through the estimate
 * summaries (all clean: the root is demanded). */
typedef struct { deriver *d; int64_t permille, anchor, total; } jump_call;
static void demand_root(void *arg, tr_world *w, dv_graph *g) {
    (void)w; jump_call *j = arg; uint32_t root;
    dv_ref r = child_ref(j->d, j->d->levels - 1, 0);
    if (dv_intern(g, r.id, r.key, &root) == TR_OK && !g->nodes[root].demand) dv_demand(g, root);
}
static int64_t node_value(deriver *d, dv_graph *g, dv_ref r) {
    uint32_t n; uint64_t v;
    if (dv_intern(g, r.id, r.key, &n) || dv_get(g, n, &v)) return -1;
    (void)d; return (int64_t)v;
}
static void find_anchor(void *arg, tr_world *w, dv_graph *g) {
    jump_call *j = arg; deriver *d = j->d;
    int64_t total = node_value(d, g, child_ref(d, d->levels - 1, 0));
    j->total = total; j->anchor = -1;
    if (total < 0) return;
    int64_t target = total * j->permille / 1000, y = 0;
    uint64_t node = 0;
    for (int level = d->levels - 1; level >= 0; level--) {   /* descend: child (level-1) of node */
        uint64_t below = level == 0 ? (uint64_t)d->paragraphs : count_at(d, level - 1), c = node * FAN;
        for (; c < below && c < node * FAN + FAN; c++) {
            int64_t h;
            if (level > 0) h = node_value(d, g, child_ref(d, level - 1, c));
            else {                                       /* paragraphs: the same estimate, from the cells */
                tr_read t, wd; const void *p = NULL; size_t n = 0;
                if (tr_current(w, d->text[c], &t) || tr_current(w, d->width, &wd)) return;
                if (bl_is(t.value)) bl_get(&d->blobs, t.value, &p, &n);
                h = (int64_t)estimate(n / 2, wd.value);
            }
            if (h < 0) return;
            if (y + h > target) break;
            y += h;
        }
        if (c >= below || c >= node * FAN + FAN) c--;    /* past the end: the last child */
        node = c;
    }
    j->anchor = (int64_t)node;
}

/* ---- owner calls ---- */
static void write_cell(deriver *d, tr_id cell, uint64_t value) {
    tr_transaction t; tr_read r[4]; tr_write w[1]; tr_receipt rc;
    tr_read old;
    tr_current(&d->world, cell, &old);
    if (tr_begin(&t, &d->world, d->driver, r, 4, w, 1) || tr_write_value(&t, cell, value) || tr_commit(&t, &rc)) return;
    if (bl_is(old.value)) bl_release(&d->blobs, old.value);   /* the cell held it */
}
typedef struct { deriver *d; int i; const jchar *units; jsize length; int64_t result; } call;
static void set_text(void *arg, tr_world *w, dv_graph *g) {
    (void)w; (void)g;
    call *c = arg; uint64_t h;
    c->result = bl_intern(&c->d->blobs, (void *)c->units, (size_t)c->length * 2, 0, &h);
    if (!c->result) write_cell(c->d, c->d->text[c->i], h);
}
static void set_width(void *arg, tr_world *w, dv_graph *g) { (void)w; (void)g; call *c = arg; write_cell(c->d, c->d->width, (uint64_t)c->i); }
static void set_viewport(void *arg, tr_world *w, dv_graph *g) {     /* i = anchor, length = offset | height << 16 */
    (void)w; (void)g; call *c = arg;
    write_cell(c->d, c->d->viewport, (uint64_t)(uint32_t)c->i | ((uint64_t)(uint32_t)c->length & 0xffff) << 32
                                     | ((uint64_t)(uint32_t)c->length >> 16) << 48);
}
static void demand_scene(void *arg, tr_world *w, dv_graph *g) {
    (void)w; call *c = arg; c->result = c->length ? dv_demand(g, c->d->scene) : dv_release(g, c->d->scene);
}
static void demand(void *arg, tr_world *w, dv_graph *g) {
    (void)w; call *c = arg;
    c->result = c->length ? dv_demand(g, c->d->node[c->i]) : dv_release(g, c->d->node[c->i]);
}
static void value(void *arg, tr_world *w, dv_graph *g) {
    (void)w; call *c = arg; uint64_t v;
    c->result = dv_get(g, c->d->node[c->i], &v) == TR_OK ? (int64_t)(v & ~BL_TAG) : -1;
}
typedef struct { deriver *d; int i; void *bytes; size_t length; int ok; } copy;
static void bytes(void *arg, tr_world *w, dv_graph *g) {
    (void)w; copy *c = arg; uint64_t v; const void *p; size_t n;
    uint32_t node = c->i < 0 ? c->d->scene : c->d->node[c->i];      /* -1: the scene */
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
    if (d->gpu) ps_close(d->gpu);
    free(d->atlas_cells); free(d->atlas_index);
    free(d->cells); free(d->text); free(d->nodes); free(d->blocks); free(d->table); free(d->path); free(d->node);
    free(d->entries); free(d->index); free(d);
}

JNIEXPORT jlong JNICALL CLASS(deriveOpen)(JNIEnv *env, jclass c, jint workers, jint paragraphs) {
    (void)c;
    if (paragraphs <= 0 || paragraphs > 100000 || workers <= 0 || workers > EX_WORKERS) return -1;
    deriver *d = calloc(1, sizeof *d);
    if (!d) return -1;
    size_t n = (size_t)paragraphs, cellc = n + 8, nodec = n + n / (FAN - 1) + 16 + 4096, blobc = 3 * n + 64 + 4096;   /* + masks */
    d->paragraphs = paragraphs;
    d->cells = calloc(cellc, sizeof *d->cells); d->text = calloc(n, sizeof *d->text);
    d->nodes = calloc(nodec, sizeof *d->nodes); d->blocks = calloc(nodec, sizeof *d->blocks);
    d->table = calloc(pow2(2 * nodec), sizeof *d->table); d->path = calloc(nodec, sizeof *d->path);
    d->node = calloc(n, sizeof *d->node);
    d->entries = calloc(blobc, sizeof *d->entries); d->index = calloc(pow2(2 * blobc), sizeof *d->index);
    jclass cls = (*env)->FindClass(env, "com/trough/hot/DeriveLayout");
    if (!d->cells || !d->text || !d->nodes || !d->blocks || !d->table || !d->path || !d->node || !d->entries || !d->index || !cls) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        destroy(d); return -2;
    }
    (*env)->GetJavaVM(env, &d->vm);
    d->cls = (*env)->NewGlobalRef(env, cls);
    d->layout = (*env)->GetStaticMethodID(env, cls, "layout", "(Ljava/lang/String;I)[B");
    d->mask = d->layout ? (*env)->GetStaticMethodID(env, cls, "mask", "(II)[B") : NULL;
    if (!d->layout || !d->mask) { (*env)->ExceptionClear(env); (*env)->DeleteGlobalRef(env, d->cls); destroy(d); return -3; }
    tr_world_init(&d->world, d->cells, cellc);
    tr_create(&d->world, 1, &d->driver); tr_create(&d->world, 1, &d->op); tr_create(&d->world, 0, &d->width);
    tr_create(&d->world, 1, &d->op_sum); tr_create(&d->world, 1, &d->op_scene); tr_create(&d->world, 0, &d->viewport);
    tr_create(&d->world, 1, &d->op_mask);
    d->levels = 1;
    while (count_at(d, d->levels - 1) > 1) d->levels++;  /* the top level has one node: the root */
    for (size_t i = 0; i < n; i++) tr_create(&d->world, 0, &d->text[i]);
    bl_init(&d->blobs, d->entries, blobc, d->index, pow2(2 * blobc), 1ULL << 30);
    dv_init(&d->graph, &d->world, d->nodes, nodec, d->blocks, nodec, d->table, pow2(2 * nodec), d->path);
    for (size_t i = 0; i < n; i++) dv_intern(&d->graph, d->op, i, &d->node[i]);
    dv_intern(&d->graph, d->op_scene, 0, &d->scene);
    ex_init(&d->ex, &d->world, &d->graph, (size_t)workers);
    ex_use_blobs(&d->ex, &d->blobs);
    ex_on_worker(&d->ex, attach, detach, d);
    dv_set_static(&d->graph, d->op);                  /* layout reads text and width; a summary its children */
    dv_set_static(&d->graph, d->op_sum);
    ex_register(&d->ex, d->op, layout_op, d);
    ex_register(&d->ex, d->op_sum, sum_op, d);
    ex_register(&d->ex, d->op_scene, scene_op, d);
    ex_register(&d->ex, d->op_mask, mask_op, d);

    if (ex_start(&d->ex)) { (*env)->DeleteGlobalRef(env, d->cls); destroy(d); return -4; }
    return (jlong)(intptr_t)d;
}
JNIEXPORT jint JNICALL CLASS(deriveText)(JNIEnv *env, jclass c, jlong h, jint i, jstring s) {
    (void)c; deriver *d = (deriver *)(intptr_t)h;
    if (i < 0 || i >= d->paragraphs) return -1;
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
    call k = {d, anchor, NULL, (jsize)((offset & 0xffff) | (height & 0xffff) << 16), 0}; ex_call(&d->ex, set_viewport, &k);
}
/* Anchor at a fraction (per mille) of the estimated height; returns the paragraph, or -1. */
JNIEXPORT jint JNICALL CLASS(deriveJump)(JNIEnv *env, jclass c, jlong h, jint permille) {
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h;
    jump_call j = {d, permille, -1, -1};
    ex_call(&d->ex, demand_root, &j);
    if (!ex_settle(&d->ex)) return -1;
    ex_call(&d->ex, find_anchor, &j);
    return (jint)j.anchor;
}
JNIEXPORT jint JNICALL CLASS(deriveDemandScene)(JNIEnv *env, jclass c, jlong h, jboolean on) {
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h; call k = {d, 0, NULL, on ? 1 : 0, 0}; ex_call(&d->ex, demand_scene, &k); return (jint)k.result;
}
JNIEXPORT jint JNICALL CLASS(deriveDemand)(JNIEnv *env, jclass c, jlong h, jint i, jboolean on) {
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h;
    if (i < 0 || i >= d->paragraphs) return -1;
    call k = {d, i, NULL, on ? 1 : 0, 0}; ex_call(&d->ex, demand, &k); return (jint)k.result;
}
JNIEXPORT jlong JNICALL CLASS(deriveSettle)(JNIEnv *env, jclass c, jlong h) {
    (void)env; (void)c; return (jlong)ex_settle(&((deriver *)(intptr_t)h)->ex);
}
JNIEXPORT jlong JNICALL CLASS(deriveValue)(JNIEnv *env, jclass c, jlong h, jint i) {
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h;
    if (i < 0 || i >= d->paragraphs) return -1;
    call k = {d, i, NULL, 0, -1}; ex_call(&d->ex, value, &k); return k.result;
}
JNIEXPORT jbyteArray JNICALL CLASS(deriveBytes)(JNIEnv *env, jclass c, jlong h, jint i) {
    (void)c; deriver *d = (deriver *)(intptr_t)h;
    if (i < -1 || i >= d->paragraphs) return NULL;
    copy k = {d, i, NULL, 0, 0};
    ex_call(&d->ex, bytes, &k);
    jbyteArray r = NULL;
    if (k.ok && (r = (*env)->NewByteArray(env, (jsize)k.length))) (*env)->SetByteArrayRegion(env, r, 0, (jsize)k.length, k.bytes);
    free(k.bytes);
    return r;
}
JNIEXPORT jlongArray JNICALL CLASS(deriveStats)(JNIEnv *env, jclass c, jlong h) {
    (void)c; deriver *d = (deriver *)(intptr_t)h;
    int64_t s[20] = {0};
    call k = {d, 0, (const jchar *)(void *)s, 0, 0};
    ex_call(&d->ex, stats, &k);
    jlongArray r = (*env)->NewLongArray(env, 20);
    if (r) (*env)->SetLongArrayRegion(env, r, 0, 20, (const jlong *)s);
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

JNIEXPORT jint JNICALL CLASS(deriveFrameOpen)(JNIEnv *env, jclass c, jlong h, jint w, jint hh, jint aw, jint ah) {
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h;
    char info[256];
    size_t cells = (size_t)(aw / 16) * (ah / 16), index = 1; while (index < 2 * cells) index <<= 1;
    if ((size_t)(aw / 256) * (ah / 256) > 64) return -1;
    d->atlas_cells = calloc(cells, sizeof *d->atlas_cells); d->atlas_index = calloc(index, sizeof *d->atlas_index);
    if (!d->atlas_cells || !d->atlas_index) return -2;
    if (at_init(&d->atlas, (uint32_t)aw, (uint32_t)ah, 256, d->atlas_cells, cells, d->atlas_index, index, d->atlas_pages)) return -3;
    int st = ps_open(&d->gpu, info, sizeof info);
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
    if (mc.result || !ex_settle(&d->ex)) goto done;
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
    if (ps_frame_submit(d->gpu, ups, nup, staging, staged, NULL, 0, glyphs, nglyph, &number)) goto done;
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
    if (timing) (*env)->SetDoubleArrayRegion(env, timing, 0, 5, ms);
    for (uint32_t k = 0; bytes && k < count; k++) free(bytes[k]);
    free(sc.bytes); free(nodes); free(handles); free(bytes); free(lengths); free(glyphs); free(ups); free(staging);
    return result;
}
