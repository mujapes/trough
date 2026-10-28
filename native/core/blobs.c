#include "blobs.h"
#include <stdlib.h>
#include <string.h>

#define TOMB UINT32_MAX
#define SLOT_BITS 24
#define GEN_MAX ((1ULL << (63 - SLOT_BITS)) - 1)

static uint64_t hash_bytes(const void *p, size_t n) {       /* FNV-1a, then a finaliser */
    const unsigned char *b = p;
    uint64_t h = 0xcbf29ce484222325ULL ^ n;
    for (size_t i = 0; i < n; i++) { h ^= b[i]; h *= 0x100000001b3ULL; }
    h ^= h >> 33; h *= 0xff51afd7ed558ccdULL; h ^= h >> 33;
#ifdef BL_WEAK_HASH
    h &= 15;                                          /* tests: constant collisions */
#endif
    return h;
}
static uint64_t encode(uint32_t slot, uint64_t generation) { return BL_TAG | (generation << SLOT_BITS) | slot; }
/* The entry a handle names, without reading `references` (which the owner changes while other
 * threads read held handles); the caller checks liveness where it is owner-side. */
static bl_entry *slot_of(const bl_store *s, uint64_t handle) {
    if (!s || !bl_is(handle)) return NULL;
    uint32_t slot = (uint32_t)(handle & ((1u << SLOT_BITS) - 1));
    uint64_t generation = (handle & ~BL_TAG) >> SLOT_BITS;
    if (slot >= s->capacity) return NULL;
    bl_entry *e = &s->entries[slot];
    return e->generation == generation ? e : NULL;
}
static bl_entry *decode(const bl_store *s, uint64_t handle) {
    bl_entry *e = slot_of(s, handle);
    return e && e->references ? e : NULL;
}
/* Tombstones only grow until a rebuild: rehash live entries when empties run low. */
static void rebuild(bl_store *s) {
    memset(s->index, 0, s->index_capacity * sizeof *s->index);
    size_t mask = s->index_capacity - 1;
    for (uint32_t k = 0; k < s->capacity; k++) {
        if (!s->entries[k].references) continue;
        size_t i = s->entries[k].hash & mask;
        while (s->index[i]) i = (i + 1) & mask;
        s->index[i] = k + 1;
    }
    s->tombstones = 0;
}

tr_status bl_init(bl_store *s, bl_entry *entries, size_t capacity, uint32_t *index, size_t index_capacity, uint64_t byte_limit) {
    if (!s || !entries || !index || !capacity || capacity >= (1u << SLOT_BITS) || index_capacity < 2 * capacity
        || (index_capacity & (index_capacity - 1))) return TR_INVALID;
    memset(s, 0, sizeof *s);
    memset(entries, 0, capacity * sizeof *entries);
    memset(index, 0, index_capacity * sizeof *index);
    s->entries = entries; s->capacity = capacity; s->index = index; s->index_capacity = index_capacity; s->byte_limit = byte_limit;
    return TR_OK;
}

tr_status bl_intern(bl_store *s, void *data, size_t length, int take, uint64_t *handle) {
    if (!s || !handle || (length && !data)) { if (take) free(data); return TR_INVALID; }
    uint64_t h = hash_bytes(data, length);
    if ((s->live + s->tombstones + 1) * 4 > s->index_capacity * 3) rebuild(s);
    size_t mask = s->index_capacity - 1, i = h & mask, tomb = SIZE_MAX;
    for (;;) {
        uint32_t x = s->index[i];
        if (!x) break;
        if (x == TOMB) { if (tomb == SIZE_MAX) tomb = i; }
        else {
            bl_entry *e = &s->entries[x - 1];
            if (e->hash == h && e->length == length && (!length || !memcmp(e->data, data, length))) {
                if (take) free(data);
                e->references++; s->deduplicated++;
                *handle = encode(x - 1, e->generation);
                return TR_OK;
            }
        }
        i = (i + 1) & mask;
    }
    if (length > s->byte_limit - s->bytes) { if (take) free(data); return TR_CAPACITY; }
    uint32_t slot = UINT32_MAX;
    for (uint32_t k = 0; k < s->capacity; k++)
        if (!s->entries[k].references && s->entries[k].generation < GEN_MAX) { slot = k; break; }
    if (slot == UINT32_MAX) { if (take) free(data); return TR_CAPACITY; }
    void *owned = data;
    if (!take) {
        owned = malloc(length ? length : 1);
        if (!owned) return TR_CAPACITY;
        if (length) memcpy(owned, data, length);
    }
    bl_entry *e = &s->entries[slot];
    e->data = owned; e->length = length; e->hash = h; e->generation++; e->references = 1; e->owned = owned;
    if (tomb != SIZE_MAX) { s->index[tomb] = slot + 1; s->tombstones--; } else s->index[i] = slot + 1;
    s->bytes += length; s->live++; s->interned++;
    *handle = encode(slot, e->generation);
    return TR_OK;
}

tr_status bl_retain(bl_store *s, uint64_t handle) {
    bl_entry *e = decode(s, handle);
    if (!e) return TR_STALE;
    e->references++;
    return TR_OK;
}

tr_status bl_release(bl_store *s, uint64_t handle) {
    bl_entry *e = decode(s, handle);
    if (!e) return TR_STALE;
    if (--e->references) return TR_OK;
    size_t mask = s->index_capacity - 1, i = e->hash & mask;
    uint32_t slot = (uint32_t)(e - s->entries);
    while (s->index[i] != slot + 1) i = (i + 1) & mask;
    s->index[i] = TOMB; s->tombstones++;
    s->bytes -= e->length; s->live--;
    free(e->owned);
    e->data = NULL; e->owned = NULL; e->length = 0;
    return TR_OK;
}

tr_status bl_get(const bl_store *s, uint64_t handle, const void **data, size_t *length) {
    const bl_entry *e = slot_of(s, handle);           /* held: generation and bytes are stable */
    if (!e || !data || !length) return TR_STALE;
    *data = e->data; *length = e->length;
    return TR_OK;
}

void bl_hook_retain(void *store, uint64_t value) { if (bl_is(value)) bl_retain(store, value); }
void bl_hook_release(void *store, uint64_t value) { if (bl_is(value)) bl_release(store, value); }
