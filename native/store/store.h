#ifndef TROUGH_STORE_H
#define TROUGH_STORE_H
#include <stddef.h>
#include <stdint.h>

/* Durable meaning and effects (DESIGN.md "Effects, storage and external systems",
 * docs/CONSTRUCTION.md stage 2). One owner opens one store; calls are not thread-safe.
 *
 * Records: a durable id (never reused), a schema, and immutable revisions. A commit validates
 * every captured read revision and write target, then installs all writes and effect
 * transitions in one SQLite transaction, or none. Returning TS_OK means durable: WAL with
 * synchronous=FULL, so the commit was fsynced before the receipt exists.
 *
 * Effects: prepared -> authorised -> issued -> observed, with cancelled, failed and unknown.
 * Reopening after a process death turns every issued effect into unknown: its outcome was
 * lost, and nothing here re-issues it. Only explicit reconciliation leaves unknown.
 *
 * Objects: immutable content-addressed blobs (SHA-256) under <dir>/objects, referenced from
 * records by hash. Never evicted here; retention policy belongs to the records referencing them.
 */
typedef enum {
    TS_OK = 0, TS_INVALID, TS_CONFLICT, TS_NOT_FOUND, TS_FULL, TS_IO, TS_CAPACITY
} ts_status;

typedef enum {
    TS_PREPARED = 1, TS_AUTHORISED, TS_ISSUED, TS_OBSERVED, TS_CANCELLED, TS_FAILED, TS_UNKNOWN
} ts_effect_state;

typedef struct ts_store ts_store;

/* Captured input: id at revision. revision 0 asserts it never existed; tombstones keep revisions. */
typedef struct { uint64_t id, revision; } ts_read;
/* id 0 creates a record; its new id is written back into id. remove ignores data/schema. */
typedef struct {
    uint64_t id;
    uint32_t schema;
    const void *data;
    size_t size;
    unsigned remove;
} ts_write;
/* Effect intent ids are caller-chosen (16 bytes) so a retried prepare is idempotent, never doubled.
 * from == 0 prepares a new intent (to must be TS_PREPARED); otherwise from and kind must
 * equal its current state and immutable kind. Kind matching is not capability validation:
 * the trusted effect owner still checks authority before authorising/issuing. */
typedef struct {
    uint8_t intent[16];
    ts_effect_state from, to;
    uint32_t kind;
    const void *detail;    /* request on prepare, outcome on observed/failed; bounded like data */
    size_t size;
} ts_effect;
typedef struct { uint64_t commit; size_t changed; } ts_receipt;
typedef struct {
    uint64_t id, revision, commit;
    uint32_t schema;
    size_t size;           /* full size, even when larger than the caller's buffer */
} ts_record;

/* Durable outbox entry. Request bytes never change; outcome is present only after an
 * observed/failed transition (an empty outcome is distinct from no outcome). commit is the
 * latest transition's durable receipt, including issued -> unknown during recovery. */
typedef struct {
    uint8_t intent[16];
    ts_effect_state state;
    uint32_t kind;
    uint64_t commit;
    size_t request_size, outcome_size;
    unsigned has_outcome;
} ts_effect_record;

enum { TS_DATA_LIMIT = 1 << 20, TS_EFFECT_PAGE_LIMIT = 256 };

/* Creates <dir>/records.db and <dir>/objects as needed, migrates the schema, recovers. */
ts_status ts_open(const char *dir, ts_store **out);
void ts_close(ts_store *);
/* Storage quota in database pages (0 = unlimited). Exceeding it fails a commit with TS_FULL. */
ts_status ts_limit_pages(ts_store *, uint64_t pages);
/* After reopen: how many issued effects recovery marked unknown. */
size_t ts_recovered_unknown(const ts_store *);

ts_status ts_get(ts_store *, uint64_t id, ts_record *, void *buffer, size_t capacity);
/* A past revision, for history and for validating derived results against what they read. */
ts_status ts_get_revision(ts_store *, uint64_t id, uint64_t revision, ts_record *, void *buffer, size_t capacity);
ts_status ts_commit(ts_store *, const ts_read *, size_t, ts_write *, size_t,
                    const ts_effect *, size_t, ts_receipt *);
/* Stable semantic keys are assigned to newly created records inside the same transaction.
 * Keys and their record association are immutable, including after removal. All-zero is legal.
 * A duplicate key conflicts and rolls back every write/effect. index names a write with id 0.
 * This permits atomic linked records without predicting SQLite's allocated record ids. */
typedef struct { size_t index; uint8_t key[16]; } ts_name;
ts_status ts_commit_named(ts_store *, const ts_read *, size_t, ts_write *, size_t,
                          const ts_effect *, size_t, const ts_name *, size_t, ts_receipt *);
ts_status ts_lookup(ts_store *, const uint8_t key[16], uint64_t *id);
ts_status ts_effect_get(ts_store *, const uint8_t intent[16], ts_effect_state *state, uint32_t *kind);
/* On TS_CAPACITY, metadata gives both required lengths and neither buffer is changed.
 * Returned bytes are owned by the caller, not borrowed from a SQLite statement. */
ts_status ts_effect_read(ts_store *, const uint8_t intent[16], ts_effect_record *,
                         void *request, size_t request_capacity, void *outcome, size_t outcome_capacity);
/* Bounded keyset scan, lexicographically ordered by the 16 intent bytes, strictly after
 * after (NULL starts a sweep, including the all-zero id). state 0 includes every state.
 * capacity must be 1..TS_EFFECT_PAGE_LIMIT. A short page ends the sweep. No payloads are
 * loaded. Each call sees current committed state, not a snapshot spanning calls: repeat
 * sweeps to discover new/changed entries behind the cursor, and revalidate state before
 * dispatch. Scanning grants no authority, and unknown outcomes must never be retried here.
 * count is zero on failure; entries are valid only on TS_OK. */
ts_status ts_effect_scan(ts_store *, ts_effect_state state, const uint8_t after[16],
                         ts_effect_record *entries, size_t capacity, size_t *count);

ts_status ts_object_put(ts_store *, const void *, size_t, uint8_t hash[32]);
/* Verifies content against its hash on every read; corruption is TS_IO, never wrong bytes. */
ts_status ts_object_get(ts_store *, const uint8_t hash[32], void *buffer, size_t capacity, size_t *size);

void ts_sha256(const void *, size_t, uint8_t out[32]);
#endif
