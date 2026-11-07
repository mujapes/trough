#ifndef TROUGH_TEXT_LAYOUT_H
#define TROUGH_TEXT_LAYOUT_H
#include "visual.h"
typedef struct {
    uint32_t start, end, visible_end, base;
    size_t first_run, run_count;
    float top, baseline, bottom, width;
} tl_line;
typedef struct {
    tl_line *lines; size_t line_capacity, line_count;
    tv_run *runs; size_t run_capacity, run_count;
    sh_glyph *glyphs; size_t glyph_capacity, glyph_count;
    float height;
    float *advances; size_t advance_capacity; /* optional final visual-run raw widths */
    float *cursor_advances; size_t cursor_capacity; /* optional whole-line forced-base cursor widths */
    float left, width;
} tl_result;
typedef struct {
    float *advances;
    br_candidate *candidates;
    br_scratch *scores;
    br_line *breaks;
    bd_run *directions;
    uint8_t *levels;
    size_t capacity; /* every scratch array has length+1 slots */
} tl_work;
/* One captured LF-free paragraph, default sans-serif Paint, fixed width. Scratch
 * and all output arrays belong to the admitted job and have distinct storage.
 * Reserve length+1 line/run slots and zero-initialize runs. A successful glyph
 * capacity probe reports required glyph_count and holds leases: release/retry.
 * Never publish until glyph_count <= glyph_capacity and captured revisions match.
 * Font context is borrowed during layout; successful visual runs retain it so
 * the caller may retire the context before inspecting/using the final glyphs.
 * Empty paragraphs produce one empty line, matching TextLayout. */
int tl_layout(sh_context *, const uint16_t *, size_t length, const char *locale,
              float size, float width, float left, float top, tl_work *, tl_result *,
              br_cancel, void *);
void tl_release(tl_result *);
/* Primary caret/character direction from final visual-run widths. Requires the
 * optional advances array above and the exact captured text used by layout.
 * Offsets include paragraph end; return -1 for invalid/incomplete output. */
int tl_caret(const tl_result *, const uint16_t *text, size_t offset, float *x, int *rtl);
int tl_hit(const tl_result *, const uint16_t *text, size_t line, float x, size_t *offset);
#endif
