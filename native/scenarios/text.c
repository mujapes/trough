#define _POSIX_C_SOURCE 200809L
/* core-text oracle: indexed text against a flat-array model. Shares only the public
 * text/store API with the implementation. */
#include "text.h"
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>

static int failures;
#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__, #c); failures++; } } while (0)
#define MUST(c) do { if (!(c)) { fprintf(stderr, "FATAL %s:%d %s\n", __FILE__, __LINE__, #c); exit(1); } } while (0)
static char base[3000];
static const char *path(const char *name) { static char p[3100]; snprintf(p, sizeof p, "%s/%s", base, name); return p; }
static uint64_t rng = 0x2545f4914f6cdd1du;
static size_t next(size_t n) { rng ^= rng << 13; rng ^= rng >> 7; rng ^= rng << 17; return n ? (size_t)(rng % n) : 0; }
static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec / 1e9; }

typedef struct { uint16_t *text; size_t length, capacity; } model;
static void put(model *m, size_t start, size_t end, const uint16_t *t, size_t n) {
    size_t length = m->length - (end - start) + n;
    if (length > m->capacity) { m->capacity = 2 * length; MUST(m->text = realloc(m->text, m->capacity * 2)); }
    memmove(m->text + start + n, m->text + end, (m->length - end) * 2);
    if (n) memcpy(m->text + start, t, n * 2);
    m->length = length;
}
static void fill(uint16_t *p, size_t n) {
    for (size_t i = 0; i < n; i++) p[i] = next(40) ? (uint16_t)('a' + next(26)) : next(2) ? 0x0a : (uint16_t)(0xd800 + next(0x800));
}
static size_t model_lines(const model *m, size_t end) { size_t c = 0; for (size_t i = 0; i < end; i++) c += m->text[i] == 0x0a; return c; }
static int equal(const tx_text *t, const model *m) {
    if (tx_length(t) != m->length) return 0;
    uint16_t *buf = malloc(m->length * 2 + 2);
    MUST(buf && tx_read(t, 0, m->length, buf) == TS_OK);
    int same = !memcmp(buf, m->text, m->length * 2);
    free(buf);
    return same;
}
static void structure(const tx_text *t) {
    tx_stats st;
    CHECK(tx_verify(t, &st) == TS_OK);
    CHECK(st.leaves <= 1 || st.smallest >= TX_LEAF_MIN);
    CHECK(st.largest <= TX_LEAF_MAX && st.leaves <= tx_length(t) / TX_LEAF_MIN + 1);
}

static void differential(void) {
    model m = {0}; tx_text *t = NULL;
    enum { KEEP = 8 };
    tx_text *old[KEEP] = {0}; model kept[KEEP] = {{0}};
    uint16_t ins[6000];
    size_t caret = 0, max_height = 0;
    for (int i = 0; i < 20000; i++) {
        size_t r = next(100), start, end, n;
        if (r < 60) { start = end = caret; n = 1; }                          /* typing */
        else if (r < 70) { start = caret ? caret - 1 : 0; end = caret; n = 0; } /* backspace */
        else if (r < 85) { start = next(m.length + 1); end = start + next(m.length - start + 1) % 64; n = next(64); }
        else if (r < 95) { start = next(m.length + 1); end = start; n = next(6000); }  /* paste */
        else { start = next(m.length + 1); end = start + next(m.length - start + 1); n = 0; } /* large cut */
        fill(ins, n);
        tx_text *e;
        MUST(tx_edit(t, start, end, ins, n, &e) == TS_OK);
        put(&m, start, end, ins, n);
        if (i % 1000 == 999) {                    /* rotate a retained snapshot */
            size_t k = (size_t)(i / 1000) % KEEP;
            if (old[k]) CHECK(equal(old[k], &kept[k]));
            tx_release(old[k]); old[k] = tx_retain(e);
            kept[k].length = 0; put(&kept[k], 0, 0, m.text, m.length);
        }
        tx_release(t); t = e;
        caret = start + n;
        if (r < 70 && next(10) == 0) caret = next(m.length + 1);
        CHECK(tx_length(t) == m.length && tx_newlines(t) == model_lines(&m, m.length));
        if (i % 97 == 0) {
            CHECK(equal(t, &m)); structure(t);
            tx_stats st; tx_verify(t, &st); if (st.height > max_height) max_height = st.height;
            for (int q = 0; q < 8; q++) {
                size_t off = next(m.length + 1), line, start_off;
                CHECK(tx_line_of(t, off, &line) == TS_OK && line == model_lines(&m, off));
                if (tx_line_start(t, line, &start_off) == TS_OK)
                    CHECK(start_off <= off && model_lines(&m, start_off) == line && (start_off == 0 || m.text[start_off - 1] == 0x0a));
                else CHECK(0);
                if (m.length) {
                    const uint16_t *units; size_t len, at, o = next(m.length);
                    CHECK(tx_chunk(t, o, &units, &len, &at) == TS_OK && at <= o && o < at + len &&
                          !memcmp(units, m.text + at, len * 2));
                }
            }
            size_t last;
            CHECK(tx_line_start(t, tx_newlines(t) + 1, &last) == TS_NOT_FOUND);
        }
    }
    CHECK(equal(t, &m));
    for (int k = 0; k < KEEP; k++) { if (old[k]) CHECK(equal(old[k], &kept[k])); tx_release(old[k]); free(kept[k].text); }
    /* Invalid edits change nothing and allocate nothing visible. */
    tx_text *e = (tx_text *)1;
    CHECK(tx_edit(t, m.length + 1, m.length + 1, NULL, 0, &e) == TS_INVALID && e == NULL);
    CHECK(tx_edit(t, 2, 1, NULL, 0, &e) == TS_INVALID);
    CHECK(tx_edit(t, 0, 0, NULL, 3, &e) == TS_INVALID);
    CHECK(tx_read(t, m.length, 1, ins) == TS_INVALID);
    printf("ok text differential: 20000 edits (typing, paste, cut), 8 retained snapshots unchanged, %zu units, height <= %zu\n",
           m.length, max_height);
    tx_release(t); free(m.text);
}

static void durable(void) {
    ts_store *s; MUST(ts_open(path("durable"), &s) == TS_OK);
    size_t n = 1 << 20;
    uint16_t *init = malloc(n * 2); fill(init, n);
    model m = {0}; put(&m, 0, 0, init, n);
    tx_text *t; MUST(tx_build(init, n, &t) == TS_OK); free(init);
    size_t cap = TX_MANIFEST_HEADER + TX_MANIFEST_ENTRY * (n / TX_LEAF_MIN + 2);
    uint8_t *manifest = malloc(cap); size_t size; tx_saved saved;
    CHECK(tx_store(s, t, manifest, 8, &size, &saved) == TS_CAPACITY && size > 8 && saved.written == 0);
    MUST(tx_store(s, t, manifest, cap, &size, &saved) == TS_OK);
    CHECK(saved.chunks == saved.written && saved.chunks == n / TX_LEAF_TARGET);
    /* Typing into a 1M-unit text: each save writes at most the chunks the edit created. */
    size_t caret = n / 3, most = 0, total = 0;
    for (int i = 0; i < 300; i++) {
        uint16_t c = (uint16_t)('A' + i % 26); tx_text *e;
        if (i % 50 == 49) caret = next(m.length);
        MUST(tx_edit(t, caret, caret, &c, 1, &e) == TS_OK);
        put(&m, caret, caret, &c, 1); caret++;
        tx_release(t); t = e;
        MUST(tx_store(s, t, manifest, cap, &size, &saved) == TS_OK);
        if (saved.written > most) most = saved.written;
        total += saved.written;
    }
    CHECK(most <= 2 && total <= 300 + 12);
    tx_text *loaded;
    MUST(tx_load(s, manifest, size, &loaded) == TS_OK);
    CHECK(equal(loaded, &m)); structure(loaded);
    /* A loaded text keeps its durable identity: saving it again writes nothing. */
    MUST(tx_store(s, loaded, manifest, cap, &size, &saved) == TS_OK);
    CHECK(saved.written == 0);
    printf("ok text durable: 300 keystrokes into %zu units wrote %zu chunks (at most %zu per save), manifest %zu bytes\n",
           m.length, total, most, size);
    /* Manifest validation fails closed. */
    uint8_t *bad = malloc(size); tx_text *x = (tx_text *)1;
    memcpy(bad, manifest, size); bad[4] = 2;
    CHECK(tx_load(s, bad, size, &x) == TS_INVALID && x == NULL);
    CHECK(tx_load(s, manifest, size - 1, &x) == TS_INVALID);
    memcpy(bad, manifest, size); bad[16] ^= 1;                       /* total units */
    CHECK(tx_load(s, bad, size, &x) == TS_INVALID);
    memcpy(bad, manifest, size); bad[TX_MANIFEST_HEADER + 36] ^= 1;   /* first chunk's newlines */
    CHECK(tx_load(s, bad, size, &x) == TS_INVALID);
    /* Consistent totals but a chunk whose stated newlines differ from its content. */
    size_t entry = TX_MANIFEST_HEADER;
    while (manifest[entry + 36] == manifest[entry + 32] || manifest[entry + 36] == 0xff) entry += TX_MANIFEST_ENTRY;
    memcpy(bad, manifest, size); bad[entry + 36]++; bad[24]++;
    CHECK(bad[24] != 0 && tx_load(s, bad, size, &x) == TS_INVALID && x == NULL);
    memcpy(bad, manifest, size); bad[TX_MANIFEST_HEADER] ^= 1;        /* unknown chunk */
    CHECK(tx_load(s, bad, size, &x) != TS_OK);
    /* A corrupted chunk object is an IO error, never wrong text. */
    char hex[65], file[3300];
    for (int i = 0; i < 32; i++) sprintf(hex + 2 * i, "%02x", manifest[TX_MANIFEST_HEADER + i]);
    snprintf(file, sizeof file, "%s/objects/%.2s/%s", path("durable"), hex, hex + 2);
    FILE *f = fopen(file, "r+b"); MUST(f); fseek(f, 10, SEEK_SET); int ch = fgetc(f); fseek(f, 10, SEEK_SET); fputc(ch ^ 1, f); fclose(f);
    CHECK(tx_load(s, manifest, size, &x) == TS_IO && x == NULL);
    puts("ok text manifest: version, truncation, totals, counts, unknown and corrupted chunks rejected");
    free(bad); free(manifest); free(m.text);
    tx_release(t); tx_release(loaded); ts_close(s);
}

/* Readers on other threads hold snapshots while the owner keeps editing and releasing. */
typedef struct { tx_text *snap; model expect; int ok; } reader;
static void *read_snapshot(void *arg) {
    reader *r = arg;
    for (int i = 0; i < 20; i++) r->ok &= equal(r->snap, &r->expect);
    return NULL;
}
static void threads(void) {
    model m = {0}; tx_text *t = NULL;
    uint16_t ins[256];
    reader rs[4]; pthread_t th[4];
    for (int round = 0; round < 4; round++) {
        for (int i = 0; i < 500; i++) {
            size_t n = 1 + next(255), at = next(m.length + 1); fill(ins, n); tx_text *e;
            MUST(tx_edit(t, at, at, ins, n, &e) == TS_OK); put(&m, at, at, ins, n); tx_release(t); t = e;
        }
        rs[round].snap = tx_retain(t); rs[round].ok = 1;
        rs[round].expect = (model){0}; put(&rs[round].expect, 0, 0, m.text, m.length);
        MUST(!pthread_create(&th[round], NULL, read_snapshot, &rs[round]));
    }
    for (int i = 0; i < 2000; i++) {    /* keep editing shared structure while readers run */
        size_t at = next(m.length + 1), end = at + next(m.length - at + 1) % 300; tx_text *e;
        MUST(tx_edit(t, at, end, ins, 7, &e) == TS_OK); put(&m, at, end, ins, 7); tx_release(t); t = e;
    }
    for (int r = 0; r < 4; r++) {
        pthread_join(th[r], NULL);
        CHECK(rs[r].ok); tx_release(rs[r].snap); free(rs[r].expect.text);
    }
    CHECK(equal(t, &m));
    puts("ok text threads: four readers verify retained snapshots while the owner edits and releases");
    tx_release(t); free(m.text);
}

/* Per-edit cost should not grow with the text: compare 64K-unit and 4M-unit texts. */
static double cost(size_t n, int flat) {
    uint16_t *init = malloc(n * 2); fill(init, n);
    model m = {0}; tx_text *t = NULL;
    if (flat) put(&m, 0, 0, init, n); else MUST(tx_build(init, n, &t) == TS_OK);
    free(init);
    double begin = now();
    for (int i = 0; i < 4000; i++) {
        uint16_t c = 'x'; size_t at = next(n);
        if (flat) put(&m, at, at, &c, 1);
        else { tx_text *e; MUST(tx_edit(t, at, at, &c, 1, &e) == TS_OK); tx_release(t); t = e; }
    }
    double per = (now() - begin) / 4000 * 1e9;
    tx_release(t); free(m.text);
    return per;
}
static void scaling(void) {
    double small = cost(1 << 16, 0), large = cost(1 << 22, 0), flat = cost(1 << 22, 1);
    printf("ok text scaling: random 1-unit edit %.0f ns at 64K units, %.0f ns at 4M units (flat array at 4M: %.0f ns)\n",
           small, large, flat);
    CHECK(large < small * 4);
}

int main(int argc, char **argv) {
    if (argc != 2 || strlen(argv[1]) >= sizeof base) { fprintf(stderr, "usage: core-text <directory>\n"); return 2; }
    strcpy(base, argv[1]); mkdir(base, 0700);
    differential(); durable(); threads(); scaling();
    if (failures) { fprintf(stderr, "core-text: %d failures\n", failures); return 1; }
    puts("core-text: all checks passed (indexed text and chunk storage, not shaping/layout evidence)"); return 0;
}
