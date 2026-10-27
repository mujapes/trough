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

/* Captured input: id at revision. revision 0 asserts the record does not exist (or was removed). */
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
 * from == 0 prepares a new intent (to must be TS_PREPARED); otherwise from must equal its state. */
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

enum { TS_DATA_LIMIT = 1 << 20 };

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
ts_status ts_effect_get(ts_store *, const uint8_t intent[16], ts_effect_state *state, uint32_t *kind);

ts_status ts_object_put(ts_store *, const void *, size_t, uint8_t hash[32]);
/* Verifies content against its hash on every read; corruption is TS_IO, never wrong bytes. */
ts_status ts_object_get(ts_store *, const uint8_t hash[32], void *buffer, size_t capacity, size_t *size);

void ts_sha256(const void *, size_t, uint8_t out[32]);
#endif
