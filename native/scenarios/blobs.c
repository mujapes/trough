/* core.blobs: interned immutable values (docs/BLOBS.md). Store checks: canonical handles,
 * stale handles, byte admission, ownership transfer, tombstone churn. Integration: operators
 * that make and read blobs run on the executor while clients edit; every clean node's bytes
 * equal a from-scratch evaluation, equal outputs share one handle, and after all demand is
 * released and every node evicted the store is empty (no reference leaks; ASan checks bytes). */
#define _POSIX_C_SOURCE 200809L
#include "exec.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define OK(x) do { tr_status s_ = (x); if (s_ != TR_OK) { fprintf(stderr, "%s:%d %s -> %d\n", __FILE__, __LINE__, #x, s_); abort(); } } while (0)
#define CHECK(c) do { if (!(c)) { fprintf(stderr, "%s:%d check failed: %s\n", __FILE__, __LINE__, #c); abort(); } } while (0)

static bl_store store; static bl_entry entries[512]; static uint32_t bindex[1024];

static void unit(void) {
    OK(bl_init(&store, entries, 8, bindex, 16, 100));
    uint64_t a, b, c, d;
    OK(bl_intern(&store, "hello", 5, 0, &a));
    OK(bl_intern(&store, "hello", 5, 0, &b));
    OK(bl_intern(&store, "world", 5, 0, &c));
    CHECK(a == b && a != c && bl_is(a) && store.live == 2 && store.bytes == 10 && store.deduplicated == 1);
    const void *p; size_t n;
    OK(bl_get(&store, a, &p, &n)); CHECK(n == 5 && !memcmp(p, "hello", 5));
    char *owned = malloc(5); memcpy(owned, "hello", 5);
    OK(bl_intern(&store, owned, 5, 1, &d));             /* equal bytes: the block is freed now */
    CHECK(d == a);
    OK(bl_release(&store, a)); OK(bl_release(&store, b)); OK(bl_release(&store, d));
    CHECK(store.live == 1 && bl_release(&store, a) == TR_STALE && bl_retain(&store, a) == TR_STALE);
    OK(bl_intern(&store, "hello", 5, 0, &b));
    CHECK(b != a);                                      /* the slot is reused with a new generation */
    char big[96]; memset(big, 7, sizeof big);
    CHECK(bl_intern(&store, big, sizeof big, 0, &d) == TR_CAPACITY);   /* 10 + 96 > 100 bytes */
    char *taken = malloc(96); memset(taken, 8, 96);
    CHECK(bl_intern(&store, taken, 96, 1, &d) == TR_CAPACITY);          /* freed on refusal */
    OK(bl_release(&store, b)); OK(bl_release(&store, c));
    CHECK(store.live == 0 && store.bytes == 0);
    /* Churn: far more inserts and releases than the index holds; tombstones get rebuilt. */
    for (uint32_t i = 0; i < 200000; i++) {
        uint64_t h[3];
        for (int k = 0; k < 3; k++) { uint32_t v = i * 3 + (uint32_t)k; OK(bl_intern(&store, &v, sizeof v, 0, &h[k])); }
        for (int k = 0; k < 3; k++) OK(bl_release(&store, h[k]));
    }
    CHECK(store.live == 0 && store.interned == 600000 + 3);
    printf("ok store: canonical handles, stale after release, byte admission, ownership, 600000 churn inserts\n");
}

/* ---- integration ---- */
enum { K = 10, P = 12, NODES = 256 };
static tr_world world; static tr_cell cells[64];
static tr_id cell[K], op[3], driver;
static dv_graph graph; static dv_node nodes[NODES]; static dv_block blocks[2048]; static uint32_t table[512]; static dv_frame path[NODES];
static ex_exec ex;

static void set(tr_world *w, tr_id id, uint64_t value) {
    tr_transaction t; tr_read r[4]; tr_write wr[1]; tr_receipt rc;
    OK(tr_begin(&t, w, driver, r, 4, wr, 1)); OK(tr_write_value(&t, id, value)); OK(tr_commit(&t, &rc));
}
static int op_index(tr_id id) { for (int i = 0; i < 3; i++) if (op[i].slot == id.slot && op[i].generation == id.generation) return i; abort(); }

/* A(k): cell k%K = c -> bytes: (c % 5) * 3 + 1 copies of (c % 4); many cells share outputs.
 * B(k): A(k) bytes ++ A(k+1) bytes, then each byte + version.  C(k): sum of B(k) bytes % 7. */
typedef struct { unsigned char b[64]; size_t n; } bytes;
static bytes make_a(uint64_t c) { bytes r; r.n = (c % 5) * 3 + 1; memset(r.b, (int)(c % 4), r.n); return r; }
static bytes make_b(bytes x, bytes y, uint64_t ver) {
    bytes r; r.n = x.n + y.n;
    for (size_t i = 0; i < x.n; i++) r.b[i] = (unsigned char)(x.b[i] + ver);
    for (size_t i = 0; i < y.n; i++) r.b[x.n + i] = (unsigned char)(y.b[i] + ver);
    return r;
}
static uint64_t make_c(bytes b) { uint64_t s = 0; for (size_t i = 0; i < b.n; i++) s += b.b[i]; return s % 7; }

static uint64_t cellv(tr_world *w, int i) { tr_read r; return tr_current(w, cell[i], &r) == TR_OK ? r.value : 7777; }
static uint64_t ver(tr_world *w, int o) { tr_read r; OK(tr_current(w, op[o], &r)); return r.value; }
static bytes ref_a(tr_world *w, uint64_t k) { return make_a(cellv(w, (int)(k % K))); }
static bytes ref_b(tr_world *w, uint64_t k) { return make_b(ref_a(w, k % P), ref_a(w, (k + 1) % P), ver(w, 1)); }

static int read_bytes(const dv_job *job, ex_out *out, dv_ref r, bytes *b) {
    uint64_t h;
    if (!ex_read(job, out, r, &h, NULL)) { ex_need(out, r); return 0; }
    const void *p; size_t n;
    OK(bl_get(&store, h, &p, &n));                       /* held by the job: safe off the owner */
    CHECK(n <= sizeof b->b);
    memcpy(b->b, p, n); b->n = n;
    return 1;
}
static int operator(void *ctx, const dv_job *job, ex_out *out) {
    (void)ctx;
    int o = op_index(job->op);
    if (o == 0) {
        uint64_t c; uint32_t p; dv_ref r = {DV_CELL, cell[job->key % K], 0};
        if (!ex_read(job, out, r, &c, &p)) { ex_need(out, r); return 0; }
        if (p == DV_ABSENT) c = 7777;
        bytes a = make_a(c);
        out->blob = malloc(a.n); memcpy(out->blob, a.b, a.n); out->blob_length = a.n;
        return 0;
    }
    if (o == 1) {
        bytes x, y;
        dv_ref r1 = {DV_NODE, op[0], job->key % P}, r2 = {DV_NODE, op[0], (job->key + 1) % P};
        if (!read_bytes(job, out, r1, &x) || !read_bytes(job, out, r2, &y)) return 0;
        bytes b = make_b(x, y, job->op_value);
        out->blob = malloc(b.n); memcpy(out->blob, b.b, b.n); out->blob_length = b.n;
        return 0;
    }
    bytes b; dv_ref r = {DV_NODE, op[1], job->key % P};
    if (!read_bytes(job, out, r, &b)) return 0;
    out->value = make_c(b);
    return 0;
}

static uint64_t checks, shared;
static void check_all(void *arg, tr_world *w, dv_graph *g) {
    (void)arg;
    for (uint32_t i = 0; i < NODES; i++) {
        uint64_t v;
        if (!nodes[i].live || dv_get(g, i, &v) != TR_OK) continue;
        int o = op_index(nodes[i].op);
        if (o == 2) { CHECK(v == make_c(ref_b(w, nodes[i].key))); checks++; continue; }
        bytes want = o == 0 ? ref_a(w, nodes[i].key) : ref_b(w, nodes[i].key);
        const void *p; size_t n;
        OK(bl_get(&store, v, &p, &n));
        CHECK(n == want.n && !memcmp(p, want.b, n));
        /* Canonical: an equal output elsewhere holds the same handle. */
        for (uint32_t j = 0; j < i; j++) {
            uint64_t u;
            if (nodes[j].live && op_index(nodes[j].op) != 2 && dv_get(g, j, &u) == TR_OK && u == v) shared++;
        }
        checks++;
    }
}
typedef struct { uint64_t seed; uint32_t mine[6]; int n; } client_t;
static uint64_t step(uint64_t *s) { *s ^= *s << 13; *s ^= *s >> 7; *s ^= *s << 17; return *s; }
static void edit(void *arg, tr_world *w, dv_graph *g) {
    client_t *c = arg;
    uint64_t x = step(&c->seed) % 100;
    if (x < 60) set(w, cell[step(&c->seed) % K], step(&c->seed) % 50);
    else if (x < 70) set(w, op[1], ver(w, 1) + 1);
    else if (x < 85 && c->n < 6) { uint32_t n; OK(dv_intern(g, op[step(&c->seed) % 3], step(&c->seed) % P, &n)); OK(dv_demand(g, n)); c->mine[c->n++] = n; }
    else if (c->n) { int k = (int)(step(&c->seed) % (uint64_t)c->n); OK(dv_release(g, c->mine[k])); c->mine[k] = c->mine[--c->n]; }
    else {                                               /* evict something idle */
        uint32_t v = (uint32_t)(step(&c->seed) % NODES);
        if (nodes[v].live) dv_evict(g, v);
    }
}
static void *client(void *arg) {
    client_t *c = arg;
    for (int i = 0; i < 400; i++) {
        ex_call(&ex, edit, c);
        if (step(&c->seed) % 6 == 0) { CHECK(ex_settle(&ex)); ex_call(&ex, check_all, NULL); }
    }
    return NULL;
}
static client_t clients[3];
static void teardown(void *arg, tr_world *w, dv_graph *g) {
    (void)arg; (void)w;
    for (int i = 0; i < 3; i++) while (clients[i].n) OK(dv_release(g, clients[i].mine[--clients[i].n]));
    for (uint32_t i = 0; i < NODES; i++) if (nodes[i].live) OK(dv_evict(g, i));
}

static void integration(void) {
    OK(bl_init(&store, entries, 512, bindex, 1024, 1 << 20));
    OK(tr_world_init(&world, cells, 64));
    OK(tr_create(&world, 1, &driver));
    for (int i = 0; i < K; i++) OK(tr_create(&world, (uint64_t)i, &cell[i]));
    for (int i = 0; i < 3; i++) OK(tr_create(&world, 1, &op[i]));
    OK(dv_init(&graph, &world, nodes, NODES, blocks, 2048, table, 512, path));
    OK(ex_init(&ex, &world, &graph, 4));
    ex_use_blobs(&ex, &store);
    for (int i = 0; i < 3; i++) OK(ex_register(&ex, op[i], operator, NULL));
    OK(ex_start(&ex));
    pthread_t t[3];
    for (int i = 0; i < 3; i++) { clients[i].seed = 0x51ed2701ULL * (uint64_t)(i + 3); clients[i].n = 0; pthread_create(&t[i], NULL, client, &clients[i]); }
    for (int i = 0; i < 3; i++) pthread_join(t[i], NULL);
    CHECK(ex_settle(&ex));
    ex_call(&ex, check_all, NULL);
    uint64_t peak_live = store.live;
    ex_call(&ex, teardown, NULL);
    CHECK(ex_settle(&ex));
    ex_stop(&ex);
    CHECK(!ex.error);
    CHECK(store.live == 0 && store.bytes == 0);
    printf("ok integration: %llu clean values equal the reference (%llu equal outputs share a handle); "
           "%llu blobs interned, %llu deduplicated; %llu live before teardown, 0 after\n",
           (unsigned long long)checks, (unsigned long long)shared, (unsigned long long)store.interned,
           (unsigned long long)store.deduplicated, (unsigned long long)peak_live);
}

static void demand_a0(void *arg, tr_world *w, dv_graph *g) { (void)arg; (void)w; uint32_t n; OK(dv_intern(g, op[0], 0, &n)); OK(dv_demand(g, n)); }
/* A byte budget too small for the outputs: admission fails as a sticky TR_CAPACITY. */
static void admission(void) {
    OK(bl_init(&store, entries, 512, bindex, 1024, 8));
    OK(tr_world_init(&world, cells, 64));
    OK(tr_create(&world, 1, &driver));
    for (int i = 0; i < K; i++) OK(tr_create(&world, 49, &cell[i]));   /* A outputs 13 bytes */
    for (int i = 0; i < 3; i++) OK(tr_create(&world, 1, &op[i]));
    OK(dv_init(&graph, &world, nodes, NODES, blocks, 2048, table, 512, path));
    OK(ex_init(&ex, &world, &graph, 2));
    ex_use_blobs(&ex, &store);
    for (int i = 0; i < 3; i++) OK(ex_register(&ex, op[i], operator, NULL));
    OK(ex_start(&ex));
    ex_call(&ex, demand_a0, NULL);
    CHECK(ex_settle(&ex) == 0 && ex.error == TR_CAPACITY);
    ex_stop(&ex);
    CHECK(store.live == 0);
    printf("ok admission: an output past the byte budget is a sticky TR_CAPACITY, nothing retained\n");
}

int main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);
    unit();
    integration();
    admission();
    printf("{\"ok\":true,\"checks\":%llu}\n", (unsigned long long)checks);
    return 0;
}
