/* JNI for native shaping (native/text/shape.c, docs/NATIVE_TEXT.md); android.shape compares
 * it with TextRunShaper. */
#include "shape.h"
#include <jni.h>
#include <stdlib.h>

#define CLASS(name) Java_com_trough_hot_NativeCore_##name

JNIEXPORT jint JNICALL CLASS(shapeInit)(JNIEnv *env, jclass c, jstring locales) {
    (void)c;
    const char *l = locales ? (*env)->GetStringUTFChars(env, locales, NULL) : NULL;
    int r = sh_init(l);
    if (l) (*env)->ReleaseStringUTFChars(env, locales, l);
    return r;
}

/* [advance, count, then face, glyph, x, y per glyph], or null on failure. */
JNIEXPORT jfloatArray JNICALL CLASS(shapeRun)(JNIEnv *env, jclass c, jcharArray text, jint start, jint end, jboolean rtl, jfloat size) {
    (void)c;
    jsize n = (*env)->GetArrayLength(env, text);
    jchar *t = (*env)->GetCharArrayElements(env, text, NULL);
    if (!t) return NULL;
    sh_run run = {0};
    run.capacity = (size_t)(end > start ? end - start : 1) * 2 + 8;
    run.glyphs = malloc(run.capacity * sizeof *run.glyphs);
    int rc = run.glyphs ? sh_shape(t, (size_t)n, (size_t)start, (size_t)end, rtl, size, &run) : -1;
    if (rc == 0 && run.count > run.capacity) {
        free(run.glyphs);
        run.capacity = run.count; run.glyphs = malloc(run.capacity * sizeof *run.glyphs);
        rc = run.glyphs ? sh_shape(t, (size_t)n, (size_t)start, (size_t)end, rtl, size, &run) : -1;
    }
    (*env)->ReleaseCharArrayElements(env, text, t, JNI_ABORT);
    jfloatArray r = NULL;
    if (rc == 0 && run.count <= run.capacity) {
        jsize len = (jsize)(2 + 4 * run.count);
        float *v = malloc((size_t)len * sizeof *v);
        if (v) {
            v[0] = run.advance; v[1] = (float)run.count;
            for (size_t g = 0; g < run.count; g++) {
                v[2 + 4 * g] = (float)run.glyphs[g].face; v[3 + 4 * g] = (float)run.glyphs[g].glyph;
                v[4 + 4 * g] = run.glyphs[g].x; v[5 + 4 * g] = run.glyphs[g].y;
            }
            r = (*env)->NewFloatArray(env, len);
            if (r) (*env)->SetFloatArrayRegion(env, r, 0, len, v);
            free(v);
        }
    }
    free(run.glyphs);
    return r;
}

JNIEXPORT jstring JNICALL CLASS(shapeFace)(JNIEnv *env, jclass c, jint face) {
    (void)c;
    char name[512];
    if (face < 0 || sh_face_name((uint32_t)face, name, sizeof name) < 0) return NULL;
    return (*env)->NewStringUTF(env, name);
}
