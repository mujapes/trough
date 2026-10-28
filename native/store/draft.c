#include "draft.h"
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

/* Logged batches keep only ranges and replacement lengths: rebasing transforms positions,
 * it never replays local text (the live value already contains it). */
typedef struct { size_t start, end, length; } range;
typedef struct batch {
    uint64_t local;
    size_t count;
    struct batch *next;
    range edits[];
} batch;

struct ti_queue {
    pthread_mutex_t lock;
    uint64_t id, local, saved, durable, external, conflict;
    uint16_t *live, *base, *flight;
    td_value value;              /* live metadata; text is always live */
    size_t base_length, flight_length;
    uint64_t flight_local;
    unsigned saving, overflowed;
    batch *head, *tail;
    size_t logged, limit;
};

#ifdef TI_CHECK
#include <stdio.h>
static void invariant(const ti_queue *q, const char *where) {
    if (q->overflowed || q->conflict) return;
    size_t n = q->base_length;
    for (batch *b = q->head; b; b = b->next)
        for (size_t i = 0; i < b->count; i++) n = n - (b->edits[i].end - b->edits[i].start) + b->edits[i].length;
    if (n != q->value.length) { fprintf(stderr, "INVARIANT %s: base+log %zu live %zu saved %lu local %lu\n", where, n, q->value.length, (unsigned long)q->saved, (unsigned long)q->local); abort(); }
}
#else
#define invariant(q, w) ((void)0)
#endif

static uint16_t *copy(const uint16_t *text, size_t n) {
    uint16_t *p = malloc((n ? n : 1) * sizeof *p);
    if (p && n) memcpy(p, text, n * sizeof *p);
    return p;
}
static size_t cost(size_t count) { return sizeof(batch) + count * sizeof(range); }
static void drop_through(ti_queue *q, uint64_t local) {
    while (q->head && q->head->local <= local) {
        batch *b = q->head;
        q->head = b->next;
        q->logged -= cost(b->count);
        free(b);
    }
    if (!q->head) q->tail = NULL;
}
static void drop_all(ti_queue *q) { drop_through(q, UINT64_MAX); }
static ti_phase phase(const ti_queue *q) {
    if (q->conflict) return TI_CONFLICT;
    if (q->saving) return TI_SAVING;
    return q->saved == q->local ? TI_CLEAN : TI_DIRTY;
}
static void state(const ti_queue *q, ti_state *s) {
    s->phase = phase(q); s->local = q->local; s->saved = q->saved; s->durable = q->durable;
    s->external = q->external; s->length = q->value.length; s->logged = q->logged;
    s->overflowed = q->overflowed;
}

ts_status ti_open(ti_queue **out, const td_snapshot *snap, const uint16_t *text, size_t limit) {
    if (!out) return TS_INVALID;
    *out = NULL;
    if (!snap || !snap->record.id || !snap->record.revision || (snap->length && !text)) return TS_INVALID;
    td_value v = {text, snap->length, snap->anchor, snap->caret, snap->composing_start, snap->composing_end};
    uint16_t scratch;
    td_value check;
    /* td_apply with no edits is the shared validity check for a value. */
    if (td_apply(&v, NULL, 0, v.anchor, v.caret, v.composing_start, v.composing_end,
                 v.length ? NULL : &scratch, 0, &check) == TS_INVALID) return TS_INVALID;
    ti_queue *q = calloc(1, sizeof *q);
    if (!q) return TS_IO;
    q->live = copy(text, v.length);
    q->base = copy(text, v.length);
    if (!q->live || !q->base || pthread_mutex_init(&q->lock, NULL)) {
        free(q->live); free(q->base); free(q);
        return TS_IO;
    }
    q->id = snap->record.id; q->durable = snap->record.revision;
    q->value = v; q->value.text = q->live; q->base_length = v.length;
    q->limit = limit ? limit : 1u << 20;
    *out = q;
    return TS_OK;
}

void ti_close(ti_queue *q) {
    if (!q) return;
    drop_all(q);
    pthread_mutex_destroy(&q->lock);
    free(q->live); free(q->base); free(q->flight); free(q);
}

ts_status ti_edit(ti_queue *q, const td_edit *edits, size_t count, uint32_t anchor, uint32_t caret,
                  int32_t cs, int32_t ce, uint64_t *local) {
    if (!q || (count && !edits)) return TS_INVALID;
    if (count > TD_EDIT_LIMIT) return TS_CAPACITY;
    pthread_mutex_lock(&q->lock);
    ts_status st = TS_CONFLICT;
    if (q->conflict) goto out;
    /* Result length; td_apply repeats the full validation before anything changes. */
    size_t n = q->value.length;
    st = TS_INVALID;
    for (size_t i = 0; i < count; i++) {
        if (edits[i].start > edits[i].end || edits[i].end > n) goto out;
        n -= edits[i].end - edits[i].start;
        if (edits[i].length > TD_TEXT_LIMIT - n) { st = TS_CAPACITY; goto out; }
        n += edits[i].length;
    }
    uint16_t *next = malloc((n ? n : 1) * sizeof *next);
    batch *b = NULL;
    st = TS_IO;
    if (!next) goto out;
    if (!q->overflowed && q->logged + cost(count) <= q->limit && !(b = malloc(cost(count)))) {
        free(next);
        goto out;
    }
    td_value result;
    st = td_apply(&q->value, edits, count, anchor, caret, cs, ce, next, n, &result);
    if (st) { free(next); free(b); goto out; }
    free(q->live);
    q->live = next; q->value = result;
    q->local++;
    if (b) {
        b->local = q->local; b->count = count; b->next = NULL;
        for (size_t i = 0; i < count; i++)
            b->edits[i] = (range){edits[i].start, edits[i].end, edits[i].length};
        if (q->tail) q->tail->next = b; else q->head = b;
        q->tail = b;
        q->logged += cost(count);
    } else if (!q->overflowed) {
        /* An incomplete log cannot rebase; it stays incomplete until a save covers it all. */
        q->overflowed = 1;
        drop_all(q);
    }
    if (local) *local = q->local;
    invariant(q, "edit");
out:
    pthread_mutex_unlock(&q->lock);
    return st;
}

ts_status ti_view(ti_queue *q, uint16_t *text, size_t capacity, td_value *v, ti_state *s) {
    if (!q || (capacity && !text)) return TS_INVALID;
    pthread_mutex_lock(&q->lock);
    ts_status st = q->value.length > capacity ? TS_CAPACITY : TS_OK;
    if (!st && q->value.length) memcpy(text, q->live, q->value.length * sizeof *text);
    if (v) { *v = q->value; v->text = st ? NULL : text; }
    if (s) state(q, s);
    pthread_mutex_unlock(&q->lock);
    return st;
}

void ti_state_get(ti_queue *q, ti_state *s) {
    pthread_mutex_lock(&q->lock);
    state(q, s);
    pthread_mutex_unlock(&q->lock);
}

ts_status ti_capture(ti_queue *q, uint16_t *text, size_t capacity, ti_captured *c) {
    if (!q || !c || (capacity && !text)) return TS_INVALID;
    memset(c, 0, sizeof *c);
    pthread_mutex_lock(&q->lock);
    ts_status st = q->conflict ? TS_CONFLICT : q->saving ? TS_INVALID :
        q->saved == q->local ? TS_NOT_FOUND : TS_OK;
    if (st) goto out;
    c->value = q->value; c->value.text = NULL;
    if (q->value.length > capacity) { st = TS_CAPACITY; goto out; }
    uint16_t *flight = copy(q->live, q->value.length);
    if (!flight) { st = TS_IO; goto out; }
    if (q->value.length) memcpy(text, q->live, q->value.length * sizeof *text);
    free(q->flight);
    q->flight = flight; q->flight_length = q->value.length; q->flight_local = q->local;
    q->saving = 1;
    c->local = q->local; c->expected = (ts_read){q->id, q->durable}; c->value.text = text;
out:
    pthread_mutex_unlock(&q->lock);
    return st;
}

ts_status ti_acknowledge(ti_queue *q, uint64_t local, const td_snapshot *r) {
    if (!q || !r) return TS_INVALID;
    pthread_mutex_lock(&q->lock);
    ts_status st = TS_INVALID;
    if (!q->saving || local != q->flight_local || r->record.id != q->id ||
        r->record.revision < q->durable || r->length != q->flight_length) goto out;
    free(q->base);
    q->base = q->flight; q->base_length = q->flight_length; q->flight = NULL;
    q->durable = r->record.revision; q->saved = local; q->saving = 0;
    drop_through(q, local);
    if (q->saved == q->local) q->overflowed = 0;
    invariant(q, "ack");
    st = TS_OK;
out:
    pthread_mutex_unlock(&q->lock);
    return st;
}

ts_status ti_reject(ti_queue *q, uint64_t local) {
    if (!q) return TS_INVALID;
    pthread_mutex_lock(&q->lock);
    ts_status st = TS_INVALID;
    if (q->saving && local == q->flight_local) {
        q->saving = 0;
        free(q->flight); q->flight = NULL;
        st = TS_OK;
    }
    pthread_mutex_unlock(&q->lock);
    return st;
}

/* E replaces [start, end) with length units. Local edits that end at or before E's start
 * (including insertions at the same point: local first) shift it; those starting at or after
 * its end are shifted by it. Anything else overlaps: conflict. */
static int transform(range *e, range *x) {
    if (x->end <= e->start) {
        size_t removed = x->end - x->start;
        e->start = e->start - removed + x->length;
        e->end = e->end - removed + x->length;
        return 1;
    }
    if (x->start >= e->end) {
        x->start = x->start - (e->end - e->start) + e->length;
        x->end = x->end - (e->end - e->start) + e->length;
        return 1;
    }
    return 0;
}
static uint32_t map(const range *e, uint32_t p) {
    if (p <= e->start) return p;
    if (p >= e->end) return (uint32_t)(p - (e->end - e->start) + e->length);
    return (uint32_t)(e->start + e->length);
}

ts_status ti_rebase(ti_queue *q, const td_snapshot *cur, const uint16_t *text) {
    if (!q || !cur || (cur->length && !text) || cur->length > TD_TEXT_LIMIT) return TS_INVALID;
    pthread_mutex_lock(&q->lock);
    ts_status st = TS_INVALID;
    if (q->saving || cur->record.id != q->id || cur->record.revision < q->durable) goto out;
    st = TS_OK;
    if (cur->record.revision == q->durable && !q->conflict) goto out;
    /* One exact replacement: common prefix and suffix of the base and the new durable text. */
    size_t b = q->base_length, c = cur->length, p = 0, s = 0;
    while (p < b && p < c && q->base[p] == text[p]) p++;
    while (s < b - p && s < c - p && q->base[b - 1 - s] == text[c - 1 - s]) s++;
    range e = {p, b - s, c - p - s};
    uint16_t *base = copy(text, c), *live = NULL;
    batch *log = NULL, **tail = &log;
    st = TS_IO;
    if (!base) goto out;
    int changed = e.start != e.end || e.length;
    /* After a conflict the base is the conflicting text, unrelated to the log. */
    int clash = q->conflict || (changed && q->overflowed);
    /* Transform E through the local log and the log through E, on copies. */
    for (batch *x = q->head; x && !clash; x = x->next) {
        batch *y = malloc(cost(x->count));
        if (!y) goto fail;
        memcpy(y, x, cost(x->count));
        y->next = NULL; *tail = y; tail = &y->next;
        for (size_t i = 0; i < y->count && changed && !clash; i++) clash = !transform(&e, &y->edits[i]);
    }
    td_value v = q->value;
    if (!clash && changed && v.composing_start >= 0) {
        size_t cs = (size_t)v.composing_start, ce = (size_t)v.composing_end;
        clash = (e.start < ce && cs < e.end) || (cs < e.start && e.start < ce);
    }
    if (clash) {
        /* Keep the live value; hold the conflicting durable text for an explicit resolve. */
        free(q->base);
        q->base = base; q->base_length = c; base = NULL;
        q->conflict = cur->record.revision;
        st = TS_CONFLICT;
        goto fail;
    }
    if (changed) {
        size_t n = v.length - (e.end - e.start) + e.length;
        if (!(live = malloc((n ? n : 1) * sizeof *live))) goto fail;
        memcpy(live, q->live, e.start * sizeof *live);
        if (e.length) memcpy(live + e.start, text + (c - s - e.length), e.length * sizeof *live);
        memcpy(live + e.start + e.length, q->live + e.end, (v.length - e.end) * sizeof *live);
        v.anchor = map(&e, v.anchor); v.caret = map(&e, v.caret);
        if (v.composing_start >= 0) {
            v.composing_start = (int32_t)map(&e, (uint32_t)v.composing_start);
            v.composing_end = (int32_t)map(&e, (uint32_t)v.composing_end);
        }
        free(q->live);
        q->live = live; v.text = live; v.length = n; q->value = v;
    }
    free(q->base);
    q->base = base; q->base_length = c; base = NULL;
    drop_all(q);
    for (q->head = log; log; log = log->next) { q->tail = log; q->logged += cost(log->count); }
    int pending = q->saved != q->local;
    q->durable = cur->record.revision; q->conflict = 0;
    if (changed) { q->local++; q->external++; }
    /* Without pending local edits the merged live value is exactly the durable text. */
    if (!pending) q->saved = q->local;
    invariant(q, "rebase");
    st = TS_OK;
fail:
    while (log) { batch *x = log; log = x->next; free(x); }
    free(base);
out:
    pthread_mutex_unlock(&q->lock);
    return st;
}

ts_status ti_resolve_keep(ti_queue *q, uint64_t durable) {
    if (!q) return TS_INVALID;
    pthread_mutex_lock(&q->lock);
    ts_status st = TS_INVALID;
    if (q->conflict && durable == q->conflict) {
        /* The live value will be saved over the conflicting revision. Its log no longer
         * relates to the new base, so it is incomplete until a save covers everything. */
        q->durable = durable; q->conflict = 0;
        drop_all(q);
        q->overflowed = 1;
        q->local++;             /* the live value is now an unsaved change to the new base */
        st = TS_OK;
    }
    pthread_mutex_unlock(&q->lock);
    return st;
}
