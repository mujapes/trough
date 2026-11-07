#ifndef TROUGH_TEXT_MEASURE_H
#define TROUGH_TEXT_MEASURE_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
/* Caller owns text[start,start+count) and count advances indexed from start. Raw shaping
 * advances put a cluster's entire width on its first UTF-16 unit. These pure queries allocate
 * nothing and retain nothing. The text and advances must remain immutable during a query.
 * Offsets are absolute UTF-16 offsets; the caller validates start+count against the text
 * length, offset in [start,start+count], finite advances and a valid cursor option.
 * MS_AT returns SIZE_MAX for a non-boundary. Queries operate on one directional run;
 * paragraph bidi ordering and line placement belong to the layout owner. */
enum { MS_AFTER, MS_AT_OR_AFTER, MS_BEFORE, MS_AT_OR_BEFORE, MS_AT };
bool ms_grapheme_break(const float *advances, const uint16_t *text, size_t start, size_t count, size_t offset);
size_t ms_cursor(const float *advances, const uint16_t *text, size_t start, size_t count, size_t offset, int option);
float ms_run_advance(const float *advances, const uint16_t *text, size_t start, size_t count, size_t offset);
size_t ms_offset_for_advance(const float *advances, const uint16_t *text, size_t start, size_t count, float advance);
#endif
