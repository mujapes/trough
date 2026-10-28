#include "document.h"
#include <limits.h>
#include <stdlib.h>
#include <string.h>

static uint32_t u32(const uint8_t *p) {
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}
static uint64_t u64(const uint8_t *p) { return u32(p) | (uint64_t)u32(p + 4) << 32; }
static void put32(uint8_t *p, uint32_t n) {
    for (unsigned i = 0; i < 4; i++) p[i] = (uint8_t)(n >> (8 * i));
}
static void put64(uint8_t *p, uint64_t n) { put32(p, (uint32_t)n); put32(p + 4, (uint32_t)(n >> 32)); }
static int valid(const td_value *v) {
    if (!v || v->length > TD_TEXT_LIMIT || (v->length && !v->text) ||
        v->anchor > v->length || v->caret > v->length) return 0;
    return (v->composing_start == -1 && v->composing_end == -1) ||
        (v->composing_start >= 0 && v->composing_end >= v->composing_start &&
         (uint32_t)v->composing_end <= v->length);
}
static void metadata(td_snapshot *s, const td_value *v) {
    s->length = v->length; s->anchor = v->anchor; s->caret = v->caret;
    s->composing_start = v->composing_start; s->composing_end = v->composing_end;
}

ts_status td_encode(const td_value *v, void *wire, size_t capacity, size_t *size) {
    if (!size) return TS_INVALID;
    *size = 0;
    if (!valid(v) || (capacity && !wire)) return TS_INVALID;
    *size = TD_HEADER_SIZE + 2 * v->length;
    if (*size > capacity) return TS_CAPACITY;
    uint8_t *p = wire;
    memcpy(p, "TDOC", 4); put32(p + 4, 1); put32(p + 8, (uint32_t)v->length);
    put32(p + 12, v->anchor); put32(p + 16, v->caret);
    put32(p + 20, (uint32_t)v->composing_start); put32(p + 24, (uint32_t)v->composing_end);
    put32(p + 28, 0);
    for (size_t i = 0; i < v->length; i++) {
        p[TD_HEADER_SIZE + 2 * i] = (uint8_t)v->text[i];
        p[TD_HEADER_SIZE + 2 * i + 1] = (uint8_t)(v->text[i] >> 8);
    }
    return TS_OK;
}

ts_status td_decode(const void *wire, size_t size, td_snapshot *out, uint16_t *text, size_t capacity) {
    if (!out) return TS_INVALID;
    memset(out, 0, sizeof *out);
    if (!wire || size < TD_HEADER_SIZE || size > TS_DATA_LIMIT || (capacity && !text)) return TS_INVALID;
    const uint8_t *p = wire;
    if (memcmp(p, "TDOC", 4) || u32(p + 4) != 1 || u32(p + 28)) return TS_INVALID;
    uint32_t n = u32(p + 8), a = u32(p + 20), b = u32(p + 24);
    if (n > TD_TEXT_LIMIT || size != TD_HEADER_SIZE + 2 * (size_t)n ||
        (a > INT32_MAX && a != UINT32_MAX) || (b > INT32_MAX && b != UINT32_MAX)) return TS_INVALID;
    td_value v = { (const uint16_t *)"", n, u32(p + 12), u32(p + 16),
        a == UINT32_MAX ? -1 : (int32_t)a, b == UINT32_MAX ? -1 : (int32_t)b };
    if (!valid(&v)) return TS_INVALID;
    metadata(out, &v);
    if (n > capacity) return TS_CAPACITY;
    for (size_t i = 0; i < n; i++) text[i] = p[TD_HEADER_SIZE + 2 * i] | (uint16_t)p[TD_HEADER_SIZE + 2 * i + 1] << 8;
    return TS_OK;
}

ts_status td_apply(const td_value *v, const td_edit *edits, size_t count,
                   uint32_t anchor, uint32_t caret, int32_t start, int32_t end,
                   uint16_t *text, size_t capacity, td_value *out) {
    if (!out || !valid(v) || (count && !edits) || (capacity && !text)) return TS_INVALID;
    if (count > TD_EDIT_LIMIT) return TS_CAPACITY;
    size_t n = v->length, peak = n;
    for (size_t i = 0; i < count; i++) {
        const td_edit *e = &edits[i];
        if (e->start > e->end || e->end > n || (e->length && !e->text)) return TS_INVALID;
        n -= e->end - e->start;
        if (e->length > TD_TEXT_LIMIT - n) return TS_CAPACITY;
        n += e->length;
        if (n > peak) peak = n;
    }
    td_value result = {text, n, anchor, caret, start, end};
    /* A zero-capacity query can still compute required length without an output buffer. */
    td_value bounds = result; bounds.text = v->text ? v->text : (const uint16_t *)"";
    if (!valid(&bounds)) return TS_INVALID;
    if (n > capacity) return TS_CAPACITY;
    /* Private scratch means an invalid/OOM batch never partially changes the caller's text.
     * Its peak is bounded by TD_TEXT_LIMIT, even for shrink-after-grow sequences. */
    uint16_t *work = malloc((peak ? peak : 1) * sizeof *work);
    if (!work) return TS_IO;
    if (v->length) memcpy(work, v->text, v->length * sizeof *work);
    size_t length = v->length;
    for (size_t i = 0; i < count; i++) {
        const td_edit *e = &edits[i];
        memmove(work + e->start + e->length, work + e->end, (length - e->end) * sizeof *work);
        if (e->length) memcpy(work + e->start, e->text, e->length * sizeof *work);
        length = length - (e->end - e->start) + e->length;
    }
    if (n) memcpy(text, work, n * sizeof *work);
    free(work);
    *out = result;
    return TS_OK;
}

static ts_status read_wire(ts_store *s, uint64_t id, uint64_t revision, ts_record *r, uint8_t **wire) {
    *wire = NULL;
    ts_status st = revision ? ts_get_revision(s, id, revision, r, NULL, 0) : ts_get(s, id, r, NULL, 0);
    if (st != TS_CAPACITY && st != TS_OK) return st;
    if (r->schema != TD_SCHEMA || r->size < TD_HEADER_SIZE || r->size > TS_DATA_LIMIT) return TS_INVALID;
    *wire = malloc(r->size);
    if (!*wire) return TS_IO;
    st = revision ? ts_get_revision(s, id, revision, r, *wire, r->size) : ts_get(s, id, r, *wire, r->size);
    if (st) { free(*wire); *wire = NULL; }
    return st;
}

/* v2: v1's header with version 2, then a TTXT manifest whose total matches the header. */
static ts_status header2(const uint8_t *p, size_t size, td_snapshot *out) {
    if (size < TD_HEADER_SIZE + TX_MANIFEST_HEADER || memcmp(p, "TDOC", 4) || u32(p + 4) != 2 || u32(p + 28) ||
        u64(p + TD_HEADER_SIZE + 16) != u32(p + 8)) return TS_INVALID;
    uint32_t n = u32(p + 8), a = u32(p + 20), b = u32(p + 24);
    if ((a > INT32_MAX && a != UINT32_MAX) || (b > INT32_MAX && b != UINT32_MAX)) return TS_INVALID;
    td_value v = { (const uint16_t *)"", 0, u32(p + 12), u32(p + 16),
        a == UINT32_MAX ? -1 : (int32_t)a, b == UINT32_MAX ? -1 : (int32_t)b };
    /* valid() bounds v1 lengths; check the selection against the indexed length directly. */
    if (v.anchor > n || v.caret > n) return TS_INVALID;
    if (!((v.composing_start == -1 && v.composing_end == -1) ||
          (v.composing_start >= 0 && v.composing_end >= v.composing_start && (uint32_t)v.composing_end <= n))) return TS_INVALID;
    metadata(out, &v);
    out->length = n;
    return TS_OK;
}
static int version(const uint8_t *wire, size_t size) { return size >= 8 ? (int)u32(wire + 4) : 0; }

ts_status td_load(ts_store *s, uint64_t id, uint64_t revision, td_snapshot *out, uint16_t *text, size_t capacity) {
    if (!out) return TS_INVALID;
    memset(out, 0, sizeof *out);
    if (!s || !id || id > INT64_MAX || revision > INT64_MAX || (capacity && !text)) return TS_INVALID;
    ts_record r;
    uint8_t *wire;
    ts_status st = read_wire(s, id, revision, &r, &wire);
    if (st) return st;
    if (version(wire, r.size) == 2) {
        st = header2(wire, r.size, out);
        if (!st && out->length > capacity) st = TS_CAPACITY;
        tx_text *t = NULL;
        if (!st && (st = tx_load(s, wire + TD_HEADER_SIZE, r.size - TD_HEADER_SIZE, &t)) == TS_OK)
            st = tx_read(t, 0, out->length, text);
        tx_release(t);
        if (st && st != TS_CAPACITY) { memset(out, 0, sizeof *out); free(wire); return st; }
    } else st = td_decode(wire, r.size, out, text, capacity);
    if (st == TS_OK || st == TS_CAPACITY) out->record = r;
    free(wire);
    return st;
}

ts_status td_load_text(ts_store *s, uint64_t id, uint64_t revision, td_snapshot *out, tx_text **text) {
    if (!out || !text) return TS_INVALID;
    memset(out, 0, sizeof *out);
    *text = NULL;
    if (!s || !id || id > INT64_MAX || revision > INT64_MAX) return TS_INVALID;
    ts_record r;
    uint8_t *wire;
    ts_status st = read_wire(s, id, revision, &r, &wire);
    if (st) return st;
    if (version(wire, r.size) == 2) {
        if (!(st = header2(wire, r.size, out))) st = tx_load(s, wire + TD_HEADER_SIZE, r.size - TD_HEADER_SIZE, text);
    } else if (!(st = td_decode(wire, r.size, out, NULL, 0)) || st == TS_CAPACITY) {
        uint16_t *flat = malloc((out->length ? out->length : 1) * sizeof *flat);
        st = flat ? td_decode(wire, r.size, out, flat, out->length) : TS_IO;
        if (!st) st = tx_build(flat, out->length, text);
        free(flat);
    }
    free(wire);
    if (st) { memset(out, 0, sizeof *out); return st; }
    out->record = r;
    return TS_OK;
}

static ts_status expect(ts_store *s, ts_read expected, ts_record *current) {
    memset(current, 0, sizeof *current);
    if (!expected.id) return TS_OK;
    if (expected.id > INT64_MAX || !expected.revision || expected.revision > INT64_MAX) return TS_INVALID;
    ts_status st = ts_get(s, expected.id, current, NULL, 0);
    if (st != TS_OK && st != TS_CAPACITY) return st;
    if (current->revision != expected.revision) return TS_CONFLICT;
    return current->schema == TD_SCHEMA ? TS_OK : TS_INVALID;
}
static ts_status commit(ts_store *s, ts_read expected, const ts_record *current, uint8_t *wire, size_t size,
                        const td_value *v, td_snapshot *out) {
    ts_write w = {expected.id, TD_SCHEMA, wire, size, 0};
    ts_receipt receipt;
    ts_status st = ts_commit(s, expected.id ? &expected : NULL, expected.id ? 1 : 0, &w, 1, NULL, 0, &receipt);
    if (!st) {
        metadata(out, v);
        out->record = (ts_record){w.id, expected.id ? expected.revision + (receipt.changed != 0) : 1,
            receipt.changed ? receipt.commit : current->commit, TD_SCHEMA, size};
    }
    return st;
}
static ts_status save(ts_store *s, ts_read expected, const td_value *v, td_snapshot *out) {
    if (!out) return TS_INVALID;
    memset(out, 0, sizeof *out);
    if (!s || !valid(v)) return TS_INVALID;
    ts_record current;
    ts_status st = expect(s, expected, &current);
    if (st) return st;
    size_t size = TD_HEADER_SIZE + 2 * v->length;
    uint8_t *wire = malloc(size);
    if (!wire) return TS_IO;
    st = td_encode(v, wire, size, &size);
    if (!st) st = commit(s, expected, &current, wire, size, v, out);
    free(wire);
    return st;
}
ts_status td_create(ts_store *s, const td_value *v, td_snapshot *out) { return save(s, (ts_read){0, 0}, v, out); }
ts_status td_save(ts_store *s, ts_read expected, const td_value *v, td_snapshot *out) {
    if (!expected.id) { if (out) memset(out, 0, sizeof *out); return TS_INVALID; }
    return save(s, expected, v, out);
}

ts_status td_save_text(ts_store *s, ts_read expected, const td_text_value *v, td_snapshot *out) {
    if (!out) return TS_INVALID;
    memset(out, 0, sizeof *out);
    if (!s || !v) return TS_INVALID;
    size_t n = tx_length(v->text);
    if (n > UINT32_MAX || v->anchor > n || v->caret > n ||
        !((v->composing_start == -1 && v->composing_end == -1) ||
          (v->composing_start >= 0 && v->composing_end >= v->composing_start && (size_t)v->composing_end <= n)))
        return TS_INVALID;
    ts_record current;
    ts_status st = expect(s, expected, &current);
    if (st) return st;
    size_t manifest;
    if ((st = tx_store(s, v->text, NULL, 0, &manifest, NULL)) != TS_CAPACITY && st) return st;
    if (manifest > TS_DATA_LIMIT - TD_HEADER_SIZE) return TS_CAPACITY;
    size_t size = TD_HEADER_SIZE + manifest;
    uint8_t *wire = malloc(size);
    if (!wire) return TS_IO;
    memcpy(wire, "TDOC", 4); put32(wire + 4, 2); put32(wire + 8, (uint32_t)n);
    put32(wire + 12, v->anchor); put32(wire + 16, v->caret);
    put32(wire + 20, (uint32_t)v->composing_start); put32(wire + 24, (uint32_t)v->composing_end);
    put32(wire + 28, 0);
    st = tx_store(s, v->text, wire + TD_HEADER_SIZE, manifest, &manifest, NULL);
    td_value meta = {NULL, 0, v->anchor, v->caret, v->composing_start, v->composing_end};
    if (!st) st = commit(s, expected, &current, wire, size, &meta, out);
    if (!st) out->length = n;
    free(wire);
    return st;
}

static int capture_valid(const td_capture *c) {
    if (!c || !c->document.id || !c->document.revision || c->document.id > INT64_MAX || c->document.revision > INT64_MAX ||
        c->count > TD_DEPENDENCY_LIMIT || (c->count && !c->dependencies) ||
        c->options_size > TD_OPTIONS_LIMIT || (c->options_size && !c->options)) return 0;
    for (size_t i = 0; i < c->count; i++) {
        const td_reference *r = &c->dependencies[i];
        if (!r->id || !r->revision || r->id > INT64_MAX || r->revision > INT64_MAX || r->id == c->document.id) return 0;
        for (size_t j = 0; j < i; j++) if (r->id == c->dependencies[j].id) return 0;
    }
    return 1;
}
ts_status td_capture_encode(const td_capture *c, void *wire, size_t capacity, size_t *size) {
    if (!size) return TS_INVALID;
    *size = 0;
    if (!capture_valid(c) || (capacity && !wire)) return TS_INVALID;
    *size = TD_CAPTURE_HEADER + 24 * c->count + c->options_size;
    if (*size > capacity) return TS_CAPACITY;
    uint8_t *p = wire;
    memcpy(p, "TASK", 4); put32(p + 4, 1); put64(p + 8, c->document.id); put64(p + 16, c->document.revision);
    put32(p + 24, (uint32_t)c->count); put32(p + 28, (uint32_t)c->options_size); put64(p + 32, 0);
    for (size_t i = 0; i < c->count; i++) {
        uint8_t *q = p + TD_CAPTURE_HEADER + i * 24;
        put64(q, c->dependencies[i].id); put64(q + 8, c->dependencies[i].revision);
        put32(q + 16, c->dependencies[i].schema); put32(q + 20, 0);
    }
    if (c->options_size) memcpy(p + TD_CAPTURE_HEADER + 24 * c->count, c->options, c->options_size);
    return TS_OK;
}
ts_status td_capture_decode(const void *wire, size_t size, td_captured *out) {
    if (!out) return TS_INVALID;
    memset(out, 0, sizeof *out);
    if (!wire || size < TD_CAPTURE_HEADER || size > TD_CAPTURE_LIMIT) return TS_INVALID;
    const uint8_t *p = wire;
    if (memcmp(p, "TASK", 4) || u32(p + 4) != 1 || u64(p + 32)) return TS_INVALID;
    td_captured c = {0};
    c.document = (ts_read){u64(p + 8), u64(p + 16)};
    c.count = u32(p + 24); c.options_size = u32(p + 28);
    if (c.count > TD_DEPENDENCY_LIMIT || c.options_size > TD_OPTIONS_LIMIT ||
        size != TD_CAPTURE_HEADER + 24 * c.count + c.options_size) return TS_INVALID;
    for (size_t i = 0; i < c.count; i++) {
        const uint8_t *q = p + TD_CAPTURE_HEADER + i * 24;
        if (u32(q + 20)) return TS_INVALID;
        c.dependencies[i] = (td_reference){u64(q), u64(q + 8), u32(q + 16)};
    }
    c.options = p + TD_CAPTURE_HEADER + 24 * c.count;
    td_capture check = {c.document, c.dependencies, c.count, c.options, c.options_size};
    if (!capture_valid(&check)) return TS_INVALID;
    *out = c;
    return TS_OK;
}

ts_status td_capture_validate(ts_store *s, const td_capture *capture, ts_read *reads, size_t capacity, size_t *count) {
    if (!count) return TS_INVALID;
    *count = 0;
    if (!s || !capture_valid(capture) || (capacity && !reads)) return TS_INVALID;
    if (capacity < capture->count + 1) return TS_CAPACITY;
    /* Validate actual typed document bytes, not only a schema tag on arbitrary data. */
    td_snapshot document;
    ts_status st = td_load(s, capture->document.id, 0, &document, NULL, 0);
    if (st != TS_OK && st != TS_CAPACITY) return st;
    if (document.record.revision != capture->document.revision) return TS_CONFLICT;
    reads[0] = capture->document;
    for (size_t i = 0; i < capture->count; i++) {
        const td_reference *r = &capture->dependencies[i];
        ts_record current;
        st = ts_get(s, r->id, &current, NULL, 0);
        if (st != TS_OK && st != TS_CAPACITY) return st;
        if (current.revision != r->revision) return TS_CONFLICT;
        if (current.schema != r->schema) return TS_INVALID;
        reads[i + 1] = (ts_read){r->id, r->revision};
    }
    *count = capture->count + 1;
    return TS_OK;
}

ts_status td_ask(ts_store *s, const uint8_t intent[16], const td_capture *capture, ts_receipt *receipt) {
    if (!receipt) return TS_INVALID;
    memset(receipt, 0, sizeof *receipt);
    if (!s || !intent) return TS_INVALID;
    uint8_t wire[TD_CAPTURE_LIMIT]; size_t size;
    ts_status st = td_capture_encode(capture, wire, sizeof wire, &size);
    if (st) return st;
    ts_effect effect = {{0}, 0, TS_PREPARED, TD_ASK_KIND, wire, size};
    memcpy(effect.intent, intent, 16);
    st = ts_effect_get(s, intent, NULL, NULL);
    if (st == TS_OK) return ts_commit(s, NULL, 0, NULL, 0, &effect, 1, receipt);
    if (st != TS_NOT_FOUND) return st;
    ts_read reads[TD_DEPENDENCY_LIMIT + 1]; size_t count;
    st = td_capture_validate(s, capture, reads, TD_DEPENDENCY_LIMIT + 1, &count);
    if (st) return st;
    return ts_commit(s, reads, count, NULL, 0, &effect, 1, receipt);
}
