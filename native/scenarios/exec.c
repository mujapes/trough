/* core.exec: the derivation executor (docs/EXEC.md). Client threads edit, demand and release
 * through owner calls while workers run operators on snapshots; every node the graph reports
 * clean must equal a memo-free evaluation of the world at that moment. Also: a running job is
 * cancelled promptly when its demand goes away, independent work scales across workers, an
 * unregistered operator is a sticky error, and settled notifications advance. */
#define _POSIX_C_SOURCE 200809L
#include "exec.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define OK(x) do { tr_status s_ = (x); if (s_ != TR_OK) { fprintf(stderr, "%s:%d %s -> %d\n", __FILE__, __LINE__, #x, s_); abort(); } } while (0)
#define CHECK(c) do { if (!(c)) { fprintf(stderr, "%s:%d check failed: %s\n", __FILE__, __LINE__, #c); abort(); } } while (0)

enum { K = 12, P = 16, OPS = 4, NODES = 256 };
static tr_world world; static tr_cell cells[64];
static tr_id cell[K], op[8], driver;
static dv_graph graph; static dv_node nodes[NODES]; static dv_block blocks[2048]; static uint32_t table[512]; static dv_frame path[NODES];
static ex_exec ex;

static uint64_t now_ns(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (uint64_t)t.tv_sec * 1000000000u + (uint64_t)t.tv_nsec; }
static uint64_t mix(uint64_t x) { x ^= x >> 31; x *= 0x7fb5d329728ea185ULL; x ^= x >> 27; x *= 0x81dadef4bc2dd44dULL; return x ^ (x >> 33); }
static void set(tr_world *w, tr_id id, uint64_t value) {
    tr_transaction t; tr_read r[4]; tr_write wr[1]; tr_receipt rc;
    OK(tr_begin(&t, w, driver, r, 4, wr, 1)); OK(tr_write_value(&t, id, value)); OK(tr_commit(&t, &rc));
}
static int op_index(tr_id id) {
    for (int i = 0; i < 8; i++) if (op[i].slot == id.slot && op[i].generation == id.generation) return i;
    abort();
}
/* Busy work that notices cancellation: about `us` microseconds. */
static int spin(const dv_job *job, uint64_t us) {
    uint64_t end = now_ns() + us * 1000;
    while (now_ns() < end) if (ex_cancelled(job)) return 1;
    return 0;
}

/* ---- operators, shared by workers (ex_read) and the reference (world) ---- */
typedef struct src {
    int (*get)(struct src *, dv_ref, uint64_t *, uint32_t *);
    const dv_job *job; ex_out *out;
} src;
static int cellv(src *s, int i, uint64_t *v) {
    uint32_t p; dv_ref r = {DV_CELL, cell[i], 0};
    if (!s->get(s, r, v, &p)) return 0;
    if (p == DV_ABSENT) *v = 7777;
    return 1;
}
static int nodev(src *s, int o, uint64_t key, uint64_t *v) { uint32_t p; dv_ref r = {DV_NODE, op[o], key % P}; return s->get(s, r, v, &p); }
static int eval(int o, uint64_t ver, uint64_t key, src *s, uint64_t *out) {
    uint64_t a, b, c;
    switch (o) {
    case 0: if (!cellv(s, (int)(key % K), &a)) return 0; *out = mix(ver * 31 + a); return 1;
    case 1: if (!cellv(s, (int)(key % K), &c) || !nodev(s, 0, key + 1 + (c & 1), &a)) return 0; *out = mix(ver ^ (a * 3)); return 1;
    case 2: {
        if (!cellv(s, (int)((key * 3 + 1) % K), &c)) return 0;
        uint64_t sum = ver;
        for (uint64_t i = 0; i < c % 4; i++) { if (!nodev(s, 1, key + i, &a)) return 0; sum += a; }
        *out = sum; return 1;
    }
    case 3: if (!nodev(s, 2, key, &a) || !nodev(s, 2, key + 1, &b)) return 0; *out = (a + b + ver) % 5; return 1;
    }
    abort();
}
static int job_get(src *s, dv_ref r, uint64_t *v, uint32_t *p) {
    if (ex_read(s->job, s->out, r, v, p)) return 1;
    ex_need(s->out, r);
    return 0;
}
static int run_op(void *ctx, const dv_job *job, ex_out *out) {
    (void)ctx;
    if (spin(job, mix(job->ticket) % 40)) return 1;
    src s = {job_get, job, out};
    uint64_t v;
    if (eval(op_index(job->op), job->op_value, job->key, &s, &v)) { out->kind = DV_RESULT_VALUE; out->value = v; }
    return 0;
}
static int ref_get(src *s, dv_ref r, uint64_t *v, uint32_t *p) {
    (void)s; *p = DV_PRESENT;
    if (r.kind == DV_CELL) { tr_read c; if (tr_current(&world, r.id, &c) != TR_OK) { *p = DV_ABSENT; *v = 0; } else *v = c.value; return 1; }
    tr_read m; OK(tr_current(&world, r.id, &m));
    src sub = {ref_get, NULL, NULL};
    CHECK(eval(op_index(r.id), m.value, r.key, &sub, v));
    return 1;
}

static void setup(size_t workers) {
    OK(tr_world_init(&world, cells, 64));
    OK(tr_create(&world, 1, &driver));
    for (int i = 0; i < K; i++) OK(tr_create(&world, (uint64_t)i * 7 + 1, &cell[i]));
    for (int i = 0; i < 8; i++) OK(tr_create(&world, 1, &op[i]));
    OK(dv_init(&graph, &world, nodes, NODES, blocks, 2048, table, 512, path));
    OK(ex_init(&ex, &world, &graph, workers));
}

/* ---- 1: concurrent clients ---- */
typedef struct { uint64_t seed, checks, edits; } client_state;
static uint64_t step(uint64_t *s) { *s ^= *s << 13; *s ^= *s >> 7; *s ^= *s << 17; return *s; }
static uint32_t demanded[3][6]; static int ndemanded[3];
typedef struct { client_state *c; int id; } call_arg;
static void client_edit(void *arg, tr_world *w, dv_graph *g) {
    call_arg *a = arg; client_state *c = a->c;
    uint64_t x = step(&c->seed) % 100;
    if (x < 65) { int i = (int)(step(&c->seed) % K); set(w, cell[i], step(&c->seed) % 9); c->edits++; }
    else if (x < 75) { int o = (int)(step(&c->seed) % OPS); tr_read r; OK(tr_current(w, op[o], &r)); set(w, op[o], r.value + 1); c->edits++; }
    else if (x < 90 && ndemanded[a->id] < 6) {
        uint32_t n; OK(dv_intern(g, op[step(&c->seed) % OPS], step(&c->seed) % P, &n));
        OK(dv_demand(g, n)); demanded[a->id][ndemanded[a->id]++] = n;
    } else if (ndemanded[a->id]) {
        int k = (int)(step(&c->seed) % (uint64_t)ndemanded[a->id]);
        OK(dv_release(g, demanded[a->id][k])); demanded[a->id][k] = demanded[a->id][--ndemanded[a->id]];
    }
}
static void client_check(void *arg, tr_world *w, dv_graph *g) {
    (void)w;
    client_state *c = arg;
    for (uint32_t i = 0; i < NODES; i++) {
        uint64_t v, want; uint32_t p;
        if (!nodes[i].live || dv_get(g, i, &v) != TR_OK) continue;
        dv_ref r = {DV_NODE, nodes[i].op, nodes[i].key};
        ref_get(NULL, r, &want, &p);
        CHECK(v == want);
        c->checks++;
    }
}
static void all_demanded_clean(void *arg, tr_world *w, dv_graph *g) {
    (void)arg; (void)w;
    uint64_t v;
    for (uint32_t i = 0; i < NODES; i++) if (nodes[i].live && nodes[i].demand) CHECK(dv_get(g, i, &v) == TR_OK);
}
static void *client(void *arg) {
    call_arg *a = arg;
    for (int i = 0; i < 400; i++) {
        ex_call(&ex, client_edit, a);
        if (step(&a->c->seed) % 8 == 0) { CHECK(ex_settle(&ex)); ex_call(&ex, client_check, a->c); }
    }
    return NULL;
}
static uint64_t settled_count, settled_last; static int settled_monotonic = 1;
static void on_settled(void *ctx, uint64_t at) { (void)ctx; if (at <= settled_last) settled_monotonic = 0; settled_last = at; settled_count++; }

static void concurrent(void) {
    setup(4);
    for (int o = 0; o < OPS; o++) OK(ex_register(&ex, op[o], run_op, NULL));
    ex_on_settled(&ex, on_settled, NULL);
    OK(ex_start(&ex));
    pthread_t t[3]; client_state cs[3]; call_arg args[3];
    for (int i = 0; i < 3; i++) { cs[i] = (client_state){0x9e3779b97f4a7c15ULL * (uint64_t)(i + 1), 0, 0}; args[i] = (call_arg){&cs[i], i}; pthread_create(&t[i], NULL, client, &args[i]); }
    for (int i = 0; i < 3; i++) pthread_join(t[i], NULL);
    CHECK(ex_settle(&ex));
    client_state final = {0, 0, 0};
    ex_call(&ex, client_check, &final);
    ex_call(&ex, all_demanded_clean, NULL);           /* settled means every demanded node is verified */
    ex_stop(&ex);
    CHECK(!ex.error && settled_monotonic && settled_count > 0);
    uint64_t checks = final.checks, edits = 0;
    for (int i = 0; i < 3; i++) { checks += cs[i].checks; edits += cs[i].edits; }
    printf("ok concurrent: 3 clients, 4 workers, %llu edits; %llu clean nodes equal the reference; %llu jobs (%llu cancelled, %llu stale); %llu settled notifications, increasing\n",
           (unsigned long long)edits, (unsigned long long)checks, (unsigned long long)ex.jobs, (unsigned long long)ex.cancelled,
           (unsigned long long)ex.stale, (unsigned long long)settled_count);
}

/* ---- 2: cancellation latency ---- */
static int slow_op(void *ctx, const dv_job *job, ex_out *out) {
    (void)ctx;
    if (spin(job, 2000000)) return 1;                     /* 2 s unless cancelled */
    out->value = 1;
    return 0;
}
static uint32_t slow_node;
static void demand_slow(void *arg, tr_world *w, dv_graph *g) { (void)arg; (void)w; OK(dv_intern(g, op[4], 0, &slow_node)); OK(dv_demand(g, slow_node)); }
static void release_slow(void *arg, tr_world *w, dv_graph *g) { (void)w; OK(dv_release(g, slow_node)); *(uint64_t *)arg = now_ns(); }
static void last_trace(void *arg, tr_world *w, dv_graph *g) { (void)w; (void)g; ex_trace_copy(&ex, arg, 1); }
static void cancellation(void) {
    setup(2);
    OK(ex_register(&ex, op[4], slow_op, NULL));
    OK(ex_start(&ex));
    ex_call(&ex, demand_slow, NULL);
    struct timespec pause = {0, 20000000}; nanosleep(&pause, NULL);
    uint64_t released;
    ex_call(&ex, release_slow, &released);
    CHECK(ex_settle(&ex));
    ex_trace t; ex_call(&ex, last_trace, &t);
    uint64_t latency = t.end_ns - released;
    CHECK(t.outcome == EX_TRACE_CANCELLED && t.end_ns > released && latency < 100000000);
    ex_stop(&ex);
    uint64_t v; CHECK(dv_get(&graph, slow_node, &v) == TR_STALE);   /* owner stopped: safe to read */
    printf("ok cancellation: a 2 s job stops %.3f ms after its demand is released\n", latency / 1e6);
}

/* ---- 3: independent work across workers ---- */
static int work_op(void *ctx, const dv_job *job, ex_out *out) {
    (void)ctx;
    if (spin(job, 2000)) return 1;                        /* 2 ms */
    out->value = job->key * 3;
    return 0;
}
static void demand_work(void *arg, tr_world *w, dv_graph *g) {
    (void)arg; (void)w;
    for (uint64_t k = 0; k < 96; k++) { uint32_t n; OK(dv_intern(g, op[5], k, &n)); OK(dv_demand(g, n)); }
}
static double scaling(size_t workers) {
    setup(workers);
    OK(ex_register(&ex, op[5], work_op, NULL));
    OK(ex_start(&ex));
    uint64_t t0 = now_ns();
    ex_call(&ex, demand_work, NULL);
    CHECK(ex_settle(&ex));
    double ms = (now_ns() - t0) / 1e6;
    CHECK(ex.jobs == 96 && !ex.error);
    ex_stop(&ex);
    return ms;
}

/* ---- 5: priorities: urgent work under a background flood ---- */
enum { BG = 64, URGENT = 20 };
static int bg_op(void *ctx, const dv_job *job, ex_out *out) { (void)ctx; if (spin(job, 5000)) return 1; out->value = job->key; return 0; }
static int urgent_op(void *ctx, const dv_job *job, ex_out *out) { (void)ctx; if (spin(job, 300)) return 1; out->value = job->key + 1; return 0; }
static uint32_t urgent_node[URGENT]; static uint64_t urgent_at[URGENT];
static void demand_bg(void *arg, tr_world *w, dv_graph *g) {
    (void)arg; (void)w;
    for (uint64_t k = 0; k < BG; k++) { uint32_t n; OK(dv_intern(g, op[7], k, &n)); OK(dv_demand_at(g, n, DV_BACKGROUND)); }
}
static void demand_urgent(void *arg, tr_world *w, dv_graph *g) {
    (void)w; int i = *(int *)arg;
    OK(dv_intern(g, op[6], (uint64_t)i, &urgent_node[i])); OK(dv_demand_at(g, urgent_node[i], DV_URGENT));
    urgent_at[i] = now_ns();
}
static ex_trace ptrace[EX_TRACE]; static size_t nptrace;
static void copy_trace(void *arg, tr_world *w, dv_graph *g) { (void)arg; (void)w; (void)g; nptrace = ex_trace_copy(&ex, ptrace, EX_TRACE); }
typedef struct { double wait_max, wait_p50, wait_p90, total; int bg_overlap; } prio_result;
static int cmp_u64(const void *a, const void *b) { uint64_t x = *(const uint64_t *)a, y = *(const uint64_t *)b; return x < y ? -1 : x > y; }
static prio_result priorities(size_t reserve) {
    setup(4);
    ex_set_reserve(&ex, reserve);
    OK(ex_register(&ex, op[6], urgent_op, NULL)); OK(ex_register(&ex, op[7], bg_op, NULL));
    OK(ex_start(&ex));
    uint64_t t0 = now_ns();
    ex_call(&ex, demand_bg, NULL);
    for (int i = 0; i < URGENT; i++) {
        struct timespec pause = {0, 3000000}; nanosleep(&pause, NULL);
        ex_call(&ex, demand_urgent, &i);
    }
    CHECK(ex_settle(&ex) && !ex.error);
    prio_result r = {0, 0, 0, (now_ns() - t0) / 1e6, 0};
    ex_call(&ex, copy_trace, NULL);
    ex_stop(&ex);
    for (int i = 0; i < URGENT; i++) { uint64_t v; OK(dv_get(&graph, urgent_node[i], &v)); CHECK(v == (uint64_t)i + 1); }
    uint64_t waits[URGENT]; int nw = 0;
    for (size_t t = 0; t < nptrace; t++) {
        const ex_trace *x = &ptrace[t];
        if (x->outcome != EX_TRACE_VALUE) continue;
        for (int i = 0; i < URGENT; i++) if (x->node == urgent_node[i]) { CHECK(x->level == DV_URGENT); waits[nw++] = x->start_ns - urgent_at[i]; }
        if (x->level != DV_BACKGROUND) continue;
        int overlap = 0;                                  /* background jobs running at x's start */
        for (size_t u = 0; u < nptrace; u++)
            if (ptrace[u].level == DV_BACKGROUND && ptrace[u].start_ns <= x->start_ns && ptrace[u].end_ns > x->start_ns) overlap++;
        if (overlap > r.bg_overlap) r.bg_overlap = overlap;
    }
    CHECK(nw == URGENT);
    qsort(waits, URGENT, sizeof waits[0], cmp_u64);
    r.wait_max = waits[URGENT - 1] / 1e6; r.wait_p50 = waits[URGENT / 2] / 1e6; r.wait_p90 = waits[URGENT * 9 / 10] / 1e6;
    return r;
}

/* Visible jobs are not capped, so they fill every slot. An urgent job must start ahead of the
 * queued ones, and so must a queued visible job promoted by an urgent demand. */
static uint32_t flood_node[BG]; static uint64_t promote_at;
static void demand_flood(void *arg, tr_world *w, dv_graph *g) {
    (void)arg; (void)w;
    for (uint64_t k = 0; k < BG; k++) { OK(dv_intern(g, op[7], k, &flood_node[k])); OK(dv_demand(g, flood_node[k])); }
}
static void promote(void *arg, tr_world *w, dv_graph *g) { (void)w; OK(dv_demand_at(g, flood_node[*(int *)arg], DV_URGENT)); promote_at = now_ns(); }
static void visible_flood(double *urgent_max, double *promoted) {
    setup(4);
    OK(ex_register(&ex, op[6], urgent_op, NULL)); OK(ex_register(&ex, op[7], bg_op, NULL));
    OK(ex_start(&ex));
    ex_call(&ex, demand_flood, NULL);
    struct timespec pause = {0, 1000000}; nanosleep(&pause, NULL);
    int last = EX_SLOTS - 1;                          /* issued in the first poll, queued behind 11 */
    ex_call(&ex, promote, &last);
    for (int i = 0; i < 4; i++) { nanosleep(&pause, NULL); nanosleep(&pause, NULL); ex_call(&ex, demand_urgent, &i); }
    CHECK(ex_settle(&ex) && !ex.error);
    ex_call(&ex, copy_trace, NULL);
    ex_stop(&ex);
    *urgent_max = 0; *promoted = -1;
    for (size_t t = 0; t < nptrace; t++) {
        const ex_trace *x = &ptrace[t];
        if (x->outcome != EX_TRACE_VALUE) continue;
        for (int i = 0; i < 4; i++) if (x->node == urgent_node[i] && (x->start_ns - urgent_at[i]) / 1e6 > *urgent_max) *urgent_max = (x->start_ns - urgent_at[i]) / 1e6;
        if (x->node == flood_node[last]) { CHECK(x->queued_ns < promote_at && x->level == DV_URGENT); *promoted = (x->start_ns - promote_at) / 1e6; }
    }
    CHECK(*promoted >= 0);
}

/* ex_settle_at(DV_URGENT) returns once the urgent root is verified, with background work
 * still running; ex_settle waits for all of it. */
/* 20 ms, so a settle that returns before the job ran cannot be covered by the race to check. */
static int urgent20_op(void *ctx, const dv_job *job, ex_out *out) { (void)ctx; if (spin(job, 20000)) return 1; out->value = job->key + 1; return 0; }
static void urgent_value(void *arg, tr_world *w, dv_graph *g) { (void)w; uint64_t v; *(int *)arg = dv_get(g, urgent_node[0], &v) == TR_OK && v == 1; }
static void bg_left(void *arg, tr_world *w, dv_graph *g) {
    (void)w; int *n = arg; *n = 0;
    for (uint64_t k = 0; k < BG; k++) { uint32_t x; uint64_t v; OK(dv_intern(g, op[7], k, &x)); *n += dv_get(g, x, &v) != TR_OK; }
}
static void settle_levels(double *urgent_ms, int *left, double *all_ms) {
    setup(4);
    OK(ex_register(&ex, op[6], urgent20_op, NULL)); OK(ex_register(&ex, op[7], bg_op, NULL));
    OK(ex_start(&ex));
    ex_call(&ex, demand_bg, NULL);
    struct timespec pause = {0, 2000000}; nanosleep(&pause, NULL);
    int i = 0, ok = 0;
    uint64_t t0 = now_ns();
    ex_call(&ex, demand_urgent, &i);
    CHECK(ex_settle_at(&ex, DV_URGENT));
    *urgent_ms = (now_ns() - t0) / 1e6;
    ex_call(&ex, urgent_value, &ok); CHECK(ok);
    ex_call(&ex, bg_left, left);
    CHECK(ex_settle_at(&ex, DV_BACKGROUND));
    *all_ms = (now_ns() - t0) / 1e6;
    int none; ex_call(&ex, bg_left, &none); CHECK(none == 0);
    ex_stop(&ex);
}

/* ---- 4: unregistered operator ---- */
static void demand_unknown(void *arg, tr_world *w, dv_graph *g) { (void)arg; (void)w; uint32_t n; OK(dv_intern(g, op[6], 0, &n)); OK(dv_demand(g, n)); }
static void unregistered(void) {
    setup(1);
    OK(ex_start(&ex));
    ex_call(&ex, demand_unknown, NULL);
    CHECK(ex_settle(&ex) == 0 && ex.error == TR_INVALID);
    ex_stop(&ex);
    printf("ok an unregistered operator is a sticky TR_INVALID; settle returns 0\n");
}

int main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);
    concurrent();
    cancellation();
    unregistered();
    prio_result kept = priorities(1), open = priorities(0);
    CHECK(kept.bg_overlap <= 3 && open.bg_overlap <= 4);
    /* A background job is 5 ms; urgent ones do not wait for one: p90 under half a job. p90 and
     * the slack because the VM's vCPUs are preempted by the host, and the Pixel once measured
     * 1.5+ ms right after the GPU scenarios (usually 0.1-0.2 ms). The background cap itself is
     * checked exactly by bg_overlap. */
    CHECK(kept.wait_p90 < 2.5);
    double urgent_ms, all_ms; int left;
    settle_levels(&urgent_ms, &left, &all_ms);
    CHECK(left > BG / 2 && urgent_ms >= 20 && urgent_ms < all_ms / 3);
    printf("ok settle by level: the urgent root (a 20 ms job) settles in %.3f ms with %d of %d background jobs left; all in %.0f ms\n", urgent_ms, left, BG, all_ms);
    double flood_max, promoted;
    visible_flood(&flood_max, &promoted);
    CHECK(flood_max < 10 && promoted < 10);     /* about one running job, not the 11 queued ahead (~14 ms) */
    printf("ok priorities: with 16 visible 5 ms jobs queued, urgent jobs start within %.3f ms and a queued job "
           "promoted by urgent demand within %.3f ms\n", flood_max, promoted);
    printf("ok priorities: %d urgent 0.3 ms jobs under %d background 5 ms jobs on 4 workers: wait p50 %.3f p90 %.3f max %.3f ms "
           "with 1 worker reserved (background on <= %d, %.0f ms total); p50 %.3f max %.3f ms without (<= %d, %.0f ms)\n",
           URGENT, BG, kept.wait_p50, kept.wait_p90, kept.wait_max, kept.bg_overlap, kept.total, open.wait_p50, open.wait_max, open.bg_overlap, open.total);
    double ms[4]; size_t w[4] = {1, 2, 4, 8};
    for (int i = 0; i < 4; i++) ms[i] = scaling(w[i]);
    printf("ok scaling: 96 independent 2 ms jobs: 1 worker %.1f ms, 2 %.1f, 4 %.1f, 8 %.1f\n", ms[0], ms[1], ms[2], ms[3]);
    printf("{\"ok\":true,\"scaling_ms\":[%.1f,%.1f,%.1f,%.1f],\"urgent_wait_max_ms\":[%.3f,%.3f]}\n", ms[0], ms[1], ms[2], ms[3], kept.wait_max, open.wait_max);
    return 0;
}
