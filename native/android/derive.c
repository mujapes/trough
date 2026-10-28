/* Text layout as a derivation operator, in process (docs/LAYOUT_DERIVE.md). A world of
 * paragraph text cells (UTF-16 blobs) and one width cell; node (layout, i) is paragraph i laid
 * out at the width. The operator runs on executor workers attached to the JVM and calls the
 * hot-loaded DeriveLayout.layout(String, int), whose serialized lines and glyphs become a
 * canonical blob. Java drives it through owner calls; no Java code sees jobs or the graph. */
#define _POSIX_C_SOURCE 200809L
#include "exec.h"
#include <jni.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

#define CLASS(name) Java_com_trough_hot_NativeCore_##name

typedef struct {
    JavaVM *vm; jclass cls; jmethodID layout;
    tr_world world; tr_cell *cells; tr_id driver, width, op, viewport, op_sum, op_scene; tr_id *text; int paragraphs;
    int levels; uint32_t scene;                       /* summary levels above paragraphs; the scene node */
    atomic_ullong runs[3];                            /* operator invocations: layout, summary, scene */
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
    if (level < 0) return (dv_ref){DV_NODE, d->op, j};
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
/* Summary (level, j): total height of its children (paragraphs at level 0). All missing children
 * are asked for at once: a summary needs every child. */
static int sum_op(void *ctx, const dv_job *job, ex_out *out) {
    deriver *d = ctx;
    atomic_fetch_add(&d->runs[1], 1);
    int level = (int)(job->key >> 32); uint64_t j = job->key & 0xffffffffu;
    uint64_t below = level == 0 ? (uint64_t)d->paragraphs : count_at(d, level - 1), total = 0;
    int missing = 0;
    for (uint64_t c = j * FAN; c < below && c < j * FAN + FAN; c++) {
        dv_ref r = child_ref(d, level - 1, c); uint64_t h;
        if (!height_of(d, job, out, r, level == 0, &h, NULL, NULL)) { missing = 1; continue; }
        total += h;
    }
    if (missing) {
        for (uint64_t c = j * FAN; c < below && c < j * FAN + FAN; c++) {
            dv_ref r = child_ref(d, level - 1, c);
            if (!ex_read(job, out, r, NULL, NULL)) ex_need(out, r);
        }
        return 0;
    }
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

/* Emits the glyphs of the lines of one layout that intersect [scroll, scroll + height). */
static void emit_paragraph(sink *s, const unsigned char *b, size_t n, uint64_t top, uint64_t scroll, uint64_t height) {
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
            if (y >= lt && y < lb) { visible = top + lb > scroll && top + lt < scroll + height; break; }
        }
        if (!visible) continue;
        put32(s, be32(q)); put32(s, be32(q + 4));
        putf(s, x); putf(s, (float)((double)top - (double)scroll + y));
        s->glyphs++;
    }
}
/* Depth-first over the summary tree, children in order, pruning what lies outside the viewport.
 * A subtree's total is read only when it starts above the viewport (to skip or enter it); one
 * that starts inside is entered directly, so nothing below the viewport is laid out except in
 * the subtree holding the scroll position. Returns 0 when a value is missing (asked for). */
static int visit(deriver *d, const dv_job *job, ex_out *out, int level, uint64_t j, uint64_t *y,
                 uint64_t scroll, uint64_t height, sink *s) {
    uint64_t below = level == 0 ? (uint64_t)d->paragraphs : count_at(d, level - 1);
    for (uint64_t c = j * FAN; c < below && c < j * FAN + FAN; c++) {
        if (*y >= scroll + height) return 1;          /* everything further is below the viewport */
        if (level > 0 && *y >= scroll) {              /* starts inside the viewport: descend, no total needed */
            if (!visit(d, job, out, level - 1, c, y, scroll, height, s)) return 0;
            continue;
        }
        dv_ref r = child_ref(d, level - 1, c); uint64_t h; const unsigned char *bytes = NULL; size_t n = 0;
        if (!height_of(d, job, out, r, level == 0, &h, &bytes, &n)) { ex_need(out, r); return 0; }
        if (*y + h > scroll) {
            if (level == 0) emit_paragraph(s, bytes, n, *y, scroll, height);
            else { uint64_t inner = *y; if (!visit(d, job, out, level - 1, c, &inner, scroll, height, s)) return 0; }
        }
        *y += h;
    }
    return 1;
}
/* Scene: the glyphs visible in the viewport cell's window, as (face, glyph, x, y) in viewport
 * coordinates, preceded by their count. It reads summaries in order only until the viewport
 * ends, so nothing below it is laid out or read (no document height: that is its own node). */
static int scene_op(void *ctx, const dv_job *job, ex_out *out) {
    deriver *d = ctx;
    atomic_fetch_add(&d->runs[2], 1);
    dv_ref rv = {DV_CELL, d->viewport, 0};
    uint64_t v;
    if (!ex_read(job, out, rv, &v, NULL)) { ex_need(out, rv); return 0; }
    uint64_t scroll = v & 0xffffffffu, height = v >> 32, y = 0;
    sink s = {0};
    put32(&s, 0);
    if (!visit(d, job, out, d->levels - 1, 0, &y, scroll, height, &s)) { free(s.b); return 0; }
    if (s.failed) { free(s.b); out->value = 0; return 0; }
    unsigned char c[4] = {s.glyphs >> 24, s.glyphs >> 16, s.glyphs >> 8, s.glyphs};
    memcpy(s.b, c, 4);
    out->blob = s.b; out->blob_length = s.n;
    return 0;
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
static void set_viewport(void *arg, tr_world *w, dv_graph *g) {
    (void)w; (void)g; call *c = arg; write_cell(c->d, c->d->viewport, (uint64_t)(uint32_t)c->i | (uint64_t)(uint32_t)c->length << 32);
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
    free(d->cells); free(d->text); free(d->nodes); free(d->blocks); free(d->table); free(d->path); free(d->node);
    free(d->entries); free(d->index); free(d);
}

JNIEXPORT jlong JNICALL CLASS(deriveOpen)(JNIEnv *env, jclass c, jint workers, jint paragraphs) {
    (void)c;
    if (paragraphs <= 0 || paragraphs > 100000 || workers <= 0 || workers > EX_WORKERS) return -1;
    deriver *d = calloc(1, sizeof *d);
    if (!d) return -1;
    size_t n = (size_t)paragraphs, cellc = n + 8, nodec = n + n / (FAN - 1) + 16, blobc = 3 * n + 64;
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
    if (!d->layout) { (*env)->ExceptionClear(env); (*env)->DeleteGlobalRef(env, d->cls); destroy(d); return -3; }
    tr_world_init(&d->world, d->cells, cellc);
    tr_create(&d->world, 1, &d->driver); tr_create(&d->world, 1, &d->op); tr_create(&d->world, 0, &d->width);
    tr_create(&d->world, 1, &d->op_sum); tr_create(&d->world, 1, &d->op_scene); tr_create(&d->world, 0, &d->viewport);
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
JNIEXPORT void JNICALL CLASS(deriveViewport)(JNIEnv *env, jclass c, jlong h, jint scroll, jint height) {
    (void)env; (void)c; deriver *d = (deriver *)(intptr_t)h; call k = {d, scroll, NULL, height, 0}; ex_call(&d->ex, set_viewport, &k);
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
    int64_t s[14] = {0};
    call k = {d, 0, (const jchar *)(void *)s, 0, 0};
    ex_call(&d->ex, stats, &k);
    jlongArray r = (*env)->NewLongArray(env, 14);
    if (r) (*env)->SetLongArrayRegion(env, r, 0, 14, (const jlong *)s);
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
