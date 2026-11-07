#include "visual.h"
#include <math.h>
#include <string.h>
#include <unicode/uchar.h>

void tv_release(tv_line *line) {
    if (!line) return;
    for (size_t i = 0; i < line->count; ++i) sh_run_release(&line->runs[i].shaped);
    line->count = line->glyph_count = 0;
}

int tv_shape(const tp_paragraph *p, sh_context *fonts, size_t start, size_t end,
             float width, float line_width, float left, float baseline,
             bd_run *directions, uint8_t *levels, size_t scratch_capacity,
             sh_glyph *glyphs, size_t glyph_capacity, tv_line *out,
             br_cancel cancel, void *context) {
    if (!out || out->count) return -1;
    out->glyph_count = 0;
    if (!p || !p->bidi || !fonts || start > end || end > p->length ||
            !directions || !levels || !out->runs || scratch_capacity < end-start ||
            out->capacity < end-start || (glyph_capacity && !glyphs) ||
            (out->advances && out->advance_capacity < end-start) ||
            !isfinite(width) || width < 0 || !isfinite(line_width) ||
            !isfinite(left) || !isfinite(baseline)) return -1;
    if (cancel && cancel(context)) return -2;
    if (out->advances) memset(out->advances, 0, (end-start)*sizeof(float));
    size_t visible = end;
    while (visible > start && u_isWhitespace(p->text[visible-1])) {
        if (cancel && cancel(context)) return -2;
        --visible;
    }
    bd_result bidi = {.runs = directions, .capacity = scratch_capacity,
        .levels = levels, .level_capacity = scratch_capacity};
    if (bd_extract_line(p->bidi, start, visible, &bidi) || bidi.count > out->capacity) return -1;
    out->visible_end = visible; out->base = bidi.base;
    float pen = (bidi.base & 1) ? left + width - line_width : left;
    if (!isfinite(pen)) return -1;
    int rc = -1;
    for (size_t i = 0; i < bidi.count; ++i) {
        if (cancel && cancel(context)) { rc = -2; goto fail; }
        tv_run *run = &out->runs[i];
        if (run->shaped.owner) { rc = -1; goto fail; }
        size_t available = out->glyph_count < glyph_capacity ? glyph_capacity-out->glyph_count : 0;
        run->direction = directions[i]; run->x = pen;
        run->shaped = (sh_run){.glyphs = available ? glyphs+out->glyph_count : NULL, .capacity = available,
            .advances = out->advances ? out->advances+directions[i].start-start : NULL,
            .advance_capacity = directions[i].end-directions[i].start};
        rc = sh_shape_owned(fonts, p->text+start, visible-start,
            directions[i].start-start, directions[i].end-start,
            directions[i].level & 1, p->size, &run->shaped);
        if (rc) goto fail;
        out->count++;
        if (run->shaped.count > SIZE_MAX-out->glyph_count) { rc = -1; goto fail; }
        out->glyph_count += run->shaped.count;
        size_t stored = run->shaped.count < available ? run->shaped.count : available;
        for (size_t j = 0; j < stored; ++j) {
            sh_glyph *g = &run->shaped.glyphs[j];
            g->cluster += (uint32_t)start;
            g->x += pen; g->y += baseline;
        }
        pen += run->shaped.advance;
        if (!isfinite(pen)) { rc = -1; goto fail; }
    }
    if (cancel && cancel(context)) { rc = -2; goto fail; }
    return 0;
fail:
    tv_release(out);
    return rc;
}
