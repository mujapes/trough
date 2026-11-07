#include "paragraph.h"
#include <limits.h>
#include <math.h>
#include <string.h>

void tp_close(tp_paragraph *p) {
    if (!p) return;
    bd_close(p->bidi);
    memset(p, 0, sizeof *p);
}

int tp_prepare(tp_paragraph *out, sh_context *fonts, const uint16_t *text,
               size_t length, float size, const char *locale,
               float *advances, size_t capacity, br_cancel cancel, void *context) {
    if (!out || out->bidi || !fonts || (!text && length) ||
            (!advances && length) || capacity < length || length > INT_MAX ||
            !isfinite(size) || size <= 0 || size >= (float)(INT_MAX / 256)) return -1;
    memset(out, 0, sizeof *out);
    if (cancel && cancel(context)) return -2;
    tp_paragraph p = {.text = text, .length = length, .size = size,
        .locale = locale, .advances = advances};
    p.bidi = bd_open(text, length);
    if (!p.bidi) return -1;
    int rc = -1;
    for (size_t offset = 0; offset < length;) {
        if (cancel && cancel(context)) { rc = -2; goto fail; }
        bd_run direction;
        if (bd_run_at(p.bidi, offset, &direction)) { rc = -1; goto fail; }
        sh_run measured = {.advances = advances + offset,
                           .advance_capacity = direction.end - offset};
        rc = sh_shape_owned(fonts, text, length, offset, direction.end,
                            direction.level & 1, size, &measured);
        sh_run_release(&measured);
        if (rc) goto fail;
        offset = direction.end;
    }
    for (size_t i = 0; i < length; ++i) {
        if (cancel && cancel(context)) { rc = -2; goto fail; }
        if (text[i] == '\t') p.has_tab = 1;
    }
    if (cancel && cancel(context)) { rc = -2; goto fail; }
    *out = p;
    return 0;
fail:
    tp_close(&p);
    return rc;
}

int tp_break(const tp_paragraph *p, float width, br_candidate *c, br_scratch *s,
             size_t capacity, br_result *out, br_cancel cancel, void *context) {
    if (!out) return -1;
    out->count = 0;
    if (!p || !p->bidi || !c || !s || capacity <= p->length ||
            out->capacity <= p->length || !out->lines ||
            !isfinite(width) || width < 0) return -1;
    if (cancel && cancel(context)) return -2;
    if (p->has_tab)
        return br_greedy(p->text, p->advances, p->length, p->locale,
                         width, NULL, 0, 20, out, cancel, context);
    size_t count = 0;
    float size = truncf(p->size);
    int rc = br_candidates(p->text, p->advances, p->length, p->locale, width,
                           size, c, capacity, &count, cancel, context);
    if (rc) return rc;
    float penalty = (float)(0.5 * size * width) * 2;
    return br_optimal(c, count, width, penalty, s, capacity, out, cancel, context);
}

int tp_extent(const tp_paragraph *p, sh_context *fonts, size_t start, size_t end,
              sh_extent *out, br_cancel cancel, void *context) {
    if (!out) return -1;
    *out = (sh_extent){0, 0};
    if (!p || !p->bidi || !fonts || start > end || end > p->length) return -1;
    sh_extent extent = {0, 0};
    for (size_t offset = start; offset < end;) {
        if (cancel && cancel(context)) return -2;
        bd_run direction;
        if (bd_run_at(p->bidi, offset, &direction)) return -1;
        size_t limit = direction.end < end ? direction.end : end;
        sh_run measured = {0};
        int rc = sh_shape_owned(fonts, p->text, p->length, offset, limit,
                                direction.level & 1, p->size, &measured);
        if (!rc) {
            if (measured.extent.ascent < extent.ascent) extent.ascent = measured.extent.ascent;
            if (measured.extent.descent > extent.descent) extent.descent = measured.extent.descent;
        }
        sh_run_release(&measured);
        if (rc) return rc;
        offset = limit;
    }
    if (cancel && cancel(context)) return -2;
    *out = extent;
    return 0;
}
