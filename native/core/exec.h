#ifndef TROUGH_EXEC_H
#define TROUGH_EXEC_H
#include "derive.h"
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
typedef struct { uint32_t kind; uint64_t value; dv_ref refs[EX_VALUES]; size_t count; int overflow; } ex_out;
/* An operator: a pure function of the job. Returns 0, or nonzero when it stopped because it
 * was cancelled. ctx is the registration's. */
typedef int (*ex_operator)(void *ctx, const dv_job *, ex_out *);

typedef enum { EX_TRACE_VALUE = 1, EX_TRACE_NEED, EX_TRACE_CANCELLED, EX_TRACE_STALE, EX_TRACE_INVALID } ex_trace_outcome;
typedef struct {
    uint32_t node, worker, outcome; tr_id op;
    uint64_t ticket, revision, queued_ns, start_ns, end_ns;
} ex_trace;

typedef enum { EX_FREE = 0, EX_READY, EX_RUNNING, EX_DONE } ex_slot_state;
typedef struct {
    dv_job job; dv_value values[EX_VALUES]; ex_out out;
    ex_slot_state state; atomic_int cancel; int stopped;
    uint32_t worker; uint64_t queued_ns, start_ns, end_ns;
} ex_slot;

typedef struct ex_call_s ex_call_t;
typedef struct { void *ex; uint32_t id; } ex_worker;
typedef struct {
    tr_world *world; dv_graph *graph;
    struct { tr_id op; ex_operator fn; void *ctx; } ops[32]; size_t nops;
    ex_slot slots[EX_SLOTS];
    pthread_t owner, workers[EX_WORKERS]; size_t nworkers;
    ex_worker worker_ctx[EX_WORKERS];
    void *slot_ctx[EX_SLOTS]; ex_operator slot_fn[EX_SLOTS];
    pthread_mutex_t lock; pthread_cond_t owner_wake, worker_wake, settled;
    ex_call_t *calls, *calls_tail;
    int stop, idle;                  /* idle: verified at `idle_at` with nothing running */
    uint64_t idle_at;
    tr_status error;                 /* sticky: a graph call failed (e.g. TR_CAPACITY) */
    ex_trace trace[EX_TRACE]; uint64_t traced;
    uint64_t jobs, cancelled, stale;
    void (*on_settled)(void *, uint64_t publication); void *settled_ctx;
} ex_exec;

/* Registration happens before ex_start. */
tr_status ex_init(ex_exec *, tr_world *, dv_graph *, size_t workers);
tr_status ex_register(ex_exec *, tr_id op, ex_operator, void *ctx);
/* on_settled runs on the owner thread each time demand is verified at a new publication. */
void ex_on_settled(ex_exec *, void (*fn)(void *, uint64_t), void *ctx);
tr_status ex_start(ex_exec *);
/* Runs fn(arg, world, graph) on the owner thread and waits for it. */
void ex_call(ex_exec *, void (*fn)(void *, tr_world *, dv_graph *), void *arg);
/* Waits until every demanded node is verified at a publication >= the one current when called
 * and no job runs. Returns that publication, or 0 after a sticky error (ex->error). */
uint64_t ex_settle(ex_exec *);
void ex_stop(ex_exec *);

/* For operators: the job's value for ref (0 when the job was not given it), recording the
 * read in out; ex_need asks for it. */
int ex_read(const dv_job *, ex_out *, dv_ref, uint64_t *value, uint32_t *presence);
void ex_need(ex_out *, dv_ref);
int ex_cancelled(const dv_job *);
/* Copies up to n of the newest trace records (oldest first) inside an owner call. */
size_t ex_trace_copy(const ex_exec *, ex_trace *, size_t n);
#endif
