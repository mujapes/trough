/* Durable store oracle: reference model, effect lifecycle, quota, ownership, objects and
 * SIGKILL crash recovery. Usage: core-store <empty or new directory>. Correctness only. */
#define _GNU_SOURCE
#include "store.h"
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

static int failures;
#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__, #c); failures++; } } while (0)
#define MUST(c) do { if (!(c)) { fprintf(stderr, "FATAL %s:%d %s\n", __FILE__, __LINE__, #c); exit(1); } } while (0)

static uint64_t seed = 0x9e3779b97f4a7c15ULL;
static uint64_t rnd(void) { seed ^= seed << 13; seed ^= seed >> 7; seed ^= seed << 17; return seed; }

static char base[3000];
static const char *dir_named(const char *name) {
    static char path[3100];
    snprintf(path, sizeof path, "%s/%s", base, name);
    return path;
}

static void intent(uint8_t out[16], uint64_t n) { memset(out, 0, 16); memcpy(out, &n, 8); out[15] = 0x7e; }

static void sha_vectors(void) {
    uint8_t h[32];
    char hexs[65];
    static const struct { const char *in; const char *out; } v[] = {
        {"", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"},
        {"abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"},
        {"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",
         "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"},
    };
    for (size_t i = 0; i < sizeof v / sizeof *v; i++) {
        ts_sha256(v[i].in, strlen(v[i].in), h);
        for (int j = 0; j < 32; j++) sprintf(hexs + 2 * j, "%02x", h[j]);
        CHECK(!strcmp(hexs, v[i].out));
    }
    printf("ok sha-256 matches FIPS 180-4 vectors\n");
}

static void records(void) {
    ts_store *s;
    MUST(ts_open(dir_named("records"), &s) == TS_OK);
    ts_store *second;
    CHECK(ts_open(dir_named("records"), &second) == TS_CONFLICT);   /* one owner */
    ts_receipt rc;
    ts_record r;
    char buf[64];
    ts_write w = {0, 7, "one", 3, 0};
    CHECK(ts_commit(s, 0, 0, &w, 1, 0, 0, &rc) == TS_OK && w.id && rc.commit && rc.changed == 1);
    uint64_t id = w.id, first = rc.commit;
    CHECK(ts_get(s, id, &r, buf, sizeof buf) == TS_OK && r.revision == 1 && r.schema == 7 && r.size == 3 && !memcmp(buf, "one", 3));
    ts_write blind = {id, 7, "two", 3, 0};
    CHECK(ts_commit(s, 0, 0, &blind, 1, 0, 0, &rc) == TS_INVALID);           /* no captured read */
    ts_read stale = {id, 0};
    CHECK(ts_commit(s, &stale, 1, &blind, 1, 0, 0, &rc) == TS_CONFLICT);
    ts_read cur = {id, 1};
    ts_write same = {id, 7, "one", 3, 0};
    CHECK(ts_commit(s, &cur, 1, &same, 1, 0, 0, &rc) == TS_OK && rc.changed == 0 && rc.commit == 0);
    CHECK(ts_commit(s, &cur, 1, &blind, 1, 0, 0, &rc) == TS_OK && rc.commit == first + 1);  /* no seq consumed above */
    CHECK(ts_get(s, id, &r, buf, 1) == TS_CAPACITY && r.size == 3 && r.revision == 2);
    CHECK(ts_get_revision(s, id, 1, &r, buf, sizeof buf) == TS_OK && !memcmp(buf, "one", 3));
    CHECK(ts_commit(s, &cur, 1, &same, 1, 0, 0, &rc) == TS_CONFLICT);            /* revision 1 is stale now */
    /* Atomic output set: a conflict anywhere installs nothing, including creations. */
    ts_read mixed[2] = {{id, 2}, {id + 1000, 5}};
    ts_write two[2] = {{id, 7, "three", 5, 0}, {0, 8, "new", 3, 0}};
    CHECK(ts_commit(s, mixed, 2, two, 2, 0, 0, &rc) == TS_CONFLICT && two[1].id == 0);
    CHECK(ts_get(s, id, &r, buf, sizeof buf) == TS_OK && r.revision == 2);
    ts_read gone = {id, 2};
    ts_write del = {id, 0, 0, 0, 1};
    CHECK(ts_commit(s, &gone, 1, &del, 1, 0, 0, &rc) == TS_OK);
    CHECK(ts_get(s, id, &r, buf, sizeof buf) == TS_NOT_FOUND && r.revision == 3);
    ts_read removed = {id, 3};
    CHECK(ts_commit(s, &removed, 1, &blind, 1, 0, 0, &rc) == TS_INVALID);          /* never revived */
    ts_write fresh = {0, 7, "x", 1, 0};
    CHECK(ts_commit(s, 0, 0, &fresh, 1, 0, 0, &rc) == TS_OK && fresh.id > id);    /* never reused */
    ts_close(s);
    printf("ok captured reads, conflicts, no blind writes, unchanged no-ops, atomic sets, history, removal, one owner\n");
}

enum { MODEL = 24, MODEL_OPS = 20000 };
static void differential(void) {
    struct { uint64_t id, revision; unsigned live; uint32_t schema; uint8_t data[16]; size_t size; } m[MODEL] = {{0}};
    ts_store *s;
    MUST(ts_open(dir_named("model"), &s) == TS_OK);
    size_t accepted = 0, conflicts = 0, unchanged = 0;
    for (int op = 0; op < MODEL_OPS; op++) {
        if (op % 2500 == 2499) { ts_close(s); MUST(ts_open(dir_named("model"), &s) == TS_OK); }  /* durable across reopen */
        ts_read reads[4];
        ts_write writes[4];
        uint8_t payload[4][16];
        int slot[4], n = 1 + (int)(rnd() % 4), nr = 0, nw = 0, stale = 0, change = 0;
        for (int i = 0; i < n; i++) {
            int k = (int)(rnd() % MODEL), dup = 0;
            for (int j = 0; j < i; j++) dup |= slot[j] == k;
            if (dup) { n = i; break; }
            slot[i] = k;
            size_t size = rnd() % 3 == 0 ? m[k].size : rnd() % 16;   /* sometimes rewrite the same bytes */
            memcpy(payload[i], m[k].data, sizeof payload[i]);
            if (size != m[k].size || rnd() % 2) for (size_t b = 0; b < size; b++) payload[i][b] = (uint8_t)rnd();
            if (!m[k].id) { writes[nw++] = (ts_write){0, 3, payload[i], size, 0}; change = 1; continue; }
            if (!m[k].live) continue;
            uint64_t captured = m[k].revision - (rnd() % 8 == 0 && m[k].revision > 1);
            stale |= captured != m[k].revision;
            reads[nr++] = (ts_read){m[k].id, captured};
            if (rnd() % 4) {
                unsigned remove = rnd() % 40 == 0;
                writes[nw++] = (ts_write){m[k].id, 3, payload[i], size, remove};
                change |= remove || size != m[k].size || memcmp(payload[i], m[k].data, size);
            }
        }
        ts_receipt rc;
        ts_status st = ts_commit(s, reads, (size_t)nr, writes, (size_t)nw, 0, 0, &rc);
        if (stale) { CHECK(st == TS_CONFLICT); conflicts++; continue; }
        CHECK(st == TS_OK);
        if (st) continue;
        CHECK((rc.commit != 0) == change);
        if (!change) unchanged++; else accepted++;
        for (int i = 0, wi = 0; i < n && wi < nw; i++) {
            int k = slot[i];
            if (m[k].id && writes[wi].id != m[k].id) continue;   /* read-only slot */
            ts_write *w = &writes[wi++];
            int differs = !m[k].id || w->remove || w->size != m[k].size || memcmp(w->data, m[k].data, w->size);
            if (!m[k].id) m[k].id = w->id;
            if (differs) m[k].revision++;
            if (w->remove) m[k].live = 0;
            else { m[k].live = 1; memcpy(m[k].data, w->data, w->size); m[k].size = w->size; }
        }
        for (int k = 0; k < MODEL; k++) if (m[k].id) {   /* the whole visible state, not only touched records */
            ts_record r;
            uint8_t buf[16];
            ts_status g = ts_get(s, m[k].id, &r, buf, sizeof buf);
            CHECK(r.revision == m[k].revision);
            CHECK(m[k].live ? g == TS_OK && r.size == m[k].size && !memcmp(buf, m[k].data, r.size) : g == TS_NOT_FOUND);
        }
        for (int k = 0; k < MODEL; k++)   /* recycle a removed record's model slot (never its id) */
            if (m[k].id && !m[k].live && rnd() % 4 == 0) memset(&m[k], 0, sizeof m[k]);
        if (failures) break;
    }
    ts_close(s);
    CHECK(accepted > MODEL_OPS / 2 && conflicts > MODEL_OPS / 50);   /* the oracle exercised real change */
    printf("ok %d differential operations: accepted=%zu unchanged=%zu stale=%zu (reopened every 2500)\n",
           MODEL_OPS, accepted, unchanged, conflicts);
}

static ts_effect fx(uint64_t n, ts_effect_state from, ts_effect_state to, const char *detail) {
    ts_effect e = {{0}, from, to, 9, detail, detail ? strlen(detail) : 0};
    intent(e.intent, n);
    return e;
}

static void effects(void) {
    ts_store *s;
    MUST(ts_open(dir_named("effects"), &s) == TS_OK);
    ts_receipt rc;
    ts_effect_state st;
    ts_effect e = fx(1, 0, TS_PREPARED, "POST /x");
    CHECK(ts_commit(s, 0, 0, 0, 0, &e, 1, &rc) == TS_OK && rc.changed == 1);
    CHECK(ts_commit(s, 0, 0, 0, 0, &e, 1, &rc) == TS_OK && rc.changed == 0);        /* retry: not doubled */
    ts_effect other = fx(1, 0, TS_PREPARED, "POST /y");
    CHECK(ts_commit(s, 0, 0, 0, 0, &other, 1, &rc) == TS_CONFLICT);                 /* same id, other intent */
    ts_effect skip = fx(1, TS_PREPARED, TS_ISSUED, 0);
    CHECK(ts_commit(s, 0, 0, 0, 0, &skip, 1, &rc) == TS_INVALID);                   /* never issued unauthorised */
    ts_effect a = fx(1, TS_PREPARED, TS_AUTHORISED, 0), i = fx(1, TS_AUTHORISED, TS_ISSUED, 0);
    CHECK(ts_commit(s, 0, 0, 0, 0, &a, 1, &rc) == TS_OK && ts_commit(s, 0, 0, 0, 0, &i, 1, &rc) == TS_OK);
    CHECK(ts_commit(s, 0, 0, 0, 0, &a, 1, &rc) == TS_CONFLICT);                     /* state moved on */
    /* Outbox: a record write and an effect transition are one durable fact. */
    ts_effect p2 = fx(2, 0, TS_PREPARED, "send");
    ts_write note = {0, 1, "draft", 5, 0};
    ts_effect bad = fx(3, TS_PREPARED, TS_AUTHORISED, 0);                            /* does not exist */
    ts_effect both[2] = {p2, bad};
    CHECK(ts_commit(s, 0, 0, &note, 1, both, 2, &rc) == TS_NOT_FOUND && note.id == 0);
    CHECK(ts_effect_get(s, p2.intent, &st, 0) == TS_NOT_FOUND);
    ts_close(s);
    MUST(ts_open(dir_named("effects"), &s) == TS_OK);
    CHECK(ts_recovered_unknown(s) == 1);
    CHECK(ts_effect_get(s, e.intent, &st, 0) == TS_OK && st == TS_UNKNOWN);         /* never silently re-issued */
    ts_effect again = fx(1, TS_ISSUED, TS_OBSERVED, "200");
    CHECK(ts_commit(s, 0, 0, 0, 0, &again, 1, &rc) == TS_CONFLICT);
    ts_effect rec = fx(1, TS_UNKNOWN, TS_OBSERVED, "found by postcondition");
    CHECK(ts_commit(s, 0, 0, 0, 0, &rec, 1, &rc) == TS_OK);
    ts_effect after = fx(1, TS_OBSERVED, TS_FAILED, 0);
    CHECK(ts_commit(s, 0, 0, 0, 0, &after, 1, &rc) == TS_INVALID);                  /* terminal */
    ts_close(s);
    MUST(ts_open(dir_named("effects"), &s) == TS_OK);
    CHECK(ts_recovered_unknown(s) == 0);
    ts_close(s);
    printf("ok effect lifecycle: idempotent prepare, no skipped authorisation, outbox atomicity, issued->unknown on reopen, reconciliation, terminal states\n");
}

static void quota_and_objects(void) {
    ts_store *s;
    MUST(ts_open(dir_named("quota"), &s) == TS_OK);
    ts_receipt rc;
    ts_record r;
    static char big[TS_DATA_LIMIT], buf[TS_DATA_LIMIT];
    memset(big, 'q', sizeof big);
    ts_write w = {0, 1, "small", 5, 0};
    CHECK(ts_commit(s, 0, 0, &w, 1, 0, 0, &rc) == TS_OK);
    CHECK(ts_limit_pages(s, 16) == TS_OK);
    ts_read cur = {w.id, 1};
    ts_write huge[2] = {{w.id, 1, big, sizeof big, 0}, {0, 1, big, sizeof big, 0}};
    CHECK(ts_commit(s, &cur, 1, huge, 2, 0, 0, &rc) == TS_FULL && huge[1].id == 0);
    CHECK(ts_get(s, w.id, &r, buf, sizeof buf) == TS_OK && r.revision == 1 && r.size == 5);
    CHECK(ts_limit_pages(s, 0) == TS_OK);
    huge[1].id = 0;
    CHECK(ts_commit(s, &cur, 1, huge, 2, 0, 0, &rc) == TS_OK);                      /* usable after the full disk */
    uint8_t h[32], h2[32];
    size_t size;
    CHECK(ts_object_put(s, "artifact", 8, h) == TS_OK && ts_object_put(s, "artifact", 8, h2) == TS_OK && !memcmp(h, h2, 32));
    CHECK(ts_object_get(s, h, buf, sizeof buf, &size) == TS_OK && size == 8 && !memcmp(buf, "artifact", 8));
    char path[3300], hx[65];
    for (int i = 0; i < 32; i++) sprintf(hx + 2 * i, "%02x", h[i]);
    snprintf(path, sizeof path, "%s/objects/%.2s/%s", dir_named("quota"), hx, hx + 2);
    FILE *f = fopen(path, "r+");
    MUST(f);
    fputc('X', f);
    fclose(f);
    CHECK(ts_object_get(s, h, buf, sizeof buf, &size) == TS_IO);                    /* corruption is never data */
    ts_close(s);
    printf("ok quota exhaustion installs nothing and recovers; objects are content-addressed, deduplicated and verified\n");
}

/* A child commits transfers (sum invariant), effect transitions and object references, and
 * reports each durable receipt. The parent SIGKILLs it at a random point and checks recovery. */
enum { ACCOUNTS = 8, CYCLES = 20 };
static void child(int out) {
    ts_store *s;
    MUST(ts_open(dir_named("crash"), &s) == TS_OK);
    uint64_t ids[ACCOUNTS + 1];
    ts_record r;
    uint8_t buf[64];
    for (uint64_t k = 0; k <= ACCOUNTS; k++) ids[k] = k + 1;   /* counter is id 1, accounts 2..9 */
    for (;;) {
        ts_read reads[ACCOUNTS + 1];
        int64_t value[ACCOUNTS + 1];
        for (int k = 0; k <= ACCOUNTS; k++) {
            MUST(ts_get(s, ids[k], &r, buf, sizeof buf) == TS_OK);
            reads[k] = (ts_read){ids[k], r.revision};
            memcpy(&value[k], buf, 8);
        }
        uint64_t n = (uint64_t)value[0] + 1;
        int from = 1 + (int)(rnd() % ACCOUNTS), to = 1 + (int)(rnd() % ACCOUNTS);
        int64_t amount = (int64_t)(rnd() % 50);
        value[from] -= amount;
        value[to] += amount;
        value[0] = (int64_t)n;
        char artifact[32];
        int len = snprintf(artifact, sizeof artifact, "artifact %llu", (unsigned long long)n);
        uint8_t counter[40];
        memcpy(counter, &value[0], 8);
        MUST(ts_object_put(s, artifact, (size_t)len, counter + 8) == TS_OK);   /* object durable before referenced */
        ts_write writes[ACCOUNTS + 1];
        writes[0] = (ts_write){ids[0], 1, counter, 40, 0};
        for (int k = 1; k <= ACCOUNTS; k++) writes[k] = (ts_write){ids[k], 2, &value[k], 8, 0};
        ts_effect e[4];
        size_t ne = 0;
        e[ne++] = fx(n, 0, TS_PREPARED, "call");
        if (n > 1) e[ne++] = fx(n - 1, TS_PREPARED, TS_AUTHORISED, 0);
        if (n > 2) e[ne++] = fx(n - 2, TS_AUTHORISED, TS_ISSUED, 0);
        if (n > 3) {   /* issued, or unknown if a crash interrupted it: reconciled by postcondition */
            ts_effect_state st;
            ts_effect q = fx(n - 3, 0, 0, 0);
            MUST(ts_effect_get(s, q.intent, &st, 0) == TS_OK);
            e[ne++] = fx(n - 3, st, TS_OBSERVED, "ok");
        }
        ts_receipt rc;
        MUST(ts_commit(s, reads, ACCOUNTS + 1, writes, ACCOUNTS + 1, e, ne, &rc) == TS_OK);
        MUST(write(out, &n, sizeof n) == sizeof n);     /* acknowledged only after durable commit */
    }
}

static void crash(void) {
    ts_store *s;
    MUST(ts_open(dir_named("crash"), &s) == TS_OK);
    ts_write init[ACCOUNTS + 1];
    int64_t start = 1000;
    uint8_t counter[40] = {0};
    init[0] = (ts_write){0, 1, counter, 40, 0};
    for (int k = 1; k <= ACCOUNTS; k++) init[k] = (ts_write){0, 2, &start, 8, 0};
    ts_receipt rc;
    MUST(ts_commit(s, 0, 0, init, ACCOUNTS + 1, 0, 0, &rc) == TS_OK && init[0].id == 1);
    ts_close(s);
    uint64_t acked = 0, unknown = 0, before = 0;
    for (int cycle = 0; cycle < CYCLES; cycle++) {
        int p[2];
        MUST(!pipe(p));
        pid_t pid = fork();
        MUST(pid >= 0);
        if (!pid) { close(p[0]); seed += (uint64_t)cycle * 7919; child(p[1]); _exit(0); }
        close(p[1]);
        uint64_t want = 5 + rnd() % 60, got = 0, n;
        while (got < want && read(p[0], &n, sizeof n) == sizeof n) { acked = n; got++; }
        if (rnd() % 2) usleep((useconds_t)(rnd() % 3000));   /* sometimes mid-commit */
        kill(pid, SIGKILL);
        waitpid(pid, 0, 0);
        while (read(p[0], &n, sizeof n) == sizeof n) acked = n;
        close(p[0]);
        MUST(ts_open(dir_named("crash"), &s) == TS_OK);
        ts_record r;
        uint8_t buf[64], object[64];
        int64_t sum = 0, v;
        uint64_t count;
        MUST(ts_get(s, 1, &r, buf, sizeof buf) == TS_OK);
        memcpy(&count, buf, 8);
        CHECK(count >= acked);                               /* every acknowledged commit survived */
        size_t size;
        if (count) CHECK(ts_object_get(s, buf + 8, object, sizeof object, &size) == TS_OK);  /* referenced object exists */
        for (uint64_t k = 2; k <= ACCOUNTS + 1; k++) {
            MUST(ts_get(s, k, &r, buf, sizeof buf) == TS_OK);
            memcpy(&v, buf, 8);
            sum += v;
        }
        CHECK(sum == ACCOUNTS * 1000);                       /* no partial transaction */
        ts_effect_state st;
        /* Exactly the one effect the last commit issued, unless no commit happened since the last recovery. */
        CHECK(ts_recovered_unknown(s) == (count > 2 && count != before));
        ts_effect issued = fx(count - 2, 0, 0, 0), observed = fx(count - 3, 0, 0, 0);
        if (count > 2) CHECK(ts_effect_get(s, issued.intent, &st, 0) == TS_OK && st == TS_UNKNOWN);
        if (count > 3) CHECK(ts_effect_get(s, observed.intent, &st, 0) == TS_OK && st == TS_OBSERVED);
        unknown += ts_recovered_unknown(s);
        ts_close(s);
        if (failures) break;
        acked = before = count;
    }
    printf("ok %d SIGKILL cycles: no acknowledged commit lost, sums intact, objects present, %llu issued effects became unknown\n",
           CYCLES, (unsigned long long)unknown);
}

int main(int argc, char **argv) {
    if (argc != 2) { fprintf(stderr, "usage: core-store <directory>\n"); return 2; }
    snprintf(base, sizeof base, "%s", argv[1]);
    mkdir(base, 0700);
    sha_vectors();
    records();
    differential();
    effects();
    quota_and_objects();
    crash();
    if (failures) { fprintf(stderr, "core-store: %d failures\n", failures); return 1; }
    printf("core-store: all checks passed (correctness only; no device performance claim)\n");
    return 0;
}
