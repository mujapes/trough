#ifndef TROUGH_STREAM_H
#define TROUGH_STREAM_H
#include "document.h"

/* Append-only semantic ordering/provenance on the shared store, not a UI event queue.
 * A mutable head and an immutable, named entry publish in one durable transaction.
 * Record revisions referenced here must be retained. All calls belong to the store owner. */
enum { TL_HEAD_SCHEMA = 0x54530101, TL_ENTRY_SCHEMA = 0x54530201,
       TL_PROVENANCE_LIMIT = 32, TL_PAGE_LIMIT = 64 };
typedef struct {
    uint32_t kind;                  /* concrete stream family, interpreted by its owner */
    uint64_t created_ms;            /* caller event time; never used to determine order */
    td_reference target;           /* exact document/task/source/artifact revision */
    size_t count;
    td_reference provenance[TL_PROVENANCE_LIMIT];
} tl_value;
typedef struct { ts_record record; uint64_t length; uint8_t last[16]; } tl_head;
typedef struct {
    ts_record record;
    uint64_t stream, sequence;
    uint8_t key[16], previous[16];
    tl_value value;
} tl_entry;
typedef struct { uint64_t stream, remaining; uint8_t next[16]; } tl_cursor;

/* Create/reopen by a stable nonzero key. Reopening preserves all entries. */
ts_status tl_open(ts_store *, const uint8_t key[16], tl_head *);
ts_status tl_load(ts_store *, uint64_t id, uint64_t revision, tl_head *);
ts_status tl_get(ts_store *, const uint8_t key[16], tl_entry *);
/* Stale heads conflict. Identical key redelivery returns the original entry and commit,
 * even when the head has advanced. Different stream/content under that key conflicts.
 * Provenance resolves immutable historical revisions; later edits do not invalidate it. */
ts_status tl_append(ts_store *, ts_read head, const uint8_t key[16], const tl_value *, tl_entry *);
/* Freeze a traversal at this exact head. Page newest-to-oldest, then reverse for display.
 * Concurrent appends cannot move the cursor or introduce duplicate/skipped entries.
 * count=0 on failure; cursor advances only on success, outputs valid only on TS_OK. */
ts_status tl_begin(const tl_head *, tl_cursor *);
ts_status tl_page(ts_store *, tl_cursor *, tl_entry *, size_t capacity, size_t *count);
#endif
