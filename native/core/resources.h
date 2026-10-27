#ifndef TROUGH_RESOURCES_H
#define TROUGH_RESOURCES_H
#include "world.h"

/* Owner-serialized, fixed-capacity resource ledger. Handles are scoped to this pool.
 * Each issued lease has its OWN generation: copying a token cannot release twice.
 * Device work holds a lease until actual completion/fence acknowledgement, not cancellation.
 * Cancellation and retirement deny new admission; existing borrowers remain valid.
 * Destruction is two-phase so a slot/budget cannot be reused before actual cleanup finishes.
 */
typedef enum { TR_FREE, TR_LIVE, TR_RETIRED, TR_REAPING } tr_resource_state;
typedef struct {
    uint64_t generation, identity, payload, bytes, references;
    uint32_t kind;
    tr_resource_state state;
} tr_resource;
typedef struct { uint64_t generation; tr_id resource; unsigned live; } tr_lease;
typedef struct {
    tr_resource *resources;
    tr_lease *leases;
    size_t resource_capacity, lease_capacity;
    uint64_t byte_limit, retained_bytes, next_identity;
} tr_resources;
typedef struct {
    tr_id id;
    uint64_t identity, payload, bytes;
    uint32_t kind;
} tr_resource_info;

tr_status tr_resources_init(tr_resources *, tr_resource *, size_t,
                            tr_lease *, size_t, uint64_t byte_limit);
tr_status tr_resource_create(tr_resources *, uint32_t kind, uint64_t payload,
                             uint64_t bytes, tr_id *out);
tr_status tr_resource_get(const tr_resources *, tr_id, tr_resource_info *);
tr_status tr_resource_acquire(tr_resources *, tr_id resource, tr_id *lease);
/* Existing work may extend its lifetime after retirement, e.g. a callback still running.
 * New work cannot acquire the retired resource without an existing valid lease. */
tr_status tr_lease_clone(tr_resources *, tr_id lease, tr_id *out);
tr_status tr_lease_get(const tr_resources *, tr_id lease, tr_resource_info *);
tr_status tr_lease_release(tr_resources *, tr_id lease);
tr_status tr_resource_retire(tr_resources *, tr_id);
/* TR_CAPACITY means there is currently nothing ready to destroy. One returned ticket pins
 * metadata in REAPING until done. No destructor callbacks run inside the ledger. */
tr_status tr_reap_begin(tr_resources *, tr_resource_info *ticket);
tr_status tr_reap_done(tr_resources *, tr_id ticket);
#endif
