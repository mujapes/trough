#ifndef TROUGH_TEXT_BREAKS_H
#define TROUGH_TEXT_BREAKS_H
#include <stddef.h>
#include <stdint.h>

/* High-quality, constant-width, unhyphenated/unjustified paragraph scoring.
 * Candidate zero is {0,0,0,0}; the last candidate ends the paragraph.
 * pre includes trailing spaces, post excludes them. Widths accumulate in float, as in the pinned Minikin release.
 * Inputs, scratch and output have distinct storage owned by one admitted job.
 * Word/cluster candidate generation and tab layout belong to separate operators. */
typedef struct {
    uint32_t offset;
    float pre, post;
    float penalty;
} br_candidate;
typedef struct { float score; uint32_t previous, lines; } br_scratch;
typedef struct { uint32_t offset, candidate; float width; } br_line;
typedef struct { br_line *lines; size_t capacity, count; } br_result;
typedef int (*br_cancel)(void *context);

/* 0 success (count can exceed capacity: retry); -1 invalid input/numeric range;
 * -2 cancelled. No output lines are written on error/cancellation; count is zero.
 * Scratch must hold n entries. A count-only call may use capacity zero.
 * Cancellation is sampled during validation/scoring; publishing the completed
 * result still requires the caller's generation check. No allocation or globals. */
int br_optimal(const br_candidate *c, size_t n, float width, float line_penalty,
               br_scratch *scratch, size_t scratch_capacity, br_result *out,
               br_cancel cancelled, void *context);
#endif
