#ifndef TROUGH_BLOBS_H
#define TROUGH_BLOBS_H
#include "world.h"

/* Interned immutable byte objects for derived values (docs/BLOBS.md). A handle is canonical:
 * two handles are equal exactly when their bytes are, so a derived value that is a handle
 * gets early cutoff for free. Handles carry a tag bit, so they can share a uint64 value space
 * with scalars.
 *
 * Owner-serialised: intern, retain and release run on the owner thread only. Any thread may
 * call bl_get for a handle it holds a reference through (e.g. a job's inputs, which the
 * executor retains until the job retires); an entry's bytes never change or move while
 * referenced. Bytes are bounded by byte_limit: interning past it is TR_CAPACITY (admission). */
#define BL_TAG (1ULL << 63)
typedef struct {
    const void *data; size_t length;
    uint64_t hash, generation, references;
    void *owned;                     /* the allocation to free, when the store owns it */
} bl_entry;
typedef struct {
    bl_entry *entries; size_t capacity;
    uint32_t *index; size_t index_capacity;   /* entry + 1; UINT32_MAX = tombstone */
    uint64_t byte_limit, bytes, live, interned, deduplicated, tombstones;
} bl_store;

/* index_capacity: a power of two >= 2 * capacity; capacity < 2^24. */
tr_status bl_init(bl_store *, bl_entry *, size_t capacity, uint32_t *index, size_t index_capacity, uint64_t byte_limit);
static inline int bl_is(uint64_t value) { return (value & BL_TAG) != 0; }
/* Returns a handle with one reference for the caller. With `take` the store owns `data` (a
 * malloc'd block): it is freed now when equal bytes already exist, else when the entry dies.
 * Without `take` the bytes are copied. On failure a taken block is freed. */
tr_status bl_intern(bl_store *, void *data, size_t length, int take, uint64_t *handle);
tr_status bl_retain(bl_store *, uint64_t handle);
tr_status bl_release(bl_store *, uint64_t handle);
/* Bytes of a handle the caller holds a reference through. TR_STALE for a dead handle. */
tr_status bl_get(const bl_store *, uint64_t handle, const void **data, size_t *length);
/* Value hooks for derive/exec: retain or release when the value is a handle, else nothing. */
void bl_hook_retain(void *store, uint64_t value);
void bl_hook_release(void *store, uint64_t value);
#endif
