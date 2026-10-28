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
JNIEXPORT jlongArray JNICALL CLASS(stats)(JNIEnv *env, jclass c, jlong k) {
    (void)c;
    tk_stats s;
    tk_stats_get((tk_owner *)(intptr_t)k, &s);
    jlong v[4] = {(jlong)s.saves, (jlong)s.failures, (jlong)s.rebases, (jlong)s.conflicts};
    jlongArray out = (*env)->NewLongArray(env, 4);
    if (out) (*env)->SetLongArrayRegion(env, out, 0, 4, v);
    return out;
}
