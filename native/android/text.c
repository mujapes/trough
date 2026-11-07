/* JNI for native shaping (native/text/shape.c, docs/NATIVE_TEXT.md); android.shape compares
 * it with TextRunShaper. */
#include "shape.h"
#include "measure.h"
#include "bidi.h"
#include "words.h"
#include "paragraph.h"
#include "visual.h"
#include "layout.h"
#include <jni.h>
#include <limits.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define CLASS(name) Java_com_trough_hot_NativeCore_##name

static int paragraph_cancel(void *context) {
    int *remaining = context;
    return *remaining >= 0 && (*remaining)-- == 0;
}

JNIEXPORT jlongArray JNICALL CLASS(fontResources)(JNIEnv *env, jclass cls) {
    (void)cls;
    jlong values[2] = {(jlong)sh_context_live(), (jlong)sh_mapping_live()};
    jlongArray result = (*env)->NewLongArray(env, 2);
    if (result) (*env)->SetLongArrayRegion(env, result, 0, 2, values);
    return result;
}

JNIEXPORT jfloatArray JNICALL CLASS(defaultMetrics)(JNIEnv *env, jclass cls,
                                                    jfloat size, jstring locale) {
    (void)cls;
    const char *l = locale ? (*env)->GetStringUTFChars(env, locale, NULL) : NULL;
    if (locale && !l) return NULL;
    sh_context *fonts = sh_context_create(l);
    sh_metrics m; jfloatArray result = NULL;
    if (fonts && !sh_default_metrics(fonts, size, &m)) {
        jfloat values[5] = {m.top, m.ascent, m.descent, m.bottom, m.leading};
        result = (*env)->NewFloatArray(env, 5);
        if (result) (*env)->SetFloatArrayRegion(env, result, 0, 5, values);
    }
    if (fonts) sh_context_retire(fonts);
    if (l) (*env)->ReleaseStringUTFChars(env, locale, l);
    return result;
}

static int layout_hit_query(const tl_result *layout, const uint16_t *text, size_t line,
                             float x, float *wire, size_t *at) {
    size_t offset;
    if (tl_hit(layout, text, line, x, &offset)) return -1;
    wire[(*at)++] = (float)line; wire[(*at)++] = x; wire[(*at)++] = (float)offset;
    return 0;
}

JNIEXPORT jobjectArray JNICALL CLASS(layoutParagraph)(JNIEnv *env, jclass cls,
        jcharArray text, jstring locale, jfloat size, jfloat width, jfloat left, jfloat top) {
    (void)cls;
    if (!text) return NULL;
    jsize n = (*env)->GetArrayLength(env, text);
    if (n > (1 << 18)) return NULL;
    jchar *t = (*env)->GetCharArrayElements(env, text, NULL);
    if (!t) return NULL;
    const char *l = locale ? (*env)->GetStringUTFChars(env, locale, NULL) : NULL;
    size_t capacity = (size_t)n+1;
    tl_work work = {.advances = calloc(capacity, sizeof(float)),
        .candidates = calloc(capacity, sizeof(br_candidate)), .scores = calloc(capacity, sizeof(br_scratch)),
        .breaks = calloc(capacity, sizeof(br_line)), .directions = calloc(capacity, sizeof(bd_run)),
        .levels = calloc(capacity, 1), .capacity = capacity};
    tl_result layout = {.lines = calloc(capacity, sizeof(tl_line)), .line_capacity = capacity,
        .runs = calloc(capacity, sizeof(tv_run)), .run_capacity = capacity,
        .advances = calloc(capacity, sizeof(float)), .advance_capacity = capacity,
        .cursor_advances = calloc(capacity, sizeof(float)), .cursor_capacity = capacity};
    sh_context *fonts = NULL; float *wire = NULL, *carets = NULL, *hits = NULL;
    jobjectArray result = NULL;
    if ((locale && !l) || !work.advances || !work.candidates || !work.scores || !work.breaks
            || !work.directions || !work.levels || !layout.lines || !layout.runs || !layout.advances
            || !layout.cursor_advances) goto done;
    fonts = sh_context_create(l);
    if (!fonts || tl_layout(fonts, t, n, l, size, width, left, top, &work, &layout, NULL, NULL)) goto done;
    size_t count = layout.glyph_count;
    tl_release(&layout);
    if (count > INT_MAX/4) goto done;
    layout.glyphs = calloc(count ? count : 1, sizeof(sh_glyph)); layout.glyph_capacity = count;
    if (!layout.glyphs || tl_layout(fonts, t, n, l, size, width, left, top, &work, &layout, NULL, NULL)
            || layout.glyph_count != count) goto done;
    sh_context_retire(fonts); fonts = NULL;
    size_t total = 4+layout.line_count*10+layout.run_count*6+count*4;
    if (total > INT_MAX) goto done;
    wire = calloc(total, sizeof *wire);
    carets = calloc(capacity*2, sizeof *carets);
    if (!wire || !carets) goto done;
    for (size_t i = 0; i <= (size_t)n; ++i) {
        int rtl;
        if (tl_caret(&layout, t, i, &carets[i*2], &rtl)) goto done;
        carets[i*2+1] = (float)rtl;
    }
    size_t hit_count = (size_t)n*3+layout.line_count*9, query_at = 0;
    hits = calloc(hit_count*3, sizeof *hits);
    if (!hits) goto done;
    for (size_t i = 0; i < layout.line_count; ++i) {
        const float probes[6] = {left-1, left, left+width/4, left+width/2, left+width, left+width+1};
        for (size_t q = 0; q < 6; ++q)
            if (layout_hit_query(&layout, t, i, probes[q], hits, &query_at)) goto done;
        for (size_t offset = layout.lines[i].start; offset <= layout.lines[i].end; ++offset) {
            for (int q = -1; q <= 1; ++q)
                if (layout_hit_query(&layout, t, i, carets[offset*2]+q*0.25f, hits, &query_at)) goto done;
        }
    }
    if (query_at != hit_count*3) goto done;
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (!string_class) goto done;
    jobjectArray names = (*env)->NewObjectArray(env, (jsize)count, string_class, NULL);
    if (!names) goto done;
    wire[0] = (float)layout.line_count; wire[1] = (float)layout.run_count;
    wire[2] = (float)count; wire[3] = layout.height;
    size_t at = 4, gi = 0;
    for (size_t i = 0; i < layout.line_count; ++i) {
        tl_line *line = &layout.lines[i];
        wire[at++] = (float)line->start; wire[at++] = (float)line->end;
        wire[at++] = (float)line->visible_end; wire[at++] = line->top;
        wire[at++] = line->baseline; wire[at++] = line->bottom; wire[at++] = line->width;
        wire[at++] = (float)line->base; wire[at++] = (float)line->first_run;
        wire[at++] = (float)line->run_count;
    }
    for (size_t i = 0; i < layout.run_count; ++i) {
        tv_run *run = &layout.runs[i];
        wire[at++] = (float)run->direction.start; wire[at++] = (float)run->direction.end;
        wire[at++] = (float)run->direction.level; wire[at++] = run->x;
        wire[at++] = run->shaped.advance; wire[at++] = (float)run->shaped.count;
        for (size_t g = 0; g < run->shaped.count; ++g) {
            char name[1024];
            int written = sh_run_face_name(&run->shaped, run->shaped.glyphs[g].face, name, sizeof name);
            if (written < 0 || (size_t)written >= sizeof name) goto done;
            jstring value = (*env)->NewStringUTF(env, name);
            if (!value) goto done;
            (*env)->SetObjectArrayElement(env, names, (jsize)gi++, value);
            (*env)->DeleteLocalRef(env, value);
            if ((*env)->ExceptionCheck(env)) goto done;
        }
    }
    for (size_t i = 0; i < count; ++i) {
        wire[at++] = (float)layout.glyphs[i].glyph; wire[at++] = (float)layout.glyphs[i].cluster;
        wire[at++] = layout.glyphs[i].x; wire[at++] = layout.glyphs[i].y;
    }
    jfloatArray geometry = (*env)->NewFloatArray(env, (jsize)total);
    if (!geometry) goto done;
    (*env)->SetFloatArrayRegion(env, geometry, 0, (jsize)total, wire);
    jclass object_class = (*env)->FindClass(env, "java/lang/Object");
    if (!object_class) goto done;
    jfloatArray caret_array = (*env)->NewFloatArray(env, (jsize)(capacity*2));
    if (!caret_array) goto done;
    (*env)->SetFloatArrayRegion(env, caret_array, 0, (jsize)(capacity*2), carets);
    jfloatArray hit_array = (*env)->NewFloatArray(env, (jsize)(hit_count*3));
    if (!hit_array) goto done;
    (*env)->SetFloatArrayRegion(env, hit_array, 0, (jsize)(hit_count*3), hits);
    result = (*env)->NewObjectArray(env, 4, object_class, NULL);
    if (result) {
        (*env)->SetObjectArrayElement(env, result, 0, geometry);
        (*env)->SetObjectArrayElement(env, result, 1, names);
        (*env)->SetObjectArrayElement(env, result, 2, caret_array);
        (*env)->SetObjectArrayElement(env, result, 3, hit_array);
    }
done:
    tl_release(&layout);
    if (fonts) sh_context_retire(fonts);
    free(wire); free(carets); free(hits); free(layout.cursor_advances); free(layout.advances);
    free(layout.glyphs); free(layout.runs); free(layout.lines);
    free(work.advances); free(work.candidates); free(work.scores); free(work.breaks);
    free(work.directions); free(work.levels);
    if (l) (*env)->ReleaseStringUTFChars(env, locale, l);
    (*env)->ReleaseCharArrayElements(env, text, t, JNI_ABORT);
    return result;
}

/* Isolated visual-line oracle. Product callers reuse their tp_paragraph across
 * every line; only this diagnostic entry point prepares it per invocation. */
JNIEXPORT jobjectArray JNICALL CLASS(visualLine)(JNIEnv *env, jclass cls,
        jcharArray text, jstring locale, jfloat size, jint start, jint end,
        jfloat width, jfloat line_width, jfloat left, jfloat baseline) {
    (void)cls;
    if (!text) return NULL;
    jsize n = (*env)->GetArrayLength(env, text);
    if (n > (1 << 18) || start < 0 || end < start || end > n) return NULL;
    jchar *t = (*env)->GetCharArrayElements(env, text, NULL);
    if (!t) return NULL;
    const char *l = locale ? (*env)->GetStringUTFChars(env, locale, NULL) : NULL;
    size_t capacity = (size_t)n + 1;
    float *advances = calloc(capacity, sizeof *advances);
    bd_run *directions = calloc(capacity, sizeof *directions);
    uint8_t *levels = calloc(capacity, 1);
    tv_line visual = {.runs = calloc(capacity, sizeof(tv_run)), .capacity = capacity};
    tp_paragraph p = {0}; sh_context *fonts = NULL;
    sh_glyph *glyphs = NULL; float *wire = NULL;
    jobjectArray result = NULL;
    if ((locale && !l) || !advances || !directions || !levels || !visual.runs) goto done;
    fonts = sh_context_create(l);
    if (!fonts || tp_prepare(&p, fonts, t, n, size, l, advances, capacity, NULL, NULL)) goto done;
    if (tv_shape(&p, fonts, start, end, width, line_width, left, baseline,
                 directions, levels, capacity, NULL, 0, &visual, NULL, NULL)) goto done;
    size_t count = visual.glyph_count;
    tv_release(&visual);
    if (count > INT_MAX / 4) goto done;
    glyphs = calloc(count ? count : 1, sizeof *glyphs);
    if (!glyphs || tv_shape(&p, fonts, start, end, width, line_width, left, baseline,
                 directions, levels, capacity, glyphs, count, &visual, NULL, NULL)
            || visual.glyph_count != count) goto done;
    /* Inspect every selected font only after retiring its original owner. */
    sh_context_retire(fonts); fonts = NULL;
    size_t total = 4 + visual.count * 6 + count * 4;
    if (total > INT_MAX) goto done;
    wire = calloc(total, sizeof *wire);
    if (!wire) goto done;
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (!string_class) goto done;
    jobjectArray names = (*env)->NewObjectArray(env, (jsize)count, string_class, NULL);
    if (!names) goto done;
    wire[0] = (float)visual.visible_end; wire[1] = visual.base;
    wire[2] = (float)visual.count; wire[3] = (float)count;
    size_t at = 4, gi = 0;
    for (size_t i = 0; i < visual.count; ++i) {
        tv_run *run = &visual.runs[i];
        wire[at++] = (float)run->direction.start; wire[at++] = (float)run->direction.end;
        wire[at++] = (float)run->direction.level; wire[at++] = run->x;
        wire[at++] = run->shaped.advance; wire[at++] = (float)run->shaped.count;
        for (size_t g = 0; g < run->shaped.count; ++g) {
            char name[1024];
            int written = sh_run_face_name(&run->shaped, run->shaped.glyphs[g].face, name, sizeof name);
            if (written < 0 || (size_t)written >= sizeof name) goto done;
            jstring value = (*env)->NewStringUTF(env, name);
            if (!value) goto done;
            (*env)->SetObjectArrayElement(env, names, (jsize)gi++, value);
            (*env)->DeleteLocalRef(env, value);
            if ((*env)->ExceptionCheck(env)) goto done;
        }
    }
    for (size_t i = 0; i < count; ++i) {
        wire[at++] = (float)glyphs[i].glyph; wire[at++] = (float)glyphs[i].cluster;
        wire[at++] = glyphs[i].x; wire[at++] = glyphs[i].y;
    }
    jfloatArray geometry = (*env)->NewFloatArray(env, (jsize)total);
    if (!geometry) goto done;
    (*env)->SetFloatArrayRegion(env, geometry, 0, (jsize)total, wire);
    jclass object_class = (*env)->FindClass(env, "java/lang/Object");
    if (!object_class) goto done;
    result = (*env)->NewObjectArray(env, 2, object_class, NULL);
    if (result) {
        (*env)->SetObjectArrayElement(env, result, 0, geometry);
        (*env)->SetObjectArrayElement(env, result, 1, names);
    }
done:
    tv_release(&visual); tp_close(&p);
    if (fonts) sh_context_retire(fonts);
    free(wire); free(glyphs); free(visual.runs); free(levels); free(directions); free(advances);
    if (l) (*env)->ReleaseStringUTFChars(env, locale, l);
    (*env)->ReleaseCharArrayElements(env, text, t, JNI_ABORT);
    return result;
}

/* Diagnostic transport only: raw widths followed by offset/width/ascent/descent.
 * No platform advances enter the native paragraph job. */
JNIEXPORT jfloatArray JNICALL CLASS(paragraphMeasure)(JNIEnv *env, jclass cls,
        jcharArray text, jstring locale, jfloat size, jfloat width, jint cancel_after) {
    (void)cls;
    if (!text) return NULL;
    jsize n = (*env)->GetArrayLength(env, text);
    if (n > (1 << 20)) return NULL;
    jchar *t = (*env)->GetCharArrayElements(env, text, NULL);
    if (!t) return NULL;
    const char *l = locale ? (*env)->GetStringUTFChars(env, locale, NULL) : NULL;
    size_t capacity = (size_t)n + 1;
    float *advances = calloc(capacity, sizeof *advances);
    br_candidate *c = calloc(capacity, sizeof *c);
    br_scratch *s = calloc(capacity, sizeof *s);
    br_result lines = {.lines = calloc(capacity, sizeof(br_line)), .capacity = capacity};
    tp_paragraph p = {0};
    sh_context *fonts = NULL;
    float *wire = NULL;
    jfloatArray result = NULL;
    int remaining = cancel_after;
    if ((locale && !l) || !advances || !c || !s || !lines.lines) goto done;
    fonts = sh_context_create(l);
    if (!fonts || tp_prepare(&p, fonts, t, n, size, l, advances, capacity,
                             paragraph_cancel, &remaining)) goto done;
    /* Measurement has no surviving font lease; line breaking uses copied widths. */
    sh_context_retire(fonts); fonts = NULL;
    if (tp_break(&p, width, c, s, capacity, &lines, paragraph_cancel, &remaining)) goto done;
    fonts = sh_context_create(l);
    if (!fonts) goto done;
    size_t count = (size_t)n + lines.count * 4;
    wire = calloc(count ? count : 1, sizeof *wire);
    if (!wire) goto done;
    memcpy(wire, advances, (size_t)n * sizeof *wire);
    size_t start = 0;
    for (size_t i = 0; i < lines.count; ++i) {
        sh_extent extent;
        size_t end = lines.lines[i].offset;
        if (tp_extent(&p, fonts, start, end, &extent, paragraph_cancel, &remaining)) goto done;
        wire[n + 4*i] = (float)end;
        wire[n + 4*i + 1] = lines.lines[i].width;
        wire[n + 4*i + 2] = extent.ascent;
        wire[n + 4*i + 3] = extent.descent;
        start = end;
    }
    result = (*env)->NewFloatArray(env, (jsize)count);
    if (result) (*env)->SetFloatArrayRegion(env, result, 0, (jsize)count, wire);
done:
    tp_close(&p);
    if (fonts) sh_context_retire(fonts);
    free(wire); free(lines.lines); free(s); free(c); free(advances);
    if (l) (*env)->ReleaseStringUTFChars(env, locale, l);
    (*env)->ReleaseCharArrayElements(env, text, t, JNI_ABORT);
    return result;
}

JNIEXPORT jfloatArray JNICALL CLASS(shapeMetrics)(JNIEnv *env, jclass cls, jcharArray text,
        jboolean rtl, jfloat size, jstring locale) {
    (void)cls;
    if (!text) return NULL;
    jsize n = (*env)->GetArrayLength(env, text);
    jchar *t = (*env)->GetCharArrayElements(env, text, NULL);
    if (!t) return NULL;
    const char *l = locale ? (*env)->GetStringUTFChars(env, locale, NULL) : NULL;
    sh_context *owner = NULL;
    sh_run run = {0};
    jfloatArray result = NULL;
    float *wire = NULL;
    if (locale && !l) goto done;
    owner = sh_context_create(l);
    if (!owner || sh_shape_owned(owner, t, n, 0, n, rtl, size, &run)) goto done;
    size_t count = run.count;
    sh_run_release(&run);
    if (count > INT_MAX / 6) goto done;
    run.glyphs = calloc(count ? count : 1, sizeof *run.glyphs); run.capacity = count;
    wire = calloc(count ? count * 6 : 1, sizeof *wire);
    if (!run.glyphs || !wire || sh_shape_owned(owner, t, n, 0, n, rtl, size, &run) || run.count != count) goto done;
    /* Retire before metric inspection: the output lease must retain font data. */
    sh_context_retire(owner); owner = NULL;
    for (size_t i = 0; i < count; ++i) {
        sh_metrics m;
        if (sh_run_face_metrics(&run, run.glyphs[i].face, &m)) goto done;
        wire[i*6] = (float)run.glyphs[i].glyph;
        wire[i*6+1] = m.top; wire[i*6+2] = m.ascent; wire[i*6+3] = m.descent;
        wire[i*6+4] = m.bottom; wire[i*6+5] = m.leading;
    }
    result = (*env)->NewFloatArray(env, (jsize)(count * 6));
    if (result) (*env)->SetFloatArrayRegion(env, result, 0, (jsize)(count * 6), wire);
done:
    sh_run_release(&run);
    if (owner) sh_context_retire(owner);
    free(run.glyphs); free(wire);
    if (l) (*env)->ReleaseStringUTFChars(env, locale, l);
    (*env)->ReleaseCharArrayElements(env, text, t, JNI_ABORT);
    return result;
}

JNIEXPORT jfloatArray JNICALL CLASS(lineCandidates)(JNIEnv *env, jclass cls, jcharArray text,
        jfloatArray advances, jstring locale, jfloat width, jfloat size) {
    (void)cls;
    if (!text || !advances || !isfinite(size) || size <= 0 || size >= (float)(INT_MAX / 256)) return NULL;
    size = truncf(size); /* default Paint's non-linear Minikin size, as in shape_impl */
    jsize n = (*env)->GetArrayLength(env, text);
    if ((*env)->GetArrayLength(env, advances) != n || n > (1 << 24)) return NULL;
    jfloatArray result = NULL;
    jchar *t = (*env)->GetCharArrayElements(env, text, NULL);
    if (!t) return NULL;
    jfloat *a = (*env)->GetFloatArrayElements(env, advances, NULL);
    const char *l = locale ? (*env)->GetStringUTFChars(env, locale, NULL) : NULL;
    size_t capacity = (size_t)n + 1, count = 0;
    br_candidate *c = calloc(capacity, sizeof *c);
    float *wire = calloc(capacity * 4, sizeof *wire);
    if (!a || (locale && !l) || !c || !wire) goto done;
    if (br_candidates(t, a, n, l, width, size, c, capacity, &count, NULL, NULL)) goto done;
    for (size_t i = 0; i < count; ++i) {
        wire[i*4] = (float)c[i].offset; wire[i*4+1] = c[i].pre;
        wire[i*4+2] = c[i].post; wire[i*4+3] = c[i].penalty;
    }
    result = (*env)->NewFloatArray(env, (jsize)(count * 4));
    if (result) (*env)->SetFloatArrayRegion(env, result, 0, (jsize)(count * 4), wire);
done:
    free(wire); free(c);
    if (l) (*env)->ReleaseStringUTFChars(env, locale, l);
    if (a) (*env)->ReleaseFloatArrayElements(env, advances, a, JNI_ABORT);
    (*env)->ReleaseCharArrayElements(env, text, t, JNI_ABORT);
    return result;
}

/* Diagnostic boundary: measured widths are supplied by the independent platform
 * oracle so candidate generation/scoring can be checked apart from shaping. */
JNIEXPORT jfloatArray JNICALL CLASS(lineBreaks)(JNIEnv *env, jclass cls, jcharArray text,
        jfloatArray advances, jstring locale, jfloat width, jfloat size) {
    (void)cls;
    if (!text || !advances || !isfinite(size) || size <= 0 || size >= (float)(INT_MAX / 256)) return NULL;
    size = truncf(size); /* penalties use the same effective size as shaping */
    jsize n = (*env)->GetArrayLength(env, text);
    /* Diagnostic wire encodes offsets in floats, so require exact integers. */
    if ((*env)->GetArrayLength(env, advances) != n || n > (1 << 24)) return NULL;
    jfloatArray result = NULL;
    jchar *t = (*env)->GetCharArrayElements(env, text, NULL);
    if (!t) return NULL;
    jfloat *a = (*env)->GetFloatArrayElements(env, advances, NULL);
    const char *l = locale ? (*env)->GetStringUTFChars(env, locale, NULL) : NULL;
    size_t capacity = (size_t)n + 1, count = 0;
    br_candidate *c = calloc(capacity, sizeof *c);
    br_scratch *scratch = calloc(capacity, sizeof *scratch);
    br_result out = {.lines = calloc(capacity, sizeof(br_line)), .capacity = capacity};
    float *wire = calloc(capacity * 2, sizeof *wire);
    if (!a || (locale && !l) || !c || !scratch || !out.lines || !wire) goto done;
    int has_tab = 0;
    for (jsize i = 0; i < n; ++i) if (t[i] == '\t') { has_tab = 1; break; }
    if (has_tab) {
        if (br_greedy(t, a, n, l, width, NULL, 0, 20,
                &out, NULL, NULL)) goto done;
    } else {
        if (br_candidates(t, a, n, l, width, size, c, capacity, &count, NULL, NULL)) goto done;
        float line_penalty = (float)(0.5 * size * width) * 2;
        if (br_optimal(c, count, width, line_penalty, scratch, capacity, &out, NULL, NULL)) goto done;
    }
    for (size_t i = 0; i < out.count; ++i) {
        wire[i*2] = (float)out.lines[i].offset; wire[i*2+1] = out.lines[i].width;
    }
    result = (*env)->NewFloatArray(env, (jsize)(out.count * 2));
    if (result) (*env)->SetFloatArrayRegion(env, result, 0, (jsize)(out.count * 2), wire);
done:
    free(wire); free(out.lines); free(scratch); free(c);
    if (l) (*env)->ReleaseStringUTFChars(env, locale, l);
    if (a) (*env)->ReleaseFloatArrayElements(env, advances, a, JNI_ABORT);
    (*env)->ReleaseCharArrayElements(env, text, t, JNI_ABORT);
    return result;
}

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
    if (!text) return NULL;
    jsize n = (*env)->GetArrayLength(env, text);
    if (start < 0 || end < start || end > n) return NULL;
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

JNIEXPORT jstring JNICALL CLASS(shapeError)(JNIEnv *env, jclass c) {
    (void)c;
    return (*env)->NewStringUTF(env, sh_error());
}

JNIEXPORT jint JNICALL CLASS(shapeFaceFormats)(JNIEnv *env, jclass c, jint face) {
    (void)env; (void)c;
    return sh_face_formats((uint32_t)face);
}

/* Development wire format v1, little endian: version/count/units/query-count/advance;
 * glyph records (face, glyph, absolute cluster, x, y, advance); raw UTF-16 advances;
 * run advances at all offsets; five cursor options at all offsets; query offsets.
 * Layout workers consume sh_run and ms_* directly, without this diagnostic transport. */
static void put32(uint8_t **p, uint32_t value) {
    for (unsigned i = 0; i < 4; i++) *(*p)++ = (uint8_t)(value >> (i * 8));
}
static void putf(uint8_t **p, float value) {
    uint32_t bits; memcpy(&bits, &value, sizeof bits); put32(p, bits);
}
JNIEXPORT jbyteArray JNICALL CLASS(shapeProbe)(JNIEnv *env, jclass c, jcharArray text,
        jint start, jint end, jboolean rtl, jfloat size, jfloatArray query) {
    (void)c;
    if (!text) return NULL;
    jsize n = (*env)->GetArrayLength(env, text);
    if (start < 0 || end < start || end > n) return NULL;
    jsize nq = query ? (*env)->GetArrayLength(env, query) : 0;
    jchar *t = (*env)->GetCharArrayElements(env, text, NULL);
    if (!t) return NULL;
    jfloat *q = query ? (*env)->GetFloatArrayElements(env, query, NULL) : NULL;
    if (query && !q) { (*env)->ReleaseCharArrayElements(env, text, t, JNI_ABORT); return NULL; }
    sh_run run = {0};
    size_t units = (size_t)(end - start);
    run.capacity = units * 2 + 8;
    run.glyphs = malloc(run.capacity * sizeof *run.glyphs);
    run.advance_capacity = units;
    run.advances = calloc(units ? units : 1, sizeof *run.advances);
    uint8_t *bytes = NULL;
    jbyteArray result = NULL;
    for (jsize i = 0; i < nq; i++) if (!isfinite(q[i])) goto done;
    int rc = run.glyphs && run.advances ? sh_shape(t, n, start, end, rtl, size, &run) : -1;
    if (!rc && run.count > run.capacity) {
        free(run.glyphs); run.capacity = run.count;
        run.glyphs = malloc(run.capacity * sizeof *run.glyphs);
        rc = run.glyphs ? sh_shape(t, n, start, end, rtl, size, &run) : -1;
    }
    if (rc || run.count > run.capacity) goto done;
    uint64_t length = 20 + 24 * (uint64_t)run.count + 4 * (uint64_t)units
        + 24 * ((uint64_t)units + 1) + 4 * (uint64_t)nq;
    if (length > INT_MAX || !(bytes = malloc((size_t)length))) goto done;
    uint8_t *p = bytes;
    put32(&p, 1); put32(&p, (uint32_t)run.count); put32(&p, (uint32_t)units);
    put32(&p, (uint32_t)nq); putf(&p, run.advance);
    for (size_t i = 0; i < run.count; i++) {
        sh_glyph *g = &run.glyphs[i];
        put32(&p, g->face); put32(&p, g->glyph); put32(&p, g->cluster);
        putf(&p, g->x); putf(&p, g->y); putf(&p, g->advance);
    }
    for (size_t i = 0; i < units; i++) putf(&p, run.advances[i]);
    for (size_t i = 0; i <= units; i++) putf(&p, ms_run_advance(run.advances, t, start, units, start + i));
    for (int opt = MS_AFTER; opt <= MS_AT; opt++) for (size_t i = 0; i <= units; i++)
        put32(&p, (uint32_t)ms_cursor(run.advances, t, start, units, start + i, opt));
    for (jsize i = 0; i < nq; i++) put32(&p, (uint32_t)ms_offset_for_advance(run.advances, t, start, units, q[i]));
    result = (*env)->NewByteArray(env, (jsize)length);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (const jbyte *)bytes);
done:
    free(bytes); free(run.glyphs); free(run.advances);
    if (q) (*env)->ReleaseFloatArrayElements(env, query, q, JNI_ABORT);
    (*env)->ReleaseCharArrayElements(env, text, t, JNI_ABORT);
    return result;
}

JNIEXPORT jstring JNICALL CLASS(shapeOwnership)(JNIEnv *env, jclass c, jstring locales) {
    (void)c;
    const char *l = locales ? (*env)->GetStringUTFChars(env, locales, NULL) : NULL;
    if (locales && !l) return NULL;
    char report[1024];
    sh_ownership_oracle(l, report, sizeof report);
    if (l) (*env)->ReleaseStringUTFChars(env, locales, l);
    return (*env)->NewStringUTF(env, report);
}

/* Batch oracle: element zero contains logical paragraph runs; subsequent
 * elements are line results extracted repeatedly from one retained analysis. */
JNIEXPORT jobjectArray JNICALL CLASS(bidiBatch)(JNIEnv *env, jclass cls, jcharArray text, jintArray ranges) {
    (void)cls;
    if (!text || !ranges) return NULL;
    jsize n = (*env)->GetArrayLength(env, text), nr = (*env)->GetArrayLength(env, ranges);
    if (n > (INT_MAX - 3) / 4 || (nr & 1) || nr > 8192) return NULL;
    jchar *t = (*env)->GetCharArrayElements(env, text, NULL);
    if (!t) return NULL;
    jint *r = (*env)->GetIntArrayElements(env, ranges, NULL);
    bd_paragraph *p = NULL;
    bd_result b = {.capacity = (size_t)n, .level_capacity = (size_t)n};
    b.runs = calloc(n ? (size_t)n : 1, sizeof *b.runs);
    b.levels = malloc(n ? (size_t)n : 1);
    jint *wire = malloc((3 + (size_t)n * 4) * sizeof *wire);
    jobjectArray result = NULL;
    if (!r || !b.runs || !b.levels || !wire) goto done;
    for (jsize i = 0; i < nr; i += 2) if (r[i] < 0 || r[i+1] < r[i] || r[i+1] > n) goto done;
    p = bd_open(t, (size_t)n);
    if (!p) goto done;
    jclass ints = (*env)->FindClass(env, "[I");
    if (!ints) goto done;
    result = (*env)->NewObjectArray(env, nr / 2 + 1, ints, NULL);
    (*env)->DeleteLocalRef(env, ints);
    if (!result) goto done;
    size_t at = 0, offset = 0;
    while (offset < (size_t)n) {
        bd_run run;
        if (bd_run_at(p, offset, &run)) { result = NULL; goto done; }
        wire[at++] = (jint)run.start; wire[at++] = (jint)run.end; wire[at++] = (jint)run.level;
        offset = run.end;
    }
    for (jsize i = -2; i < nr; i += 2) {
        if (i >= 0) {
            size_t start = (size_t)r[i], end = (size_t)r[i+1], units = end - start;
            if (bd_extract_line(p, start, end, &b) || b.count > (size_t)n) { result = NULL; goto done; }
            wire[0] = b.base; wire[1] = (jint)b.count; wire[2] = (jint)units;
            for (size_t j = 0; j < units; ++j) wire[3+j] = b.levels[j];
            at = 3 + units;
            for (size_t j = 0; j < b.count; ++j) {
                wire[at++] = (jint)b.runs[j].start; wire[at++] = (jint)b.runs[j].end;
                wire[at++] = (jint)b.runs[j].level;
            }
        }
        jintArray line = (*env)->NewIntArray(env, (jsize)at);
        if (!line) { result = NULL; goto done; }
        (*env)->SetIntArrayRegion(env, line, 0, (jsize)at, wire);
        (*env)->SetObjectArrayElement(env, result, i / 2 + 1, line);
        (*env)->DeleteLocalRef(env, line);
        if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
    }
done:
    bd_close(p); free(wire); free(b.runs); free(b.levels);
    if (r) (*env)->ReleaseIntArrayElements(env, ranges, r, JNI_ABORT);
    (*env)->ReleaseCharArrayElements(env, text, t, JNI_ABORT);
    return result;
}

JNIEXPORT jintArray JNICALL CLASS(bidiLine)(JNIEnv *env, jclass c, jcharArray text, jint start, jint end) {
    (void)c;
    if (!text) return NULL;
    jsize n = (*env)->GetArrayLength(env, text);
    if (start < 0 || end < start || end > n) return NULL;
    size_t units = (size_t)(end - start);
    if (units > (INT_MAX - 3) / 4) return NULL;
    jchar *t = (*env)->GetCharArrayElements(env, text, NULL);
    if (!t) return NULL;
    bd_result b = {.capacity = units, .level_capacity = units};
    b.runs = calloc(units ? units : 1, sizeof *b.runs);
    b.levels = malloc(units ? units : 1);
    jintArray result = NULL;
    jint *wire = NULL;
    if (!b.runs || !b.levels || bd_line(t, n, start, end, &b) || b.count > units) goto done;
    jsize length = (jsize)(3 + units + b.count * 3);
    wire = malloc((size_t)length * sizeof *wire);
    if (!wire) goto done;
    wire[0] = b.base; wire[1] = (jint)b.count; wire[2] = (jint)units;
    for (size_t i = 0; i < units; i++) wire[3 + i] = b.levels[i];
    for (size_t i = 0; i < b.count; i++) {
        wire[3 + units + 3 * i] = (jint)b.runs[i].start;
        wire[4 + units + 3 * i] = (jint)b.runs[i].end;
        wire[5 + units + 3 * i] = (jint)b.runs[i].level;
    }
    result = (*env)->NewIntArray(env, length);
    if (result) (*env)->SetIntArrayRegion(env, result, 0, length, wire);
done:
    free(wire); free(b.runs); free(b.levels);
    (*env)->ReleaseCharArrayElements(env, text, t, JNI_ABORT);
    return result;
}
