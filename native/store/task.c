#include "task.h"
#include <string.h>

ts_status tt_ask(ts_store *s, ts_read stream, const uint8_t intent[16], uint64_t created_ms,
                  const td_capture *capture, tl_entry *out) {
    if (!out) return TS_INVALID;
    uint8_t key[16]; if (intent) { memcpy(key, intent, 16); intent = key; }
    memset(out, 0, sizeof *out);
    if (!s || !intent) return TS_INVALID;
    uint8_t request[TD_CAPTURE_LIMIT]; size_t size;
    ts_status st = td_capture_encode(capture, request, sizeof request, &size);
    if (st) return st;
    ts_effect effect = {{0}, 0, TS_PREPARED, TD_ASK_KIND, request, size};
    memcpy(effect.intent, intent, 16);
    tl_value value = {0}; value.kind = TT_ASK_ENTRY; value.created_ms = created_ms;
    value.target = (td_reference){capture->document.id, capture->document.revision, TD_SCHEMA};
    value.count = capture->count;
    if (value.count) memcpy(value.provenance, capture->dependencies, value.count * sizeof *value.provenance);
    ts_read guards[TD_DEPENDENCY_LIMIT + 1]; size_t count = 0; uint64_t id;
    st = ts_lookup(s, intent, &id);
    if (st == TS_NOT_FOUND) {
        st = td_capture_validate(s, capture, guards, TD_DEPENDENCY_LIMIT + 1, &count);
        if (st) return st;
    } else if (st != TS_OK) return st;
    return tl_append_prepared(s, stream, intent, &value, guards, count, &effect, out);
}

static int same_reference(const td_reference *a, const td_reference *b) {
    return a->id == b->id && a->revision == b->revision && a->schema == b->schema;
}
ts_status tt_load(ts_store *s, const uint8_t intent[16], tt_task *out, void *outcome, size_t capacity) {
    if (!out) return TS_INVALID;
    uint8_t key[16]; if (intent) memcpy(key, intent, 16);
    memset(out, 0, sizeof *out);
    if (!s || !intent || (capacity && !outcome)) return TS_INVALID;
    ts_status st = tl_get(s, key, &out->entry);
    if (st) return st;
    if (out->entry.value.kind != TT_ASK_ENTRY) return TS_INVALID;
    st = ts_effect_read(s, key, &out->effect, out->request, sizeof out->request, outcome, capacity);
    if (st != TS_OK && st != TS_CAPACITY) return st;
    if (out->effect.kind != TD_ASK_KIND || out->effect.request_size > sizeof out->request) return TS_INVALID;
    if (st) return st;
    st = td_capture_decode(out->request, out->effect.request_size, &out->captured);
    if (st) return st;
    td_reference target = {out->captured.document.id, out->captured.document.revision, TD_SCHEMA};
    if (!same_reference(&out->entry.value.target, &target) || out->entry.value.count != out->captured.count) return TS_INVALID;
    for (size_t i = 0; i < out->captured.count; i++)
        if (!same_reference(&out->entry.value.provenance[i], &out->captured.dependencies[i])) return TS_INVALID;
    return TS_OK;
}
