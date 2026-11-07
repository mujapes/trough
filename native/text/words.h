#ifndef TROUGH_TEXT_WORDS_H
#define TROUGH_TEXT_WORDS_H
#include "breaks.h"
/* Generate candidates from captured per-UTF16 advances, using the paragraph's
 * single locale/default line style. Caller reserves length+1 candidates.
 * Tabs need the greedy operator and return -3. Errors/cancellation clear count.
 * ICU iterator lives only for this call; caller retains text and widths. Size is
 * the resolved Minikin size (truncate default non-linear Paint size, as shaping
 * does); passing raw fractional Paint size changes the line/URL penalties.
 * 0 success, -1 invalid input/ICU error, -2 cancelled, -3 tab encountered. */
int br_candidates(const uint16_t *text, const float *advances, size_t length,
                  const char *locale, float width, float size,
                  br_candidate *out, size_t capacity, size_t *count,
                  br_cancel cancelled, void *context);
/* Greedy path required when a paragraph contains tabs. Default word style,
 * constant line width, no hyphenation/letter spacing. Explicit stops are in
 * ascending order; after the last stop use multiples of tab_width (zero -> 0).
 * Reserve length output lines. Errors clear count; caller discards scratch lines. */
int br_greedy(const uint16_t *text, const float *advances, size_t length,
              const char *locale, float width, const float *stops, size_t stop_count,
              float tab_width, br_result *out, br_cancel cancelled, void *context);
#endif
