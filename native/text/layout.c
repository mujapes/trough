#include "layout.h"
#include "measure.h"
#include <limits.h>
#include <math.h>

void tl_release(tl_result *out) {
    if (!out) return;
    for (size_t i = 0; i < out->run_count; ++i) sh_run_release(&out->runs[i].shaped);
    out->run_count = out->line_count = out->glyph_count = 0;
    out->height = 0;
}

int tl_layout(sh_context *fonts, const uint16_t *text, size_t length, const char *locale,
              float size, float width, float left, float top, tl_work *work, tl_result *out,
              br_cancel cancel, void *context) {
    if (!out || out->run_count) return -1;
    out->line_count = out->glyph_count = 0; out->height = 0;
    if (!fonts || (!text && length) || length > INT_MAX || !work ||
            work->capacity <= length || !work->advances || !work->candidates ||
            !work->scores || !work->breaks || !work->directions || !work->levels ||
            !out->lines || !out->runs || out->line_capacity <= length || out->run_capacity <= length ||
            (out->glyph_capacity && !out->glyphs) || !isfinite(width) || width < 0 ||
            (out->advances && out->advance_capacity < length) ||
            (out->cursor_advances && out->cursor_capacity < length) ||
            !isfinite(left) || !isfinite(top)) return -1;
    for (size_t i = 0; i < length; ++i) {
        if (cancel && cancel(context)) return -2;
        if (text[i] == '\n') return -1;
    }
    tp_paragraph p = {0};
    int rc = tp_prepare(&p, fonts, text, length, size, locale, work->advances,
                        work->capacity, cancel, context);
    if (rc) return rc;
    sh_metrics defaults;
    if (sh_default_metrics(fonts, size, &defaults)) { rc = -1; goto done; }
    br_result broken = {.lines = work->breaks, .capacity = work->capacity};
    rc = tp_break(&p, width, work->candidates, work->scores, work->capacity, &broken, cancel, context);
    if (rc) goto done;
    size_t count = length ? broken.count : 1, start = 0;
    float y = top;
    for (size_t i = 0; i < count; ++i) {
        if (cancel && cancel(context)) { rc = -2; goto done; }
        size_t end = length ? broken.lines[i].offset : 0;
        float used = length ? broken.lines[i].width : 0;
        sh_extent extent;
        rc = tp_extent(&p, fonts, start, end, &extent, cancel, context);
        if (rc) goto done;
        float ascent = fmaxf(floorf(-defaults.ascent + 0.5f), floorf(-extent.ascent + 0.5f));
        float descent = fmaxf(floorf(defaults.descent + 0.5f), floorf(extent.descent + 0.5f));
        float baseline = y + ascent, bottom = baseline + descent;
        if (!isfinite(bottom)) { rc = -1; goto done; }
        size_t remaining = out->glyph_count < out->glyph_capacity ? out->glyph_capacity-out->glyph_count : 0;
        tv_line visual = {.runs = out->runs+out->run_count, .capacity = out->run_capacity-out->run_count,
            .advances = out->advances ? out->advances+start : NULL, .advance_capacity = end-start};
        rc = tv_shape(&p, fonts, start, end, width, used, left, baseline,
                      work->directions, work->levels, work->capacity,
                      remaining ? out->glyphs+out->glyph_count : NULL, remaining,
                      &visual, cancel, context);
        if (rc) goto done;
        if (visual.glyph_count > SIZE_MAX-out->glyph_count) { tv_release(&visual); rc = -1; goto done; }
        out->lines[i] = (tl_line){(uint32_t)start, (uint32_t)end, (uint32_t)visual.visible_end,
            visual.base, out->run_count, visual.count, y, baseline, bottom, used};
        out->run_count += visual.count; out->glyph_count += visual.glyph_count;
        out->line_count++;
        if (out->cursor_advances && end > start) {
            sh_run cursor = {.advances = out->cursor_advances+start, .advance_capacity = end-start};
            rc = sh_shape_owned(fonts, text+start, end-start, 0, end-start,
                                visual.base & 1, size, &cursor);
            sh_run_release(&cursor);
            if (rc) goto done;
        }
        start = end; y = bottom;
    }
    if (cancel && cancel(context)) { rc = -2; goto done; }
    out->height = y-top;
    out->left = left; out->width = width;
done:
    tp_close(&p);
    if (rc) tl_release(out);
    return rc;
}

static const tv_run *run_at(const tl_result *layout, const tl_line *line, size_t offset) {
    for (size_t i = 0; i < line->run_count; ++i) {
        const tv_run *run = &layout->runs[line->first_run+i];
        if (offset >= run->direction.start && offset < run->direction.end) return run;
    }
    return NULL;
}

static float edge(const tl_result *layout, const uint16_t *text, const tv_run *run, size_t offset) {
    size_t start = run->direction.start, end = run->direction.end;
    if (offset < start) offset = start;
    if (offset > end) offset = end;
    float into = ms_run_advance(layout->advances+start, text, start, end-start, offset);
    return (run->direction.level & 1) ? run->x+run->shaped.advance-into : run->x+into;
}

int tl_caret(const tl_result *layout, const uint16_t *text, size_t offset, float *x, int *rtl) {
    if (!layout || !layout->line_count || !x || !rtl || !layout->advances ||
            layout->glyph_count > layout->glyph_capacity) return -1;
    size_t length = layout->lines[layout->line_count-1].end;
    if ((!text && length) || offset > length || layout->advance_capacity < length) return -1;
    size_t index = layout->line_count-1;
    while (index && offset < layout->lines[index].start) --index;
    const tl_line *line = &layout->lines[index];
    int base = line->base & 1;
    const tv_run *at = run_at(layout, line, offset);
    const tv_run *before = offset == line->start ? NULL : run_at(layout, line, offset-1);
    *rtl = at ? (int)(at->direction.level & 1) : base;
    float leading = base ? layout->left+layout->width : layout->left;
    if (!line->run_count) { *x = leading; return 0; }
    uint32_t level_at = at ? at->direction.level : (uint32_t)base;
    uint32_t level_before = before ? before->direction.level : (uint32_t)base;
    if (level_before < level_at) {
        if (offset == line->start) { *x = leading; return 0; }
        if (before) { *x = edge(layout, text, before, offset); return 0; }
    } else if (at) { *x = edge(layout, text, at, offset); return 0; }
    const tv_run *last = &layout->runs[line->first_run+(base ? 0 : line->run_count-1)];
    *x = base ? last->x : last->x+last->shaped.advance;
    return 0;
}

int tl_hit(const tl_result *layout, const uint16_t *text, size_t index, float x, size_t *offset) {
    if (!layout || !offset || !isfinite(x) || index >= layout->line_count ||
            !layout->cursor_advances || !layout->advances ||
            (!text && layout->lines[layout->line_count-1].end) ||
            layout->cursor_capacity < layout->lines[layout->line_count-1].end)
        return -1;
    const tl_line *line = &layout->lines[index];
    size_t maximum = line->end;
    const float *advances = layout->cursor_advances+line->start;
    if (index+1 < layout->line_count && maximum > line->start)
        maximum = ms_cursor(advances, text, line->start, line->end-line->start, maximum, MS_BEFORE);
    if (maximum < line->start || maximum > line->end) return -1;
    size_t best = line->start;
    float best_distance = INFINITY;
    for (size_t at = line->start; at <= maximum; ++at) {
        if (at != line->start && at != maximum &&
                ms_cursor(advances, text, line->start, line->end-line->start, at, MS_AT) != at) continue;
        float caret; int rtl;
        if (tl_caret(layout, text, at, &caret, &rtl)) return -1;
        float distance = fabsf(caret-x);
        if (distance < best_distance || (at == maximum && distance <= best_distance)) {
            best = at; best_distance = distance;
        }
    }
    *offset = best;
    return 0;
}
