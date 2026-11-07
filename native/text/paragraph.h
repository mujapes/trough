#ifndef TROUGH_TEXT_PARAGRAPH_H
#define TROUGH_TEXT_PARAGRAPH_H
#include "bidi.h"
#include "shape.h"
#include "words.h"

/* One worker's captured, single-style/default-Paint paragraph. Text, locale and
 * advances are borrowed immutable until close; prepare fills advances privately.
 * Initialize to zero, never copy a live handle. The caller owns font admission
 * through prepare; no font pointer/lease survives measurement. ICU analysis does
 * survive for line extraction. Cancellation/errors discard all provisional data.
 * Array/ICU/font byte admission and publication are the enclosing job's duties. */
typedef struct {
    const uint16_t *text;
    const char *locale;
    size_t length;
    float size;
    float *advances;
    bd_paragraph *bidi;
    int has_tab;
} tp_paragraph;
/* 0 success, -1 invalid input/resource failure, -2 cancelled. On failure the
 * handle is empty; caller discards advances. Shaping cancellation is sampled
 * between directional runs (a single HarfBuzz call is not interruptible yet). */
int tp_prepare(tp_paragraph *, sh_context *, const uint16_t *, size_t,
               float size, const char *locale, float *advances, size_t capacity,
               br_cancel, void *);
void tp_close(tp_paragraph *);
/* Reuse the same captured measurement for widths. Scratch holds length+1
 * candidates/scores; lines require length+1 slots. No allocation except the
 * word iterator. Result is unpublished and must be discarded on any error. */
int tp_break(const tp_paragraph *, float width, br_candidate *, br_scratch *,
             size_t scratch_capacity, br_result *, br_cancel, void *);
/* Aggregate fallback extent for a logical line using paragraph direction levels,
 * as MeasuredText::getExtent does before visual-line trailing-space resets.
 * The caller admits/holds fonts for this call; no output font lease survives. */
int tp_extent(const tp_paragraph *, sh_context *, size_t start, size_t end,
              sh_extent *, br_cancel, void *);
#endif
