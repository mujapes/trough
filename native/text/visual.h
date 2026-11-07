#ifndef TROUGH_TEXT_VISUAL_H
#define TROUGH_TEXT_VISUAL_H
#include "paragraph.h"
typedef struct {
    bd_run direction;
    float x;
    sh_run shaped; /* output font lease; glyphs are a slice of caller storage */
} tv_run;
typedef struct {
    tv_run *runs;
    size_t count, capacity, glyph_count;
    size_t visible_end;
    uint8_t base;
    float *advances; size_t advance_capacity; /* optional raw widths, indexed from line start */
} tv_line;
/* Zero-initialize every tv_run. Release even a successful capacity probe before
 * retrying: each directional shape holds its font owner independently. Arrays
 * and glyph storage remain caller-owned; never copy live output leases. */
void tv_release(tv_line *);
/* Position one logical line as TextLayout does: trim Java whitespace, extract
 * visual bidi runs from the retained paragraph, shape in the visible line's
 * context, and add paragraph alignment/baseline. No glyph rasterization here.
 * direction/level scratch and run slots need end-start entries (one for empty).
 * 0 success, -1 invalid/resource failure, -2 cancelled. On success glyph_count
 * can exceed glyph_capacity: release/retry, never publish partial geometry.
 * On error all leases acquired by this call are released and count is zero. */
int tv_shape(const tp_paragraph *, sh_context *, size_t start, size_t end,
             float width, float line_width, float left, float baseline,
             bd_run *directions, uint8_t *levels, size_t scratch_capacity,
             sh_glyph *glyphs, size_t glyph_capacity, tv_line *, br_cancel, void *);
#endif
