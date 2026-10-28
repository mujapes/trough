#include "workspace.h"
#include <stdlib.h>
#include <string.h>

enum { ROOT_SIZE = 64 };
static int zero(const uint8_t key[16]) { static const uint8_t empty[16]; return !memcmp(key, empty, 16); }
static int valid_keys(const tw_keys *keys) {
    return keys && !zero(keys->root) && !zero(keys->draft) && !zero(keys->stream) &&
        memcmp(keys->root, keys->draft, 16) && memcmp(keys->root, keys->stream, 16) && memcmp(keys->draft, keys->stream, 16);
}
static void encode_root(const tw_keys *keys, uint8_t wire[ROOT_SIZE]) {
    memset(wire, 0, ROOT_SIZE); memcpy(wire, "TWSP\1\0\0\0", 8);
    memcpy(wire + 8, keys->root, 16); memcpy(wire + 24, keys->draft, 16); memcpy(wire + 40, keys->stream, 16);
}
static ts_status decode_root(const uint8_t wire[ROOT_SIZE], tw_keys *keys) {
    static const uint8_t reserved[8];
    if (memcmp(wire, "TWSP\1\0\0\0", 8) || memcmp(wire + 56, reserved, 8)) return TS_INVALID;
    memcpy(keys->root, wire + 8, 16); memcpy(keys->draft, wire + 24, 16); memcpy(keys->stream, wire + 40, 16);
    return valid_keys(keys) ? TS_OK : TS_INVALID;
}
ts_status tw_load(ts_store *s, const uint8_t root[16], tw_workspace *out) {
    if (!out) return TS_INVALID;
    uint8_t key[16]; if (root) memcpy(key, root, 16);
    memset(out, 0, sizeof *out);
    if (!s || !root || zero(key)) return TS_INVALID;
    uint64_t id; ts_status st = ts_lookup(s, key, &id);
    if (st) return st;
    tw_workspace w = {0}; uint8_t wire[ROOT_SIZE];
    st = ts_get(s, id, &w.record, wire, sizeof wire);
    if (st) return st == TS_CAPACITY ? TS_INVALID : st;
    if (w.record.schema != TW_SCHEMA || w.record.revision != 1 || w.record.size != ROOT_SIZE) return TS_INVALID;
    st = decode_root(wire, &w.keys);
    if (st || memcmp(w.keys.root, key, 16)) return TS_INVALID;
    st = ts_lookup(s, w.keys.draft, &id);
    if (st) return st == TS_NOT_FOUND ? TS_INVALID : st;
    st = td_load(s, id, 0, &w.draft, NULL, 0);
    if (st != TS_OK && st != TS_CAPACITY) return st == TS_NOT_FOUND ? TS_INVALID : st;
    st = ts_lookup(s, w.keys.stream, &id);
    if (st) return st == TS_NOT_FOUND ? TS_INVALID : st;
    st = tl_load(s, id, 0, &w.stream);
    if (st) return st == TS_NOT_FOUND ? TS_INVALID : st;
    *out = w; return TS_OK;
}
ts_status tw_create(ts_store *s, const tw_keys *keys, const td_value *initial, tw_workspace *out) {
    if (!out) return TS_INVALID;
    tw_keys identity; if (keys) { identity = *keys; keys = &identity; }
    memset(out, 0, sizeof *out);
    if (!s || !valid_keys(keys)) return TS_INVALID;
    uint64_t id; ts_status st = ts_lookup(s, keys->root, &id);
    if (st == TS_OK) {
        tw_workspace existing;
        st = tw_load(s, keys->root, &existing);
        if (st) return st;
        if (memcmp(keys->draft, existing.keys.draft, 16) || memcmp(keys->stream, existing.keys.stream, 16)) return TS_CONFLICT;
        *out = existing; return TS_OK;
    }
    if (st != TS_NOT_FOUND) return st;
    size_t size;
    st = td_encode(initial, NULL, 0, &size);
    if (st != TS_CAPACITY) return st;
    uint8_t *document = malloc(size);
    if (!document) return TS_IO;
    st = td_encode(initial, document, size, &size);
    if (st) { free(document); return st; }
    uint8_t wire[ROOT_SIZE], stream_wire[TL_HEAD_SIZE]; encode_root(keys, wire);
    tl_head stream = {0}; size_t stream_size;
    st = tl_head_encode(&stream, stream_wire, sizeof stream_wire, &stream_size);
    if (st) { free(document); return st; }
    ts_write writes[] = {{0, TW_SCHEMA, wire, sizeof wire, 0, NULL, 0}, {0, TD_SCHEMA, document, size, 0, NULL, 0},
                         {0, TL_HEAD_SCHEMA, stream_wire, stream_size, 0, NULL, 0}};
    ts_name names[3] = {{0, {0}}, {1, {0}}, {2, {0}}};
    memcpy(names[0].key, keys->root, 16); memcpy(names[1].key, keys->draft, 16); memcpy(names[2].key, keys->stream, 16);
    ts_receipt receipt;
    st = ts_commit_named(s, NULL, 0, writes, 3, NULL, 0, names, 3, &receipt);
    free(document);
    if (st) return st;
    out->keys = *keys;
    out->record = (ts_record){writes[0].id, 1, receipt.commit, TW_SCHEMA, sizeof wire};
    out->draft.record = (ts_record){writes[1].id, 1, receipt.commit, TD_SCHEMA, size};
    out->draft.length = initial->length; out->draft.anchor = initial->anchor; out->draft.caret = initial->caret;
    out->draft.composing_start = initial->composing_start; out->draft.composing_end = initial->composing_end;
    out->stream.record = (ts_record){writes[2].id, 1, receipt.commit, TL_HEAD_SCHEMA, stream_size};
    return TS_OK;
}
