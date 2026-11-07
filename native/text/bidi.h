#ifndef TROUGH_TEXT_BIDI_H
#define TROUGH_TEXT_BIDI_H
#include <stddef.h>
#include <stdint.h>
typedef struct bd_paragraph bd_paragraph;
/* One captured paragraph, first-strong/LTR default, and a logical line within it.
 * Results use absolute UTF-16 offsets; runs are in visual order. Caller owns all buffers.
 * ICU state retains the input only during this call and is destroyed before returning.
 * The layout owner splits paragraphs and admits memory before invoking this operator. */
typedef struct { uint32_t start, end, level; } bd_run;
typedef struct {
    bd_run *runs; size_t count, capacity;
    uint8_t *levels; size_t level_capacity; /* one level per UTF-16 unit in the line */
    uint8_t base;
} bd_result;
/* 0 on success (count may exceed run capacity; retry); -1 on invalid input/ICU failure.
 * Empty lines have no runs and keep the paragraph's base level. Failed output is discarded. */
/* Ranges crossing a bidi paragraph boundary (e.g. embedded CR) use Android's
 * resolved-level direction-array path: ICU setLine cannot represent them. */
int bd_line(const uint16_t *text, size_t length, size_t start, size_t end, bd_result *out);
/* One job retains paragraph analysis for measurement and every line extraction.
 * Text stays immutable/alive until close. One worker owns a paragraph; line scratch
 * is reused, so calls on the same handle must not overlap. Returned arrays are
 * caller-owned copies and remain valid after close. NULL means invalid input/ICU
 * or allocation failure. Empty paragraphs are valid. */
bd_paragraph *bd_open(const uint16_t *text, size_t length);
void bd_close(bd_paragraph *);
int bd_extract_line(bd_paragraph *, size_t start, size_t end, bd_result *out);
/* Logical run beginning at offset (0 <= offset < length); advance to out->end.
 * This is the paragraph's level before line-specific trailing-space resets. */
int bd_run_at(const bd_paragraph *, size_t offset, bd_run *out);
#endif
