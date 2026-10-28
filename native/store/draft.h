#ifndef TROUGH_DRAFT_H
#define TROUGH_DRAFT_H
#include "document.h"

/* Input persistence queue between the interaction owner (IME/input thread) and the storage
 * owner. See docs/DRAFTS.md. The interaction side never waits for SQLite: ti_edit applies a
 * batch to the live value and logs it. The storage side captures the newest live value,
 * runs td_save_text outside the queue's lock, then acknowledges or rejects that exact local
 * revision. The live value is a tx_text (docs/TEXT.md): an edit is O(log n + inserted) and a
 * capture retains an immutable snapshot instead of copying the text. Saves coalesce: one capture is in flight at a time and the next one carries
 * every later edit. A returning storage snapshot never replaces the live draft.
 *
 * External durable revisions (another device, an agent edit) are rebased: the queue keeps
 * the last acknowledged durable text, diffs it against the new durable text into one
 * replacement, transforms that through the unacknowledged local batches and applies it to
 * the live value. Overlap with local edits or the active composition, or an overflowed log,
 * is a conflict: the live value is left untouched and saves stop until the owner resolves. */
typedef struct ti_queue ti_queue;
typedef enum { TI_CLEAN = 0, TI_DIRTY, TI_SAVING, TI_CONFLICT } ti_phase;
typedef struct {
    ti_phase phase;
    uint64_t local;        /* live revision, advanced by every accepted edit or rebase */
    uint64_t saved;        /* newest local revision acknowledged durable */
    uint64_t durable;      /* durable document revision the saved/base value is */
    uint64_t external;     /* count of external revisions merged into the live value */
    size_t length;         /* live length in UTF-16 units */
    size_t logged;         /* bytes of unacknowledged batches retained for rebasing */
    unsigned overflowed;   /* log limit exceeded: any external revision becomes a conflict */
} ti_state;
typedef struct {
    uint64_t local;        /* pass back to ti_acknowledge / ti_reject */
    ts_read expected;      /* the exact durable input for td_save */
    td_text_value value;   /* value.text is a reference the caller releases after saving */
} ti_captured;

/* Opens over a loaded durable snapshot (td_load_text); the queue takes its own reference.
 * log_limit bounds retained batch bytes (0 = 1 MiB). */
ts_status ti_open(ti_queue **, const td_snapshot *, tx_text *text, size_t log_limit);
void ti_close(ti_queue *);

/* Interaction owner. Same contract as td_apply; on success local names the new revision.
 * Rejected while in conflict: the owner must resolve first, so no edit is silently lost. */
ts_status ti_edit(ti_queue *, const td_edit *, size_t count, uint32_t anchor, uint32_t caret,
                  int32_t composing_start, int32_t composing_end, uint64_t *local);
/* Copies the live value. TS_CAPACITY still fills state and value metadata. */
ts_status ti_view(ti_queue *, uint16_t *text, size_t capacity, td_value *, ti_state *);
/* O(1) snapshot of the live value for any reader (layout, agents); release value->text. */
ts_status ti_snapshot(ti_queue *, td_text_value *, ti_state *);
void ti_state_get(ti_queue *, ti_state *);

/* Storage owner; its calls are not concurrent with each other. TS_NOT_FOUND: nothing to save.
 * TS_CONFLICT: resolve first. TS_INVALID: a capture is already in flight. */
ts_status ti_capture(ti_queue *, ti_captured *);
/* receipt is td_save's TS_OK snapshot for that capture. Edits made after the capture stay
 * pending; the batches it covered are dropped from the log. */
ts_status ti_acknowledge(ti_queue *, uint64_t local, const td_snapshot *receipt);
/* td_save failed. TS_CONFLICT from storage means the durable document moved: load the
 * current revision and pass it to ti_rebase. Other failures leave the edits pending. */
ts_status ti_reject(ti_queue *, uint64_t local);
/* Merge a newer durable revision. TS_OK: merged (or already current). TS_CONFLICT: the
 * queue entered TI_CONFLICT and the live value is unchanged. */
ts_status ti_rebase(ti_queue *, const td_snapshot *current, tx_text *text);
/* Explicit owner decision after a conflict: keep the live value and save it over the
 * given durable revision (which must be the one passed to ti_rebase). */
ts_status ti_resolve_keep(ti_queue *, uint64_t durable);
#endif
