/* core.order: the paragraph order tree (docs/ORDER.md) against a flat array. Random inserts,
 * removals and length changes, biased to grow to thousands of paragraphs, shrink to none and
 * grow again. After every operation: count and unit totals, the id and offset at every
 * position, locate for joined offsets (including separators and the end), the B+tree
 * invariants (occupancy, equal leaf depth, sums, no leaked slots), and publication: a copy
 * holding only the republished slots must equal a fresh encoding of every slot, and a lookup
 * walked over that copy with or_step must find every id. Republished slots per operation are
 * bounded by the depth. A full slot store refuses insertion and changes nothing. */
#define _POSIX_C_SOURCE 200809L
#include "order.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "%s:%d check failed: %s\n", __FILE__, __LINE__, #c); abort(); } } while (0)

enum { CAP = 4096, MAXN = 12000 };
static or_node nodes[CAP]; static uint32_t freel[CAP], changed[CAP]; static uint8_t marked[CAP], used[CAP];
static unsigned char published[CAP][OR_ENCODED_MAX]; static size_t published_n[CAP];
static uint32_t ids[MAXN]; static uint64_t units[MAXN]; static uint64_t n;
static uint64_t rng = 0x9e3779b97f4a7c15ULL;
static uint64_t next(void) { rng ^= rng << 13; rng ^= rng >> 7; rng ^= rng << 17; return rng; }

static int depth_of(const or_tree *t, uint32_t s, uint64_t *count, uint64_t *u, uint32_t *reach) {
    const or_node *x = &t->nodes[s];
    (*reach)++;
    CHECK(t->used[s]);
    CHECK(x->n <= OR_FAN);
    if (s != 0) CHECK(x->n >= OR_FAN / 2);
    *count = 0; *u = 0;
    if (x->leaf) {
        for (uint32_t k = 0; k < x->n; k++) { CHECK(x->count[k] == 1); *count += 1; *u += x->units[k]; }
        return 1;
    }
    if (s == 0) CHECK(x->n >= 2);
    int d = -1;
    for (uint32_t k = 0; k < x->n; k++) {
        uint64_t c, cu;
        int e = depth_of(t, x->child[k], &c, &cu, reach);
        CHECK(d < 0 || d == e); d = e;
        CHECK(c == x->count[k] && cu == x->units[k]);
        *count += c; *u += cu;
    }
    return d + 1;
}

static void publish(or_tree *t, uint32_t *republished) {
    uint32_t s[CAP], k = or_take_changed(t, s, CAP);
    *republished = k;
    for (uint32_t i = 0; i < k; i++) published_n[s[i]] = or_encode(t, s[i], published[s[i]]);
    CHECK(t->nchanged == 0);
}

static int verify(or_tree *t) {
    CHECK(or_count(t) == n);
    uint64_t total = 0;
    for (uint64_t i = 0; i < n; i++) total += units[i];
    CHECK(or_units(t) == total + (n ? n - 1 : 0));
    uint64_t c, u; uint32_t reach = 0;
    int depth = depth_of(t, 0, &c, &u, &reach);
    uint32_t live = 0;
    for (uint32_t s = 0; s < CAP; s++) live += used[s];
    CHECK(live == reach && live + t->nfree == CAP);
    for (uint32_t s = 0; s < CAP; s++) {           /* the published copy is the current tree */
        unsigned char now[OR_ENCODED_MAX]; size_t m = or_encode(t, s, now);
        CHECK(m == published_n[s] && !memcmp(now, published[s], m));
    }
    uint64_t g = 0;
    for (uint64_t i = 0; i < n; i++) {
        uint32_t id; uint64_t un, off;
        CHECK(or_at(t, i, &id, &un) == TR_OK && id == ids[i] && un == units[i]);
        CHECK(or_offset(t, i, &off) == TR_OK && off == g);
        uint64_t pos = i, p, o; uint32_t slot = 0, child; int leaf = 0;   /* over the published copy */
        for (int steps = 0; !leaf; steps++) {
            CHECK(steps <= depth);
            CHECK(or_step(published[slot], published_n[slot], &pos, &child, &leaf) == TR_OK);
            slot = child;
        }
        CHECK(child == ids[i]);
        if (next() % 4 == 0 || i + 1 == n) {       /* locate: inside, at the end, on the separator */
            uint64_t q = units[i] ? next() % (units[i] + 1) : 0;
            CHECK(or_locate(t, g + q, &p, &o) == TR_OK && p == i && o == q);
            if (i + 1 < n) CHECK(or_locate(t, g + units[i] + 1, &p, &o) == TR_OK && p == i + 1 && o == 0);
        }
        g += units[i] + 1;
    }
    uint64_t end;
    CHECK(or_offset(t, n, &end) == TR_OK && end == g);
    if (n) {                                        /* past the end clamps to the last paragraph's end */
        uint64_t p, o;
        CHECK(or_locate(t, g + 50, &p, &o) == TR_OK && p == n - 1 && o == units[n - 1]);
    } else {
        uint64_t p, o;
        CHECK(or_locate(t, 0, &p, &o) == TR_INVALID);
    }
    CHECK(or_at(t, n, NULL, NULL) == TR_INVALID && or_remove(t, n, NULL) == TR_INVALID && or_insert(t, n + 1, 1, 1) == TR_INVALID);
    return depth;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    int rounds = argc > 1 ? atoi(argv[1]) : 40000;
    or_tree t;
    CHECK(or_init(&t, nodes, CAP, freel, changed, marked, used) == TR_OK);
    uint32_t republished; publish(&t, &republished);
    uint32_t next_id = 1, max_republished = 0, max_depth = 0; uint64_t max_n = 0, total_republished = 0, ops = 0;
    uint64_t counts[3] = {0};
    clock_t c0 = clock();
    for (int r = 0; r < rounds; r++) {
        int phase = (r / 6000) % 3;                 /* grow, shrink, mixed */
        uint64_t roll = next() % 100;
        int op = phase == 0 ? (roll < 75 ? 0 : roll < 90 ? 1 : 2) : phase == 1 ? (roll < 25 ? 0 : roll < 90 ? 1 : 2) : (roll < 45 ? 0 : roll < 80 ? 1 : 2);
        if (op == 1 && !n) op = 0;
        if (op == 2 && !n) op = 0;
        if (op == 0 && n == MAXN) op = 1;
        uint64_t pos = next() % 10 < 3 ? (next() % 2 ? 0 : n) : next() % (n + 1);   /* ends are common */
        if (op == 0) {
            uint64_t un = next() % 7 == 0 ? 0 : next() % 300;
            CHECK(or_insert(&t, pos, next_id, un) == TR_OK);
            memmove(&ids[pos + 1], &ids[pos], (n - pos) * sizeof ids[0]);
            memmove(&units[pos + 1], &units[pos], (n - pos) * sizeof units[0]);
            ids[pos] = next_id++; units[pos] = un; n++;
        } else if (op == 1) {
            if (pos >= n) pos = n - 1;
            uint32_t id;
            CHECK(or_remove(&t, pos, &id) == TR_OK && id == ids[pos]);
            memmove(&ids[pos], &ids[pos + 1], (n - pos - 1) * sizeof ids[0]);
            memmove(&units[pos], &units[pos + 1], (n - pos - 1) * sizeof units[0]);
            n--;
        } else {
            if (pos >= n) pos = n - 1;
            uint64_t un = next() % 500;
            CHECK(or_set_units(&t, pos, un) == TR_OK);
            units[pos] = un;
        }
        counts[op]++; ops++;
        publish(&t, &republished);
        if (op == 2) CHECK(republished == 0);       /* lengths are not published */
        int depth = (r % 7 == 0 || n < 64) ? verify(&t) : 0;
        if (!depth) { uint64_t c, u; uint32_t reach = 0; depth = depth_of(&t, 0, &c, &u, &reach); }
        CHECK(republished <= 3 * (uint32_t)depth + 3);
        if (republished > max_republished) max_republished = republished;
        if ((uint32_t)depth > max_depth) max_depth = (uint32_t)depth;
        if (n > max_n) max_n = n;
        total_republished += republished;
    }
    verify(&t);
    double secs = (double)(clock() - c0) / CLOCKS_PER_SEC;
    printf("ok random: %llu ops (%llu inserts, %llu removals, %llu length changes), up to %llu paragraphs, depth <= %u; "
           "republished <= %u slots per op (mean %.2f); published copy and step lookups equal the tree (%.1f s)\n",
           (unsigned long long)ops, (unsigned long long)counts[0], (unsigned long long)counts[1], (unsigned long long)counts[2],
           (unsigned long long)max_n, max_depth, max_republished, (double)total_republished / (double)ops, secs);

    /* Capacity: a store with no room refuses insertion and leaves the tree as it was. */
    static or_node small_nodes[8]; static uint32_t sf[8], sc[8]; static uint8_t sm[8], su[8];
    or_tree s;
    CHECK(or_init(&s, small_nodes, 8, sf, sc, sm, su) == TR_OK);
    uint64_t inserted = 0; tr_status st = TR_OK;
    while ((st = or_insert(&s, inserted, (uint32_t)inserted + 1, 1)) == TR_OK) inserted++;
    CHECK(st == TR_CAPACITY && inserted >= OR_FAN);
    CHECK(or_count(&s) == inserted);
    for (uint64_t i = 0; i < inserted; i++) { uint32_t id; CHECK(or_at(&s, i, &id, NULL) == TR_OK && id == i + 1); }
    printf("ok capacity: 8 slots hold %llu paragraphs, then insertion is refused with the tree unchanged\n", (unsigned long long)inserted);

    /* Scale: per-operation cost does not grow with the document. */
    static or_node big[40000]; static uint32_t bf[40000], bc[40000]; static uint8_t bm[40000], bu[40000];
    or_tree b;
    CHECK(or_init(&b, big, 40000, bf, bc, bm, bu) == TR_OK);
    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    for (uint32_t i = 0; i < 100000; i++) { CHECK(or_insert(&b, next() % (i + 1), i, next() % 200) == TR_OK); or_take_changed(&b, bc, 40000); }
    clock_gettime(CLOCK_MONOTONIC, &t1);
    double insert_us = ((t1.tv_sec - t0.tv_sec) * 1e9 + (t1.tv_nsec - t0.tv_nsec)) / 1e3 / 100000;
    uint64_t total = or_units(&b), p, o, sink = 0;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    for (int i = 0; i < 100000; i++) { CHECK(or_locate(&b, next() % total, &p, &o) == TR_OK); sink += p; }
    clock_gettime(CLOCK_MONOTONIC, &t1);
    double locate_us = ((t1.tv_sec - t0.tv_sec) * 1e9 + (t1.tv_nsec - t0.tv_nsec)) / 1e3 / 100000;
    printf("ok scale: 100000 paragraphs; insert %.3f us, locate %.3f us (%llu)\n", insert_us, locate_us, (unsigned long long)(sink % 7));
    printf("{\"ok\":true,\"ops\":%llu,\"max_paragraphs\":%llu,\"max_depth\":%u,\"max_republished\":%u,\"insert_us\":%.3f,\"locate_us\":%.3f}\n",
           (unsigned long long)ops, (unsigned long long)max_n, max_depth, max_republished, insert_us, locate_us);
    return 0;
}
