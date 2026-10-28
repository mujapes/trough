/* Text layout as a derivation operator, in process (docs/LAYOUT_DERIVE.md). A world of
 * paragraph text cells (UTF-16 blobs) and one width cell; node (layout, i) is paragraph i laid
 * out at the width. The operator runs on executor workers attached to the JVM and calls the
 * hot-loaded DeriveLayout.layout(String, int), whose serialized lines and glyphs become a
 * canonical blob. Java drives it through owner calls; no Java code sees jobs or the graph. */
#define _POSIX_C_SOURCE 200809L
#include "exec.h"
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#define CLASS(name) Java_com_trough_hot_NativeCore_##name

typedef struct {
    JavaVM *vm; jclass cls; jmethodID layout;
    tr_world world; tr_cell *cells; tr_id driver, width, op; tr_id *text; int paragraphs;
    dv_graph graph; dv_node *nodes; dv_block *blocks; uint32_t *table; dv_frame *path; uint32_t *node;
    bl_store blobs; bl_entry *entries; uint32_t *index;
    ex_exec ex;
} deriver;

static size_t pow2(size_t n) { size_t p = 1; while (p < n) p <<= 1; return p; }

static void attach(void *ctx) { deriver *d = ctx; JNIEnv *env; (*d->vm)->AttachCurrentThread(d->vm, &env, NULL); }
static void detach(void *ctx) { deriver *d = ctx; (*d->vm)->DetachCurrentThread(d->vm); }

/* Worker thread: text and width from the job, layout through Java, bytes out. */
static int layout_op(void *ctx, const dv_job *job, ex_out *out) {
    deriver *d = ctx;
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
    if (dv_get(g, c->d->node[c->i], &v) || !bl_is(v) || bl_get(&c->d->blobs, v, &p, &n)) return;
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
    size_t n = (size_t)paragraphs, cellc = n + 8, nodec = n + 8, blobc = 3 * n + 16;
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
    for (size_t i = 0; i < n; i++) tr_create(&d->world, 0, &d->text[i]);
    bl_init(&d->blobs, d->entries, blobc, d->index, pow2(2 * blobc), 1ULL << 30);
    dv_init(&d->graph, &d->world, d->nodes, nodec, d->blocks, nodec, d->table, pow2(2 * nodec), d->path);
    for (size_t i = 0; i < n; i++) dv_intern(&d->graph, d->op, i, &d->node[i]);
    ex_init(&d->ex, &d->world, &d->graph, (size_t)workers);
    ex_use_blobs(&d->ex, &d->blobs);
    ex_on_worker(&d->ex, attach, detach, d);
    ex_register(&d->ex, d->op, layout_op, d);
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
    if (i < 0 || i >= d->paragraphs) return NULL;
    copy k = {d, i, NULL, 0, 0};
    ex_call(&d->ex, bytes, &k);
    jbyteArray r = NULL;
    if (k.ok && (r = (*env)->NewByteArray(env, (jsize)k.length))) (*env)->SetByteArrayRegion(env, r, 0, (jsize)k.length, k.bytes);
    free(k.bytes);
    return r;
}
JNIEXPORT jlongArray JNICALL CLASS(deriveStats)(JNIEnv *env, jclass c, jlong h) {
    (void)c; deriver *d = (deriver *)(intptr_t)h;
    int64_t s[11] = {0};
    call k = {d, 0, (const jchar *)(void *)s, 0, 0};
    ex_call(&d->ex, stats, &k);
    jlongArray r = (*env)->NewLongArray(env, 11);
    if (r) (*env)->SetLongArrayRegion(env, r, 0, 11, (const jlong *)s);
    return r;
}
JNIEXPORT void JNICALL CLASS(deriveClose)(JNIEnv *env, jclass c, jlong h) {
    (void)c; deriver *d = (deriver *)(intptr_t)h;
    ex_stop(&d->ex);
    (*env)->DeleteGlobalRef(env, d->cls);
    destroy(d);
}
