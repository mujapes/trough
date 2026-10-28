/* In-process binding of the storage owner for the Android app (docs/BIONIC.md). Built only by
 * native/bionic.sh against bionic. Handles are opaque jlongs; every call takes a batch, never a
 * per-character callback. Text crosses as Java's UTF-16, unconverted. */
#include "keeper.h"
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#define CLASS(name) Java_com_trough_hot_NativeCore_##name

static ts_status make(ts_store *s, void *arg) {
    td_text_value v = {NULL, 0, 0, -1, -1};
    td_snapshot snap;
    ts_status st = td_save_text(s, (ts_read){0, 0}, &v, &snap);
    *(uint64_t *)arg = snap.record.id;
    return st;
}
typedef struct { uint64_t id, revision; jchar *units; size_t length; } loaded;
static ts_status load(ts_store *s, void *arg) {
    loaded *l = arg;
    td_snapshot snap;
    ts_status st = td_load(s, l->id, l->revision, &snap, NULL, 0);
    if (st != TS_CAPACITY && st != TS_OK) return st;
    l->units = malloc((snap.length ? snap.length : 1) * sizeof *l->units);
    if (!l->units) return TS_IO;
    l->length = snap.length;
    return td_load(s, l->id, l->revision, &snap, l->units, snap.length);
}

/* All calls return a negative ts_status on failure. */
JNIEXPORT jlong JNICALL CLASS(start)(JNIEnv *env, jclass c, jstring dir) {
    (void)c;
    const char *d = (*env)->GetStringUTFChars(env, dir, NULL);
    if (!d) return -TS_IO;
    /* The keeper keeps the path only while starting; it is copied by ts_open. */
    tk_owner *k;
    ts_status st = tk_start(d, &k);
    (*env)->ReleaseStringUTFChars(env, dir, d);
    return st ? -(jlong)st : (jlong)(intptr_t)k;
}
JNIEXPORT void JNICALL CLASS(stop)(JNIEnv *env, jclass c, jlong k) {
    (void)env; (void)c;
    tk_stop((tk_owner *)(intptr_t)k);
}
JNIEXPORT jlong JNICALL CLASS(create)(JNIEnv *env, jclass c, jlong k) {
    (void)env; (void)c;
    uint64_t id = 0;
    ts_status st = tk_run((tk_owner *)(intptr_t)k, make, &id);
    return st ? -(jlong)st : (jlong)id;
}
JNIEXPORT jlong JNICALL CLASS(attach)(JNIEnv *env, jclass c, jlong k, jlong document) {
    (void)env; (void)c;
    ti_queue *q;
    ts_status st = tk_attach((tk_owner *)(intptr_t)k, (uint64_t)document, 0, &q);
    return st ? -(jlong)st : (jlong)(intptr_t)q;
}
JNIEXPORT jlong JNICALL CLASS(detach)(JNIEnv *env, jclass c, jlong k, jlong q) {
    (void)env; (void)c;
    return -(jlong)tk_detach((tk_owner *)(intptr_t)k, (ti_queue *)(intptr_t)q);
}
/* One edit batch from the input adapter: replace [start, end) with text, then the final
 * selection/composition. Returns the new local revision. Pokes the keeper; never waits on it. */
JNIEXPORT jlong JNICALL CLASS(edit)(JNIEnv *env, jclass c, jlong k, jlong q, jint start, jint end,
                                     jstring text, jint anchor, jint caret, jint cs, jint ce) {
    (void)c;
    if (start < 0 || end < start || anchor < 0 || caret < 0) return -TS_INVALID;
    jsize n = text ? (*env)->GetStringLength(env, text) : 0;
    const jchar *units = n ? (*env)->GetStringCritical(env, text, NULL) : NULL;
    if (n && !units) return -TS_IO;
    td_edit e = {(size_t)start, (size_t)end, units, (size_t)n};
    uint64_t local = 0;
    ts_status st = ti_edit((ti_queue *)(intptr_t)q, &e, 1, (uint32_t)anchor, (uint32_t)caret, cs, ce, &local);
    if (units) (*env)->ReleaseStringCritical(env, text, units);
    if (!st) tk_poke((tk_owner *)(intptr_t)k);
    return st ? -(jlong)st : (jlong)local;
}
/* Blocks until local is durable; returns the document revision an Ask must capture. */
JNIEXPORT jlong JNICALL CLASS(flush)(JNIEnv *env, jclass c, jlong k, jlong q, jlong local, jint timeout_ms) {
    (void)env; (void)c;
    ts_read durable;
    ts_status st = tk_flush((tk_owner *)(intptr_t)k, (ti_queue *)(intptr_t)q, (uint64_t)local,
                            (unsigned)timeout_ms, &durable);
    return st ? -(jlong)st : (jlong)durable.revision;
}
/* The live draft as a Java string (the input adapter's synchronous text queries). */
JNIEXPORT jstring JNICALL CLASS(live)(JNIEnv *env, jclass c, jlong q) {
    (void)c;
    ti_queue *queue = (ti_queue *)(intptr_t)q;
    ti_state st;
    ti_state_get(queue, &st);
    jchar *units = malloc((st.length ? st.length : 1) * sizeof *units);
    if (!units) return NULL;
    td_value v;
    ts_status s = ti_view(queue, units, st.length, &v, NULL);
    /* Raced with an edit that grew the text: retry once at the new size. */
    if (s == TS_CAPACITY) {
        free(units);
        if (!(units = malloc((v.length ? v.length : 1) * sizeof *units))) return NULL;
        s = ti_view(queue, units, v.length, &v, NULL);
    }
    jstring out = s ? NULL : (*env)->NewString(env, units, (jsize)v.length);
    free(units);
    return out;
}
/* A durable revision's text, loaded by the keeper (verification and history). */
JNIEXPORT jstring JNICALL CLASS(durable)(JNIEnv *env, jclass c, jlong k, jlong document, jlong revision) {
    (void)c;
    loaded l = {(uint64_t)document, (uint64_t)revision, NULL, 0};
    ts_status st = tk_run((tk_owner *)(intptr_t)k, load, &l);
    jstring out = st ? NULL : (*env)->NewString(env, l.units, (jsize)l.length);
    free(l.units);
    return out;
}
/* Queue diagnostics: {local, saved, durable, contended}. */
JNIEXPORT jlongArray JNICALL CLASS(queueState)(JNIEnv *env, jclass c, jlong q) {
    (void)c;
    ti_state s;
    ti_state_get((ti_queue *)(intptr_t)q, &s);
    jlong v[4] = {(jlong)s.local, (jlong)s.saved, (jlong)s.durable, (jlong)s.contended};
    jlongArray out = (*env)->NewLongArray(env, 4);
    if (out) (*env)->SetLongArrayRegion(env, out, 0, 4, v);
    return out;
}
JNIEXPORT jlongArray JNICALL CLASS(stats)(JNIEnv *env, jclass c, jlong k) {
    (void)c;
    tk_stats s;
    tk_stats_get((tk_owner *)(intptr_t)k, &s);
    jlong v[5] = {(jlong)s.saves, (jlong)s.failures, (jlong)s.rebases, (jlong)s.conflicts, (jlong)s.poke_contended};
    jlongArray out = (*env)->NewLongArray(env, 5);
    if (out) (*env)->SetLongArrayRegion(env, out, 0, 5, v);
    return out;
}

/* Input adapter support. value: {length, anchor, caret, composing start, composing end, local,
 * external (count of merged external revisions)}. */
JNIEXPORT jlongArray JNICALL CLASS(value)(JNIEnv *env, jclass c, jlong q) {
    (void)c;
    td_text_value v;
    ti_state st;
    if (ti_snapshot((ti_queue *)(intptr_t)q, &v, &st)) return NULL;
    jlong out[7] = {(jlong)tx_length(v.text), v.anchor, v.caret, v.composing_start, v.composing_end,
                    (jlong)st.local, (jlong)st.external};
    tx_release(v.text);
    jlongArray a = (*env)->NewLongArray(env, 7);
    if (a) (*env)->SetLongArrayRegion(env, a, 0, 7, out);
    return a;
}
/* [start, end) of the live text, clamped; from an O(1) snapshot, so no full copy. */
JNIEXPORT jstring JNICALL CLASS(slice)(JNIEnv *env, jclass c, jlong q, jint start, jint end) {
    (void)c;
    td_text_value v;
    if (ti_snapshot((ti_queue *)(intptr_t)q, &v, NULL)) return NULL;
    size_t n = tx_length(v.text), a = start < 0 ? 0 : (size_t)start, b = end < 0 ? 0 : (size_t)end;
    if (a > n) a = n;
    if (b > n) b = n;
    if (b < a) b = a;
    jchar *units = malloc((b - a ? b - a : 1) * sizeof *units);
    jstring out = NULL;
    if (units && !tx_read(v.text, a, b - a, units)) out = (*env)->NewString(env, units, (jsize)(b - a));
    free(units);
    tx_release(v.text);
    return out;
}
/* One IME operation as one atomic batch: ranges[2i], ranges[2i+1] replaced by texts[i], each in
 * the coordinates left by the previous edit, then the final selection and composition. */
JNIEXPORT jlong JNICALL CLASS(batch)(JNIEnv *env, jclass c, jlong k, jlong q, jintArray ranges,
                                      jobjectArray texts, jint anchor, jint caret, jint cs, jint ce) {
    (void)c;
    jsize count = texts ? (*env)->GetArrayLength(env, texts) : 0;
    if (count > TD_EDIT_LIMIT || (count && (!ranges || (*env)->GetArrayLength(env, ranges) != 2 * count)) ||
        anchor < 0 || caret < 0) return -TS_INVALID;
    td_edit edits[TD_EDIT_LIMIT];
    jstring strings[TD_EDIT_LIMIT];
    const jchar *units[TD_EDIT_LIMIT];
    jint r[2 * TD_EDIT_LIMIT];
    if (count) (*env)->GetIntArrayRegion(env, ranges, 0, 2 * count, r);
    ts_status st = TS_OK;
    jsize got = 0;
    for (; got < count; got++) {
        strings[got] = (jstring)(*env)->GetObjectArrayElement(env, texts, got);
        jsize n = strings[got] ? (*env)->GetStringLength(env, strings[got]) : 0;
        units[got] = n ? (*env)->GetStringChars(env, strings[got], NULL) : NULL;
        if ((n && !units[got]) || r[2 * got] < 0 || r[2 * got + 1] < r[2 * got]) { st = TS_INVALID; got++; break; }
        edits[got] = (td_edit){(size_t)r[2 * got], (size_t)r[2 * got + 1], units[got], (size_t)n};
    }
    uint64_t local = 0;
    if (!st) st = ti_edit((ti_queue *)(intptr_t)q, edits, (size_t)count, (uint32_t)anchor, (uint32_t)caret, cs, ce, &local);
    for (jsize i = 0; i < got; i++) {
        if (units[i]) (*env)->ReleaseStringChars(env, strings[i], units[i]);
        if (strings[i]) (*env)->DeleteLocalRef(env, strings[i]);
    }
    if (!st) tk_poke((tk_owner *)(intptr_t)k);
    return st ? -(jlong)st : (jlong)local;
}

JNIEXPORT jint JNICALL CLASS(notifyFd)(JNIEnv *env, jclass c, jlong k) {
    (void)env; (void)c;
    return tk_notify_fd((tk_owner *)(intptr_t)k);
}
/* An edit made by someone other than the draft's interaction owner (an agent): a new durable
 * revision of the document, which the keeper then merges into the attached draft. */
typedef struct { uint64_t id; size_t start, end; const jchar *units; size_t length; } external;
static ts_status apply_external(ts_store *s, void *arg) {
    external *x = arg;
    td_snapshot cur;
    tx_text *t, *n;
    ts_status st = td_load_text(s, x->id, 0, &cur, &t);
    if (st) return st;
    if (!(st = tx_edit(t, x->start, x->end, x->units, x->length, &n))) {
        td_text_value v = {n, cur.anchor, cur.caret, -1, -1};
        size_t len = tx_length(n);
        if (v.anchor > len) v.anchor = (uint32_t)len;
        if (v.caret > len) v.caret = (uint32_t)len;
        td_snapshot r;
        st = td_save_text(s, (ts_read){x->id, cur.record.revision}, &v, &r);
        tx_release(n);
    }
    tx_release(t);
    return st;
}
JNIEXPORT jlong JNICALL CLASS(externalEdit)(JNIEnv *env, jclass c, jlong k, jlong document, jint start, jint end, jstring text) {
    (void)c;
    if (start < 0 || end < start) return -TS_INVALID;
    jsize n = text ? (*env)->GetStringLength(env, text) : 0;
    const jchar *units = n ? (*env)->GetStringChars(env, text, NULL) : NULL;
    if (n && !units) return -TS_IO;
    external x = {(uint64_t)document, (size_t)start, (size_t)end, units, (size_t)n};
    ts_status st = tk_run((tk_owner *)(intptr_t)k, apply_external, &x);
    if (units) (*env)->ReleaseStringChars(env, text, units);
    return -(jlong)st;
}

/* The rectangle renderer's pixel oracle (native/present/oracle.c) on the device GPU. */
int ps_oracle(char *out, size_t capacity);
JNIEXPORT jstring JNICALL CLASS(sceneOracle)(JNIEnv *env, jclass c) {
    (void)c;
    static char out[4096];
    ps_oracle(out, sizeof out);
    return (*env)->NewStringUTF(env, out);
}

int ps_glyph_oracle(char *out, size_t capacity);
JNIEXPORT jstring JNICALL CLASS(glyphOracle)(JNIEnv *env, jclass c) {
    (void)c;
    static char out[4096];
    ps_glyph_oracle(out, sizeof out);
    return (*env)->NewStringUTF(env, out);
}
/* Composes glyph instances (16 floats each, ps_glyph layout) from an R8 atlas on the GPU and
 * returns the RGBA result, or null. timing[0] receives the GPU time in ms. */
#include "scene.h"
JNIEXPORT jbyteArray JNICALL CLASS(textRender)(JNIEnv *env, jclass c, jint w, jint h, jfloatArray glyphs,
                                                jbyteArray atlas, jint aw, jint ah, jdoubleArray timing) {
    (void)c;
    if (w <= 0 || h <= 0 || aw <= 0 || ah <= 0) return NULL;
    jsize nf = (*env)->GetArrayLength(env, glyphs);
    if (nf % 16 || (*env)->GetArrayLength(env, atlas) != aw * ah) return NULL;
    ps_gpu *g;
    if (ps_open(&g, NULL, 0)) return NULL;
    jfloat *f = (*env)->GetFloatArrayElements(env, glyphs, NULL);
    jbyte *a = (*env)->GetByteArrayElements(env, atlas, NULL);
    uint8_t *rgba = malloc((size_t)w * h * 4);
    double ms = 0;
    int st = !f || !a || !rgba ? -1 : ps_atlas(g, (const uint8_t *)a, (uint32_t)aw, (uint32_t)ah);
    if (!st) st = ps_render_text(g, NULL, 0, (const ps_glyph *)f, (size_t)nf / 16, (uint32_t)w, (uint32_t)h, rgba, &ms);
    if (f) (*env)->ReleaseFloatArrayElements(env, glyphs, f, JNI_ABORT);
    if (a) (*env)->ReleaseByteArrayElements(env, atlas, a, JNI_ABORT);
    ps_close(g);
    jbyteArray out = NULL;
    if (!st && (out = (*env)->NewByteArray(env, w * h * 4))) (*env)->SetByteArrayRegion(env, out, 0, w * h * 4, (const jbyte *)rgba);
    free(rgba);
    if (timing && (*env)->GetArrayLength(env, timing) > 0) (*env)->SetDoubleArrayRegion(env, timing, 0, 1, &ms);
    return out;
}
