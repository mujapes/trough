#ifndef TROUGH_DERIVE_H
#define TROUGH_DERIVE_H
#include "world.h"

/* Demand-driven derivation over a tr_world (docs/DERIVE.md). A node is a cached derived value
 * identified by (operator module cell, key). Its operator runs on a worker as a pure function
 * of the values a job carries; it never sees this graph or the world.
 *
 * Verification follows Salsa's red-green algorithm on the world's publication clock: a node
 * verified at revision V is still valid at R if its module cell and every recorded dependency
 * (cell or node, in the order read) are unchanged since V. A recomputed node whose value is
 * equal keeps its changed_at, so dependants stop there (early cutoff).
 * Dependencies are dynamic, by Skyframe's restart model: a job carries the values it was
 * given; when the operator needs one it was not given, it returns the missing refs, and the
 * node is reissued once they are verified. Every job is a snapshot at one revision.
 *
 * Owner-serialised like the world. Fixed capacities, caller storage, no allocation.
 * Values are scalars or canonical handles (equality implies semantic equality); retaining the
 * resources they name is the owner's obligation, as for world values. */
typedef enum { DV_CELL = 1, DV_NODE = 2 } dv_kind;
/* A dependency as the operator names it: a world cell, or a derived node by (op, key). */
typedef struct { uint32_t kind; tr_id id; uint64_t key; } dv_ref;
/* DV_PRESENT: value is current. DV_ABSENT: removed cell. DV_CYCLE: the node is on the
 * dependency path being computed (a cycle); the operator decides what that means. */
typedef enum { DV_PRESENT = 0, DV_ABSENT, DV_CYCLE } dv_presence;
typedef struct { dv_ref ref; uint64_t value; uint32_t presence; } dv_value;

typedef struct {
    uint32_t kind, node;            /* DV_CELL: cell; DV_NODE: node index + generation */
    uint32_t absent;                /* DV_CELL: the cell was removed when read (ids never revive) */
    tr_id cell;
    uint64_t generation;
} dv_dep;
#define DV_BLOCK 8
typedef struct { uint32_t next, count; dv_dep deps[DV_BLOCK]; } dv_block;

typedef struct {
    tr_id op;
    uint64_t key, generation, value, changed_at, verified_at, ticket, sweep, blocked_sweep;
    uint32_t head, count, demand;   /* dependency blocks; demand = owner roots */
    uint8_t live, memo, recompute, running, volatile_, on_path, op_absent;
} dv_node;
typedef struct { uint32_t node, cursor; } dv_frame;

typedef struct {
    tr_world *world;
    dv_node *nodes; size_t node_capacity;
    dv_block *blocks; size_t block_capacity; uint32_t free_block;
    uint32_t *table; size_t table_capacity;      /* (op, key) -> node + 1; UINT32_MAX = tombstone */
    dv_frame *path;                               /* walk stack, node_capacity frames */
    uint64_t next_ticket, sweep, computations;
    size_t interned, tombstones;
    void (*retain)(void *, uint64_t), (*release)(void *, uint64_t); void *hooks;
    tr_id static_ops[16]; size_t nstatic;
} dv_graph;

/* A unit of work: run node's operator (op_value is the module identity) on key with values. */
typedef struct {
    uint32_t node; uint64_t generation, ticket, revision;
    tr_id op; uint64_t op_value, key; int op_present;   /* a removed module: op_value 0 */
    dv_value *values; size_t count, capacity;     /* caller storage */
} dv_job;
/* What a job produced: a value and the refs it actually read (a subset of its values, in
 * order), or the refs it needs and was not given. */
typedef enum { DV_RESULT_VALUE = 1, DV_RESULT_NEED } dv_result_kind;
typedef struct { uint32_t kind; uint64_t value; const dv_ref *refs; size_t count; } dv_result;

typedef enum { DV_IDLE = 0, DV_READY, DV_WAITING } dv_progress;

/* table_capacity must be a power of two >= 2 * node_capacity. */
tr_status dv_init(dv_graph *, tr_world *, dv_node *, size_t node_capacity, dv_block *, size_t block_capacity,
                  uint32_t *table, size_t table_capacity, dv_frame *path);
/* Value ownership: the graph retains a node's value while it is the memo, and releases it when
 * replaced or evicted (e.g. bl_hook_retain/bl_hook_release with a blob store). */
void dv_set_hooks(dv_graph *, void (*retain)(void *, uint64_t), void (*release)(void *, uint64_t), void *ctx);
/* Declares that op's nodes always read the same dependencies (for a given key). On recompute
 * they keep every old dependency as a provided value, verified before the job is issued, so a
 * changed input costs one job instead of one restart per unverified dependency. */
tr_status dv_set_static(dv_graph *, tr_id op);
/* The node for (op, key), created empty if new. TR_CAPACITY when full. */
tr_status dv_intern(dv_graph *, tr_id op, uint64_t key, uint32_t *node);
/* Owner roots: demanded nodes are kept verified by dv_poll. Counted. */
tr_status dv_demand(dv_graph *, uint32_t node);
tr_status dv_release(dv_graph *, uint32_t node);
/* Verifies demanded nodes at the world's current publication and fills up to `capacity` jobs
 * (each with its own values storage). progress: DV_IDLE when every demanded node is verified,
 * DV_WAITING when only running jobs block progress. */
tr_status dv_poll(dv_graph *, dv_job *jobs, size_t capacity, size_t *count, dv_progress *progress);
/* TR_STALE: the job was cancelled or its node evicted (nothing changes). TR_INVALID: the result
 * reads a ref it was not given, or needs one it was given; the node is left to recompute. */
tr_status dv_complete(dv_graph *, const dv_job *, const dv_result *);
/* Withdraws a running job; its late completion is TR_STALE. */
tr_status dv_cancel(dv_graph *, const dv_job *);
/* Whether the job's node was reached by the last dv_poll (a cancellation hint). */
int dv_wanted(const dv_graph *, const dv_job *);
/* The node's value, only when verified at the current publication (TR_STALE otherwise). */
tr_status dv_get(const dv_graph *, uint32_t node, uint64_t *value);
/* Drops a node and its memo. Refused (TR_INVALID) while demanded or running. Dependants see
 * a changed dependency and recompute. */
tr_status dv_evict(dv_graph *, uint32_t node);
#endif
