#ifndef TROUGH_EXEC_H
#define TROUGH_EXEC_H
#include "derive.h"
#include "blobs.h"
#include "resources.h"
#include <pthread.h>
#include <stdatomic.h>

/* Bounded execution of a derivation graph (docs/EXEC.md). One owner thread holds the world
 * and the graph; nothing else touches them. Other threads act through ex_call, which runs a
 * function on the owner thread. A fixed pool of workers runs registered operators on job
 * snapshots. When the last poll no longer reaches a running job's node, the job is withdrawn
 * and its cancel flag is raised; operators check ex_cancelled and stop early.
 * Every finished, needing or cancelled job leaves a trace record. */
#define EX_VALUES 256
#define EX_WORKERS 8
#define EX_SLOTS (2 * EX_WORKERS)
#define EX_TRACE 1024

/* What an operator writes: a value and the refs it read, or the refs it needs. */
/* Instead of value, an operator may return bytes (malloc'd; ownership passes to the executor),
 * which the owner interns into a canonical blob handle (ex_use_blobs). */
typedef struct { uint32_t kind; uint64_t value; dv_ref refs[EX_VALUES]; size_t count; int overflow; void *blob; size_t blob_length; } ex_out;
/* An operator: a pure function of the job. Returns 0, or nonzero when it stopped because it
 * was cancelled. ctx is the registration's. */
typedef int (*ex_operator)(void *ctx, const dv_job *, ex_out *);
/* An operator's working memory for a job, in bytes, estimated from the job before it runs
 * (its inputs are in the job). 0: not counted. Called on the owner thread. */
typedef uint64_t (*ex_cost)(void *ctx, const dv_job *);

typedef enum { EX_TRACE_VALUE = 1, EX_TRACE_NEED, EX_TRACE_CANCELLED, EX_TRACE_STALE, EX_TRACE_INVALID } ex_trace_outcome;
typedef struct {
    uint32_t node, worker, outcome, level; tr_id op;   /* level: when started */
    uint64_t ticket, revision, queued_ns, start_ns, end_ns;
} ex_trace;

typedef enum { EX_FREE = 0, EX_READY, EX_RUNNING, EX_DONE } ex_slot_state;
typedef struct {
    dv_job job; dv_value values[EX_VALUES]; ex_out out;
    ex_slot_state state; atomic_int cancel; int stopped;
    uint32_t worker, level; uint64_t order, queued_ns, start_ns, end_ns;   /* level: current */
    uint64_t bytes, charged; tr_id admitted;   /* admission (ex_set_budget): estimate, ledger charge, its resource */
} ex_slot;

typedef struct ex_call_s ex_call_t;
typedef struct { void *ex; uint32_t id; } ex_worker;
typedef struct {
    tr_world *world; dv_graph *graph;
    struct { tr_id op; ex_operator fn; void *ctx; ex_cost cost; } ops[32]; size_t nops;
    ex_slot slots[EX_SLOTS];
    pthread_t owner, workers[EX_WORKERS]; size_t nworkers;
    ex_worker worker_ctx[EX_WORKERS];
    void *slot_ctx[EX_SLOTS]; ex_operator slot_fn[EX_SLOTS];
    /* Admission by bytes (ex_set_budget): running jobs' working memory is charged to a resource
     * ledger (resources.h) whose byte limit is the budget; background jobs leave bg_reserve. */
    tr_resources ledger; tr_resource ledger_res[EX_SLOTS]; tr_lease ledger_leases[EX_SLOTS];
    uint64_t budget, bg_reserve, peak_charged, deferred; int admission;
    pthread_mutex_t lock; pthread_cond_t owner_wake, worker_wake, settled;
    ex_call_t *calls, *calls_tail;
    int stop, idle;                  /* idle: verified at `idle_at` with nothing running */
    uint64_t idle_at;
    uint64_t settled_at[DV_LEVELS];  /* levels <= l verified at this publication (ex_settle_at) */
    tr_status error;                 /* sticky: a graph call failed (e.g. TR_CAPACITY) */
    ex_trace trace[EX_TRACE]; uint64_t traced;
    uint64_t jobs, cancelled, stale, issued;
    size_t reserve;                  /* workers background jobs may not occupy (ex_set_reserve) */
    void (*on_settled)(void *, uint64_t publication); void *settled_ctx;
    bl_store *blobs;
    void (*thread_start)(void *), (*thread_stop)(void *); void *thread_hooks;
} ex_exec;

/* Registration happens before ex_start. */
tr_status ex_init(ex_exec *, tr_world *, dv_graph *, size_t workers);
tr_status ex_register(ex_exec *, tr_id op, ex_operator, void *ctx);
/* Declares op's working memory per job (after ex_register for op). */
tr_status ex_register_cost(ex_exec *, tr_id op, ex_cost);
/* Admission by bytes (docs/EXEC.md "Admission"): a ready job starts only while the running
 * jobs' estimated bytes plus its own fit `budget`; background jobs only while they fit
 * budget - bg_reserve, so urgent and visible work keeps that headroom. A job larger than the
 * budget runs alone. Jobs are admitted in priority order: one that does not fit holds back
 * every job after it at its level and below, so a large job cannot be starved by small ones.
 * 0: no admission (the default). Before ex_start. */
tr_status ex_set_budget(ex_exec *, uint64_t budget, uint64_t bg_reserve);
/* Blob values: node values become owned by the store through graph hooks, job inputs are
 * retained until the job retires, and operators may return bytes. Before ex_start. */
void ex_use_blobs(ex_exec *, bl_store *);
/* Run on each worker thread as it starts and before it exits (e.g. attach to a JVM). */
void ex_on_worker(ex_exec *, void (*start)(void *), void (*stop)(void *), void *ctx);
/* on_settled runs on the owner thread each time demand is verified at a new publication. */
void ex_on_settled(ex_exec *, void (*fn)(void *, uint64_t), void *ctx);
/* Priorities: workers start the most urgent ready job first (dv_level; issue order within a
 * level), and a job's level follows its demand while it waits. DV_BACKGROUND jobs in flight
 * are limited to workers - reserve (at least 1), so `reserve` workers are kept for urgent and
 * visible work. Default 1. Before ex_start. */
void ex_set_reserve(ex_exec *, size_t reserve);
tr_status ex_start(ex_exec *);
/* Runs fn(arg, world, graph) on the owner thread and waits for it. */
void ex_call(ex_exec *, void (*fn)(void *, tr_world *, dv_graph *), void *arg);
/* Waits until every demanded node is verified at a publication >= the one current when called
 * and no job runs. Returns that publication, or 0 after a sticky error (ex->error). */
uint64_t ex_settle(ex_exec *);
/* ex_settle for the roots at levels <= level only: background work may still run. */
uint64_t ex_settle_at(ex_exec *, uint32_t level);
void ex_stop(ex_exec *);

/* For operators: the job's value for ref (0 when the job was not given it), recording the
 * read in out; ex_need asks for it. */
int ex_read(const dv_job *, ex_out *, dv_ref, uint64_t *value, uint32_t *presence);
void ex_need(ex_out *, dv_ref);
int ex_cancelled(const dv_job *);
/* Copies up to n of the newest trace records (oldest first) inside an owner call. */
size_t ex_trace_copy(const ex_exec *, ex_trace *, size_t n);
#endif
