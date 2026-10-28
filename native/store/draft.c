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
    td_text_value value;         /* live text and selection/composition */
    tx_text *base, *flight;      /* last acknowledged durable text; text being saved */
    uint64_t flight_local;
    unsigned saving, overflowed;
    uint64_t contended;
    batch *head, *tail;
    size_t logged, limit;
};

#ifdef TI_CHECK
#include <stdio.h>
static void invariant(const ti_queue *q, const char *where) {
    if (q->overflowed || q->conflict) return;
    size_t n = tx_length(q->base);
    for (batch *b = q->head; b; b = b->next)
        for (size_t i = 0; i < b->count; i++) n = n - (b->edits[i].end - b->edits[i].start) + b->edits[i].length;
    if (n != tx_length(q->value.text)) { fprintf(stderr, "INVARIANT %s: base+log %zu live %zu saved %lu local %lu\n", where, n, tx_length(q->value.text), (unsigned long)q->saved, (unsigned long)q->local); abort(); }
}
#else
#define invariant(q, w) ((void)0)
#endif

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
    s->external = q->external; s->length = tx_length(q->value.text); s->logged = q->logged;
    s->overflowed = q->overflowed; s->contended = q->contended;
}
static int selection_valid(size_t n, uint32_t anchor, uint32_t caret, int32_t cs, int32_t ce) {
    if (anchor > n || caret > n) return 0;
    return (cs == -1 && ce == -1) || (cs >= 0 && ce >= cs && (size_t)ce <= n);
}

ts_status ti_open(ti_queue **out, const td_snapshot *snap, tx_text *text, size_t limit) {
    if (!out) return TS_INVALID;
    *out = NULL;
    if (!snap || !snap->record.id || !snap->record.revision || tx_length(text) != snap->length ||
        !selection_valid(snap->length, snap->anchor, snap->caret, snap->composing_start, snap->composing_end))
        return TS_INVALID;
    ti_queue *q = calloc(1, sizeof *q);
    if (!q) return TS_IO;
    if (pthread_mutex_init(&q->lock, NULL)) { free(q); return TS_IO; }
    q->id = snap->record.id; q->durable = snap->record.revision;
    q->value = (td_text_value){tx_retain(text), snap->anchor, snap->caret, snap->composing_start, snap->composing_end};
    q->base = tx_retain(text);
    q->limit = limit ? limit : 1u << 20;
    *out = q;
    return TS_OK;
}

void ti_close(ti_queue *q) {
    if (!q) return;
    drop_all(q);
    pthread_mutex_destroy(&q->lock);
    tx_release(q->value.text); tx_release(q->base); tx_release(q->flight);
    free(q);
}

ts_status ti_edit(ti_queue *q, const td_edit *edits, size_t count, uint32_t anchor, uint32_t caret,
                  int32_t cs, int32_t ce, uint64_t *local) {
    if (!q || (count && !edits)) return TS_INVALID;
    if (count > TD_EDIT_LIMIT) return TS_CAPACITY;
    int busy = pthread_mutex_trylock(&q->lock);
    if (busy) pthread_mutex_lock(&q->lock);
    q->contended += busy != 0;
    ts_status st = TS_CONFLICT;
    tx_text *next = NULL;
    batch *b = NULL;
    if (q->conflict) goto out;
    /* Validate the whole batch before applying it, like td_apply. */
    size_t n = tx_length(q->value.text);
    st = TS_INVALID;
    for (size_t i = 0; i < count; i++) {
        if (edits[i].start > edits[i].end || edits[i].end > n || (edits[i].length && !edits[i].text)) goto out;
        n -= edits[i].end - edits[i].start;
        if (edits[i].length > TX_UNIT_LIMIT - n) { st = TS_CAPACITY; goto out; }
        n += edits[i].length;
    }
    if (!selection_valid(n, anchor, caret, cs, ce)) goto out;
    st = TS_IO;
    if (!q->overflowed && q->logged + cost(count) <= q->limit && !(b = malloc(cost(count)))) goto out;
    next = tx_retain(q->value.text);
    for (size_t i = 0; i < count; i++) {
        tx_text *e;
        if ((st = tx_edit(next, edits[i].start, edits[i].end, edits[i].text, edits[i].length, &e))) goto out;
        tx_release(next); next = e;
    }
    tx_release(q->value.text);
    q->value = (td_text_value){next, anchor, caret, cs, ce};
    next = NULL;
    q->local++;
    if (b) {
        b->local = q->local; b->count = count; b->next = NULL;
        for (size_t i = 0; i < count; i++)
            b->edits[i] = (range){edits[i].start, edits[i].end, edits[i].length};
        if (q->tail) q->tail->next = b; else q->head = b;
        q->tail = b;
        q->logged += cost(count);
        b = NULL;
    } else if (!q->overflowed) {
        /* An incomplete log cannot rebase; it stays incomplete until a save covers it all. */
        q->overflowed = 1;
        drop_all(q);
    }
    if (local) *local = q->local;
    invariant(q, "edit");
    st = TS_OK;
out:
    pthread_mutex_unlock(&q->lock);
    tx_release(next);
    free(b);
    return st;
}

ts_status ti_view(ti_queue *q, uint16_t *text, size_t capacity, td_value *v, ti_state *s) {
    if (!q || (capacity && !text)) return TS_INVALID;
    pthread_mutex_lock(&q->lock);
    size_t n = tx_length(q->value.text);
    ts_status st = n > capacity ? TS_CAPACITY : TS_OK;
    if (!st) tx_read(q->value.text, 0, n, text);
    if (v) *v = (td_value){st ? NULL : text, n, q->value.anchor, q->value.caret, q->value.composing_start, q->value.composing_end};
    if (s) state(q, s);
    pthread_mutex_unlock(&q->lock);
    return st;
}

ts_status ti_snapshot(ti_queue *q, td_text_value *v, ti_state *s) {
    if (!q || !v) return TS_INVALID;
    pthread_mutex_lock(&q->lock);
    *v = q->value;
    tx_retain(v->text);
    if (s) state(q, s);
    pthread_mutex_unlock(&q->lock);
    return TS_OK;
}

void ti_state_get(ti_queue *q, ti_state *s) {
    pthread_mutex_lock(&q->lock);
    state(q, s);
    pthread_mutex_unlock(&q->lock);
}

ts_status ti_capture(ti_queue *q, ti_captured *c) {
    if (!q || !c) return TS_INVALID;
    memset(c, 0, sizeof *c);
    pthread_mutex_lock(&q->lock);
    ts_status st = q->conflict ? TS_CONFLICT : q->saving ? TS_INVALID :
        q->saved == q->local ? TS_NOT_FOUND : TS_OK;
    if (!st) {
        tx_release(q->flight);
        q->flight = tx_retain(q->value.text); q->flight_local = q->local;
        q->saving = 1;
        c->local = q->local; c->expected = (ts_read){q->id, q->durable};
        c->value = q->value; tx_retain(c->value.text);
    }
    pthread_mutex_unlock(&q->lock);
    return st;
}

ts_status ti_acknowledge(ti_queue *q, uint64_t local, const td_snapshot *r) {
    if (!q || !r) return TS_INVALID;
    pthread_mutex_lock(&q->lock);
    ts_status st = TS_INVALID;
    if (!q->saving || local != q->flight_local || r->record.id != q->id ||
        r->record.revision < q->durable || r->length != tx_length(q->flight)) goto out;
    tx_release(q->base);
    q->base = q->flight; q->flight = NULL;
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
        tx_release(q->flight); q->flight = NULL;
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
/* Common prefix and suffix lengths, skipping chunks the two texts share at the same offset. */
static void common(const tx_text *a, const tx_text *b, size_t *prefix, size_t *suffix) {
    size_t na = tx_length(a), nb = tx_length(b), p = 0, s = 0;
    const uint16_t *x, *y;
    size_t xl, yl, xs, ys;
    while (p < na && p < nb) {
        tx_chunk(a, p, &x, &xl, &xs); tx_chunk(b, p, &y, &yl, &ys);
        if (x == y && xs == ys) { p = xs + xl; continue; }
        size_t end = xs + xl < ys + yl ? xs + xl : ys + yl;
        while (p < end && x[p - xs] == y[p - ys]) p++;
        if (p < end) break;
    }
    size_t limit = (na < nb ? na : nb) - p;       /* the suffix never overlaps the prefix */
    while (s < limit) {
        size_t oa = na - 1 - s, ob = nb - 1 - s;
        tx_chunk(a, oa, &x, &xl, &xs); tx_chunk(b, ob, &y, &yl, &ys);
        size_t ia = oa - xs, ib = ob - ys;
        size_t k = (ia < ib ? ia : ib) + 1;         /* units at or before oa/ob in both chunks */
        if (k > limit - s) k = limit - s;
        if (x == y && ia == ib) { s += k; continue; }
        size_t same = 0;
        while (same < k && x[ia - same] == y[ib - same]) same++;
        s += same;
        if (same < k) break;
    }
    *prefix = p; *suffix = s;
}

ts_status ti_rebase(ti_queue *q, const td_snapshot *cur, tx_text *text) {
    if (!q || !cur || tx_length(text) != cur->length) return TS_INVALID;
    pthread_mutex_lock(&q->lock);
    ts_status st = TS_INVALID;
    if (q->saving || cur->record.id != q->id || cur->record.revision < q->durable) goto out;
    st = TS_OK;
    if (cur->record.revision == q->durable && !q->conflict) goto out;
    /* Only the storage owner changes base, so the O(n) comparison runs without the lock. */
    tx_text *old = tx_retain(q->base);
    pthread_mutex_unlock(&q->lock);
    size_t p, s, b = tx_length(old), c = cur->length;
    common(old, text, &p, &s);
    tx_release(old);
    range e = {p, b - s, c - p - s};
    uint16_t *units = malloc((e.length ? e.length : 1) * sizeof *units);
    if (units) tx_read(text, p, e.length, units);
    pthread_mutex_lock(&q->lock);
    batch *log = NULL, **tail = &log;
    tx_text *live = NULL;
    st = TS_IO;
    if (!units) goto fail;
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
    td_text_value v = q->value;
    if (!clash && changed && v.composing_start >= 0) {
        size_t cs = (size_t)v.composing_start, ce = (size_t)v.composing_end;
        clash = (e.start < ce && cs < e.end) || (cs < e.start && e.start < ce);
    }
    if (clash) {
        /* Keep the live value; hold the conflicting durable text for an explicit resolve. */
        tx_release(q->base);
        q->base = tx_retain(text);
        q->conflict = cur->record.revision;
        st = TS_CONFLICT;
        goto fail;
    }
    if (changed) {
        if ((st = tx_edit(v.text, e.start, e.end, units, e.length, &live))) goto fail;
        v.anchor = map(&e, v.anchor); v.caret = map(&e, v.caret);
        if (v.composing_start >= 0) {
            v.composing_start = (int32_t)map(&e, (uint32_t)v.composing_start);
            v.composing_end = (int32_t)map(&e, (uint32_t)v.composing_end);
        }
        tx_release(q->value.text);
        v.text = live; live = NULL;
        q->value = v;
    }
    tx_release(q->base);
    q->base = tx_retain(text);
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
    free(units);
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
