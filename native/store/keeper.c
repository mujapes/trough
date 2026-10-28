#define _POSIX_C_SOURCE 200809L
#include "keeper.h"
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

enum { RETRY_FIRST_MS = 50, RETRY_MAX_MS = 5000, DRAIN_ATTEMPTS = 8 };
typedef enum { CALL_NONE, CALL_ATTACH, CALL_DETACH, CALL_RUN } call_kind;

typedef struct slot {
    ti_queue *q;
    uint64_t document;
    uint64_t acked_local, acked_revision;  /* newest local revision known durable, and where */
    ts_status last;                        /* last save outcome */
    unsigned failures;
    uint64_t retry_at;
    struct slot *next;
} slot;

struct tk_owner {
    pthread_t thread;
    pthread_mutex_t lock, call;
    pthread_cond_t wake, done;
    ts_store *store;
    const char *dir;
    ts_status opened;
    int started, stopping;
    uint64_t pokes, progress;
    slot *slots;
    tk_stats stats;
    /* One pending call at a time; callers serialise on `call`. */
    call_kind kind;
    int finished;
    ts_status result;
    uint64_t document;
    size_t log_limit;
    ti_queue *queue;
    tk_task task;
    void *arg;
};

static uint64_t now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000 + (uint64_t)t.tv_nsec / 1000000;
}
static void advanced(tk_owner *k) {
    pthread_mutex_lock(&k->lock);
    k->progress++;
    pthread_cond_broadcast(&k->done);
    pthread_mutex_unlock(&k->lock);
}
/* The queue's own state is read under its lock; slot fields change only on the keeper thread
 * and are published under k->lock. */
static void record_clean(tk_owner *k, slot *s) {
    ti_state st;
    ti_state_get(s->q, &st);
    if (st.phase == TI_CLEAN && st.saved > s->acked_local) {
        pthread_mutex_lock(&k->lock);
        s->acked_local = st.saved; s->acked_revision = st.durable;
        pthread_mutex_unlock(&k->lock);
    }
}

static ts_status rebase(tk_owner *k, slot *s) {
    td_snapshot cur;
    tx_text *text;
    ts_status st = td_load_text(k->store, s->document, 0, &cur, &text);
    if (st) return st;
    st = ti_rebase(s->q, &cur, text);
    tx_release(text);
    pthread_mutex_lock(&k->lock);
    if (st == TS_OK) k->stats.rebases++;
    else if (st == TS_CONFLICT) k->stats.conflicts++;
    pthread_mutex_unlock(&k->lock);
    record_clean(k, s);
    return st;
}

/* One capture/save/acknowledge. TS_NOT_FOUND: nothing to save. */
static ts_status save(tk_owner *k, slot *s) {
    ti_captured c;
    ts_status st = ti_capture(s->q, &c);
    if (st) return st;
    td_snapshot r;
    st = td_save_text(k->store, c.expected, &c.value, &r);
    tx_release(c.value.text);
    if (st == TS_OK) {
        ti_acknowledge(s->q, c.local, &r);
        pthread_mutex_lock(&k->lock);
        if (c.local > s->acked_local) { s->acked_local = c.local; s->acked_revision = r.record.revision; }
        k->stats.saves++;
        pthread_mutex_unlock(&k->lock);
        return TS_OK;
    }
    ti_reject(s->q, c.local);
    if (st == TS_CONFLICT) {        /* the document moved: merge it, the next round saves */
        ts_status rb = rebase(k, s);
        return rb == TS_OK ? TS_OK : rb;
    }
    return st;
}

/* Saves a slot until clean, a failure, or a conflict. Returns the outcome; on failure
 * schedules a retry with exponential backoff. */
static ts_status drive(tk_owner *k, slot *s, uint64_t now) {
    if (s->failures && now < s->retry_at) return s->last;
    ts_status st;
    while ((st = save(k, s)) == TS_OK) advanced(k);
    if (st == TS_NOT_FOUND) st = TS_OK;
    pthread_mutex_lock(&k->lock);
    if (st == TS_OK || st == TS_CONFLICT) s->failures = 0;
    else {
        unsigned shift = s->failures < 7 ? s->failures : 7;
        uint64_t delay = (uint64_t)RETRY_FIRST_MS << shift;
        s->retry_at = now + (delay < RETRY_MAX_MS ? delay : RETRY_MAX_MS);
        s->failures++;
        k->stats.failures++;
    }
    s->last = st;
    pthread_mutex_unlock(&k->lock);
    advanced(k);
    return st;
}

/* After tk_run: any attached document whose durable revision moved is rebased. */
static void check_external(tk_owner *k) {
    for (slot *s = k->slots; s; s = s->next) {
        ts_record r;
        ti_state st;
        ti_state_get(s->q, &st);
        if (ts_get(k->store, s->document, &r, NULL, 0) == TS_CAPACITY && r.revision > st.durable) {
            ts_status rb = rebase(k, s);
            pthread_mutex_lock(&k->lock);
            if (rb != TS_OK && rb != TS_CONFLICT) s->last = rb;
            pthread_mutex_unlock(&k->lock);
            advanced(k);
        }
    }
}

static void execute(tk_owner *k) {
    ts_status st = TS_INVALID;
    if (k->kind == CALL_ATTACH) {
        td_snapshot snap;
        tx_text *text;
        slot *s = calloc(1, sizeof *s);
        st = s ? td_load_text(k->store, k->document, 0, &snap, &text) : TS_IO;
        if (!st) {
            st = ti_open(&s->q, &snap, text, k->log_limit);
            tx_release(text);
        }
        if (st) free(s);
        else {
            s->document = k->document; s->acked_revision = snap.record.revision;
            pthread_mutex_lock(&k->lock);
            s->next = k->slots; k->slots = s;
            pthread_mutex_unlock(&k->lock);
            k->queue = s->q;
        }
    } else if (k->kind == CALL_DETACH) {
        slot **p = &k->slots;
        while (*p && (*p)->q != k->queue) p = &(*p)->next;
        if (*p) {
            slot *s = *p;
            st = drive(k, s, now_ms() + RETRY_MAX_MS);   /* ignore backoff: the caller is waiting */
            ti_state state;
            ti_state_get(s->q, &state);
            if (!st && state.phase == TI_CLEAN) {
                pthread_mutex_lock(&k->lock);
                *p = s->next;
                pthread_mutex_unlock(&k->lock);
                ti_close(s->q);
                free(s);
            } else if (!st) st = TS_INVALID;          /* still being edited: stop editing first */
        }
    } else if (k->kind == CALL_RUN) {
        st = k->task(k->store, k->arg);
        check_external(k);
    }
    k->result = st;
}

static void *run(void *arg) {
    tk_owner *k = arg;
    ts_status opened = ts_open(k->dir, &k->store);
    pthread_mutex_lock(&k->lock);
    k->opened = opened; k->started = 1;
    pthread_cond_broadcast(&k->done);
    if (opened) { pthread_mutex_unlock(&k->lock); return NULL; }
    uint64_t seen = k->pokes;
    for (;;) {
        if (k->kind != CALL_NONE && !k->finished) {
            pthread_mutex_unlock(&k->lock);
            execute(k);
            pthread_mutex_lock(&k->lock);
            k->finished = 1;
            pthread_cond_broadcast(&k->done);
            continue;
        }
        if (k->stopping) break;
        seen = k->pokes;
        pthread_mutex_unlock(&k->lock);
        uint64_t now = now_ms(), next = 0;
        for (slot *s = k->slots; s; s = s->next) {
            drive(k, s, now);
            if (s->failures && (!next || s->retry_at < next)) next = s->retry_at;
        }
        pthread_mutex_lock(&k->lock);
        if (k->pokes != seen || (k->kind != CALL_NONE && !k->finished) || k->stopping) continue;
        if (!next) pthread_cond_wait(&k->wake, &k->lock);
        else {
            struct timespec at;
            clock_gettime(CLOCK_MONOTONIC, &at);
            uint64_t wait = next > now_ms() ? next - now_ms() : 0;
            at.tv_sec += (time_t)(wait / 1000);
            at.tv_nsec += (long)(wait % 1000) * 1000000L;
            if (at.tv_nsec >= 1000000000L) { at.tv_sec++; at.tv_nsec -= 1000000000L; }
            pthread_cond_timedwait(&k->wake, &k->lock, &at);
        }
    }
    pthread_mutex_unlock(&k->lock);
    /* Drain: bounded attempts per queue, without backoff, then close everything. */
    for (slot *s = k->slots, *n; s; s = n) {
        n = s->next;
        for (int i = 0; i < DRAIN_ATTEMPTS && drive(k, s, now_ms() + RETRY_MAX_MS) != TS_OK; i++) {}
        ti_close(s->q);
        free(s);
    }
    k->slots = NULL;
    ts_close(k->store);
    return NULL;
}

ts_status tk_start(const char *dir, tk_owner **out) {
    if (!out) return TS_INVALID;
    *out = NULL;
    if (!dir) return TS_INVALID;
    tk_owner *k = calloc(1, sizeof *k);
    if (!k) return TS_IO;
    pthread_condattr_t attr;
    pthread_condattr_init(&attr);
    pthread_condattr_setclock(&attr, CLOCK_MONOTONIC);
    pthread_mutex_init(&k->lock, NULL); pthread_mutex_init(&k->call, NULL);
    pthread_cond_init(&k->wake, &attr); pthread_cond_init(&k->done, &attr);
    pthread_condattr_destroy(&attr);
    k->dir = dir;
    if (pthread_create(&k->thread, NULL, run, k)) { free(k); return TS_IO; }
    pthread_mutex_lock(&k->lock);
    while (!k->started) pthread_cond_wait(&k->done, &k->lock);
    ts_status st = k->opened;
    pthread_mutex_unlock(&k->lock);
    if (st) { pthread_join(k->thread, NULL); free(k); return st; }
    *out = k;
    return TS_OK;
}

void tk_stop(tk_owner *k) {
    if (!k) return;
    pthread_mutex_lock(&k->lock);
    k->stopping = 1;
    pthread_cond_signal(&k->wake);
    pthread_mutex_unlock(&k->lock);
    pthread_join(k->thread, NULL);
    pthread_mutex_destroy(&k->lock); pthread_mutex_destroy(&k->call);
    pthread_cond_destroy(&k->wake); pthread_cond_destroy(&k->done);
    free(k);
}

static ts_status call(tk_owner *k, call_kind kind) {
    pthread_mutex_lock(&k->lock);
    k->kind = kind; k->finished = 0;
    pthread_cond_signal(&k->wake);
    while (!k->finished) pthread_cond_wait(&k->done, &k->lock);
    k->kind = CALL_NONE;
    ts_status st = k->result;
    pthread_mutex_unlock(&k->lock);
    return st;
}

ts_status tk_attach(tk_owner *k, uint64_t document, size_t log_limit, ti_queue **out) {
    if (!out) return TS_INVALID;
    *out = NULL;
    if (!k || !document) return TS_INVALID;
    pthread_mutex_lock(&k->call);
    k->document = document; k->log_limit = log_limit; k->queue = NULL;
    ts_status st = call(k, CALL_ATTACH);
    if (!st) *out = k->queue;
    pthread_mutex_unlock(&k->call);
    return st;
}

ts_status tk_detach(tk_owner *k, ti_queue *q) {
    if (!k || !q) return TS_INVALID;
    pthread_mutex_lock(&k->call);
    k->queue = q;
    ts_status st = call(k, CALL_DETACH);
    pthread_mutex_unlock(&k->call);
    return st;
}

ts_status tk_run(tk_owner *k, tk_task task, void *arg) {
    if (!k || !task) return TS_INVALID;
    pthread_mutex_lock(&k->call);
    k->task = task; k->arg = arg;
    ts_status st = call(k, CALL_RUN);
    pthread_mutex_unlock(&k->call);
    return st;
}

void tk_poke(tk_owner *k) {
    pthread_mutex_lock(&k->lock);
    k->pokes++;
    pthread_cond_signal(&k->wake);
    pthread_mutex_unlock(&k->lock);
}

ts_status tk_flush(tk_owner *k, ti_queue *q, uint64_t local, unsigned timeout_ms, ts_read *durable) {
    if (!k || !q || !durable) return TS_INVALID;
    *durable = (ts_read){0, 0};
    uint64_t deadline = timeout_ms ? now_ms() + timeout_ms : 0;
    tk_poke(k);
    pthread_mutex_lock(&k->lock);
    ts_status st;
    for (;;) {
        slot *s = k->slots;
        while (s && s->q != q) s = s->next;
        if (!s) { st = TS_INVALID; break; }
        ti_state state;
        ti_state_get(q, &state);
        if (local > state.local) { st = TS_INVALID; break; }
        if (s->acked_local >= local) { *durable = (ts_read){s->document, s->acked_revision}; st = TS_OK; break; }
        if (state.phase == TI_CONFLICT) { st = TS_CONFLICT; break; }
        uint64_t now = now_ms();
        if (deadline && now >= deadline) { st = s->failures ? s->last : TS_IO; break; }
        struct timespec at;
        clock_gettime(CLOCK_MONOTONIC, &at);
        uint64_t wait = deadline ? deadline - now : 1000;
        if (wait > 1000) wait = 1000;
        at.tv_sec += (time_t)(wait / 1000);
        at.tv_nsec += (long)(wait % 1000) * 1000000L;
        if (at.tv_nsec >= 1000000000L) { at.tv_sec++; at.tv_nsec -= 1000000000L; }
        pthread_cond_timedwait(&k->done, &k->lock, &at);
    }
    pthread_mutex_unlock(&k->lock);
    return st;
}

void tk_stats_get(tk_owner *k, tk_stats *out) {
    pthread_mutex_lock(&k->lock);
    *out = k->stats;
    pthread_mutex_unlock(&k->lock);
}
