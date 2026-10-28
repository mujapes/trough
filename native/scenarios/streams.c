#define _POSIX_C_SOURCE 200809L
#include "stream.h"
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
static char base[3000];
static const char *path(const char *name) { static char p[3100]; snprintf(p, sizeof p, "%s/%s", base, name); return p; }
static void key_for(uint8_t key[16], unsigned group, uint64_t number) {
    memset(key, 0, 16); key[0] = (uint8_t)group;
    for (unsigned i = 0; i < 8; i++) key[8 + i] = (uint8_t)(number >> (8 * i));
}
static tl_value target(ts_store *s) {
    ts_write w = {0, 42, "original", 8, 0, NULL, 0}; ts_receipt receipt;
    MUST(ts_commit(s, NULL, 0, &w, 1, NULL, 0, &receipt) == TS_OK);
    tl_value v = {0}; v.kind = 1; v.created_ms = 1000; v.target = (td_reference){w.id, 1, 42};
    v.count = 1; v.provenance[0] = v.target;
    return v;
}
static void append(ts_store *s, tl_head *head, unsigned group, uint64_t number, const tl_value *value, tl_entry *out) {
    uint8_t key[16]; key_for(key, group, number);
    MUST(tl_append(s, (ts_read){head->record.id, head->record.revision}, key, value, out) == TS_OK);
    MUST(tl_load(s, head->record.id, 0, head) == TS_OK);
}

static void ordering(void) {
    enum { TOTAL = 1200 };
    ts_store *s; MUST(ts_open(path("order"), &s) == TS_OK);
    tl_value value = target(s); tl_head heads[2]; uint8_t root[16];
    for (int i = 0; i < 2; i++) { key_for(root, 100, (unsigned)i); MUST(tl_open(s, root, &heads[i]) == TS_OK); }
    uint64_t order[2][TOTAL], count[2] = {0}, ids[2][TOTAL], commits[2][TOTAL];
    tl_cursor snapshot[2]; uint64_t frozen_count[2] = {0};
    tl_entry entry;
    for (unsigned n = 0; n < TOTAL; n++) {
        unsigned domain = ((n * 17) ^ (n >> 2)) % 2;
        ts_read captured = {heads[domain].record.id, heads[domain].record.revision};
        value.created_ms = TOTAL - n; /* Deliberately decreasing wall time must not reorder. */
        append(s, &heads[domain], 1, n, &value, &entry);
        uint64_t position = count[domain]++;
        order[domain][position] = n; ids[domain][position] = entry.record.id; commits[domain][position] = entry.record.commit;
        CHECK(entry.sequence == count[domain] && heads[domain].length == count[domain] &&
              heads[domain].record.commit == entry.record.commit && heads[domain].record.revision == count[domain] + 1);
        if (n % 11 == 0) {
            CHECK(tl_append(s, captured, entry.key, &value, &entry) == TS_OK && entry.record.id == ids[domain][position] &&
                  entry.record.commit == commits[domain][position]);
            tl_value other = value; other.created_ms++;
            CHECK(tl_append(s, captured, entry.key, &other, &entry) == TS_CONFLICT);
        }
        if (n % 19 == 0) {
            uint8_t rejected[16]; key_for(rejected, 2, n);
            CHECK(tl_append(s, captured, rejected, &value, &entry) == TS_CONFLICT && !entry.record.commit);
            uint64_t id; CHECK(ts_lookup(s, rejected, &id) == TS_NOT_FOUND);
        }
        if (n == TOTAL / 2) for (int i = 0; i < 2; i++) {
            MUST(tl_begin(&heads[i], &snapshot[i]) == TS_OK); frozen_count[i] = count[i];
        }
        if (n % 100 == 99) { ts_close(s); MUST(ts_open(path("order"), &s) == TS_OK); }
        if (failures) break;
    }
    /* Independent serial append vectors are the oracle, across varying pages and old tails. */
    for (int frozen = 0; frozen < 2; frozen++) for (int domain = 0; domain < 2; domain++) {
        tl_cursor cursor; tl_entry page[TL_PAGE_LIMIT];
        if (frozen) cursor = snapshot[domain]; else MUST(tl_begin(&heads[domain], &cursor) == TS_OK);
        uint64_t remaining = frozen ? frozen_count[domain] : count[domain];
        size_t got, page_size = 1 + (size_t)(remaining % TL_PAGE_LIMIT);
        do {
            MUST(tl_page(s, &cursor, page, page_size, &got) == TS_OK);
            for (size_t k = 0; k < got; k++) {
                MUST(remaining > 0); uint64_t slot = --remaining; uint8_t key[16]; key_for(key, 1, order[domain][slot]);
                CHECK(page[k].sequence == slot + 1 && page[k].record.id == ids[domain][slot] &&
                      page[k].record.commit == commits[domain][slot] && !memcmp(page[k].key, key, 16));
                CHECK(page[k].value.created_ms == TOTAL - order[domain][slot] && page[k].value.target.id == value.target.id);
                CHECK(page[k].value.count == 1 && page[k].value.provenance[0].revision == 1);
            }
        } while (got);
        CHECK(!remaining && !cursor.remaining);
    }
    for (int i = 0; i < 2; i++) {
        tl_head historical;
        CHECK(tl_load(s, heads[i].record.id, frozen_count[i] + 1, &historical) == TS_OK && historical.length == frozen_count[i]);
        key_for(root, 100, (unsigned)i); tl_head reopened;
        CHECK(tl_open(s, root, &reopened) == TS_OK && reopened.length == count[i] && reopened.record.id == heads[i].record.id);
    }
    /* The reference remains the original content after current content is edited/deleted. */
    ts_read read = {value.target.id, 1}; ts_write edit = {value.target.id, 42, "new text", 8, 0, NULL, 0}; ts_receipt receipt;
    MUST(ts_commit(s, &read, 1, &edit, 1, NULL, 0, &receipt) == TS_OK);
    append(s, &heads[0], 3, 1, &value, &entry); /* an explicitly historical input is valid */
    read.revision = 2; edit.remove = 1; MUST(ts_commit(s, &read, 1, &edit, 1, NULL, 0, &receipt) == TS_OK);
    append(s, &heads[0], 3, 2, &value, &entry);
    char text[16]; ts_record record;
    CHECK(ts_get_revision(s, entry.value.target.id, entry.value.target.revision, &record, text, sizeof text) == TS_OK &&
          record.size == 8 && !memcmp(text, "original", 8));
    ts_close(s);
    puts("ok 1200 reference appends across two streams: atomic tails, durable ordering, retries, stale rejection, snapshot paging and historical provenance");
}

static void validation_and_quota(void) {
    ts_store *s; MUST(ts_open(path("validation"), &s) == TS_OK);
    tl_value value = target(s); tl_head head; uint8_t root[16] = {100}, key[16] = {1}; tl_entry entry;
    MUST(tl_open(s, root, &head) == TS_OK);
    tl_cursor cursor; size_t count;
    MUST(tl_begin(&head, &cursor) == TS_OK);
    CHECK(tl_page(s, &cursor, &entry, 1, &count) == TS_OK && count == 0);
    ts_read expected = {head.record.id, head.record.revision};
    value.target.schema++;
    CHECK(tl_append(s, expected, key, &value, &entry) == TS_INVALID);
    value.target.schema--; value.target.revision++;
    CHECK(tl_append(s, expected, key, &value, &entry) == TS_NOT_FOUND);
    value.target.revision--; value.provenance[0].revision++;
    CHECK(tl_append(s, expected, key, &value, &entry) == TS_NOT_FOUND);
    value.provenance[0].revision--; value.count = TL_PROVENANCE_LIMIT + 1;
    CHECK(tl_append(s, expected, key, &value, &entry) == TS_INVALID);
    value.count = 1; key[0] = 0;
    CHECK(tl_append(s, expected, key, &value, &entry) == TS_INVALID);
    key[0] = 1;
    CHECK(tl_append(s, expected, root, &value, &entry) == TS_CONFLICT);
    CHECK(tl_load(s, head.record.id, 0, &head) == TS_OK && head.length == 0);
    /* Constrain allocation at the current size. A complete append must either fit or leave
     * both tail and key absent; use large provenance to exhaust remaining page slack. */
    value.count = TL_PROVENANCE_LIMIT;
    for (size_t i = 0; i < value.count; i++) value.provenance[i] = value.target;
    MUST(ts_limit_pages(s, 1) == TS_OK);
    ts_status status = TS_OK;
    for (unsigned n = 0; n < 200 && status == TS_OK; n++) {
        key_for(key, 2, n); expected = (ts_read){head.record.id, head.record.revision};
        status = tl_append(s, expected, key, &value, &entry);
        if (!status) MUST(tl_load(s, head.record.id, 0, &head) == TS_OK);
    }
    CHECK(status == TS_FULL && entry.record.commit == 0);
    tl_head after; MUST(tl_load(s, head.record.id, 0, &after) == TS_OK);
    CHECK(after.length == head.length && after.record.revision == head.record.revision);
    uint64_t id; CHECK(ts_lookup(s, key, &id) == TS_NOT_FOUND);
    MUST(ts_limit_pages(s, 0) == TS_OK);
    CHECK(tl_append(s, expected, key, &value, &entry) == TS_OK && entry.sequence == head.length + 1);
    /* Fixed wire offsets are checked without using the encoder as its own reference. */
    uint8_t wire[1024]; ts_record record;
    MUST(ts_get(s, entry.record.id, &record, wire, sizeof wire) == TS_OK);
    CHECK(record.size == 96 + 24 * TL_PROVENANCE_LIMIT && !memcmp(wire, "TENT\1\0\0\0", 8) &&
          wire[8] == head.record.id && wire[16] == head.length + 1 && !memcmp(wire + 40, key, 16) &&
          wire[56] == 1 && wire[60] == TL_PROVENANCE_LIMIT && wire[72] == value.target.id && wire[80] == 1 && wire[88] == 42);
    CHECK(tl_get(s, entry.key, &entry) == TS_OK); /* intentional output-key alias */
    /* Mutation through the raw store must be detected as an immutable-entry violation. */
    ts_read old = {entry.record.id, 1}; wire[56] = 2;
    ts_write corrupt = {entry.record.id, TL_ENTRY_SCHEMA, wire, record.size, 0, NULL, 0}; ts_receipt receipt;
    MUST(ts_commit(s, &old, 1, &corrupt, 1, NULL, 0, &receipt) == TS_OK);
    MUST(tl_load(s, head.record.id, 0, &head) == TS_OK); MUST(tl_begin(&head, &cursor) == TS_OK);
    tl_cursor saved_cursor = cursor;
    CHECK(tl_page(s, &cursor, &entry, 1, &count) == TS_INVALID && !count &&
          cursor.remaining == saved_cursor.remaining && !memcmp(cursor.next, saved_cursor.next, 16));
    CHECK(tl_page(s, &cursor, &entry, TL_PAGE_LIMIT + 1, &count) == TS_INVALID && !count);
    ts_close(s);
    puts("ok stream admission, schema/provenance validation, quota rollback, fixed wire fixture and corrupt-chain detection");
}

static void crash(void) {
    ts_store *s; MUST(ts_open(path("crash"), &s) == TS_OK);
    tl_value value = target(s); uint8_t root[16] = {88}; tl_head head;
    MUST(tl_open(s, root, &head) == TS_OK); uint64_t stream = head.record.id; ts_close(s);
    uint64_t acknowledged = 0;
    for (unsigned cycle = 0; cycle < 10; cycle++) {
        int p[2]; MUST(pipe(p) == 0); pid_t child = fork(); MUST(child >= 0);
        if (!child) {
            close(p[0]); MUST(ts_open(path("crash"), &s) == TS_OK); MUST(tl_load(s, stream, 0, &head) == TS_OK);
            for (;;) {
                tl_entry entry; append(s, &head, 9, head.length + 1, &value, &entry);
                MUST(write(p[1], &head.length, sizeof head.length) == sizeof head.length);
            }
        }
        close(p[1]); uint64_t n;
        for (unsigned i = 0; i < 3 + cycle; i++) { MUST(read(p[0], &n, sizeof n) == sizeof n); acknowledged = n; }
        MUST(kill(child, SIGKILL) == 0); int status; MUST(waitpid(child, &status, 0) == child && WIFSIGNALED(status));
        while (read(p[0], &n, sizeof n) == sizeof n) acknowledged = n;
        close(p[0]); MUST(ts_open(path("crash"), &s) == TS_OK); MUST(tl_load(s, stream, 0, &head) == TS_OK);
        CHECK(head.length >= acknowledged);
        tl_cursor cursor; MUST(tl_begin(&head, &cursor) == TS_OK); tl_entry entries[7]; size_t count; uint64_t left = head.length;
        do {
            MUST(tl_page(s, &cursor, entries, 7, &count) == TS_OK);
            for (size_t i = 0; i < count; i++) { uint8_t key[16]; key_for(key, 9, left);
                CHECK(entries[i].sequence == left && !memcmp(entries[i].key, key, 16)); left--; }
        } while (count);
        CHECK(left == 0); ts_close(s);
    }
    puts("ok 10 SIGKILL stream cycles: every acknowledged append retained, tail and immutable chain agree, no sequence gaps");
}
int main(int argc, char **argv) {
    if (argc != 2 || strlen(argv[1]) >= sizeof base) { fprintf(stderr, "usage: core-streams <directory>\n"); return 2; }
    strcpy(base, argv[1]); mkdir(base, 0700);
    ordering(); validation_and_quota(); crash();
    if (failures) { fprintf(stderr, "core-streams: %d failures\n", failures); return 1; }
    puts("core-streams: all checks passed (semantic/storage correctness, not scene/scroll continuity evidence)"); return 0;
}
