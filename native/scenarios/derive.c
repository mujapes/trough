/* core.derive: the derivation graph (docs/DERIVE.md) against a from-scratch reference.
 * Five operator levels with value-dependent dependencies run as "workers" that see only job
 * values. A random driver interleaves cell edits, module replacement, cell removal, demand,
 * eviction, cancellation and out-of-order completion (including completion after the world
 * moved on). Whenever the graph reports idle, every demanded node, and every node it reports
 * clean, must equal a memo-free evaluation of the current world. Deterministic cases check
 * that unchanged inputs cost nothing, early cutoff, cycles and capacity bounds. */
#include "derive.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define OK(x) do { tr_status s_ = (x); if (s_ != TR_OK) { fprintf(stderr, "%s:%d %s -> %d\n", __FILE__, __LINE__, #x, s_); abort(); } } while (0)
#define CHECK(c) do { if (!(c)) { fprintf(stderr, "%s:%d check failed: %s\n", __FILE__, __LINE__, #c); abort(); } } while (0)

enum { K = 12, P = 16, OPS = 5, CYC = 5, MAXV = 64, JOBS = 6 };
static tr_world world; static tr_cell cells[64];
static tr_id cell[K], op[OPS + 1], driver;
static dv_graph g; static dv_node nodes[256]; static dv_block blocks[1024]; static uint32_t table[512]; static dv_frame path[256];
static uint64_t computed[OPS + 1];
static uint64_t rng = 0x9e3779b97f4a7c15ULL;
static uint64_t next(void) { rng ^= rng << 13; rng ^= rng >> 7; rng ^= rng << 17; return rng; }
static uint64_t mix(uint64_t x) { x ^= x >> 31; x *= 0x7fb5d329728ea185ULL; x ^= x >> 27; x *= 0x81dadef4bc2dd44dULL; return x ^ (x >> 33); }

static void set(tr_id id, uint64_t value) {
    tr_transaction t; tr_read r[4]; tr_write w[1]; tr_receipt rc;
    OK(tr_begin(&t, &world, driver, r, 4, w, 1));
    OK(tr_write_value(&t, id, value));
    OK(tr_commit(&t, &rc));
}
static int op_index(tr_id id) {
    for (int i = 0; i <= OPS; i++) if (op[i].slot == id.slot && op[i].generation == id.generation) return i;
    abort();
}

/* ---- operators: a reader supplies values or reports them missing ---- */
typedef struct reader {
    int (*read)(struct reader *, dv_ref, uint64_t *, uint32_t *);
    dv_ref used[MAXV]; size_t nused;
    dv_ref need; int missing;
} reader;
static int get(reader *r, dv_ref ref, uint64_t *v, uint32_t *presence) {
    if (!r->read(r, ref, v, presence)) { r->need = ref; r->missing = 1; return 0; }
    CHECK(r->nused < MAXV);
    r->used[r->nused++] = ref;
    return 1;
}
static int in_cell(reader *r, int i, uint64_t *v) {
    dv_ref ref = {DV_CELL, cell[i], 0}; uint32_t p;
    if (!get(r, ref, v, &p)) return 0;
    if (p == DV_ABSENT) *v = 7777;
    return 1;
}
static int in_node(reader *r, int o, uint64_t key, uint64_t *v, uint32_t *presence) {
    dv_ref ref = {DV_NODE, op[o], key % P}; uint32_t p = DV_PRESENT;
    if (!get(r, ref, v, &p)) return 0;
    if (presence) *presence = p;
    return 1;
}
/* 0 with r->missing when a value is missing. */
static int eval(int o, uint64_t version, uint64_t key, reader *r, uint64_t *out) {
    uint64_t a, b, c;
    switch (o) {
    case 0: if (!in_cell(r, (int)(key % K), &a)) return 0; *out = mix(version * 31 + a); return 1;
    case 1:
        if (!in_cell(r, (int)(key % K), &c) || !in_node(r, 0, key + 1 + (c & 1), &a, NULL)) return 0;
        *out = mix(version ^ (a * 3)); return 1;
    case 2: {
        if (!in_cell(r, (int)((key * 3 + 1) % K), &c)) return 0;
        uint64_t sum = version;
        for (uint64_t i = 0; i < c % 4; i++) { if (!in_node(r, 1, key + i, &a, NULL)) return 0; sum += a; }
        *out = sum; return 1;
    }
    case 3: if (!in_node(r, 2, key, &a, NULL)) return 0; *out = (a + version) % 3; return 1;
    case 4:
        if (!in_node(r, 3, key, &a, NULL) || !in_node(r, 3, key + 1, &b, NULL) || !in_node(r, 1, key, &c, NULL)) return 0;
        *out = mix(a * 9 + b * 3 + version) + (c & 1); return 1;
    case CYC: {                       /* key k reads k^1 when cell 0 is odd: a two-node cycle */
        uint32_t p;
        if (!in_cell(r, 0, &c)) return 0;
        if (!(c & 1)) { *out = 100 + key; return 1; }
        if (!in_node(r, CYC, key ^ 1, &a, &p)) return 0;
        *out = p == DV_CYCLE ? 999 : a + 1; return 1;
    }
    }
    abort();
}

/* Reference: the current world, recursively, no memo. */
static int ref_read(reader *r, dv_ref ref, uint64_t *v, uint32_t *p) {
    (void)r; *p = DV_PRESENT;
    if (ref.kind == DV_CELL) {
        tr_read c;
        if (tr_current(&world, ref.id, &c) != TR_OK) { *p = DV_ABSENT; *v = 0; } else *v = c.value;
        return 1;
    }
    int o = op_index(ref.id); tr_read m;
    OK(tr_current(&world, op[o], &m));
    reader sub = {ref_read, {{0}}, 0, {0}, 0};
    CHECK(eval(o, m.value, ref.key, &sub, v));
    return 1;
}
static uint64_t reference(int o, uint64_t key) {
    uint64_t v; dv_ref ref = {DV_NODE, op[o], key}; uint32_t p;
    ref_read(NULL, ref, &v, &p);
    return v;
}

/* Worker: only the job's values. */
static const dv_job *current_job;
static int job_read(reader *r, dv_ref ref, uint64_t *v, uint32_t *p) {
    (void)r;
    for (size_t k = 0; k < current_job->count; k++) {
        const dv_value *x = &current_job->values[k];
        if (x->ref.kind == ref.kind && x->ref.id.slot == ref.id.slot && x->ref.id.generation == ref.id.generation
            && (ref.kind != DV_NODE || x->ref.key == ref.key)) { *v = x->value; *p = x->presence; return 1; }
    }
    return 0;
}
static uint64_t restarts, jobs_run;
static tr_status run(const dv_job *job) {
    reader r = {job_read, {{0}}, 0, {0}, 0};
    current_job = job;
    uint64_t v = 0;
    int o = op_index(job->op);
    computed[o]++;
    dv_result res;
    if (eval(o, job->op_value, job->key, &r, &v)) res = (dv_result){DV_RESULT_VALUE, v, r.used, r.nused};
    else { CHECK(r.missing); restarts++; res = (dv_result){DV_RESULT_NEED, 0, &r.need, 1}; }
    return dv_complete(&g, job, &res);
}

static dv_value storage[JOBS][MAXV];
static dv_job pool[JOBS];
static size_t inflight;
static void prepare(dv_job *jobs, size_t n, size_t first) {
    for (size_t i = 0; i < n; i++) { jobs[i].values = storage[first + i]; jobs[i].capacity = MAXV; }
}
/* Runs until idle, completing every job at once. Returns the jobs run. */
static uint64_t settle(void) {
    uint64_t total = 0;
    for (;;) {
        dv_job jobs[JOBS]; size_t n; dv_progress pr;
        prepare(jobs, JOBS, 0);
        OK(dv_poll(&g, jobs, JOBS, &n, &pr));
        if (pr == DV_IDLE) return total;
        CHECK(pr == DV_READY);
        for (size_t i = 0; i < n; i++) { OK(run(&jobs[i])); total++; }
    }
}
static uint32_t node(int o, uint64_t key) { uint32_t n; OK(dv_intern(&g, op[o], key % P, &n)); return n; }
static uint64_t value(uint32_t n) { uint64_t v; OK(dv_get(&g, n, &v)); return v; }
static uint64_t sum_computed(void) { uint64_t s = 0; for (int i = 0; i <= OPS; i++) s += computed[i]; return s; }

static int static_ops;   /* op0, op3, op4 always read the same dependencies */
static void setup(void) {
    OK(tr_world_init(&world, cells, 64));
    OK(tr_create(&world, 1, &driver));
    for (int i = 0; i < K; i++) OK(tr_create(&world, (uint64_t)i * 7 + 1, &cell[i]));
    for (int i = 0; i <= OPS; i++) OK(tr_create(&world, 1, &op[i]));
    OK(dv_init(&g, &world, nodes, 256, blocks, 1024, table, 512, path));
    if (static_ops) { OK(dv_set_static(&g, op[0])); OK(dv_set_static(&g, op[3])); OK(dv_set_static(&g, op[4])); }
    memset(computed, 0, sizeof computed);
}

static void deterministic(void) {
    setup();
    uint32_t top = node(4, 3);
    OK(dv_demand(&g, top));
    CHECK(dv_get(&g, top, &(uint64_t){0}) == TR_STALE);
    uint64_t first = settle();
    CHECK(value(top) == reference(4, 3));
    /* Nothing changed: verifying costs no job. */
    CHECK(settle() == 0);
    /* A cell nothing demanded reads: the publication moves, no job runs. */
    uint64_t before = sum_computed();
    set(cell[11], 5000);                                  /* top(3) never reads cell 11 */
    CHECK(settle() == 0 && sum_computed() == before && value(top) == reference(4, 3));
    printf("ok first evaluation %llu jobs (%llu restarts); unchanged world and unrelated cells cost 0 jobs\n",
           (unsigned long long)first, (unsigned long long)restarts);

    /* Early cutoff: op3 = (op2 + v) % 3. Find an edit of a cell op2(3) reads where op2 changes
     * but op3 does not: op4 must not run. op2(3) reads cell 10 (fan-out 3: op1(3..5)). */
    int cut = 0;
    uint32_t n2 = node(2, 3), n3 = node(3, 3), n3b = node(3, 4), n1 = node(1, 3);
    for (uint64_t trial = 0; trial < 64; trial++) {
        uint64_t v2 = value(n2), v3 = value(n3), v3b = value(n3b), v1 = value(n1);
        uint64_t c4 = computed[4];
        set(cell[(trial % 3) + 3], 1000 + trial);         /* cells 3..5: under op2(3) */
        settle();
        CHECK(value(top) == reference(4, 3));
        int inputs_same = value(n3) == v3 && value(n3b) == v3b && value(n1) == v1;
        if (inputs_same) CHECK(computed[4] == c4);        /* op4's inputs equal: it must not run */
        if (inputs_same && value(n2) != v2) cut++;
    }
    CHECK(cut);
    printf("ok early cutoff: %d edits changed op2 but not op4's inputs, and op4 never ran\n", cut);

    /* Module replacement reruns exactly the nodes of that operator and what changes above. */
    uint64_t c0 = computed[0];
    tr_read m; OK(tr_current(&world, op[0], &m));
    set(op[0], m.value + 1);
    settle();
    CHECK(computed[0] > c0 && value(top) == reference(4, 3));
    printf("ok module replacement reruns its operator's nodes\n");

    /* A removed cell reads as absent, once; it stays verified afterwards. */
    OK(tr_remove(&world, cell[3]));
    settle();
    CHECK(value(top) == reference(4, 3));
    CHECK(settle() == 0);
    printf("ok a removed input is a stable absent value\n");

    /* Cancellation: the late completion is refused and the node is reissued. */
    set(cell[3 + 1], 4242);
    { dv_job jobs[JOBS]; size_t n; dv_progress pr; prepare(jobs, JOBS, 0);
      OK(dv_poll(&g, jobs, JOBS, &n, &pr)); CHECK(n > 0);
      OK(dv_cancel(&g, &jobs[0]));
      CHECK(run(&jobs[0]) == TR_STALE);
      for (size_t i = 1; i < n; i++) OK(run(&jobs[i])); }
    settle();
    CHECK(value(top) == reference(4, 3));
    set(cell[3 + 1], 4343);
    { dv_job jobs[JOBS], again[JOBS]; size_t n, m; dv_progress pr; prepare(jobs, JOBS, 0);
      OK(dv_poll(&g, jobs, 1, &n, &pr)); CHECK(n == 1);
      OK(dv_cancel(&g, &jobs[0]));
      prepare(again, 1, 1);
      OK(dv_poll(&g, again, 1, &m, &pr)); CHECK(m == 1 && again[0].node == jobs[0].node);
      CHECK(run(&jobs[0]) == TR_STALE);                    /* the old ticket, while the new one runs */
      OK(run(&again[0])); }
    settle();
    CHECK(value(top) == reference(4, 3));
    printf("ok cancelled job: late completion is stale, also while its reissue runs\n");

    /* Eviction refuses demanded nodes; an evicted dependency makes dependants recompute. */
    CHECK(dv_evict(&g, top) == TR_INVALID);
    n3 = node(3, 3);
    OK(dv_evict(&g, n3));
    settle();
    CHECK(value(top) == reference(4, 3));
    int moved = 0;                                        /* evict, then change what it computed */
    for (uint64_t trial = 0; trial < 64 && !moved; trial++) {
        n3 = node(3, 3);
        uint64_t before3 = reference(3, 3);
        OK(dv_evict(&g, n3));
        set(cell[10], 2000 + trial);                      /* op2(3)'s fan-out */
        if (reference(3, 3) != before3) moved = 1;
        settle();
        CHECK(value(top) == reference(4, 3));
    }
    CHECK(moved);
    printf("ok eviction: demanded nodes refused, evicted dependency recomputed after its inputs change\n");

    /* A result naming a read it was not given, or needing what it was given, is refused. */
    { tr_read m4; OK(tr_current(&world, op[4], &m4)); set(op[4], m4.value + 1); }   /* top must rerun */
    { dv_job jobs[JOBS]; size_t n; dv_progress pr; prepare(jobs, JOBS, 0);
      OK(dv_poll(&g, jobs, 1, &n, &pr)); CHECK(n == 1 && jobs[0].node == top);
      dv_ref stranger = {DV_CELL, cell[11], 0};
      dv_result bad = {DV_RESULT_VALUE, 1, &stranger, 1};
      CHECK(dv_complete(&g, &jobs[0], &bad) == TR_INVALID);
      CHECK(dv_get(&g, jobs[0].node, &(uint64_t){0}) == TR_STALE);
      OK(dv_poll(&g, jobs, 1, &n, &pr)); CHECK(n == 1);
      if (jobs[0].count) {
          dv_result again = {DV_RESULT_NEED, 0, &jobs[0].values[0].ref, 1};
          CHECK(dv_complete(&g, &jobs[0], &again) == TR_INVALID);
      } else OK(dv_cancel(&g, &jobs[0])); }
    settle();
    CHECK(value(top) == reference(4, 3));
    printf("ok invalid results refused: unprovided reads, needs already provided\n");

    /* A dependency the operator no longer reads is not recomputed: op1(9) reads op0(10) while
     * cell 9 is even, op0(11) once it is odd. Change op0(10)'s input and flip cell 9 together. */
    uint32_t sw = node(1, 9);
    OK(dv_demand(&g, sw));
    set(cell[9], 2);
    settle();
    CHECK(value(sw) == reference(1, 9) && dv_get(&g, node(0, 10), &(uint64_t){0}) == TR_OK);
    set(cell[10], 6060); set(cell[9], 3);
    settle();
    CHECK(value(sw) == reference(1, 9));
    CHECK(dv_get(&g, node(0, 10), &(uint64_t){0}) == TR_STALE);
    OK(dv_release(&g, sw));
    printf("ok a dependency no longer read is not recomputed\n");

    /* Cycle: cell 0 odd makes CYC(0) and CYC(1) read each other. The walk provides DV_CYCLE to
     * the inner node; the result settles in one revision and recomputes on the next. */
    set(cell[0], 1);
    uint32_t y0 = node(CYC, 0);
    OK(dv_demand(&g, y0));
    uint64_t jobs_cyc = settle();
    uint64_t v0 = value(y0), v1;
    CHECK(v0 == 1000);                                    /* y0 = y1 + 1, y1 = 999 (cycle) */
    CHECK(dv_get(&g, node(CYC, 1), &v1) == TR_OK && v1 == 999);
    set(cell[0], 2);
    settle();
    CHECK(value(y0) == 100);
    set(cell[0], 3);
    settle();
    CHECK(value(y0) == 1000);
    OK(dv_release(&g, y0));
    /* Cycle values depend on the demand path; a node that saw DV_CYCLE is not reused across
     * revisions. Entered first, y1 now computes and y0 sees the cycle. */
    uint32_t y1 = node(CYC, 1);
    OK(dv_demand(&g, y1));
    set(cell[11], 7);                                     /* a new revision, cycle unchanged */
    settle();
    CHECK(value(y1) == 1000 && value(y0) == 999);
    OK(dv_release(&g, y1));
    printf("ok cycle: provided as DV_CYCLE, settles in %llu jobs, recovers when broken\n", (unsigned long long)jobs_cyc);

    /* Capacity: a graph with room for 3 nodes cannot intern op4's dependencies. */
    dv_graph small; dv_node sn[3]; dv_block sb[8]; uint32_t st[8]; dv_frame sp[3];
    OK(dv_init(&small, &world, sn, 3, sb, 8, st, 8, sp));
    uint32_t sroot; OK(dv_intern(&small, op[4], 0, &sroot)); OK(dv_demand(&small, sroot));
    tr_status last = TR_OK;
    for (int round = 0; round < 10 && last == TR_OK; round++) {
        dv_job jobs[1]; size_t n; dv_progress pr; prepare(jobs, 1, 0);
        OK(dv_poll(&small, jobs, 1, &n, &pr));
        if (!n) break;
        dv_graph *saved = &g; (void)saved;
        reader r = {job_read, {{0}}, 0, {0}, 0}; current_job = &jobs[0];
        uint64_t v;
        int done = eval(op_index(jobs[0].op), jobs[0].op_value, jobs[0].key, &r, &v);
        dv_result res = done ? (dv_result){DV_RESULT_VALUE, v, r.used, r.nused} : (dv_result){DV_RESULT_NEED, 0, &r.need, 1};
        last = dv_complete(&small, &jobs[0], &res);
    }
    CHECK(last == TR_CAPACITY);
    printf("ok capacity: interning past the node bound is TR_CAPACITY\n");
}

/* Demand levels: urgent roots are issued first, a background limit refuses without starving
 * the walk, running work stays wanted when capacity runs out, and a job's level follows its
 * most urgent demand. */
static void levels(void) {
    setup();
    uint32_t a = node(0, 1), b = node(0, 2), c = node(0, 3);   /* independent op0 nodes */
    OK(dv_demand_at(&g, a, DV_BACKGROUND)); OK(dv_demand_at(&g, b, DV_VISIBLE)); OK(dv_demand_at(&g, c, DV_URGENT));
    settle();                                        /* first runs restart for their cells */
    set(cell[1], 99); set(cell[2], 99); set(cell[3], 99);
    dv_job jobs[JOBS]; size_t n; dv_progress pr;
    prepare(jobs, JOBS, 0);
    OK(dv_poll(&g, jobs, 1, &n, &pr));
    CHECK(n == 1 && jobs[0].node == c && jobs[0].level == DV_URGENT);
    CHECK(dv_job_level(&g, &jobs[0]) == DV_URGENT);
    prepare(&jobs[1], 2, 1);
    OK(dv_poll(&g, &jobs[1], 2, &n, &pr));          /* c is running: b, then a */
    CHECK(n == 2 && jobs[1].node == b && jobs[1].level == DV_VISIBLE && jobs[2].node == a && jobs[2].level == DV_BACKGROUND);
    for (int i = 0; i < 3; i++) OK(run(&jobs[i]));
    CHECK(value(a) == reference(0, 1) && value(b) == reference(0, 2) && value(c) == reference(0, 3));

    /* A background limit of 0: a is refused, the poll waits, the others are issued. */
    set(cell[1], 100); set(cell[2], 100); set(cell[3], 100);
    size_t none[DV_LEVELS] = {JOBS, JOBS, 0}, all[DV_LEVELS] = {JOBS, JOBS, JOBS};
    prepare(jobs, JOBS, 0);
    OK(dv_poll_levels(&g, jobs, JOBS, none, &n, &pr));
    CHECK(n == 2 && jobs[0].node == c && jobs[1].node == b && pr == DV_READY);
    OK(run(&jobs[0])); OK(run(&jobs[1]));
    prepare(jobs, JOBS, 0);
    OK(dv_poll_levels(&g, jobs, JOBS, none, &n, &pr));
    CHECK(n == 0 && pr == DV_WAITING);
    OK(dv_poll_levels(&g, jobs, JOBS, all, &n, &pr));
    CHECK(n == 1 && jobs[0].node == a);
    OK(run(&jobs[0]));
    prepare(jobs, JOBS, 0);
    OK(dv_poll(&g, jobs, JOBS, &n, &pr)); CHECK(pr == DV_IDLE);

    /* Capacity runs out at an earlier root: a later root's running job is still wanted. */
    set(cell[1], 101); set(cell[2], 101);
    prepare(jobs, JOBS, 0);
    OK(dv_poll(&g, jobs, 2, &n, &pr));
    CHECK(n == 2 && jobs[0].node == b && jobs[1].node == a);
    OK(run(&jobs[0]));
    set(cell[2], 102);                               /* b has new work; a still runs */
    prepare(&jobs[2], 1, 2);
    OK(dv_poll(&g, &jobs[2], 1, &n, &pr));
    CHECK(n == 1 && jobs[2].node == b);
    CHECK(dv_wanted(&g, &jobs[1]) && dv_job_level(&g, &jobs[1]) == DV_BACKGROUND);
    OK(run(&jobs[1])); OK(run(&jobs[2]));

    /* A running background job follows its demand: urgent while urgently demanded too, back to
     * background, unwanted when released. */
    set(cell[1], 103);
    size_t one[DV_LEVELS] = {JOBS, JOBS, 1};
    prepare(jobs, JOBS, 0);
    OK(dv_poll_levels(&g, jobs, JOBS, one, &n, &pr));
    CHECK(n == 1 && jobs[0].node == a && jobs[0].level == DV_BACKGROUND);
    OK(dv_demand_at(&g, a, DV_URGENT));
    prepare(&jobs[1], 1, 1);
    OK(dv_poll(&g, &jobs[1], 1, &n, &pr)); CHECK(n == 0 && dv_job_level(&g, &jobs[0]) == DV_URGENT);
    CHECK(dv_release_at(&g, a, DV_VISIBLE) == TR_INVALID);       /* never demanded at that level */
    OK(dv_release_at(&g, a, DV_URGENT));
    OK(dv_poll(&g, &jobs[1], 1, &n, &pr)); CHECK(dv_job_level(&g, &jobs[0]) == DV_BACKGROUND);
    OK(dv_release_at(&g, a, DV_BACKGROUND));
    CHECK(dv_evict(&g, b) == TR_INVALID);                         /* demanded at one level */
    OK(dv_poll(&g, &jobs[1], 1, &n, &pr)); CHECK(!dv_wanted(&g, &jobs[0]) && dv_job_level(&g, &jobs[0]) == DV_LEVELS);
    OK(dv_cancel(&g, &jobs[0]));
    printf("ok levels: issued urgent first, background limit refuses without dropping running work, levels follow demand\n");
}

static void randomized(int rounds) {
    setup();
    uint32_t roots[8], level[8]; int nroots = 0;
    uint64_t checks = 0, stale = 0, cancelled = 0, evicted = 0, removed = 0, edits = 0, late = 0;
    uint64_t jobs_total = 0, scratch_total = 0;
    for (int round = 0; round < rounds; round++) {
        /* Owner changes. */
        int changes = (int)(next() % 4);
        for (int i = 0; i < changes; i++) {
            uint64_t x = next() % 100;
            if (x < 70) { int c = (int)(next() % K); tr_read r; if (tr_current(&world, cell[c], &r) == TR_OK) { set(cell[c], next() % 9); edits++; } }
            else if (x < 82) { int o = (int)(next() % OPS); tr_read r; OK(tr_current(&world, op[o], &r)); set(op[o], r.value + 1); edits++; }
            else if (x < 84 && removed < 3) { int c = (int)(next() % K); if (tr_remove(&world, cell[c]) == TR_OK) removed++; }
            else if (x < 92 && nroots < 8) {
                roots[nroots] = node((int)(next() % OPS), next()); level[nroots] = (uint32_t)(next() % DV_LEVELS);
                OK(dv_demand_at(&g, roots[nroots], level[nroots])); nroots++;
            } else if (nroots > 1) {
                int k = (int)(next() % (uint64_t)nroots); OK(dv_release_at(&g, roots[k], level[k]));
                nroots--; roots[k] = roots[nroots]; level[k] = level[nroots];
            }
            else {                                         /* evict a random live node */
                uint32_t start = (uint32_t)(next() % 256);
                for (uint32_t j = 0; j < 256; j++) {
                    uint32_t v = (start + j) % 256;
                    if (nodes[v].live) { if (dv_evict(&g, v) == TR_OK) evicted++; break; }
                }
            }
        }
        /* Execution with up to JOBS in flight, completed in random order; the world may move
         * while they run. */
        for (int steps = 0; steps < 400; steps++) {
            dv_progress pr = DV_WAITING;
            if (inflight < JOBS) {
                size_t n;
                prepare(&pool[inflight], JOBS - inflight, inflight);
                dv_job fresh[JOBS];
                for (size_t i = 0; i < JOBS - inflight; i++) fresh[i] = pool[inflight + i];
                size_t limits[DV_LEVELS] = {JOBS, JOBS, JOBS};   /* random admission while work runs */
                if (inflight) for (int l = 0; l < DV_LEVELS; l++) if (!(next() % 4)) limits[l] = next() % 2;
                OK(dv_poll_levels(&g, fresh, JOBS - inflight, limits, &n, &pr));
                size_t used[DV_LEVELS] = {0};
                for (size_t i = 0; i < n; i++) {
                    CHECK(!i || fresh[i].level >= fresh[i - 1].level);   /* most urgent first */
                    CHECK(++used[fresh[i].level] <= limits[fresh[i].level]);
                    pool[inflight + i] = fresh[i];
                }
                for (size_t i = 0; i < inflight; i++) CHECK(dv_wanted(&g, &pool[i]) || dv_job_level(&g, &pool[i]) == DV_LEVELS);
                inflight += n;
            }
            if (!inflight) { CHECK(pr == DV_IDLE); break; }
            size_t k = (size_t)(next() % inflight);
            uint64_t x = next() % 100;
            tr_status s;
            if (x < 6) { OK(dv_cancel(&g, &pool[k])); s = run(&pool[k]); CHECK(s == TR_STALE); cancelled++; }
            else {
                if (x < 12) { int c = (int)(next() % K); tr_read r; if (tr_current(&world, cell[c], &r) == TR_OK) { set(cell[c], next() % 9); late++; } }
                s = run(&pool[k]);
                if (s == TR_STALE) stale++; else OK(s);
                jobs_total++;
            }
            /* Keep values with their job: move the last job (and its storage) into the hole. */
            inflight--;
            if (k != inflight) {
                memcpy(storage[k], storage[inflight], sizeof storage[k]);
                pool[k] = pool[inflight]; pool[k].values = storage[k];
            }
        }
        CHECK(inflight == 0);
        /* Idle: everything demanded, and everything reported clean, equals the reference. */
        for (int i = 0; i < nroots; i++) {
            const dv_node *n = &nodes[roots[i]];
            CHECK(value(roots[i]) == reference(op_index(n->op), n->key)); checks++;
        }
        for (uint32_t i = 0; i < 256; i++) {
            uint64_t v;
            if (nodes[i].live && dv_get(&g, i, &v) == TR_OK) { CHECK(v == reference(op_index(nodes[i].op), nodes[i].key)); checks++; }
        }
        /* Cost of answering the same demand from scratch: one evaluation per reachable node. */
        scratch_total += (uint64_t)nroots;
    }
    jobs_run = jobs_total;
    printf("ok random: %d rounds, %llu checks equal the reference; %llu jobs, %llu restarts, %llu edits "
           "(%llu while jobs ran), %llu cancelled, %llu evicted, %llu removed inputs\n",
           rounds, (unsigned long long)checks, (unsigned long long)jobs_total, (unsigned long long)restarts,
           (unsigned long long)edits, (unsigned long long)late, (unsigned long long)cancelled,
           (unsigned long long)evicted, (unsigned long long)removed);
    (void)stale; (void)scratch_total;
    printf("{\"ok\":true,\"rounds\":%d,\"checks\":%llu,\"jobs\":%llu,\"restarts\":%llu}\n", rounds,
           (unsigned long long)checks, (unsigned long long)jobs_total, (unsigned long long)restarts);
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    deterministic();
    levels();
    restarts = 0;
    int rounds = argc > 1 ? atoi(argv[1]) : 3000;
    uint64_t seed = rng;
    randomized(rounds);
    uint64_t dynamic_restarts = restarts, dynamic_jobs = jobs_run;
    /* Again with op0, op3 and op4 declared static: the same reference, fewer restarts. */
    static_ops = 1; restarts = 0; rng = seed;
    randomized(rounds);
    /* The random stream also drives job interleaving, so the runs differ: compare ratios. */
    CHECK(restarts * dynamic_jobs < dynamic_restarts * jobs_run);
    printf("ok static dependencies: restarts per job %.3f -> %.3f, both runs equal the reference\n",
           (double)dynamic_restarts / (double)dynamic_jobs, (double)restarts / (double)jobs_run);
    return 0;
}
