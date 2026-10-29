#define _POSIX_C_SOURCE 200809L
#include "exec.h"
#include <stdlib.h>
#include <string.h>
#include <time.h>

struct ex_call_s {
    void (*fn)(void *, tr_world *, dv_graph *); void *arg;
    int done; ex_call_t *next;
};

static uint64_t now_ns(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000000u + (uint64_t)t.tv_nsec;
}
static int same_id(tr_id a, tr_id b) { return a.slot == b.slot && a.generation == b.generation; }

tr_status ex_init(ex_exec *e, tr_world *w, dv_graph *g, size_t workers) {
    if (!e || !w || !g || !workers || workers > EX_WORKERS) return TR_INVALID;
    memset(e, 0, sizeof *e);
    e->world = w; e->graph = g; e->nworkers = workers; e->reserve = 1;
    pthread_mutex_init(&e->lock, NULL);
    pthread_cond_init(&e->owner_wake, NULL); pthread_cond_init(&e->worker_wake, NULL); pthread_cond_init(&e->settled, NULL);
    return TR_OK;
}
tr_status ex_register(ex_exec *e, tr_id op, ex_operator fn, void *ctx) {
    if (!e || !fn || e->nops == sizeof e->ops / sizeof e->ops[0]) return TR_CAPACITY;
    e->ops[e->nops].op = op; e->ops[e->nops].fn = fn; e->ops[e->nops].ctx = ctx; e->nops++;
    return TR_OK;
}
void ex_use_blobs(ex_exec *e, bl_store *b) { e->blobs = b; dv_set_hooks(e->graph, bl_hook_retain, bl_hook_release, b); }
void ex_on_worker(ex_exec *e, void (*start)(void *), void (*stop)(void *), void *ctx) {
    e->thread_start = start; e->thread_stop = stop; e->thread_hooks = ctx;
}
void ex_set_reserve(ex_exec *e, size_t reserve) { e->reserve = reserve; }
tr_status ex_register_cost(ex_exec *e, tr_id op, ex_cost cost) {
    for (size_t o = 0; o < e->nops; o++) if (same_id(e->ops[o].op, op)) { e->ops[o].cost = cost; return TR_OK; }
    return TR_INVALID;
}
tr_status ex_set_budget(ex_exec *e, uint64_t budget, uint64_t bg_reserve) {
    if (!budget) { e->admission = 0; return TR_OK; }
    if (bg_reserve >= budget) return TR_INVALID;
    tr_status st = tr_resources_init(&e->ledger, e->ledger_res, EX_SLOTS, e->ledger_leases, EX_SLOTS, budget);
    if (st) return st;
    e->budget = budget; e->bg_reserve = bg_reserve; e->admission = 1;
    return TR_OK;
}
void ex_on_settled(ex_exec *e, void (*fn)(void *, uint64_t), void *ctx) { e->on_settled = fn; e->settled_ctx = ctx; }

/* ---- operator helpers ---- */

static int same_ref(const dv_ref *a, const dv_ref *b) {
    return a->kind == b->kind && same_id(a->id, b->id) && (a->kind != DV_NODE || a->key == b->key);
}
int ex_read(const dv_job *job, ex_out *out, dv_ref ref, uint64_t *value, uint32_t *presence) {
    for (size_t k = 0; k < job->count; k++) {
        const dv_value *v = &job->values[k];
        if (!same_ref(&v->ref, &ref)) continue;
        if (out->count < EX_VALUES) out->refs[out->count++] = ref; else out->overflow = 1;
        if (value) *value = v->value;
        if (presence) *presence = v->presence;
        return 1;
    }
    return 0;
}
void ex_need(ex_out *out, dv_ref ref) {
    if (out->kind != DV_RESULT_NEED) { out->kind = DV_RESULT_NEED; out->count = 0; }
    for (size_t k = 0; k < out->count; k++) if (same_ref(&out->refs[k], &ref)) return;
    if (out->count < EX_VALUES) out->refs[out->count++] = ref; else out->overflow = 1;
}
int ex_cancelled(const dv_job *job) {
    const ex_slot *s = (const ex_slot *)(const void *)job;     /* job is the slot's first member */
    return atomic_load_explicit(&s->cancel, memory_order_relaxed);
}

/* ---- admission (under the lock) ---- */

/* The next job to start: ready jobs in priority order (level, then issue order); the first one
 * whose bytes do not fit stops the walk, so nothing after it overtakes it. Charges the ledger. */
static int admit(ex_exec *e) {
    int order[EX_SLOTS], n = 0;
    for (int i = 0; i < EX_SLOTS; i++) if (e->slots[i].state == EX_READY) order[n++] = i;
    for (int a = 1; a < n; a++)                         /* insertion sort: at most EX_SLOTS */
        for (int b = a; b > 0; b--) {
            const ex_slot *x = &e->slots[order[b - 1]], *y = &e->slots[order[b]];
            if (y->level < x->level || (y->level == x->level && y->order < x->order)) { int t = order[b]; order[b] = order[b - 1]; order[b - 1] = t; }
            else break;
        }
    for (int k = 0; k < n; k++) {
        ex_slot *s = &e->slots[order[k]];
        if (!s->bytes || atomic_load(&s->cancel)) return order[k];   /* a cancelled job skips its operator */
        uint64_t charge = s->bytes < e->budget ? s->bytes : e->budget;   /* larger than the budget: alone */
        uint64_t limit = s->level == DV_BACKGROUND ? e->budget - e->bg_reserve : e->budget;
        if (e->ledger.retained_bytes + charge > limit && !(charge == e->budget && e->ledger.retained_bytes == 0)) { e->deferred++; return -1; }
        if (tr_resource_create(&e->ledger, 1, (uint64_t)order[k], charge, &s->admitted)) { e->deferred++; return -1; }
        s->charged = charge;
        if (e->ledger.retained_bytes > e->peak_charged) e->peak_charged = e->ledger.retained_bytes;
        return order[k];
    }
    return -1;
}
/* A finished job's charge leaves the ledger (retired, then reaped: no destructor to run). */
static int unadmit(ex_exec *e, ex_slot *s) {
    if (!s->charged) return 0;
    tr_resource_info ticket;
    tr_resource_retire(&e->ledger, s->admitted);
    while (tr_reap_begin(&e->ledger, &ticket) == TR_OK) tr_reap_done(&e->ledger, ticket.id);
    s->charged = 0;
    return 1;
}

/* ---- workers ---- */

static void *worker(void *arg) {
    ex_exec *e = ((ex_worker *)arg)->ex;
    uint32_t id = ((ex_worker *)arg)->id;
    if (e->thread_start) e->thread_start(e->thread_hooks);
    pthread_mutex_lock(&e->lock);
    while (!e->stop) {
        int found = e->admission ? admit(e) : -1;
        if (!e->admission)
            for (int i = 0; i < EX_SLOTS; i++) {
                const ex_slot *c = &e->slots[i];
                if (c->state != EX_READY) continue;
                if (found < 0 || c->level < e->slots[found].level || (c->level == e->slots[found].level && c->order < e->slots[found].order)) found = i;
            }
        if (found < 0) { pthread_cond_wait(&e->worker_wake, &e->lock); continue; }
        ex_slot *s = &e->slots[found];
        s->state = EX_RUNNING; s->worker = id; s->start_ns = now_ns();   /* s->level is now fixed */
        ex_operator fn = e->slot_fn[found]; void *ctx = e->slot_ctx[found];
        pthread_mutex_unlock(&e->lock);
        memset(&s->out, 0, sizeof s->out);
        s->out.kind = DV_RESULT_VALUE;
        int stopped = atomic_load(&s->cancel) ? 1 : fn(ctx, &s->job, &s->out);
        pthread_mutex_lock(&e->lock);
        s->stopped = stopped; s->end_ns = now_ns(); s->state = EX_DONE;
        if (unadmit(e, s)) pthread_cond_broadcast(&e->worker_wake);   /* bytes freed: others may fit now */
        pthread_cond_signal(&e->owner_wake);
    }
    pthread_mutex_unlock(&e->lock);
    if (e->thread_stop) e->thread_stop(e->thread_hooks);
    return NULL;
}

/* ---- owner ---- */

static void trace(ex_exec *e, const ex_slot *s, uint32_t outcome) {
    ex_trace *t = &e->trace[e->traced++ % EX_TRACE];
    t->node = s->job.node; t->worker = s->worker; t->outcome = outcome; t->level = s->level; t->op = s->job.op;
    t->ticket = s->job.ticket; t->revision = s->job.revision;
    t->queued_ns = s->queued_ns; t->start_ns = s->start_ns; t->end_ns = s->end_ns;
}

/* A job's inputs are held from issue until it retires, so a worker can read them while the
 * graph moves on. */
static void hold(ex_exec *e, ex_slot *s, int retain) {
    dv_graph *g = e->graph;
    void (*f)(void *, uint64_t) = retain ? g->retain : g->release;
    if (!f) return;
    for (size_t k = 0; k < s->job.count; k++) if (s->job.values[k].presence == DV_PRESENT) f(g->hooks, s->job.values[k].value);
}

static void complete(ex_exec *e, ex_slot *s) {
    uint32_t outcome;
    uint64_t made = 0;                                /* an interned output: our reference */
    if (s->out.blob && !s->stopped && !atomic_load(&s->cancel) && s->out.kind == DV_RESULT_VALUE && !s->out.overflow) {
        void *bytes = s->out.blob;
        s->out.blob = NULL;                           /* bl_intern takes it, success or not */
        tr_status st = e->blobs ? bl_intern(e->blobs, bytes, s->out.blob_length, 1, &made) : TR_INVALID;
        if (st) {
            if (!e->blobs) free(bytes);
            dv_cancel(e->graph, &s->job);
            e->error = st; e->jobs++; trace(e, s, EX_TRACE_INVALID); hold(e, s, 0); s->state = EX_FREE;
            return;
        }
        s->out.value = made;
    }
    free(s->out.blob); s->out.blob = NULL;
    if (s->stopped || atomic_load(&s->cancel)) {
        dv_cancel(e->graph, &s->job);                 /* TR_STALE when already withdrawn */
        outcome = EX_TRACE_CANCELLED; e->cancelled++;
    } else if (s->out.overflow) {
        dv_cancel(e->graph, &s->job);
        e->error = TR_CAPACITY; outcome = EX_TRACE_INVALID;
    } else {
        dv_result r = {s->out.kind, s->out.value, s->out.refs, s->out.count};
        tr_status st = dv_complete(e->graph, &s->job, &r);
        if (st == TR_STALE) { outcome = EX_TRACE_STALE; e->stale++; }
        else if (st) { e->error = st; outcome = EX_TRACE_INVALID; }
        else outcome = r.kind == DV_RESULT_NEED ? EX_TRACE_NEED : EX_TRACE_VALUE;
    }
    if (made) bl_release(e->blobs, made);              /* the memo holds its own reference */
    e->jobs++;
    trace(e, s, outcome);
    hold(e, s, 0);
    s->state = EX_FREE;
}

/* Fills free slots from a poll, and withdraws running jobs the poll no longer reached. */
static dv_progress schedule(ex_exec *e) {
    dv_job jobs[EX_SLOTS]; int index[EX_SLOTS]; size_t free = 0, n = 0;
    for (int i = 0; i < EX_SLOTS; i++) if (e->slots[i].state == EX_FREE) {
        index[free] = i; jobs[free].values = e->slots[i].values; jobs[free].capacity = EX_VALUES; free++;
    }
    size_t background = 0, limits[DV_LEVELS] = {EX_SLOTS, EX_SLOTS, 0};
    for (int i = 0; i < EX_SLOTS; i++)
        if ((e->slots[i].state == EX_READY || e->slots[i].state == EX_RUNNING) && e->slots[i].level == DV_BACKGROUND) background++;
    size_t cap = e->nworkers > e->reserve ? e->nworkers - e->reserve : 1;
    limits[DV_BACKGROUND] = background < cap ? cap - background : 0;
    dv_progress progress;
    tr_status st = dv_poll_levels(e->graph, free ? jobs : NULL, free, limits, &n, &progress);
    if (st) { e->error = st; return DV_IDLE; }
    uint64_t now = now_ns();
    for (size_t k = 0; k < n; k++) {
        ex_slot *s = &e->slots[index[k]];
        s->job = jobs[k];
        e->slot_fn[index[k]] = NULL;
        for (size_t o = 0; o < e->nops; o++)
            if (same_id(e->ops[o].op, s->job.op)) {
                e->slot_fn[index[k]] = e->ops[o].fn; e->slot_ctx[index[k]] = e->ops[o].ctx;
                s->bytes = e->admission && e->ops[o].cost ? e->ops[o].cost(e->ops[o].ctx, &s->job) : 0;
            }
        if (!e->slot_fn[index[k]]) { dv_cancel(e->graph, &s->job); e->error = TR_INVALID; continue; }
        s->charged = 0;
        atomic_store(&s->cancel, 0); s->stopped = 0; s->queued_ns = now; s->start_ns = s->end_ns = 0;
        s->level = s->job.level; s->order = ++e->issued;
        hold(e, s, 1);
        s->state = EX_READY;
    }
    if (n) pthread_cond_broadcast(&e->worker_wake);
    for (int i = 0; i < EX_SLOTS; i++) {
        ex_slot *s = &e->slots[i];
        if ((s->state != EX_READY && s->state != EX_RUNNING) || atomic_load(&s->cancel)) continue;
        uint32_t level = dv_job_level(e->graph, &s->job);
        if (level == DV_LEVELS) {
            dv_cancel(e->graph, &s->job);
            atomic_store(&s->cancel, 1);
        } else if (s->state == EX_READY) s->level = level;
    }
    return progress;
}

static void *owner(void *arg) {
    ex_exec *e = arg;
    uint64_t notified = 0;
    pthread_mutex_lock(&e->lock);
    while (!e->stop) {
        int worked = 0;
        while (e->calls) {
            ex_call_t *c = e->calls;
            e->calls = c->next; if (!e->calls) e->calls_tail = NULL;
            e->idle = 0; memset(e->settled_at, 0, sizeof e->settled_at);   /* a call may demand */
            pthread_mutex_unlock(&e->lock);
            c->fn(c->arg, e->world, e->graph);
            pthread_mutex_lock(&e->lock);
            c->done = 1; worked = 1;
            pthread_cond_broadcast(&e->settled);
        }
        for (int i = 0; i < EX_SLOTS; i++) if (e->slots[i].state == EX_DONE) { complete(e, &e->slots[i]); worked = 1; }
        dv_progress progress = e->error ? DV_IDLE : schedule(e);
        if (!e->error) {
            uint32_t u = dv_unsettled(e->graph); int moved = 0;
            for (uint32_t l = 0; l < u; l++) if (e->settled_at[l] != e->world->publication) { e->settled_at[l] = e->world->publication; moved = 1; }
            if (moved) pthread_cond_broadcast(&e->settled);
        }
        int busy = 0;
        for (int i = 0; i < EX_SLOTS; i++) if (e->slots[i].state != EX_FREE) busy = 1;
        if (progress == DV_IDLE && !busy && !e->calls) {
            uint64_t at = e->world->publication;
            if (!e->idle || e->idle_at != at) { e->idle = 1; e->idle_at = at; pthread_cond_broadcast(&e->settled); }
            if (e->on_settled && notified != at && !e->error) {
                notified = at;
                pthread_mutex_unlock(&e->lock);
                e->on_settled(e->settled_ctx, at);
                pthread_mutex_lock(&e->lock);
                continue;
            }
            if (e->error) pthread_cond_broadcast(&e->settled);
            pthread_cond_wait(&e->owner_wake, &e->lock);
        } else if (!worked && progress != DV_READY) {
            int done = 0;
            for (int i = 0; i < EX_SLOTS; i++) if (e->slots[i].state == EX_DONE) done = 1;
            if (!done && !e->calls) pthread_cond_wait(&e->owner_wake, &e->lock);
        }
    }
    pthread_mutex_unlock(&e->lock);
    return NULL;
}

tr_status ex_start(ex_exec *e) {
    if (!e) return TR_INVALID;
    for (size_t i = 0; i < e->nworkers; i++) {
        e->worker_ctx[i].ex = e; e->worker_ctx[i].id = (uint32_t)i;
        if (pthread_create(&e->workers[i], NULL, worker, &e->worker_ctx[i])) return TR_CAPACITY;
    }
    if (pthread_create(&e->owner, NULL, owner, e)) return TR_CAPACITY;
    return TR_OK;
}

void ex_call(ex_exec *e, void (*fn)(void *, tr_world *, dv_graph *), void *arg) {
    ex_call_t c = {fn, arg, 0, NULL};
    pthread_mutex_lock(&e->lock);
    if (e->calls_tail) e->calls_tail->next = &c; else e->calls = &c;
    e->calls_tail = &c;
    pthread_cond_signal(&e->owner_wake);
    while (!c.done) pthread_cond_wait(&e->settled, &e->lock);
    pthread_mutex_unlock(&e->lock);
}

static void read_publication(void *arg, tr_world *w, dv_graph *g) { (void)g; *(uint64_t *)arg = w->publication; }
uint64_t ex_settle(ex_exec *e) {
    uint64_t target;
    ex_call(e, read_publication, &target);
    pthread_mutex_lock(&e->lock);
    while (!e->error && !(e->idle && e->idle_at >= target && !e->calls)) pthread_cond_wait(&e->settled, &e->lock);
    uint64_t at = e->error ? 0 : e->idle_at;
    pthread_mutex_unlock(&e->lock);
    return at;
}

uint64_t ex_settle_at(ex_exec *e, uint32_t level) {
    if (level >= DV_LEVELS) return ex_settle(e);
    uint64_t target;
    ex_call(e, read_publication, &target);
    pthread_mutex_lock(&e->lock);
    while (!e->error && !(e->settled_at[level] >= target && !e->calls)) pthread_cond_wait(&e->settled, &e->lock);
    uint64_t at = e->error ? 0 : e->settled_at[level];
    pthread_mutex_unlock(&e->lock);
    return at;
}

void ex_stop(ex_exec *e) {
    pthread_mutex_lock(&e->lock);
    e->stop = 1;
    for (int i = 0; i < EX_SLOTS; i++) atomic_store(&e->slots[i].cancel, 1);
    pthread_cond_broadcast(&e->owner_wake); pthread_cond_broadcast(&e->worker_wake);
    pthread_mutex_unlock(&e->lock);
    pthread_join(e->owner, NULL);
    for (size_t i = 0; i < e->nworkers; i++) pthread_join(e->workers[i], NULL);
    for (int i = 0; i < EX_SLOTS; i++) {             /* retire what was left in flight */
        ex_slot *s = &e->slots[i];
        if (s->state == EX_FREE) continue;
        if (s->state == EX_DONE) { free(s->out.blob); s->out.blob = NULL; }
        dv_cancel(e->graph, &s->job);
        hold(e, s, 0);
        s->state = EX_FREE;
    }
}

size_t ex_trace_copy(const ex_exec *e, ex_trace *out, size_t n) {
    uint64_t have = e->traced < EX_TRACE ? e->traced : EX_TRACE;
    if (n > have) n = (size_t)have;
    for (size_t i = 0; i < n; i++) out[i] = e->trace[(e->traced - n + i) % EX_TRACE];
    return n;
}
