#ifndef TROUGH_WORLD_H
#define TROUGH_WORLD_H
#include <stddef.h>
#include <stdint.h>

/* Trusted semantic publication kernel. All world access is externally serialised by its
 * owner. Workers get captured values, NEVER tr_world pointers. Publication is atomic with
 * respect to that owner protocol, not lock-free/thread-safe access to these structs.
 *
 * A value is a scalar or canonical handle to an immutable object owned elsewhere. Equality
 * must imply semantic equality. The kernel does not retain/release the underlying resource;
 * its owner must keep captured inputs alive until work retires, including cancelled work.
 * No filesystem, clocks, effects, allocation, untrusted parsing or Android dependencies.
 */
typedef enum {
    TR_OK = 0, TR_INVALID, TR_CAPACITY, TR_STALE, TR_DUPLICATE, TR_EXHAUSTED
} tr_status;

typedef struct { uint32_t slot; uint64_t generation; } tr_id;
typedef struct { tr_id id; uint64_t revision, value; } tr_read;
typedef struct { tr_id id; uint64_t value; } tr_write;
typedef struct {
    uint64_t generation, revision, value;
    unsigned live;
} tr_cell;
typedef struct {
    tr_cell *cells;
    size_t capacity;
    uint64_t publication;
} tr_world;
typedef struct {
    tr_world *world;
    tr_read *reads;
    tr_write *writes;
    size_t read_count, write_count, read_capacity, write_capacity;
    unsigned active;
    tr_status failure;
} tr_transaction;

typedef struct {
    uint64_t publication;
    size_t changed;
} tr_receipt;

tr_status tr_world_init(tr_world *, tr_cell *storage, size_t capacity);
/* Creation/removal are owner-side graph/schema operations, outside a worker transaction.
 * Slot generation never wraps. Retired exhausted slots are never reused. */
tr_status tr_create(tr_world *, uint64_t value, tr_id *out);
tr_status tr_remove(tr_world *, tr_id);
tr_status tr_current(const tr_world *, tr_id, tr_read *out);

/* A module cell's value identifies immutable code/schema/operator identity. Capturing it is
 * mandatory, so replacement invalidates outstanding jobs just like a changed data input.
 * Storage belongs to the caller; capacities are hard bounds. begin does not allocate. */
tr_status tr_begin(tr_transaction *, tr_world *, tr_id module,
                   tr_read *, size_t read_capacity, tr_write *, size_t write_capacity);
/* Any failed read/write poisons the candidate: commit then refuses ALL its writes.
 * Repeated reads return the originally captured value. They do not silently mix revisions.
 * Separate capture from worker computation; only captured values cross to workers. */
tr_status tr_read_value(tr_transaction *, tr_id, uint64_t *out);
/* Automatically captures the write target to prevent lost updates. Duplicate writes fail. */
tr_status tr_write_value(tr_transaction *, tr_id, uint64_t value);
/* Consumes a transaction on success OR conflict. Validate all reads/targets and overflow
 * BEFORE changing any cell. Unchanged values do not advance revisions or publication.
 * receipt is cleared on failure. No external side effect is performed by commit. */
tr_status tr_commit(tr_transaction *, tr_receipt *);
void tr_abort(tr_transaction *);
#endif
