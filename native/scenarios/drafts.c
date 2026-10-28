#define _POSIX_C_SOURCE 200809L
/* core-drafts oracle: input persistence queue against the real document store.
 * Shares only the public draft/document/store API with the implementation. The merge
 * oracle is independent of the queue's transform: every inserted unit is unique, so a
 * correct merge is characterised by sets and relative orders, not by recomputing ranges. */
#include "draft.h"
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

static int failures;
#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__, #c); failures++; } } while (0)
#define MUST(c) do { if (!(c)) { fprintf(stderr, "FATAL %s:%d %s\n", __FILE__, __LINE__, #c); exit(1); } } while (0)
static char base[3000];
static const char *path(const char *name) { static char p[3100]; snprintf(p, sizeof p, "%s/%s", base, name); return p; }
static uint64_t rng = 0x9e3779b97f4a7c15u;
static uint32_t next(void) { rng ^= rng << 13; rng ^= rng >> 7; rng ^= rng << 17; return (uint32_t)(rng >> 16); }
static uint16_t fresh = 0x100;  /* unique units; reset per document */
static uint8_t was_local[65536], was_durable[65536];  /* ever in live text / any durable revision */
enum { CAP = 4096 };

typedef struct { uint16_t text[CAP]; size_t length; } model;
static void put(model *m, size_t start, size_t end, const uint16_t *t, size_t n) {
    memmove(m->text + start + n, m->text + end, (m->length - end) * 2);
    if (n) memcpy(m->text + start, t, n * 2);
    m->length = m->length - (end - start) + n;
}
static int same(const uint16_t *a, size_t an, const uint16_t *b, size_t bn) { return an == bn && !memcmp(a, b, an * 2); }
static td_snapshot create(ts_store *s, const uint16_t *t, size_t n) {
    td_value v = {t, n, 0, 0, -1, -1}; td_snapshot snap;
    MUST(td_create(s, &v, &snap) == TS_OK);
    return snap;
}
/* Storage owner: one full capture/save/acknowledge, rebasing on a storage conflict. */
static ts_status persist(ts_store *s, ti_queue *q) {
    static uint16_t buf[CAP]; ti_captured c;
    ts_status st = ti_capture(q, buf, CAP, &c);
    if (st) return st;
    td_snapshot r;
    st = td_save(s, c.expected, &c.value, &r);
    if (st == TS_OK) {
        for (size_t i = 0; i < c.value.length; i++) was_durable[c.value.text[i]] = 1;
        MUST(ti_acknowledge(q, c.local, &r) == TS_OK); return TS_OK;
    }
    MUST(ti_reject(q, c.local) == TS_OK);
    if (st != TS_CONFLICT) return st;
    MUST(td_load(s, c.expected.id, 0, &r, buf, CAP) == TS_OK);
    return ti_rebase(q, &r, buf);
}
static void view(ti_queue *q, model *m, td_value *v, ti_state *st) {
    MUST(ti_view(q, m->text, CAP, v, st) == TS_OK); m->length = v->length;
    for (size_t i = 0; i < m->length; i++) was_local[m->text[i]] = 1;
}

static void basics(void) {
    ts_store *s; MUST(ts_open(path("basics"), &s) == TS_OK);
    uint16_t hello[] = {'h','e','l','l','o'};
    td_snapshot doc = create(s, hello, 5); ti_queue *q;
    MUST(ti_open(&q, &doc, hello, 0) == TS_OK);
    ti_state st; ti_state_get(q, &st);
    CHECK(st.phase == TI_CLEAN && st.durable == 1 && st.local == 0 && st.saved == 0);
    uint16_t buf[64]; ti_captured c;
    CHECK(ti_capture(q, buf, 64, &c) == TS_NOT_FOUND);
    td_edit bang = {5, 5, (uint16_t[]){'!'}, 1}; uint64_t local;
    MUST(ti_edit(q, &bang, 1, 6, 6, -1, -1, &local) == TS_OK && local == 1);
    CHECK(ti_capture(q, buf, 2, &c) == TS_CAPACITY && c.value.length == 6);
    MUST(ti_capture(q, buf, 64, &c) == TS_OK && c.local == 1 && c.expected.id == doc.record.id && c.expected.revision == 1);
    CHECK(ti_capture(q, buf, 64, &(ti_captured){0}) == TS_INVALID);      /* one save in flight */
    /* Typing continues while the save runs; the returning snapshot never replaces it. */
    td_edit more = {6, 6, (uint16_t[]){'?'}, 1};
    MUST(ti_edit(q, &more, 1, 7, 7, -1, -1, &local) == TS_OK && local == 2);
    td_snapshot r; MUST(td_save(s, c.expected, &c.value, &r) == TS_OK);
    CHECK(ti_acknowledge(q, 2, &r) == TS_INVALID);                      /* not the captured revision */
    MUST(ti_acknowledge(q, 1, &r) == TS_OK);
    model m; td_value v; view(q, &m, &v, &st);
    CHECK(st.phase == TI_DIRTY && st.saved == 1 && st.local == 2 && st.durable == 2 && m.length == 7 && m.text[6] == '?' && v.caret == 7);
    /* A failed save keeps the edits pending; the next capture carries everything. */
    MUST(ti_capture(q, buf, 64, &c) == TS_OK && c.local == 2 && c.expected.revision == 2);
    MUST(ti_reject(q, 2) == TS_OK);
    ti_state_get(q, &st); CHECK(st.phase == TI_DIRTY);
    CHECK(persist(s, q) == TS_OK);
    ti_state_get(q, &st); CHECK(st.phase == TI_CLEAN && st.saved == 2 && st.durable == 3 && st.logged == 0);
    MUST(td_load(s, doc.record.id, 0, &r, buf, 64) == TS_OK);
    CHECK(r.length == 7 && buf[5] == '!' && buf[6] == '?' && r.caret == 7);
    /* Invalid edits change nothing. */
    td_edit bad = {9, 9, NULL, 0};
    CHECK(ti_edit(q, &bad, 1, 0, 0, -1, -1, NULL) == TS_INVALID);
    CHECK(ti_edit(q, NULL, 0, 99, 0, -1, -1, NULL) == TS_INVALID);
    ti_state_get(q, &st); CHECK(st.local == 2 && st.phase == TI_CLEAN);
    ti_close(q); ts_close(s);
    puts("ok draft basics: coalesced in-flight save, typing during save, exact acknowledgement, reject keeps edits");
}

/* External revision helper: writes text over the document's current durable revision. */
static void external(ts_store *s, uint64_t id, const uint16_t *t, size_t n) {
    td_snapshot cur; static uint16_t buf[CAP];
    MUST(td_load(s, id, 0, &cur, buf, CAP) == TS_OK);
    td_value v = {t, n, 0, 0, -1, -1}; td_snapshot r;
    MUST(td_save(s, (ts_read){id, cur.record.revision}, &v, &r) == TS_OK);
    for (size_t i = 0; i < n; i++) was_durable[t[i]] = 1;
}
static td_snapshot current(ts_store *s, uint64_t id, uint16_t *buf) {
    td_snapshot r; MUST(td_load(s, id, 0, &r, buf, CAP) == TS_OK);
    for (size_t i = 0; i < r.length; i++) was_durable[buf[i]] = 1;
    return r;
}

static void rebases(void) {
    ts_store *s; MUST(ts_open(path("rebases"), &s) == TS_OK);
    uint16_t abc[] = {'a','b','c','d','e','f'}, buf[CAP];
    td_snapshot doc = create(s, abc, 6); ti_queue *q;
    MUST(ti_open(&q, &doc, abc, 0) == TS_OK);
    /* Local insert at the start with selection and composition; remote append at the end. */
    td_edit x = {0, 0, (uint16_t[]){'X'}, 1};
    MUST(ti_edit(q, &x, 1, 1, 1, 0, 1, NULL) == TS_OK);
    uint16_t remote[] = {'a','b','c','d','e','f','Z','Z'};
    external(s, doc.record.id, remote, 8);
    CHECK(persist(s, q) == TS_OK);                       /* storage conflict -> rebase */
    model m; td_value v; ti_state st; view(q, &m, &v, &st);
    uint16_t merged[] = {'X','a','b','c','d','e','f','Z','Z'};
    CHECK(same(m.text, m.length, merged, 9) && v.caret == 1 && v.composing_start == 0 && v.composing_end == 1 &&
          st.external == 1 && st.phase == TI_DIRTY && st.durable == 2);
    CHECK(persist(s, q) == TS_OK);
    td_snapshot r = current(s, doc.record.id, buf);
    CHECK(same(buf, r.length, merged, 9) && r.record.revision == 3);
    /* Remote insertion before the caret shifts selection; inside a range maps to its end. */
    uint16_t r2[] = {'Q','X','a','b','c','d','e','f','Z','Z'};
    td_edit sel = {0, 0, NULL, 0};
    MUST(ti_edit(q, &sel, 1, 2, 4, -1, -1, NULL) == TS_OK);
    external(s, doc.record.id, r2, 10);
    CHECK(persist(s, q) == TS_OK);
    view(q, &m, &v, &st);
    CHECK(same(m.text, m.length, r2, 10) && v.anchor == 3 && v.caret == 5);
    CHECK(persist(s, q) == TS_OK);
    /* Clean queue: an external revision merges with no storage round trip and no dirt. */
    uint16_t r3[] = {'Q','X'};
    external(s, doc.record.id, r3, 2);
    r = current(s, doc.record.id, buf);
    CHECK(ti_rebase(q, &r, buf) == TS_OK);
    view(q, &m, &v, &st);
    CHECK(same(m.text, m.length, r3, 2) && st.phase == TI_CLEAN && v.anchor == 2 && v.caret == 2);
    CHECK(ti_capture(q, buf, CAP, &(ti_captured){0}) == TS_NOT_FOUND);
    CHECK(ti_rebase(q, &r, buf) == TS_OK);                /* same revision: no change */
    ti_state_get(q, &st); CHECK(st.external == 3);
    /* Overlapping edits conflict: live untouched, edits and saves refused until resolved. */
    td_edit y = {1, 2, (uint16_t[]){'Y'}, 1};
    MUST(ti_edit(q, &y, 1, 2, 2, -1, -1, NULL) == TS_OK);
    uint16_t r4[] = {'Q','W'};
    external(s, doc.record.id, r4, 2);
    CHECK(persist(s, q) == TS_CONFLICT);
    view(q, &m, &v, &st);
    CHECK(st.phase == TI_CONFLICT && m.length == 2 && m.text[1] == 'Y');
    CHECK(ti_edit(q, &sel, 1, 0, 0, -1, -1, NULL) == TS_CONFLICT);
    CHECK(ti_capture(q, buf, CAP, &(ti_captured){0}) == TS_CONFLICT);
    r = current(s, doc.record.id, buf);
    CHECK(ti_resolve_keep(q, r.record.revision + 1) == TS_INVALID);
    MUST(ti_resolve_keep(q, r.record.revision) == TS_OK);
    CHECK(persist(s, q) == TS_OK);
    r = current(s, doc.record.id, buf);
    CHECK(buf[1] == 'Y' && r.length == 2);
    ti_state_get(q, &st); CHECK(st.phase == TI_CLEAN && !st.overflowed);
    /* Composition overlap conflicts even though the text ranges merely touch it. */
    td_edit comp = {2, 2, (uint16_t[]){'k','a'}, 2};
    MUST(ti_edit(q, &comp, 1, 4, 4, 2, 4, NULL) == TS_OK);
    CHECK(persist(s, q) == TS_OK);
    MUST(ti_edit(q, &sel, 1, 4, 4, 2, 4, NULL) == TS_OK);
    uint16_t r5[] = {'Q','Y','k','!','a'};
    external(s, doc.record.id, r5, 5);
    CHECK(persist(s, q) == TS_CONFLICT);
    ti_close(q);
    /* Boundaries: insertions at one point merge local-first; an edit starting exactly at the
     * end of a remote replacement (or ending at its start) touches it without overlapping. */
    doc = create(s, abc, 6);
    MUST(ti_open(&q, &doc, abc, 0) == TS_OK);
    td_edit l = {3, 3, (uint16_t[]){'L'}, 1}, d = {4, 5, (uint16_t[]){'D'}, 1};
    MUST(ti_edit(q, &l, 1, 4, 4, -1, -1, NULL) == TS_OK);
    MUST(ti_edit(q, &d, 1, 5, 5, -1, -1, NULL) == TS_OK);   /* "abcLDef" */
    uint16_t rb[] = {'a','Z','R','d','e','f'};               /* bc -> ZR (ends at 3) */
    external(s, doc.record.id, rb, 6);
    CHECK(persist(s, q) == TS_OK);
    view(q, &m, &v, &st);
    uint16_t want[] = {'a','Z','R','L','D','e','f'};
    CHECK(same(m.text, m.length, want, 7) && v.caret == 5);
    uint16_t rc[] = {'a','Z','R','L','D','e','f','!'};
    CHECK(persist(s, q) == TS_OK);
    MUST(ti_edit(q, &(td_edit){7, 7, (uint16_t[]){'?'}, 1}, 1, 8, 8, -1, -1, NULL) == TS_OK);
    external(s, doc.record.id, rc, 8);                       /* same insertion point */
    CHECK(persist(s, q) == TS_OK);
    view(q, &m, &v, &st);
    uint16_t both[] = {'a','Z','R','L','D','e','f','?','!'};
    CHECK(same(m.text, m.length, both, 9));
    ti_close(q); ts_close(s);
    puts("ok draft rebase: disjoint merges keep selection/composition, overlap and composition conflicts stop saves");
}

static void overflow(void) {
    ts_store *s; MUST(ts_open(path("overflow"), &s) == TS_OK);
    uint16_t t[] = {'a','b'}, buf[CAP];
    td_snapshot doc = create(s, t, 2); ti_queue *q;
    MUST(ti_open(&q, &doc, t, 64) == TS_OK);
    td_edit e = {0, 0, (uint16_t[]){'x'}, 1};
    for (int i = 0; i < 8; i++) MUST(ti_edit(q, &e, 1, 0, 0, -1, -1, NULL) == TS_OK);
    ti_state st; ti_state_get(q, &st);
    CHECK(st.overflowed && st.logged == 0 && st.local == 8);
    uint16_t r1[] = {'a','b','c'};
    external(s, doc.record.id, r1, 3);
    CHECK(persist(s, q) == TS_CONFLICT);                 /* incomplete log: never guess */
    td_snapshot r = current(s, doc.record.id, buf);
    MUST(ti_resolve_keep(q, r.record.revision) == TS_OK);
    CHECK(persist(s, q) == TS_OK);
    ti_state_get(q, &st); CHECK(st.phase == TI_CLEAN && !st.overflowed);
    MUST(ti_edit(q, &e, 1, 0, 0, -1, -1, NULL) == TS_OK);
    uint16_t r2[CAP]; r = current(s, doc.record.id, r2); r2[r.length] = 'z';
    external(s, doc.record.id, r2, r.length + 1);
    CHECK(persist(s, q) == TS_OK);                       /* log complete again: merges */
    ti_close(q); ts_close(s);
    puts("ok draft overflow: bounded log, overflow conflicts instead of merging, recovers after a full save");
}

/* Differential: random local edits, saves, rejections and disjoint-or-not remote revisions.
 * Every unit ever inserted is unique, so a merge is checked by content sets and orders. */
static size_t position(const model *m, uint16_t u) {
    for (size_t i = 0; i < m->length; i++) if (m->text[i] == u) return i;
    return SIZE_MAX;
}
static int ordered(const model *in, const model *sub) {
    size_t last = 0; int first = 1;
    for (size_t i = 0; i < sub->length; i++) {
        size_t p = position(in, sub->text[i]);
        if (p == SIZE_MAX) continue;
        if (!first && p <= last) return 0;
        last = p; first = 0;
    }
    return 1;
}
static void differential(void) {
    ts_store *s; MUST(ts_open(path("differential"), &s) == TS_OK);
    unsigned merges = 0, conflicts = 0, saves = 0, edits = 0;
    for (int round = 0; round < 60; round++) {
        fresh = 0x100; memset(was_local, 0, sizeof was_local); memset(was_durable, 0, sizeof was_durable);
        model live = {{0}, 0}, durable;
        for (int i = 0; i < 20; i++) live.text[live.length++] = fresh++;
        td_snapshot doc = create(s, live.text, live.length); ti_queue *q;
        MUST(ti_open(&q, &doc, live.text, round % 5 ? 0 : 200) == TS_OK);
        for (size_t i = 0; i < live.length; i++) was_local[live.text[i]] = was_durable[live.text[i]] = 1;
        for (int step = 0; step < 120; step++) {
            uint32_t r = next() % 100;
            if (r < 55 && live.length < CAP - 64) {
                size_t a = next() % (live.length + 1), b = a + next() % (live.length - a + 1) % 4, n = next() % 3;
                uint16_t ins[4]; for (size_t i = 0; i < n; i++) ins[i] = fresh++;
                td_edit e = {a, b, ins, n}; size_t len = live.length - (b - a) + n;
                ts_status st = ti_edit(q, &e, 1, (uint32_t)(a + n), (uint32_t)(a + n), -1, -1, NULL);
                if (st == TS_CONFLICT) continue;
                MUST(st == TS_OK); put(&live, a, b, ins, n); edits++;
                td_value v; ti_state ts; model got; view(q, &got, &v, &ts);
                CHECK(same(got.text, got.length, live.text, live.length) && got.length == len);
            } else if (r < 80) {
                ti_state before; ti_state_get(q, &before);
                durable.length = 0;
                td_snapshot cur = current(s, doc.record.id, durable.text); durable.length = cur.length;
                ts_status st = persist(s, q);
                ti_state after; model got; td_value v; view(q, &got, &v, &after);
                if (st == TS_OK && after.external > before.external) {
                    /* Merge: everything in live and the new durable text, minus remote deletions,
                     * with both relative orders preserved. */
                    merges++;
                    model now; cur = current(s, doc.record.id, now.text); now.length = cur.length;
                    CHECK(ordered(&got, &live) && ordered(&got, &now));
                    for (size_t i = 0; i < now.length; i++)   /* a durable unit is absent only if deleted locally */
                        if (position(&got, now.text[i]) == SIZE_MAX)
                            CHECK(was_local[now.text[i]] && position(&live, now.text[i]) == SIZE_MAX);
                    for (size_t i = 0; i < got.length; i++)
                        CHECK(position(&now, got.text[i]) != SIZE_MAX || position(&live, got.text[i]) != SIZE_MAX);
                    for (size_t i = 0; i < live.length; i++)  /* a live unit vanishes only if remote deleted it */
                        if (position(&got, live.text[i]) == SIZE_MAX)
                            CHECK(was_durable[live.text[i]] && position(&now, live.text[i]) == SIZE_MAX);
                    live = got;
                } else if (st == TS_CONFLICT) {
                    conflicts++;
                    CHECK(after.phase == TI_CONFLICT && same(got.text, got.length, live.text, live.length));
                    if (next() % 2) {
                        model now; td_snapshot c = current(s, doc.record.id, now.text);
                        MUST(ti_resolve_keep(q, c.record.revision) == TS_OK);
                    } else {
                        /* Owner chooses the durable text: reopen the queue from storage. */
                        ti_close(q);
                        doc = current(s, doc.record.id, live.text); live.length = doc.length;
                        MUST(ti_open(&q, &doc, live.text, round % 5 ? 0 : 200) == TS_OK);
                        for (size_t i = 0; i < live.length; i++) was_local[live.text[i]] = 1;
                    }
                } else {
                    CHECK(st == TS_OK || st == TS_NOT_FOUND);
                    CHECK(same(got.text, got.length, live.text, live.length));
                    if (st == TS_OK) saves++;
                }
            } else if (r < 90) {
                /* Remote revision: a local-style random edit of the durable text. */
                model d; td_snapshot cur = current(s, doc.record.id, d.text); d.length = cur.length;
                size_t a = next() % (d.length + 1), b = a + next() % (d.length - a + 1) % 3, n = next() % 3;
                uint16_t ins[4]; for (size_t i = 0; i < n; i++) ins[i] = fresh++;
                put(&d, a, b, ins, n);
                external(s, doc.record.id, d.text, d.length);
            } else {
                /* Lost save: captured, then the store fails before acknowledging. */
                uint16_t buf[CAP]; ti_captured c;
                if (ti_capture(q, buf, CAP, &c) == TS_OK) MUST(ti_reject(q, c.local) == TS_OK);
            }
        }
        /* Drain: every acknowledged save leaves durable == live once clean. */
        for (int i = 0; i < 32; i++) {
            ti_state st; ti_state_get(q, &st);
            if (st.phase == TI_CLEAN) {
                /* A clean queue learns of external revisions only from its storage owner. */
                model now; td_snapshot c = current(s, doc.record.id, now.text);
                if (c.record.revision == st.durable) break;
                CHECK(ti_rebase(q, &c, now.text) == TS_OK);
                continue;
            }
            if (st.phase == TI_CONFLICT) {
                model now; td_snapshot c = current(s, doc.record.id, now.text);
                MUST(ti_resolve_keep(q, c.record.revision) == TS_OK);
            }
            ts_status ps = persist(s, q);
            CHECK(ps == TS_OK || ps == TS_CONFLICT);
        }
        model got, now; td_value v; ti_state st; view(q, &got, &v, &st);
        td_snapshot c = current(s, doc.record.id, now.text); now.length = c.length;
        CHECK(st.phase == TI_CLEAN && same(got.text, got.length, now.text, now.length) && st.durable == c.record.revision);
        ti_close(q);
    }
    CHECK(merges > 50 && conflicts > 10 && saves > 500);
    printf("ok draft differential: %u edits, %u saves, %u merges, %u conflicts over 60 documents\n", edits, saves, merges, conflicts);
    ts_close(s);
}

/* Interaction and storage owners on separate threads. The interaction thread never blocks
 * on storage; the final drained durable text must equal the independently tracked live text. */
typedef struct { ti_queue *q; model live; int done; } shared;
static void *typist(void *arg) {
    shared *sh = arg;
    for (int i = 0; i < 4000; i++) {
        size_t n = sh->live.length;
        td_edit e;
        uint16_t u = (uint16_t)(0x100 + i);
        if (n > 50 && i % 3 == 0) e = (td_edit){n - 1, n, NULL, 0};
        else e = (td_edit){n, n, &u, 1};
        MUST(ti_edit(sh->q, &e, 1, 0, 0, -1, -1, NULL) == TS_OK);
        put(&sh->live, e.start, e.end, e.text, e.length);
    }
    __atomic_store_n(&sh->done, 1, __ATOMIC_RELEASE);
    return NULL;
}
static void threads(void) {
    ts_store *s; MUST(ts_open(path("threads"), &s) == TS_OK);
    static shared sh;
    td_snapshot doc = create(s, NULL, 0);
    MUST(ti_open(&sh.q, &doc, NULL, 0) == TS_OK);
    pthread_t t; MUST(!pthread_create(&t, NULL, typist, &sh));
    unsigned saves = 0;
    while (!__atomic_load_n(&sh.done, __ATOMIC_ACQUIRE)) if (persist(s, sh.q) == TS_OK) saves++;
    pthread_join(t, NULL);
    while (persist(s, sh.q) == TS_OK) saves++;
    uint16_t buf[CAP]; td_snapshot r = current(s, doc.record.id, buf);
    ti_state st; ti_state_get(sh.q, &st);
    CHECK(st.phase == TI_CLEAN && st.local == 4000 && same(buf, r.length, sh.live.text, sh.live.length));
    CHECK(saves >= 1 && saves <= 4000);
    printf("ok draft threads: 4000 edits coalesced into %u durable saves while typing continued\n", saves);
    ti_close(sh.q); ts_close(s);
}

int main(int argc, char **argv) {
    if (argc != 2 || strlen(argv[1]) >= sizeof base) { fprintf(stderr, "usage: core-drafts <directory>\n"); return 2; }
    strcpy(base, argv[1]); mkdir(base, 0700);
    basics(); rebases(); overflow(); differential(); threads();
    if (failures) { fprintf(stderr, "core-drafts: %d failures\n", failures); return 1; }
    puts("core-drafts: all checks passed (input persistence queue semantics, not Android InputConnection evidence)"); return 0;
}
