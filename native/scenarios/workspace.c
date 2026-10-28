#define _POSIX_C_SOURCE 200809L
#include "workspace.h"
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
static tw_keys keys(unsigned n) { tw_keys k = {{1}, {2}, {3}}; k.root[1] = k.draft[1] = k.stream[1] = (uint8_t)n; return k; }

static void roots(void) {
    ts_store *s; MUST(ts_open(path("roots"), &s) == TS_OK);
    tw_keys identity = keys(1); tw_workspace workspace, other;
    uint16_t units[] = {'h','e','l','l','o',0xd800}; td_value initial = {units, 6, 5, 2, 1, 4};
    MUST(tw_create(s, &identity, &initial, &workspace) == TS_OK);
    CHECK(workspace.record.id != workspace.draft.record.id && workspace.draft.record.id != workspace.stream.record.id &&
          workspace.record.commit == workspace.draft.record.commit && workspace.record.commit == workspace.stream.record.commit &&
          workspace.record.revision == 1 && workspace.draft.record.revision == 1 && workspace.stream.length == 0);
    uint64_t root_id = workspace.record.id, doc_id = workspace.draft.record.id, stream_id = workspace.stream.record.id;
    uint8_t wire[80]; ts_record record;
    MUST(ts_get(s, root_id, &record, wire, sizeof wire) == TS_OK);
    CHECK(record.size == 64 && !memcmp(wire, "TWSP\1\0\0\0", 8) && !memcmp(wire + 8, identity.root, 16) &&
          !memcmp(wire + 24, identity.draft, 16) && !memcmp(wire + 40, identity.stream, 16) && wire[56] == 0 && wire[63] == 0);
    tw_keys second = keys(2); MUST(tw_create(s, &second, &initial, &other) == TS_OK);
    uint64_t other_commit = other.record.commit;
    uint16_t edit[] = {'n','e','w'}; td_value updated = {edit, 3, 3, 0, -1, -1}; td_snapshot snapshot;
    MUST(td_save(s, (ts_read){doc_id, 1}, &updated, &snapshot) == TS_OK);
    uint8_t intent[16] = {17}; td_capture capture = {{doc_id, 2}, NULL, 0, NULL, 0}; tl_entry entry;
    MUST(tt_ask(s, (ts_read){stream_id, 1}, intent, 100, &capture, &entry) == TS_OK);
    ts_close(s); MUST(ts_open(path("roots"), &s) == TS_OK);
    MUST(tw_create(s, &identity, &initial, &workspace) == TS_OK); /* initial seed must not reset edited text */
    CHECK(workspace.record.id == root_id && workspace.draft.record.id == doc_id && workspace.draft.record.revision == 2 &&
          workspace.draft.length == 3 && workspace.stream.record.id == stream_id && workspace.stream.length == 1);
    MUST(tw_load(s, second.root, &other) == TS_OK);
    CHECK(other.draft.record.revision == 1 && other.draft.length == 6 && other.stream.length == 0 && other.record.commit == other_commit);
    uint16_t text[8]; MUST(td_load(s, doc_id, 0, &snapshot, text, 8) == TS_OK);
    CHECK(!memcmp(text, edit, sizeof edit) && snapshot.anchor == 3 && snapshot.caret == 0);
    tt_task task; CHECK(tt_load(s, intent, &task, NULL, 0) == TS_OK && task.captured.document.id == doc_id && task.captured.document.revision == 2);
    CHECK(tw_load(s, workspace.keys.root, &workspace) == TS_OK && workspace.record.id == root_id);
    tw_keys conflict = identity; conflict.draft[1]++;
    CHECK(tw_create(s, &conflict, &initial, &workspace) == TS_CONFLICT && !workspace.record.id);
    conflict = keys(3); memcpy(conflict.draft, identity.draft, 16);
    CHECK(tw_create(s, &conflict, &initial, &workspace) == TS_CONFLICT && !workspace.record.id);
    uint64_t id; CHECK(ts_lookup(s, conflict.root, &id) == TS_NOT_FOUND && ts_lookup(s, conflict.stream, &id) == TS_NOT_FOUND);
    conflict = keys(4); memcpy(conflict.draft, conflict.root, 16);
    CHECK(tw_create(s, &conflict, &initial, &workspace) == TS_INVALID);
    conflict = keys(4); memset(conflict.root, 0, 16);
    CHECK(tw_create(s, &conflict, &initial, &workspace) == TS_INVALID);
    /* A broken root is a recovery error, never a silent empty replacement draft. */
    ts_read read = {doc_id, 2}; ts_write remove = {doc_id, 0, NULL, 0, 1, NULL, 0}; ts_receipt receipt;
    MUST(ts_commit(s, &read, 1, &remove, 1, NULL, 0, &receipt) == TS_OK);
    CHECK(tw_load(s, identity.root, &workspace) == TS_INVALID);
    CHECK(tw_create(s, &identity, &initial, &workspace) == TS_INVALID);
    CHECK(ts_lookup(s, identity.draft, &id) == TS_OK && id == doc_id);
    ts_close(s);
    puts("ok workspace roots: three-record atomic identity, fixed wire fixture, idempotent reopen, independent drafts/streams, conflicts and missing-child rejection");
}

static void quota(void) {
    ts_store *s; MUST(ts_open(path("quota"), &s) == TS_OK);
    uint16_t *text = malloc(TD_TEXT_LIMIT * sizeof *text); MUST(text); memset(text, 7, TD_TEXT_LIMIT * sizeof *text);
    td_value initial = {text, TD_TEXT_LIMIT, 0, TD_TEXT_LIMIT, -1, -1}; tw_keys identity = keys(9); tw_workspace workspace;
    MUST(ts_limit_pages(s, 1) == TS_OK);
    CHECK(tw_create(s, &identity, &initial, &workspace) == TS_FULL && !workspace.record.id && !workspace.draft.record.id && !workspace.stream.record.id);
    uint64_t id;
    CHECK(ts_lookup(s, identity.root, &id) == TS_NOT_FOUND && ts_lookup(s, identity.draft, &id) == TS_NOT_FOUND && ts_lookup(s, identity.stream, &id) == TS_NOT_FOUND);
    MUST(ts_limit_pages(s, 0) == TS_OK);
    CHECK(tw_create(s, &identity, &initial, &workspace) == TS_OK && workspace.draft.length == TD_TEXT_LIMIT);
    ts_close(s); MUST(ts_open(path("quota"), &s) == TS_OK);
    CHECK(tw_load(s, identity.root, &workspace) == TS_OK && workspace.draft.length == TD_TEXT_LIMIT);
    free(text); ts_close(s);
    puts("ok workspace full-store creation installs no root/child/key; retry and restart recover maximum-size draft");
}

static void crash(void) {
    tw_keys identity = keys(5); int p[2]; MUST(pipe(p) == 0); pid_t child = fork(); MUST(child >= 0);
    if (!child) {
        close(p[0]); ts_store *s; MUST(ts_open(path("crash"), &s) == TS_OK);
        uint16_t old[] = {'a','s','k'}, fresh[] = {'n','e','w'};
        td_value initial = {old, 3, 3, 0, 1, 3}; tw_workspace workspace;
        MUST(tw_create(s, &identity, &initial, &workspace) == TS_OK);
        uint8_t intent[16] = {77}; td_capture capture = {{workspace.draft.record.id, 1}, NULL, 0, NULL, 0}; tl_entry entry;
        MUST(tt_ask(s, (ts_read){workspace.stream.record.id, 1}, intent, 100, &capture, &entry) == TS_OK);
        td_value updated = {fresh, 3, 2, 1, 0, 2}; td_snapshot snapshot;
        MUST(td_save(s, capture.document, &updated, &snapshot) == TS_OK);
        uint64_t ack = snapshot.record.commit; MUST(write(p[1], &ack, sizeof ack) == sizeof ack);
        for (;;) pause();
    }
    close(p[1]); uint64_t ack; MUST(read(p[0], &ack, sizeof ack) == sizeof ack); close(p[0]);
    MUST(kill(child, SIGKILL) == 0); int status; MUST(waitpid(child, &status, 0) == child && WIFSIGNALED(status));
    ts_store *s; MUST(ts_open(path("crash"), &s) == TS_OK); tw_workspace workspace;
    MUST(tw_load(s, identity.root, &workspace) == TS_OK);
    CHECK(workspace.draft.record.commit == ack && workspace.draft.record.revision == 2 && workspace.stream.length == 1);
    uint16_t units[8]; td_snapshot snapshot;
    CHECK(td_load(s, workspace.draft.record.id, 0, &snapshot, units, 8) == TS_OK && units[0] == 'n' && snapshot.anchor == 2 && snapshot.composing_end == 2);
    tl_cursor cursor; MUST(tl_begin(&workspace.stream, &cursor) == TS_OK); tl_entry entry; size_t count;
    MUST(tl_page(s, &cursor, &entry, 1, &count) == TS_OK && count == 1);
    tt_task task; MUST(tt_load(s, entry.key, &task, NULL, 0) == TS_OK);
    CHECK(td_load(s, task.captured.document.id, task.captured.document.revision, &snapshot, units, 8) == TS_OK && units[0] == 'a' &&
          snapshot.anchor == 3 && snapshot.composing_start == 1 && task.effect.state == TS_PREPARED);
    ts_close(s);
    puts("ok workspace SIGKILL path: root alone recovers edited draft/composition plus original Ask and stream position");
}
int main(int argc, char **argv) {
    if (argc != 2 || strlen(argv[1]) >= sizeof base) { fprintf(stderr, "usage: core-workspace <directory>\n"); return 2; }
    strcpy(base, argv[1]); mkdir(base, 0700);
    roots(); quota(); crash();
    if (failures) { fprintf(stderr, "core-workspace: %d failures\n", failures); return 1; }
    puts("core-workspace: all checks passed (durable workspace ownership, not full product/IME evidence)"); return 0;
}
