/* Independent text-vector oracle, fixed wire fixtures, stale-save/capture and crash cases.
 * Shares only the public document/store API with the implementation. */
#define _POSIX_C_SOURCE 200809L
#include "document.h"
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static int failures;
#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__, #c); failures++; } } while (0)
#define MUST(c) do { if (!(c)) { fprintf(stderr, "FATAL %s:%d %s\n", __FILE__, __LINE__, #c); exit(1); } } while (0)
static char base[3000];
static const char *path(const char *name) {
    static char p[3100]; snprintf(p, sizeof p, "%s/%s", base, name); return p;
}
static uint32_t seed = 0x492f13ad;
static uint32_t random32(void) { seed ^= seed << 13; seed ^= seed >> 17; seed ^= seed << 5; return seed; }
static void check_text(const td_snapshot *s, const uint16_t *text, const td_value *expected) {
    CHECK(s->length == expected->length && s->anchor == expected->anchor && s->caret == expected->caret);
    CHECK(s->composing_start == expected->composing_start && s->composing_end == expected->composing_end);
    CHECK(!expected->length || !memcmp(text, expected->text, expected->length * sizeof *text));
}

static void codec(void) {
    /* Literal little-endian fixture: A, NUL, isolated high surrogate, Hebrew alef.
     * Reverse selection [4,1], composition [2,4]. No host struct serialization. */
    static const uint8_t fixture[] = {
        'T','D','O','C', 1,0,0,0, 4,0,0,0, 4,0,0,0, 1,0,0,0,
        2,0,0,0, 4,0,0,0, 0,0,0,0, 0x41,0, 0,0, 0,0xd8, 0xd0,5
    };
    uint16_t units[] = {0x41, 0, 0xd800, 0x05d0}, text[16];
    td_value value = {units, 4, 4, 1, 2, 4};
    uint8_t wire[128]; size_t size;
    td_snapshot snapshot;
    CHECK(td_encode(&value, wire, sizeof wire, &size) == TS_OK && size == sizeof fixture && !memcmp(wire, fixture, size));
    CHECK(td_decode(fixture, sizeof fixture, &snapshot, text, 16) == TS_OK);
    check_text(&snapshot, text, &value);
    for (size_t n = 0; n < sizeof fixture; n++) CHECK(td_decode(fixture, n, &snapshot, text, 16) == TS_INVALID);
    for (size_t i = 0; i < 4; i++) {
        memcpy(wire, fixture, sizeof fixture); wire[28 + i] = 1;
        CHECK(td_decode(wire, sizeof fixture, &snapshot, text, 16) == TS_INVALID);
    }
    const size_t offsets[] = {0, 4, 8, 12, 16, 20, 24};
    for (size_t i = 0; i < sizeof offsets / sizeof *offsets; i++) {
        memcpy(wire, fixture, sizeof fixture); wire[offsets[i]] = 127;
        text[0] = 4321;
        CHECK(td_decode(wire, sizeof fixture, &snapshot, text, 16) == TS_INVALID && text[0] == 4321);
    }
    memset(wire, 77, sizeof wire);
    CHECK(td_encode(&value, wire, sizeof fixture - 1, &size) == TS_CAPACITY && size == sizeof fixture && wire[0] == 77);
    CHECK(td_decode(fixture, sizeof fixture, &snapshot, text, 3) == TS_CAPACITY && snapshot.length == 4 && text[0] == 4321);
    value.composing_start = -1;
    CHECK(td_encode(&value, wire, sizeof wire, &size) == TS_INVALID && size == 0 && wire[0] == 77);
    value.composing_end = -1;
    CHECK(td_encode(&value, wire, sizeof wire, &size) == TS_OK && wire[20] == 255 && wire[27] == 255);
    CHECK(td_decode(wire, size, &snapshot, text, 16) == TS_OK && snapshot.composing_start == -1);
    value.length = SIZE_MAX;
    CHECK(td_encode(&value, wire, sizeof wire, &size) == TS_INVALID);
    puts("ok document wire: fixed independent fixture, lossless UTF-16, truncation, version, ranges and capacity");
}

/* The reference builds the next vector by enumerating retained/inserted elements, independently
 * of the candidate's range moves. Random data spans all UTF-16 units, not just ASCII. */
static void edits_and_history(void) {
    enum { STEPS = 4000, LIMIT = 512 };
    uint16_t model[LIMIT] = {0}, result[LIMIT], reference[LIMIT], insert[3][8], loaded[LIMIT];
    td_value current = {model, 0, 0, 0, -1, -1};
    ts_store *store; MUST(ts_open(path("edits"), &store) == TS_OK);
    td_snapshot saved; MUST(td_create(store, &current, &saved) == TS_OK);
    uint64_t id = saved.record.id, revision = 1, last_commit = saved.record.commit;
    unsigned stale = 0, noops = 0, changes = 0;
    for (int op = 0; op < STEPS; op++) {
        td_edit edits[3]; size_t count = random32() % 4, length = current.length;
        memcpy(reference, model, length * sizeof *model);
        for (size_t e = 0; e < count; e++) {
            size_t start = random32() % (length + 1), end = start + random32() % (length - start + 1);
            size_t add = random32() % 8;
            for (size_t k = 0; k < add; k++) insert[e][k] = (uint16_t)random32();
            edits[e] = (td_edit){start, end, insert[e], add};
            uint16_t next[LIMIT]; size_t n = 0;
            for (size_t k = 0; k < start; k++) next[n++] = reference[k];
            for (size_t k = 0; k < add; k++) next[n++] = insert[e][k];
            for (size_t k = end; k < length; k++) next[n++] = reference[k];
            MUST(n < LIMIT);
            memcpy(reference, next, n * sizeof *next); length = n;
        }
        uint32_t anchor = (uint32_t)(random32() % (length + 1)), caret = (uint32_t)(random32() % (length + 1));
        int32_t cs = -1, ce = -1;
        if (random32() % 2) { cs = (int32_t)(random32() % (length + 1)); ce = cs + (int32_t)(random32() % (length - (size_t)cs + 1)); }
        if (op % 13 == 0) { count = 0; length = current.length; memcpy(reference, model, length * sizeof *model);
            anchor = current.anchor; caret = current.caret; cs = current.composing_start; ce = current.composing_end; }
        td_value next;
        MUST(td_apply(&current, edits, count, anchor, caret, cs, ce, result, LIMIT, &next) == TS_OK);
        CHECK(next.length == length && !memcmp(result, reference, length * sizeof *result));
        int differs = length != current.length || memcmp(result, model, length * sizeof *result) ||
            anchor != current.anchor || caret != current.caret || cs != current.composing_start || ce != current.composing_end;
        ts_read expected = {id, revision};
        if (op % 7 == 0 && revision > 1) {
            ts_read old = {id, revision - 1};
            CHECK(td_save(store, old, &next, &saved) == TS_CONFLICT && saved.record.commit == 0); stale++;
        }
        MUST(td_save(store, expected, &next, &saved) == TS_OK);
        if (differs) { revision++; changes++; CHECK(saved.record.commit > last_commit); }
        else { noops++; CHECK(saved.record.commit == last_commit); }
        CHECK(saved.record.revision == revision && saved.record.id == id);
        last_commit = saved.record.commit;
        if (op % 100 == 99) { ts_close(store); MUST(ts_open(path("edits"), &store) == TS_OK); }
        MUST(td_load(store, id, 0, &saved, loaded, LIMIT) == TS_OK); check_text(&saved, loaded, &next);
        CHECK(saved.record.revision == revision && saved.record.commit == last_commit);
        MUST(td_load(store, id, expected.revision, &saved, loaded, LIMIT) == TS_OK);
        check_text(&saved, loaded, &current);
        memcpy(model, reference, length * sizeof *model); current = next; current.text = model;
        if (failures) break;
    }
    CHECK(stale > 500 && noops > 300 && changes > 3000);
    ts_close(store);
    printf("ok %d reference edit batches: %u changes, %u unchanged, %u stale saves; exact history and 40 reopens\n", STEPS, changes, noops, stale);
}

static void bounds(void) {
    uint16_t text[] = {'a','b','c'}, replacement[] = {'x','y'}, out[8] = {999};
    td_value value = {text, 3, 3, 0, -1, -1}, result;
    td_edit edits[] = {{0, 1, replacement, 2}, {0, 5, replacement, 1}};
    CHECK(td_apply(&value, edits, 2, 0, 0, -1, -1, out, 8, &result) == TS_INVALID && out[0] == 999);
    edits[1].end = 4;
    CHECK(td_apply(&value, edits, 2, 1, 0, -1, -1, out, 1, &result) == TS_OK && result.length == 1 && out[0] == 'x');
    out[0] = 999;
    CHECK(td_apply(&value, edits, 1, 0, 0, -1, -1, out, 3, &result) == TS_CAPACITY && out[0] == 999);
    CHECK(td_apply(&value, edits, 1, 5, 0, -1, -1, out, 8, &result) == TS_INVALID && out[0] == 999);
    edits[0].length = SIZE_MAX;
    CHECK(td_apply(&value, edits, 1, 0, 0, -1, -1, out, 8, &result) == TS_CAPACITY && out[0] == 999);
    CHECK(td_apply(&value, edits, TD_EDIT_LIMIT + 1, 0, 0, -1, -1, out, 8, &result) == TS_CAPACITY);
    puts("ok edit admission: atomic invalid batches, intermediate growth, final bounds, length overflow");
}

static void capture(void) {
    ts_store *store; MUST(ts_open(path("capture"), &store) == TS_OK);
    uint16_t first[] = {'o','l','d',0xd83d,0xde80}, newer[] = {'n','e','w'}, text[16];
    td_value value = {first, 5, 5, 5, -1, -1}; td_snapshot document;
    MUST(td_create(store, &value, &document) == TS_OK);
    uint64_t doc_id = document.record.id;
    ts_write source = {0, 77, "source revision one", 19, 0}; ts_receipt receipt;
    MUST(ts_commit(store, NULL, 0, &source, 1, NULL, 0, &receipt) == TS_OK);
    td_reference ref = {source.id, 1, 77};
    const uint8_t options[] = {0, 1, 255, 0, 2};
    td_capture ask = {{doc_id, 1}, &ref, 1, options, sizeof options};
    uint8_t intent[16] = {1}, other[16] = {2}, wire[TD_CAPTURE_LIMIT], outcome[16]; size_t size;
    CHECK(td_capture_encode(&ask, wire, sizeof wire, &size) == TS_OK && size == 69);
    CHECK(wire[0] == 'T' && wire[3] == 'K' && wire[4] == 1 && wire[8] == doc_id && wire[16] == 1 &&
          wire[24] == 1 && wire[28] == 5 && wire[40] == source.id && wire[48] == 1 && wire[56] == 77);
    td_captured decoded;
    CHECK(td_capture_decode(wire, size, &decoded) == TS_OK && decoded.count == 1 && decoded.dependencies[0].id == source.id &&
          decoded.document.id == doc_id && decoded.document.revision == 1 && decoded.options_size == sizeof options &&
          !memcmp(decoded.options, options, sizeof options));
    for (size_t n = 0; n < size; n++) CHECK(td_capture_decode(wire, n, &decoded) == TS_INVALID);
    const size_t bad_offsets[] = {0,4,24,28,32,36,60};
    for (size_t i = 0; i < sizeof bad_offsets / sizeof *bad_offsets; i++) {
        uint8_t copy[TD_CAPTURE_LIMIT]; memcpy(copy, wire, size); copy[bad_offsets[i]] = 255;
        CHECK(td_capture_decode(copy, size, &decoded) == TS_INVALID);
    }
    CHECK(td_ask(store, intent, &ask, &receipt) == TS_OK && receipt.changed == 1);
    uint64_t captured_at = receipt.commit;
    CHECK(td_ask(store, intent, &ask, &receipt) == TS_OK && receipt.changed == 0);
    /* Advanced effects are not reset by request redelivery. */
    ts_effect authorised = {{1}, TS_PREPARED, TS_AUTHORISED, TD_ASK_KIND, NULL, 0};
    CHECK(ts_commit(store, NULL, 0, NULL, 0, &authorised, 1, &receipt) == TS_OK);
    value = (td_value){newer, 3, 2, 1, 0, 2};
    CHECK(td_save(store, (ts_read){doc_id, 1}, &value, &document) == TS_OK);
    CHECK(td_ask(store, intent, &ask, &receipt) == TS_OK && receipt.changed == 0);
    CHECK(td_ask(store, other, &ask, &receipt) == TS_CONFLICT && receipt.commit == 0);
    ts_effect_state state; CHECK(ts_effect_get(store, other, &state, NULL) == TS_NOT_FOUND);
    ask.document.revision = 2;
    CHECK(td_ask(store, intent, &ask, &receipt) == TS_CONFLICT);
    /* Relevant source changes reject the whole new capture. */
    ts_read old_source = {source.id, 1}; source.data = "source revision two";
    CHECK(ts_commit(store, &old_source, 1, &source, 1, NULL, 0, &receipt) == TS_OK);
    CHECK(td_ask(store, other, &ask, &receipt) == TS_CONFLICT);
    ref.revision = 2; ref.schema = 78;
    CHECK(td_ask(store, other, &ask, &receipt) == TS_INVALID);
    ref.schema = 77;
    CHECK(td_ask(store, other, &ask, &receipt) == TS_OK);
    ts_close(store); MUST(ts_open(path("capture"), &store) == TS_OK);
    ts_effect_record entry;
    MUST(ts_effect_read(store, intent, &entry, wire, sizeof wire, outcome, sizeof outcome) == TS_OK);
    CHECK(entry.kind == TD_ASK_KIND && entry.state == TS_AUTHORISED && entry.commit > captured_at);
    MUST(td_capture_decode(wire, entry.request_size, &decoded) == TS_OK);
    MUST(td_load(store, decoded.document.id, decoded.document.revision, &document, text, 16) == TS_OK);
    CHECK(document.length == 5 && !memcmp(text, first, sizeof first));
    ts_record old; char source_bytes[32];
    CHECK(ts_get_revision(store, decoded.dependencies[0].id, decoded.dependencies[0].revision, &old, source_bytes, sizeof source_bytes) == TS_OK &&
          old.size == 19 && !memcmp(source_bytes, "source revision one", 19));
    /* Deleting inputs cannot destroy retained history, and cannot permit a new capture. */
    ts_read current = {doc_id, 2}; ts_write remove = {doc_id, 0, NULL, 0, 1};
    CHECK(ts_commit(store, &current, 1, &remove, 1, NULL, 0, &receipt) == TS_OK);
    ask.document.revision = 1; ref.revision = 1;
    CHECK(td_ask(store, intent, &ask, &receipt) == TS_OK && receipt.changed == 0);
    intent[0] = 3;
    CHECK(td_ask(store, intent, &ask, &receipt) == TS_NOT_FOUND);
    CHECK(td_load(store, doc_id, 1, &document, text, 16) == TS_OK && !memcmp(text, first, sizeof first));
    ref.id = doc_id;
    CHECK(td_capture_encode(&ask, wire, sizeof wire, &size) == TS_INVALID);
    ts_close(store);
    puts("ok Ask: exact draft/source history, immutable options, idempotent retries, stale rejection, schema checks, restart and deletion");
}

static void quota(void) {
    ts_store *store; MUST(ts_open(path("quota"), &store) == TS_OK);
    td_value empty = {NULL, 0, 0, 0, -1, -1}; td_snapshot document;
    MUST(td_create(store, &empty, &document) == TS_OK);
    ts_read expected = {document.record.id, document.record.revision};
    uint16_t *text = malloc(TD_TEXT_LIMIT * sizeof *text), *loaded = malloc(TD_TEXT_LIMIT * sizeof *loaded);
    MUST(text && loaded); for (size_t i = 0; i < TD_TEXT_LIMIT; i++) text[i] = (uint16_t)i;
    td_value large = {text, TD_TEXT_LIMIT, TD_TEXT_LIMIT, 0, 1, TD_TEXT_LIMIT};
    MUST(ts_limit_pages(store, 16) == TS_OK);
    CHECK(td_save(store, expected, &large, &document) == TS_FULL && document.record.commit == 0);
    CHECK(td_load(store, expected.id, 0, &document, NULL, 0) == TS_OK && document.length == 0 && document.record.revision == 1);
    MUST(ts_limit_pages(store, 0) == TS_OK);
    CHECK(td_save(store, expected, &large, &document) == TS_OK && document.record.revision == 2);
    CHECK(td_load(store, expected.id, 0, &document, loaded, TD_TEXT_LIMIT - 1) == TS_CAPACITY && document.length == TD_TEXT_LIMIT);
    MUST(td_load(store, expected.id, 0, &document, loaded, TD_TEXT_LIMIT) == TS_OK);
    check_text(&document, loaded, &large);
    free(text); free(loaded); ts_close(store);
    puts("ok document quota: failed save has no receipt or partial revision; maximum admitted document survives recovery");
}

static void process_death(void) {
    int pipefd[2]; MUST(pipe(pipefd) == 0);
    pid_t child = fork(); MUST(child >= 0);
    if (!child) {
        close(pipefd[0]); ts_store *store; MUST(ts_open(path("crash"), &store) == TS_OK);
        uint16_t units[] = {'i','m','e',0xd800}; td_value value = {units, 4, 4, 1, 1, 4}; td_snapshot document;
        MUST(td_create(store, &value, &document) == TS_OK);
        td_capture ask = {{document.record.id, 1}, NULL, 0, NULL, 0};
        uint8_t intent[16] = {99}; ts_receipt receipt;
        MUST(td_ask(store, intent, &ask, &receipt) == TS_OK);
        ts_effect e = {{99}, TS_PREPARED, TS_AUTHORISED, TD_ASK_KIND, NULL, 0};
        MUST(ts_commit(store, NULL, 0, NULL, 0, &e, 1, &receipt) == TS_OK);
        e.from = TS_AUTHORISED; e.to = TS_ISSUED;
        MUST(ts_commit(store, NULL, 0, NULL, 0, &e, 1, &receipt) == TS_OK);
        MUST(write(pipefd[1], &document.record.id, sizeof document.record.id) == sizeof document.record.id);
        for (;;) pause();
    }
    close(pipefd[1]); uint64_t id;
    MUST(read(pipefd[0], &id, sizeof id) == sizeof id); close(pipefd[0]);
    MUST(kill(child, SIGKILL) == 0); int status; MUST(waitpid(child, &status, 0) == child && WIFSIGNALED(status));
    ts_store *store; MUST(ts_open(path("crash"), &store) == TS_OK);
    td_snapshot document; uint16_t text[8];
    CHECK(td_load(store, id, 0, &document, text, 8) == TS_OK && document.record.revision == 1 &&
        document.length == 4 && text[3] == 0xd800 && document.anchor == 4 && document.caret == 1 &&
        document.composing_start == 1 && document.composing_end == 4);
    ts_effect_record entry[2]; size_t count;
    CHECK(ts_effect_scan(store, TS_UNKNOWN, NULL, entry, 2, &count) == TS_OK && count == 1 && entry[0].intent[0] == 99);
    td_capture ask = {{id, 1}, NULL, 0, NULL, 0}; ts_receipt receipt; uint8_t intent[16] = {99};
    CHECK(td_ask(store, intent, &ask, &receipt) == TS_OK && receipt.changed == 0);
    ts_effect_state state; CHECK(ts_effect_get(store, intent, &state, NULL) == TS_OK && state == TS_UNKNOWN);
    ts_close(store);
    puts("ok SIGKILL: acknowledged text, reverse selection and composition retained; issued Ask remains unknown on retry");
}

/* Indexed (v2) documents. Text for step k is reproducible, so a recovered revision can be
 * checked exactly after SIGKILL. */
static size_t indexed_text(unsigned k, uint16_t *out) {
    size_t n = 50000;
    for (size_t i = 0; i < n; i++) out[i] = (uint16_t)(i % 61 == 0 ? 0x0a : 'a' + i % 26);
    for (unsigned j = 1; j <= k; j++) {
        size_t at = (j * 7919u) % (n + 1), cut = at < n ? (j % 3) : 0;
        memmove(out + at + 2, out + at + cut, (n - at - cut) * 2);
        out[at] = (uint16_t)('A' + j % 26); out[at + 1] = (uint16_t)j;
        n = n - cut + 2;
    }
    return n;
}
static tx_text *indexed_tree(unsigned k, uint16_t *scratch) {
    tx_text *t; size_t n = indexed_text(k, scratch);
    MUST(tx_build(scratch, n, &t) == TS_OK);
    return t;
}
static int same_tree(const tx_text *t, const uint16_t *text, size_t n) {
    if (tx_length(t) != n) return 0;
    uint16_t *buf = malloc(n * 2 + 2); MUST(buf && tx_read(t, 0, n, buf) == TS_OK);
    int same = !memcmp(buf, text, n * 2); free(buf); return same;
}
static void indexed(void) {
    ts_store *store; MUST(ts_open(path("indexed"), &store) == TS_OK);
    static uint16_t a[60000], b[60000];
    size_t na = indexed_text(0, a);
    tx_text *t = indexed_tree(0, b), *u;
    td_text_value v = {t, 5, 2, 1, 4}; td_snapshot snap, again;
    MUST(td_save_text(store, (ts_read){0, 0}, &v, &snap) == TS_OK);
    uint64_t id = snap.record.id;
    CHECK(snap.record.revision == 1 && snap.length == na && snap.anchor == 5 && snap.composing_end == 4);
    /* td_load serves the flat API from v2; small capacity still reports metadata. */
    CHECK(td_load(store, id, 0, &again, b, 10) == TS_CAPACITY && again.length == na && again.caret == 2);
    CHECK(td_load(store, id, 0, &again, b, 60000) == TS_OK && again.length == na && !memcmp(a, b, na * 2) &&
          again.record.revision == 1 && again.record.schema == TD_SCHEMA);
    /* Edit through the tree, save over revision 1; history stays readable. */
    MUST(tx_edit(t, 100, 103, (uint16_t[]){'X', 'Y'}, 2, &u) == TS_OK);
    td_text_value w = {u, 0, 0, -1, -1};
    MUST(td_save_text(store, (ts_read){id, 1}, &w, &snap) == TS_OK);
    CHECK(snap.record.revision == 2 && snap.length == na - 1);
    td_snapshot old; tx_text *ot, *nt;
    MUST(td_load_text(store, id, 1, &old, &ot) == TS_OK);
    CHECK(same_tree(ot, a, na) && old.anchor == 5 && old.record.revision == 1);
    MUST(td_load_text(store, id, 0, &again, &nt) == TS_OK);
    CHECK(tx_length(nt) == na - 1 && again.record.revision == 2);
    uint16_t mid[4]; MUST(tx_read(nt, 99, 4, mid) == TS_OK);
    CHECK(mid[0] == a[99] && mid[1] == 'X' && mid[2] == 'Y' && mid[3] == a[103]);
    /* Stale and unchanged saves. */
    CHECK(td_save_text(store, (ts_read){id, 1}, &w, &again) == TS_CONFLICT);
    td_text_value same = {nt, 0, 0, -1, -1};
    MUST(td_save_text(store, (ts_read){id, 2}, &same, &again) == TS_OK);
    CHECK(again.record.revision == 2 && again.record.commit == snap.record.commit);
    td_text_value bad = {nt, (uint32_t)na, 0, -1, -1};
    CHECK(td_save_text(store, (ts_read){id, 2}, &bad, &again) == TS_INVALID);
    bad.anchor = 0; bad.composing_start = 3; bad.composing_end = 2;
    CHECK(td_save_text(store, (ts_read){id, 2}, &bad, &again) == TS_INVALID);
    /* v1 documents load as trees; v2 documents can be captured by Ask like any other. */
    uint16_t small[] = {'h', 'i'}; td_value flat = {small, 2, 2, 2, -1, -1}; td_snapshot one; tx_text *ft;
    MUST(td_create(store, &flat, &one) == TS_OK);
    MUST(td_load_text(store, one.record.id, 0, &again, &ft) == TS_OK);
    CHECK(same_tree(ft, small, 2) && again.caret == 2 && again.record.revision == 1);
    td_capture ask = {{id, 2}, NULL, 0, NULL, 0}; uint8_t intent[16] = {0x7d}; ts_receipt receipt;
    CHECK(td_ask(store, intent, &ask, &receipt) == TS_OK);
    tx_release(t); tx_release(u); tx_release(ot); tx_release(nt); tx_release(ft);
    ts_close(store);
    puts("ok indexed documents: v2 round trip, flat reads, history, stale/unchanged saves, v1 migration, Ask");
}
static void indexed_death(void) {
    static uint16_t scratch[60000], expect[60000];
    uint64_t least = UINT64_MAX, most = 0;
    for (int cycle = 0; cycle < 8; cycle++) {
        char name[32]; snprintf(name, sizeof name, "indexed-death-%d", cycle);
        int ready[2]; MUST(pipe(ready) == 0);
        pid_t child = fork(); MUST(child >= 0);
        if (!child) {
            close(ready[0]);
            ts_store *s; MUST(ts_open(path(name), &s) == TS_OK);
            tx_text *t = indexed_tree(0, scratch); td_text_value v = {t, 0, 0, -1, -1}; td_snapshot snap;
            MUST(td_save_text(s, (ts_read){0, 0}, &v, &snap) == TS_OK);
            MUST(write(ready[1], "r", 1) == 1);     /* revision 1 is durable; start the kill clock */
            for (unsigned k = 1;; k++) {
                tx_text *n = indexed_tree(k, scratch); v.text = n;
                MUST(td_save_text(s, (ts_read){snap.record.id, snap.record.revision}, &v, &snap) == TS_OK);
                tx_release(t); t = n;
            }
        }
        close(ready[1]);
        char byte; MUST(read(ready[0], &byte, 1) == 1); close(ready[0]);
        nanosleep(&(struct timespec){0, 100000000L + 60000000L * cycle}, NULL);
        MUST(kill(child, SIGKILL) == 0); int status; MUST(waitpid(child, &status, 0) == child && WIFSIGNALED(status));
        ts_store *s; MUST(ts_open(path(name), &s) == TS_OK);
        td_snapshot snap; tx_text *t;
        MUST(td_load_text(s, 1, 0, &snap, &t) == TS_OK);   /* every chunk of the committed manifest exists */
        size_t n = indexed_text((unsigned)snap.record.revision - 1, expect);
        CHECK(snap.record.revision >= 1 && same_tree(t, expect, n));
        if (snap.record.revision < least) least = snap.record.revision;
        if (snap.record.revision > most) most = snap.record.revision;
        tx_release(t); ts_close(s);
    }
    CHECK(least > 1);
    printf("ok indexed SIGKILL: 8 cycles killed at revisions %llu..%llu; each loads with all chunks and exact text\n",
           (unsigned long long)least, (unsigned long long)most);
}

int main(int argc, char **argv) {
    if (argc != 2 || strlen(argv[1]) >= sizeof base) { fprintf(stderr, "usage: core-documents <directory>\n"); return 2; }
    strcpy(base, argv[1]); mkdir(base, 0700);
    codec(); bounds(); edits_and_history(); capture(); quota(); process_death(); indexed(); indexed_death();
    if (failures) { fprintf(stderr, "core-documents: %d failures\n", failures); return 1; }
    puts("core-documents: all checks passed (storage/semantic correctness, not Android IME or presentation evidence)");
    return 0;
}
