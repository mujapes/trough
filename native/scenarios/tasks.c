#define _POSIX_C_SOURCE 200809L
#include "task.h"
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
static void key_for(uint8_t key[16], uint64_t n) {
    memset(key, 0, 16); key[0] = 1;
    for (unsigned i = 0; i < 8; i++) key[8 + i] = (uint8_t)(n >> (8 * i));
}
static void initialise(ts_store *s, tl_head *stream, td_snapshot *document) {
    uint8_t root[16] = {100}; td_value empty = {NULL, 0, 0, 0, -1, -1};
    MUST(tl_open(s, root, stream) == TS_OK); MUST(td_create(s, &empty, document) == TS_OK);
}
static void transition(ts_store *s, const uint8_t key[16], ts_effect_state from, ts_effect_state to, const char *outcome) {
    ts_effect effect = {{0}, from, to, TD_ASK_KIND, outcome, outcome ? strlen(outcome) : 0};
    memcpy(effect.intent, key, 16); ts_receipt receipt;
    MUST(ts_commit(s, NULL, 0, NULL, 0, &effect, 1, &receipt) == TS_OK);
}

static void acceptance(void) {
    ts_store *s; MUST(ts_open(path("acceptance"), &s) == TS_OK);
    tl_head stream; td_snapshot document; initialise(s, &stream, &document);
    uint64_t doc_id = document.record.id;
    ts_write route = {0, 88, "subscription route", 18, 0, NULL, 0}; ts_receipt receipt;
    MUST(ts_commit(s, NULL, 0, &route, 1, NULL, 0, &receipt) == TS_OK);
    td_reference ref = {route.id, 1, 88}; const uint8_t options[] = {0, 3, 255, 0};
    td_capture capture = {{doc_id, 1}, &ref, 1, options, sizeof options};
    uint8_t key[16] = {1}, other[16] = {2}; tl_entry entry; tt_task task; char outcome[32];
    ts_read head = {stream.record.id, stream.record.revision};
    MUST(tt_ask(s, head, key, 1200, &capture, &entry) == TS_OK);
    CHECK(entry.value.kind == TT_ASK_ENTRY && entry.sequence == 1 && entry.value.target.id == doc_id);
    uint64_t task_id = entry.record.id, accepted_commit = entry.record.commit;
    MUST(tt_load(s, key, &task, outcome, sizeof outcome) == TS_OK);
    CHECK(task.entry.record.id == task_id && task.effect.state == TS_PREPARED && task.effect.commit == accepted_commit &&
          task.captured.document.id == doc_id && task.captured.count == 1 && task.captured.dependencies[0].id == route.id &&
          task.captured.options_size == sizeof options && !memcmp(task.captured.options, options, sizeof options));
    MUST(tl_load(s, stream.record.id, 0, &stream) == TS_OK);
    CHECK(stream.length == 1 && stream.record.commit == accepted_commit);
    CHECK(tt_ask(s, head, key, 1200, &capture, &entry) == TS_OK && entry.record.commit == accepted_commit);
    CHECK(tt_ask(s, head, key, 1201, &capture, &entry) == TS_CONFLICT);
    capture.options = "diff";
    CHECK(tt_ask(s, head, key, 1200, &capture, &entry) == TS_CONFLICT);
    capture.options = options;
    CHECK(tt_ask(s, head, other, 1200, &capture, &entry) == TS_CONFLICT); /* stale head */
    ts_effect_state state;
    CHECK(ts_effect_get(s, other, &state, NULL) == TS_NOT_FOUND);
    /* Later draft and policy revisions never retarget or rewrite an accepted task. */
    uint16_t units[] = {'l','a','t','e','r'}; td_value value = {units, 5, 4, 2, 1, 4};
    MUST(td_save(s, (ts_read){doc_id, 1}, &value, &document) == TS_OK);
    ts_read policy = {route.id, 1}; route.data = "another route.....";
    MUST(ts_commit(s, &policy, 1, &route, 1, NULL, 0, &receipt) == TS_OK);
    transition(s, key, TS_PREPARED, TS_AUTHORISED, NULL); transition(s, key, TS_AUTHORISED, TS_ISSUED, NULL);
    ts_close(s); MUST(ts_open(path("acceptance"), &s) == TS_OK);
    CHECK(tt_ask(s, head, key, 1200, &capture, &entry) == TS_OK && entry.record.id == task_id);
    MUST(tt_load(s, key, &task, outcome, sizeof outcome) == TS_OK);
    CHECK(task.effect.state == TS_UNKNOWN && task.captured.document.revision == 1 && task.captured.dependencies[0].revision == 1);
    transition(s, key, TS_UNKNOWN, TS_OBSERVED, "verified outcome");
    outcome[0] = 123;
    CHECK(tt_load(s, key, &task, outcome, 1) == TS_CAPACITY && outcome[0] == 123 && task.effect.outcome_size == 16);
    MUST(tt_load(s, key, &task, outcome, sizeof outcome) == TS_OK);
    CHECK(task.effect.state == TS_OBSERVED && !memcmp(outcome, "verified outcome", 16));
    CHECK(tt_ask(s, head, key, 1200, &capture, &entry) == TS_OK && entry.record.commit == accepted_commit);
    MUST(td_load(s, doc_id, 0, &document, units, 5) == TS_OK);
    CHECK(document.length == 5 && document.record.revision == 2 && document.anchor == 4 && document.caret == 2);
    /* Existing standalone effects/entries cannot be attached after the fact. */
    capture.document.revision = 2; ref.revision = 2;
    CHECK(td_ask(s, other, &capture, &receipt) == TS_OK);
    head = (ts_read){stream.record.id, stream.record.revision};
    CHECK(tt_ask(s, head, other, 1300, &capture, &entry) == TS_CONFLICT);
    uint64_t id; CHECK(ts_lookup(s, other, &id) == TS_NOT_FOUND);
    uint8_t plain[16] = {3}; tl_value standalone = {0}; standalone.kind = TT_ASK_ENTRY;
    standalone.created_ms = 1400; standalone.target = (td_reference){doc_id, 2, TD_SCHEMA};
    standalone.count = 1; standalone.provenance[0] = ref;
    MUST(tl_append(s, head, plain, &standalone, &entry) == TS_OK);
    CHECK(tt_ask(s, head, plain, 1400, &capture, &entry) == TS_CONFLICT);
    CHECK(ts_effect_get(s, plain, &state, NULL) == TS_NOT_FOUND);
    ts_close(s);
    puts("ok task acceptance: stream/effect share commit, exact capture, idempotent advanced/unknown/terminal retry, stale and split-identity rejection");
}

static void guards_and_quota(void) {
    ts_store *s; MUST(ts_open(path("guards"), &s) == TS_OK);
    tl_head head; td_snapshot document; initialise(s, &head, &document);
    td_capture capture = {{document.record.id, 1}, NULL, 0, NULL, 0};
    ts_read guards[TD_DEPENDENCY_LIMIT + 1]; size_t count;
    MUST(td_capture_validate(s, &capture, guards, TD_DEPENDENCY_LIMIT + 1, &count) == TS_OK && count == 1);
    uint8_t wire[TD_CAPTURE_LIMIT], key[16] = {1}; size_t size;
    MUST(td_capture_encode(&capture, wire, sizeof wire, &size) == TS_OK);
    ts_effect effect = {{1}, 0, TS_PREPARED, TD_ASK_KIND, wire, size};
    tl_value value = {0}; value.kind = TT_ASK_ENTRY; value.target = (td_reference){document.record.id, 1, TD_SCHEMA};
    uint16_t unit = 'x'; td_value edited = {&unit, 1, 1, 1, -1, -1};
    MUST(td_save(s, capture.document, &edited, &document) == TS_OK);
    tl_entry entry;
    CHECK(tl_append_prepared(s, (ts_read){head.record.id, 1}, key, &value, guards, count, &effect, &entry) == TS_CONFLICT);
    uint64_t id; ts_effect_state state;
    CHECK(ts_lookup(s, key, &id) == TS_NOT_FOUND && ts_effect_get(s, key, &state, NULL) == TS_NOT_FOUND);
    MUST(tl_load(s, head.record.id, 0, &head) == TS_OK); CHECK(head.length == 0 && head.record.revision == 1);
    capture.document.revision = 2;
    uint8_t options[TD_OPTIONS_LIMIT]; memset(options, 17, sizeof options);
    capture.options = options; capture.options_size = sizeof options;
    MUST(ts_limit_pages(s, 1) == TS_OK);
    ts_read expected = {head.record.id, head.record.revision};
    CHECK(tt_ask(s, expected, key, 100, &capture, &entry) == TS_FULL && !entry.record.commit);
    CHECK(ts_lookup(s, key, &id) == TS_NOT_FOUND && ts_effect_get(s, key, &state, NULL) == TS_NOT_FOUND);
    MUST(tl_load(s, head.record.id, 0, &head) == TS_OK); CHECK(!head.length && head.record.revision == 1);
    MUST(ts_limit_pages(s, 0) == TS_OK);
    CHECK(tt_ask(s, expected, key, 100, &capture, &entry) == TS_OK && entry.sequence == 1);
    ts_close(s);
    puts("ok captured-input guards rechecked at atomic publication; full store leaves no task, stream key or advanced tail");
}

static void crash(void) {
    ts_store *s; MUST(ts_open(path("crash"), &s) == TS_OK);
    tl_head head; td_snapshot document; initialise(s, &head, &document);
    uint64_t stream = head.record.id; td_capture capture = {{document.record.id, 1}, NULL, 0, NULL, 0}; ts_close(s);
    uint64_t acknowledged = 0;
    for (unsigned cycle = 0; cycle < 10; cycle++) {
        int p[2]; MUST(pipe(p) == 0); pid_t child = fork(); MUST(child >= 0);
        if (!child) {
            close(p[0]); MUST(ts_open(path("crash"), &s) == TS_OK); MUST(tl_load(s, stream, 0, &head) == TS_OK);
            for (;;) {
                uint8_t key[16]; key_for(key, head.length + 1); tl_entry entry;
                MUST(tt_ask(s, (ts_read){stream, head.record.revision}, key, head.length + 1, &capture, &entry) == TS_OK);
                MUST(tl_load(s, stream, 0, &head) == TS_OK);
                MUST(write(p[1], &head.length, sizeof head.length) == sizeof head.length);
            }
        }
        close(p[1]); uint64_t n;
        for (unsigned i = 0; i < 3 + cycle; i++) { MUST(read(p[0], &n, sizeof n) == sizeof n); acknowledged = n; }
        MUST(kill(child, SIGKILL) == 0); int status; MUST(waitpid(child, &status, 0) == child && WIFSIGNALED(status));
        while (read(p[0], &n, sizeof n) == sizeof n) acknowledged = n;
        close(p[0]);
        MUST(ts_open(path("crash"), &s) == TS_OK); MUST(tl_load(s, stream, 0, &head) == TS_OK);
        CHECK(head.length >= acknowledged);
        tl_cursor cursor; MUST(tl_begin(&head, &cursor) == TS_OK); tl_entry entries[7]; size_t count; uint64_t seen = 0;
        do {
            MUST(tl_page(s, &cursor, entries, 7, &count) == TS_OK);
            for (size_t i = 0; i < count; i++) {
                tt_task task; MUST(tt_load(s, entries[i].key, &task, NULL, 0) == TS_OK);
                CHECK(task.effect.commit == entries[i].record.commit && task.effect.state == TS_PREPARED &&
                      task.captured.document.id == capture.document.id && task.entry.sequence == head.length - seen);
                seen++;
            }
        } while (count);
        ts_effect_record page[16]; uint8_t after[16]; const uint8_t *cursor_key = NULL; uint64_t effects = 0;
        do {
            MUST(ts_effect_scan(s, 0, cursor_key, page, 16, &count) == TS_OK);
            effects += count;
            if (count) { memcpy(after, page[count - 1].intent, 16); cursor_key = after; }
        } while (count);
        CHECK(effects == seen && seen == head.length); ts_close(s);
    }
    puts("ok 10 task SIGKILL cycles: acknowledged tasks retained, one effect per stream entry, no orphan accepts or sequence gaps");
}

int main(int argc, char **argv) {
    if (argc != 2 || strlen(argv[1]) >= sizeof base) { fprintf(stderr, "usage: core-tasks <directory>\n"); return 2; }
    strcpy(base, argv[1]); mkdir(base, 0700);
    acceptance(); guards_and_quota(); crash();
    if (failures) { fprintf(stderr, "core-tasks: %d failures\n", failures); return 1; }
    puts("core-tasks: all checks passed (durable task acceptance, not provider dispatch or capability-authority evidence)"); return 0;
}
