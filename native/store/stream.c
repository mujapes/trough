#include "stream.h"
#include <string.h>

enum { HEAD_SIZE = TL_HEAD_SIZE, ENTRY_HEADER = 96, ENTRY_LIMIT = ENTRY_HEADER + 24 * TL_PROVENANCE_LIMIT };
static uint32_t get32(const uint8_t *p) { return p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24; }
static uint64_t get64(const uint8_t *p) { return get32(p) | (uint64_t)get32(p + 4) << 32; }
static void put32(uint8_t *p, uint32_t n) { for (unsigned i = 0; i < 4; i++) p[i] = (uint8_t)(n >> (8 * i)); }
static void put64(uint8_t *p, uint64_t n) { put32(p, (uint32_t)n); put32(p + 4, (uint32_t)(n >> 32)); }
static int zero(const uint8_t *p) { static const uint8_t empty[16]; return !memcmp(p, empty, 16); }
static int reference_ok(const td_reference *r) { return r->id && r->id <= INT64_MAX && r->revision && r->revision <= INT64_MAX; }
static int value_ok(const tl_value *v) {
    if (!v || !v->kind || v->created_ms > INT64_MAX || !reference_ok(&v->target) || v->count > TL_PROVENANCE_LIMIT) return 0;
    for (size_t i = 0; i < v->count; i++) if (!reference_ok(&v->provenance[i])) return 0;
    return 1;
}
static void putref(uint8_t *p, const td_reference *r) { put64(p, r->id); put64(p + 8, r->revision); put32(p + 16, r->schema); put32(p + 20, 0); }
static td_reference getref(const uint8_t *p) { return (td_reference){get64(p), get64(p + 8), get32(p + 16)}; }
static void encode_head(const tl_head *h, uint8_t p[HEAD_SIZE]) {
    memcpy(p, "THED", 4); put32(p + 4, 1); put64(p + 8, h->length); memcpy(p + 16, h->last, 16); put64(p + 32, 0);
}
ts_status tl_head_encode(const tl_head *head, void *wire, size_t capacity, size_t *size) {
    if (!size) return TS_INVALID;
    *size = 0;
    if (!head || head->length > INT64_MAX || (head->length == 0) != zero(head->last) || (capacity && !wire)) return TS_INVALID;
    *size = HEAD_SIZE;
    if (capacity < HEAD_SIZE) return TS_CAPACITY;
    encode_head(head, wire); return TS_OK;
}
static ts_status decode_head(const uint8_t *p, size_t n, tl_head *h) {
    if (n != HEAD_SIZE || memcmp(p, "THED", 4) || get32(p + 4) != 1 || get64(p + 32)) return TS_INVALID;
    h->length = get64(p + 8); memcpy(h->last, p + 16, 16);
    if (h->length > INT64_MAX || (h->length == 0) != zero(h->last)) return TS_INVALID;
    return TS_OK;
}
static size_t encode_entry(const tl_entry *e, uint8_t *p) {
    memcpy(p, "TENT", 4); put32(p + 4, 1); put64(p + 8, e->stream); put64(p + 16, e->sequence);
    memcpy(p + 24, e->previous, 16); memcpy(p + 40, e->key, 16);
    put32(p + 56, e->value.kind); put32(p + 60, (uint32_t)e->value.count); put64(p + 64, e->value.created_ms);
    putref(p + 72, &e->value.target);
    for (size_t i = 0; i < e->value.count; i++) putref(p + ENTRY_HEADER + 24 * i, &e->value.provenance[i]);
    return ENTRY_HEADER + 24 * e->value.count;
}
static ts_status decode_entry(const uint8_t *p, size_t n, tl_entry *e) {
    if (n < ENTRY_HEADER || n > ENTRY_LIMIT || memcmp(p, "TENT", 4) || get32(p + 4) != 1 || get32(p + 92)) return TS_INVALID;
    e->stream = get64(p + 8); e->sequence = get64(p + 16);
    memcpy(e->previous, p + 24, 16); memcpy(e->key, p + 40, 16);
    e->value.kind = get32(p + 56); e->value.count = get32(p + 60); e->value.created_ms = get64(p + 64);
    e->value.target = getref(p + 72);
    if (!e->stream || e->stream > INT64_MAX || !e->sequence || e->sequence > INT64_MAX || zero(e->key) ||
        (e->sequence == 1) != zero(e->previous) || e->value.count > TL_PROVENANCE_LIMIT ||
        n != ENTRY_HEADER + 24 * e->value.count) return TS_INVALID;
    for (size_t i = 0; i < e->value.count; i++) {
        const uint8_t *q = p + ENTRY_HEADER + 24 * i;
        if (get32(q + 20)) return TS_INVALID;
        e->value.provenance[i] = getref(q);
    }
    return value_ok(&e->value) ? TS_OK : TS_INVALID;
}

ts_status tl_load(ts_store *s, uint64_t id, uint64_t revision, tl_head *out) {
    if (!out) return TS_INVALID;
    memset(out, 0, sizeof *out);
    if (!s || !id || id > INT64_MAX || revision > INT64_MAX) return TS_INVALID;
    tl_head h = {0}; uint8_t wire[HEAD_SIZE];
    ts_status st = revision ? ts_get_revision(s, id, revision, &h.record, wire, sizeof wire) : ts_get(s, id, &h.record, wire, sizeof wire);
    if (st) return st == TS_CAPACITY ? TS_INVALID : st;
    if (h.record.schema != TL_HEAD_SCHEMA) return TS_INVALID;
    st = decode_head(wire, h.record.size, &h);
    if (!st) *out = h;
    return st;
}
ts_status tl_open(ts_store *s, const uint8_t key[16], tl_head *out) {
    if (!out) return TS_INVALID;
    memset(out, 0, sizeof *out);
    if (!s || !key || zero(key)) return TS_INVALID;
    uint64_t id;
    ts_status st = ts_lookup(s, key, &id);
    if (st == TS_OK) return tl_load(s, id, 0, out);
    if (st != TS_NOT_FOUND) return st;
    tl_head h = {0}; uint8_t wire[HEAD_SIZE]; encode_head(&h, wire);
    ts_write w = {0, TL_HEAD_SCHEMA, wire, sizeof wire, 0};
    ts_name name = {0, {0}}; memcpy(name.key, key, 16); ts_receipt receipt;
    st = ts_commit_named(s, NULL, 0, &w, 1, NULL, 0, &name, 1, &receipt);
    if (!st) { h.record = (ts_record){w.id, 1, receipt.commit, TL_HEAD_SCHEMA, sizeof wire}; *out = h; }
    return st;
}
ts_status tl_get(ts_store *s, const uint8_t key[16], tl_entry *out) {
    if (!out) return TS_INVALID;
    uint8_t saved_key[16];
    if (key) { memcpy(saved_key, key, 16); key = saved_key; }
    memset(out, 0, sizeof *out);
    if (!s || !key || zero(key)) return TS_INVALID;
    uint64_t id;
    ts_status st = ts_lookup(s, key, &id);
    if (st) return st;
    tl_entry e = {0}; uint8_t wire[ENTRY_LIMIT];
    st = ts_get(s, id, &e.record, wire, sizeof wire);
    if (st) return st == TS_CAPACITY ? TS_INVALID : st;
    /* Entry identity, content and predecessor are immutable. Raw record owners cannot
     * rewrite them without breaking this contract, even to identical-looking history. */
    if (e.record.schema != TL_ENTRY_SCHEMA || e.record.revision != 1) return TS_INVALID;
    st = decode_entry(wire, e.record.size, &e);
    if (st) return st;
    if (memcmp(key, e.key, 16)) return TS_INVALID;
    *out = e; return TS_OK;
}
static ts_status check_reference(ts_store *s, const td_reference *ref) {
    ts_record r;
    ts_status st = ts_get_revision(s, ref->id, ref->revision, &r, NULL, 0);
    if (st != TS_OK && st != TS_CAPACITY) return st;
    return r.schema == ref->schema ? TS_OK : TS_INVALID;
}
static ts_status append_entry(ts_store *s, ts_read expected, const uint8_t key[16], const tl_value *value,
                               const ts_read *guards, size_t count, const ts_effect *effect, tl_entry *out) {
    if (!out) return TS_INVALID;
    uint8_t saved_key[16]; tl_value saved_value;
    if (key) { memcpy(saved_key, key, 16); key = saved_key; }
    if (value) { saved_value = *value; value = &saved_value; }
    memset(out, 0, sizeof *out);
    if (!s || !key || zero(key) || !expected.id || expected.id > INT64_MAX ||
        !expected.revision || expected.revision > INT64_MAX || !value_ok(value)) return TS_INVALID;
    if (count > TD_DEPENDENCY_LIMIT + 1) return TS_CAPACITY;
    if ((count && !guards) || (effect && (effect->from || effect->to != TS_PREPARED || memcmp(effect->intent, key, 16)))) return TS_INVALID;
    tl_entry e = {0};
    ts_status st = tl_get(s, key, &e);
    if (st == TS_OK) {
        if (e.stream != expected.id) return TS_CONFLICT;
        uint8_t original[ENTRY_LIMIT], retry[ENTRY_LIMIT];
        size_t n = encode_entry(&e, original);
        tl_entry candidate = e; candidate.value = *value;
        size_t m = encode_entry(&candidate, retry);
        if (n != m || memcmp(original, retry, n)) return TS_CONFLICT;
        if (effect) {
            st = ts_effect_get(s, key, NULL, NULL);
            if (st != TS_OK) return st == TS_NOT_FOUND ? TS_CONFLICT : st;
            ts_receipt receipt;
            st = ts_commit(s, NULL, 0, NULL, 0, effect, 1, &receipt);
            if (st) return st;
        }
        *out = e; return TS_OK;
    }
    if (st != TS_NOT_FOUND) return st == TS_INVALID ? TS_CONFLICT : st;
    if (effect) {
        st = ts_effect_get(s, key, NULL, NULL);
        if (st != TS_NOT_FOUND) return st == TS_OK ? TS_CONFLICT : st;
    }
    tl_head head;
    st = tl_load(s, expected.id, 0, &head);
    if (st) return st;
    if (head.record.revision != expected.revision) return TS_CONFLICT;
    if (head.length == INT64_MAX) return TS_FULL;
    st = check_reference(s, &value->target);
    if (st) return st;
    for (size_t i = 0; i < value->count; i++) {
        st = check_reference(s, &value->provenance[i]); if (st) return st;
    }
    e.stream = expected.id; e.sequence = head.length + 1; e.value = *value;
    memcpy(e.key, key, 16); memcpy(e.previous, head.last, 16);
    head.length++; memcpy(head.last, key, 16);
    uint8_t head_wire[HEAD_SIZE], entry_wire[ENTRY_LIMIT]; encode_head(&head, head_wire);
    size_t size = encode_entry(&e, entry_wire);
    ts_write writes[] = {{head.record.id, TL_HEAD_SCHEMA, head_wire, sizeof head_wire, 0},
                         {0, TL_ENTRY_SCHEMA, entry_wire, size, 0}};
    ts_name name = {1, {0}}; memcpy(name.key, key, 16); ts_receipt receipt;
    ts_read reads[TD_DEPENDENCY_LIMIT + 2]; reads[0] = expected;
    if (count) memcpy(reads + 1, guards, count * sizeof *guards);
    st = ts_commit_named(s, reads, count + 1, writes, 2, effect, effect ? 1 : 0, &name, 1, &receipt);
    if (!st) { e.record = (ts_record){writes[1].id, 1, receipt.commit, TL_ENTRY_SCHEMA, size}; *out = e; }
    return st;
}

ts_status tl_append(ts_store *s, ts_read expected, const uint8_t key[16], const tl_value *value, tl_entry *out) {
    return append_entry(s, expected, key, value, NULL, 0, NULL, out);
}
ts_status tl_append_prepared(ts_store *s, ts_read expected, const uint8_t key[16], const tl_value *value,
                             const ts_read *guards, size_t count, const ts_effect *effect, tl_entry *out) {
    if (!effect) { if (out) memset(out, 0, sizeof *out); return TS_INVALID; }
    return append_entry(s, expected, key, value, guards, count, effect, out);
}

ts_status tl_begin(const tl_head *head, tl_cursor *out) {
    if (!out) return TS_INVALID;
    memset(out, 0, sizeof *out);
    if (!head || !head->record.id || head->record.id > INT64_MAX || head->record.schema != TL_HEAD_SCHEMA ||
        head->length > INT64_MAX || (head->length == 0) != zero(head->last)) return TS_INVALID;
    out->stream = head->record.id; out->remaining = head->length; memcpy(out->next, head->last, 16);
    return TS_OK;
}
ts_status tl_page(ts_store *s, tl_cursor *cursor, tl_entry *entries, size_t capacity, size_t *count) {
    if (!count) return TS_INVALID;
    *count = 0;
    if (!s || !cursor || !entries || !capacity || capacity > TL_PAGE_LIMIT ||
        !cursor->stream || cursor->stream > INT64_MAX || cursor->remaining > INT64_MAX ||
        (cursor->remaining == 0) != zero(cursor->next)) return TS_INVALID;
    tl_cursor next = *cursor; size_t n = 0;
    while (n < capacity && next.remaining) {
        ts_status st = tl_get(s, next.next, &entries[n]);
        if (st) return st;
        if (entries[n].stream != next.stream || entries[n].sequence != next.remaining) return TS_INVALID;
        memcpy(next.next, entries[n].previous, 16); next.remaining--; n++;
    }
    *cursor = next; *count = n; return TS_OK;
}
