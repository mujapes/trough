#include "text.h"
#include <stdlib.h>
#include <string.h>

/* Leaves are 1..TX_LEAF_MAX units. Edits keep every leaf at least TX_LEAF_MIN units unless the
 * whole text is one leaf, by normalising the leaves around each seam they create. */
struct tx_node {
    size_t refs, units, lines;
    unsigned height;           /* 0 for a leaf */
    unsigned durable;          /* leaf hash is a stored object (single storage owner sets it) */
    struct tx_node *left, *right;
    uint8_t hash[32];
    uint16_t text[];
};
#define LEAF(t) (!(t)->height)

tx_text *tx_retain(tx_text *t) {
    if (t) __atomic_add_fetch(&t->refs, 1, __ATOMIC_RELAXED);
    return t;
}
void tx_release(tx_text *t) {
    while (t && __atomic_sub_fetch(&t->refs, 1, __ATOMIC_ACQ_REL) == 0) {
        tx_text *right = t->right;
        tx_release(t->left);    /* recursion depth is bounded by the tree height */
        free(t);
        t = right;
    }
}
size_t tx_length(const tx_text *t) { return t ? t->units : 0; }
size_t tx_newlines(const tx_text *t) { return t ? t->lines : 0; }
static unsigned height(const tx_text *t) { return t ? t->height : 0; }

static size_t newlines(const uint16_t *p, size_t n) {
    size_t c = 0;
    for (size_t i = 0; i < n; i++) c += p[i] == 0x0a;
    return c;
}
static tx_text *leaf(const uint16_t *a, size_t an, const uint16_t *b, size_t bn, int *err) {
    tx_text *t = malloc(sizeof *t + (an + bn) * sizeof(uint16_t));
    if (!t) { *err = 1; return NULL; }
    memset(t, 0, sizeof *t);
    t->refs = 1; t->units = an + bn;
    if (an) memcpy(t->text, a, an * sizeof *a);
    if (bn) memcpy(t->text + an, b, bn * sizeof *b);
    t->lines = newlines(t->text, t->units);
    return t;
}
/* Borrowed children, owned result. */
static tx_text *node(tx_text *l, tx_text *r, int *err) {
    if (!l || !r) return tx_retain(l ? l : r);
    tx_text *t = malloc(sizeof *t);
    if (!t) { *err = 1; return NULL; }
    memset(t, 0, sizeof *t);
    t->refs = 1; t->units = l->units + r->units; t->lines = l->lines + r->lines;
    t->height = 1 + (l->height > r->height ? l->height : r->height);
    t->left = tx_retain(l); t->right = tx_retain(r);
    return t;
}
/* Children whose heights differ by at most two: one AVL rotation restores balance. */
static tx_text *balance(tx_text *l, tx_text *r, int *err) {
    tx_text *inner = NULL, *outer = NULL, *out;
    if (height(l) > height(r) + 1) {
        if (height(l->left) >= height(l->right)) {
            inner = node(l->right, r, err);
            out = inner ? node(l->left, inner, err) : NULL;
        } else {
            tx_text *m = l->right;
            outer = node(l->left, m->left, err);
            inner = outer ? node(m->right, r, err) : NULL;
            out = inner ? node(outer, inner, err) : NULL;
        }
    } else if (height(r) > height(l) + 1) {
        if (height(r->right) >= height(r->left)) {
            inner = node(l, r->left, err);
            out = inner ? node(inner, r->right, err) : NULL;
        } else {
            tx_text *m = r->left;
            outer = node(m->right, r->right, err);
            inner = outer ? node(l, m->left, err) : NULL;
            out = inner ? node(inner, outer, err) : NULL;
        }
    } else return node(l, r, err);
    tx_release(inner); tx_release(outer);
    return out;
}
/* Borrowed, owned result. Descends the taller spine; two leaves merge when they fit. */
static tx_text *join(tx_text *l, tx_text *r, int *err) {
    if (!l || !r) return tx_retain(l ? l : r);
    if (LEAF(l) && LEAF(r))
        return l->units + r->units <= TX_LEAF_MAX ? leaf(l->text, l->units, r->text, r->units, err) : node(l, r, err);
    tx_text *j, *out;
    if (l->height > r->height + 1 || LEAF(r)) {
        if (!(j = join(l->right, r, err))) return NULL;
        out = balance(l->left, j, err);
    } else if (r->height > l->height + 1 || LEAF(l)) {
        if (!(j = join(l, r->left, err))) return NULL;
        out = balance(j, r->right, err);
    } else return node(l, r, err);
    tx_release(j);
    return out;
}
static void split(tx_text *t, size_t pos, tx_text **l, tx_text **r, int *err) {
    *l = *r = NULL;
    if (!t || *err) return;
    if (pos == 0) { *r = tx_retain(t); return; }
    if (pos >= t->units) { *l = tx_retain(t); return; }
    if (LEAF(t)) {
        *l = leaf(t->text, pos, NULL, 0, err);
        *r = leaf(t->text + pos, t->units - pos, NULL, 0, err);
    } else if (pos <= t->left->units) {
        tx_text *b;
        split(t->left, pos, l, &b, err);
        *r = join(b, t->right, err);
        tx_release(b);
    } else {
        tx_text *a;
        split(t->right, pos - t->left->units, &a, r, err);
        *l = join(t->left, a, err);
        tx_release(a);
    }
    if (*err) { tx_release(*l); tx_release(*r); *l = *r = NULL; }
}
/* Perfectly balanced tree over leaves [lo, hi) of n units split as evenly as possible. */
static tx_text *build(const uint16_t *p, size_t n, size_t lo, size_t hi, size_t k, int *err) {
    if (hi - lo == 1) {
        size_t a = n * lo / k, b = n * hi / k;
        return leaf(p + a, b - a, NULL, 0, err);
    }
    size_t mid = lo + (hi - lo) / 2;
    tx_text *l = build(p, n, lo, mid, k, err), *r = l ? build(p, n, mid, hi, k, err) : NULL;
    tx_text *t = r ? node(l, r, err) : NULL;
    tx_release(l); tx_release(r);
    return t;
}
static tx_text *build_text(const uint16_t *p, size_t n, int *err) {
    if (!n) return NULL;
    size_t k = (n + TX_LEAF_TARGET - 1) / TX_LEAF_TARGET;
    return build(p, n, 0, k, k, err);
}
ts_status tx_build(const uint16_t *p, size_t n, tx_text **out) {
    if (!out) return TS_INVALID;
    *out = NULL;
    if ((n && !p)) return TS_INVALID;
    if (n > TX_UNIT_LIMIT) return TS_CAPACITY;
    int err = 0;
    *out = build_text(p, n, &err);
    return err ? TS_IO : TS_OK;
}

static const tx_text *leaf_at(const tx_text *t, size_t pos, size_t *start) {
    *start = 0;
    while (!LEAF(t)) {
        if (pos < t->left->units) t = t->left;
        else { pos -= t->left->units; *start += t->left->units; t = t->right; }
    }
    return t;
}
static ts_status read_into(const tx_text *t, size_t start, size_t count, uint16_t *out) {
    while (count) {
        size_t at;
        const tx_text *l = leaf_at(t, start, &at);
        size_t n = l->units - (start - at);
        if (n > count) n = count;
        memcpy(out, l->text + (start - at), n * sizeof *out);
        out += n; start += n; count -= n;
    }
    return TS_OK;
}
ts_status tx_read(const tx_text *t, size_t start, size_t count, uint16_t *out) {
    if ((count && !out) || start > tx_length(t) || count > tx_length(t) - start) return TS_INVALID;
    return read_into(t, start, count, out);
}
/* Replace [ws, we) with the same units rebuilt into even leaves; nothing else changes. */
static tx_text *rechunk(tx_text *t, size_t ws, size_t we, int *err) {
    uint16_t *buf = malloc((we - ws) * sizeof *buf);
    if (!buf) { *err = 1; return NULL; }
    read_into(t, ws, we - ws, buf);
    tx_text *a, *rest, *mid, *c, *m = NULL, *am = NULL, *out = NULL;
    split(t, ws, &a, &rest, err);
    split(rest, we - ws, &mid, &c, err);
    if (!*err) m = build_text(buf, we - ws, err);
    if (!*err) am = join(a, m, err);
    if (!*err) out = join(am, c, err);
    free(buf);
    tx_release(a); tx_release(rest); tx_release(mid); tx_release(c); tx_release(m); tx_release(am);
    return out;
}
/* A leaf beside the seam at offset that is smaller than TX_LEAF_MIN is rebuilt together with its
 * neighbours (a window of at most four leaves). Leaves that are already large enough are left
 * alone, so typing rewrites one chunk rather than rechunking its neighbours. */
static tx_text *normalise(tx_text *t, size_t offset, int *err) {
    if (!t || LEAF(t) || !offset || offset >= t->units) return tx_retain(t);
    size_t s1, s2;
    const tx_text *l1 = leaf_at(t, offset - 1, &s1), *l2 = leaf_at(t, offset, &s2);
    if (l1 == l2) return tx_retain(t);
    size_t ws = s1, we = s2 + l2->units;
    if (l1->units >= TX_LEAF_MIN && l2->units >= TX_LEAF_MIN) return tx_retain(t);
    if (l1->units < TX_LEAF_MIN && ws) { size_t s; leaf_at(t, ws - 1, &s); ws = s; }
    if (l2->units < TX_LEAF_MIN && we < t->units) { size_t s; const tx_text *n = leaf_at(t, we, &s); we = s + n->units; }
    return rechunk(t, ws, we, err);
}
ts_status tx_edit(tx_text *t, size_t start, size_t end, const uint16_t *p, size_t n, tx_text **out) {
    if (!out) return TS_INVALID;
    *out = NULL;
    size_t length = tx_length(t);
    if (start > end || end > length || (n && !p)) return TS_INVALID;
    if (n > TX_UNIT_LIMIT - (length - (end - start))) return TS_CAPACITY;
    int err = 0;
    tx_text *a, *rest, *gone, *c, *m, *am = NULL, *raw = NULL, *once = NULL, *done = NULL;
    split(t, start, &a, &rest, &err);
    split(rest, end - start, &gone, &c, &err);
    m = err ? NULL : build_text(p, n, &err);
    if (!err) am = join(a, m, &err);
    if (!err) raw = join(am, c, &err);
    if (!err) once = normalise(raw, start, &err);
    if (!err) done = n ? normalise(once, start + n, &err) : tx_retain(once);
    tx_release(a); tx_release(rest); tx_release(gone); tx_release(c); tx_release(m);
    tx_release(am); tx_release(raw); tx_release(once);
    if (err) { tx_release(done); return TS_IO; }
    *out = done;
    return TS_OK;
}

ts_status tx_line_start(const tx_text *t, size_t line, size_t *offset) {
    if (!offset) return TS_INVALID;
    *offset = 0;
    if (!line) return TS_OK;
    if (line > tx_newlines(t)) return TS_NOT_FOUND;
    size_t at = 0;              /* find the line-th newline; the line starts after it */
    while (!LEAF(t)) {
        if (line <= t->left->lines) t = t->left;
        else { line -= t->left->lines; at += t->left->units; t = t->right; }
    }
    for (size_t i = 0;; i++)
        if (t->text[i] == 0x0a && !--line) { *offset = at + i + 1; return TS_OK; }
}
ts_status tx_line_of(const tx_text *t, size_t offset, size_t *line) {
    if (!line || offset > tx_length(t)) return TS_INVALID;
    *line = 0;
    while (t && !LEAF(t)) {
        if (offset < t->left->units) t = t->left;
        else { *line += t->left->lines; offset -= t->left->units; t = t->right; }
    }
    if (t) *line += newlines(t->text, offset);
    return TS_OK;
}
ts_status tx_chunk(const tx_text *t, size_t offset, const uint16_t **units, size_t *length, size_t *chunk_start) {
    if (!units || !length || !chunk_start || offset >= tx_length(t)) return TS_INVALID;
    const tx_text *l = leaf_at(t, offset, chunk_start);
    *units = l->text; *length = l->units;
    return TS_OK;
}

static int check(const tx_text *t, tx_stats *s) {
    if (LEAF(t)) {
        if (!t->units || t->units > TX_LEAF_MAX || t->left || t->right || t->lines != newlines(t->text, t->units)) return 0;
        s->leaves++;
        if (!s->smallest || t->units < s->smallest) s->smallest = t->units;
        if (t->units > s->largest) s->largest = t->units;
        return 1;
    }
    if (!t->left || !t->right || !check(t->left, s) || !check(t->right, s)) return 0;
    unsigned hl = t->left->height, hr = t->right->height;
    return t->height == 1 + (hl > hr ? hl : hr) && (hl > hr ? hl - hr : hr - hl) <= 1 &&
        t->units == t->left->units + t->right->units && t->lines == t->left->lines + t->right->lines;
}
ts_status tx_verify(const tx_text *t, tx_stats *s) {
    if (!s) return TS_INVALID;
    memset(s, 0, sizeof *s);
    if (!t) return TS_OK;
    s->height = t->height;
    return check(t, s) ? TS_OK : TS_INVALID;
}

/* Durable form. Manifest v1: "TTXT", version, leaf count, reserved 0, units (u64), newlines
 * (u64); then per leaf: SHA-256 of its UTF-16LE object, units (u32), newlines (u32). */
static uint32_t u32(const uint8_t *p) {
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}
static uint64_t u64(const uint8_t *p) { return u32(p) | (uint64_t)u32(p + 4) << 32; }
static void put32(uint8_t *p, uint32_t n) { for (unsigned i = 0; i < 4; i++) p[i] = (uint8_t)(n >> (8 * i)); }
static void put64(uint8_t *p, uint64_t n) { put32(p, (uint32_t)n); put32(p + 4, (uint32_t)(n >> 32)); }
static size_t leaves(const tx_text *t) { return !t ? 0 : LEAF(t) ? 1 : leaves(t->left) + leaves(t->right); }
static void collect(tx_text *t, tx_text **out, size_t *n) {
    if (!t) return;
    if (LEAF(t)) { out[(*n)++] = t; return; }
    collect(t->left, out, n); collect(t->right, out, n);
}

ts_status tx_store(ts_store *s, tx_text *t, void *manifest, size_t capacity, size_t *size, tx_saved *saved) {
    if (!size) return TS_INVALID;
    *size = 0;
    if (saved) memset(saved, 0, sizeof *saved);
    if (!s || (capacity && !manifest)) return TS_INVALID;
    size_t count = leaves(t);
    if (count > UINT32_MAX) return TS_CAPACITY;
    *size = TX_MANIFEST_HEADER + TX_MANIFEST_ENTRY * count;
    if (*size > capacity) return TS_CAPACITY;
    tx_text **list = malloc((count ? count : 1) * sizeof *list);
    uint8_t *wire = malloc(TX_LEAF_MAX * 2);
    ts_status st = TS_IO;
    if (!list || !wire) goto out;
    size_t n = 0;
    collect(t, list, &n);
    for (size_t i = 0; i < n; i++) {
        tx_text *l = list[i];
        if (__atomic_load_n(&l->durable, __ATOMIC_ACQUIRE)) continue;
        for (size_t j = 0; j < l->units; j++) { wire[2 * j] = (uint8_t)l->text[j]; wire[2 * j + 1] = (uint8_t)(l->text[j] >> 8); }
        if ((st = ts_object_put(s, wire, l->units * 2, l->hash))) goto out;
        __atomic_store_n(&l->durable, 1, __ATOMIC_RELEASE);
        if (saved) saved->written++;
    }
    uint8_t *p = manifest;
    memcpy(p, "TTXT", 4); put32(p + 4, 1); put32(p + 8, (uint32_t)n); put32(p + 12, 0);
    put64(p + 16, tx_length(t)); put64(p + 24, tx_newlines(t));
    for (size_t i = 0; i < n; i++) {
        uint8_t *e = p + TX_MANIFEST_HEADER + TX_MANIFEST_ENTRY * i;
        memcpy(e, list[i]->hash, 32); put32(e + 32, (uint32_t)list[i]->units); put32(e + 36, (uint32_t)list[i]->lines);
    }
    if (saved) saved->chunks = n;
    st = TS_OK;
out:
    free(list); free(wire);
    return st;
}

static tx_text *assemble(tx_text **list, size_t lo, size_t hi, int *err) {
    if (hi - lo == 1) return tx_retain(list[lo]);
    size_t mid = lo + (hi - lo) / 2;
    tx_text *l = assemble(list, lo, mid, err), *r = l ? assemble(list, mid, hi, err) : NULL;
    tx_text *t = r ? node(l, r, err) : NULL;
    tx_release(l); tx_release(r);
    return t;
}
ts_status tx_load(ts_store *s, const void *manifest, size_t size, tx_text **out) {
    if (!out) return TS_INVALID;
    *out = NULL;
    const uint8_t *p = manifest;
    if (!s || !p || size < TX_MANIFEST_HEADER || memcmp(p, "TTXT", 4) || u32(p + 4) != 1 || u32(p + 12)) return TS_INVALID;
    size_t n = u32(p + 8);
    if ((size - TX_MANIFEST_HEADER) / TX_MANIFEST_ENTRY != n || (size - TX_MANIFEST_HEADER) % TX_MANIFEST_ENTRY) return TS_INVALID;
    uint64_t units = 0, lines = 0;
    for (size_t i = 0; i < n; i++) {
        const uint8_t *e = p + TX_MANIFEST_HEADER + TX_MANIFEST_ENTRY * i;
        uint32_t u = u32(e + 32), l = u32(e + 36);
        if (!u || u > TX_LEAF_MAX || l > u) return TS_INVALID;
        units += u; lines += l;
    }
    if (units != u64(p + 16) || lines != u64(p + 24) || units > TX_UNIT_LIMIT) return TS_INVALID;
    if (!n) return TS_OK;
    tx_text **list = calloc(n, sizeof *list);
    uint8_t *wire = malloc(TX_LEAF_MAX * 2);
    ts_status st = TS_IO;
    int err = 0;
    if (!list || !wire) goto out;
    for (size_t i = 0; i < n; i++) {
        const uint8_t *e = p + TX_MANIFEST_HEADER + TX_MANIFEST_ENTRY * i;
        size_t got, u = u32(e + 32);
        if ((st = ts_object_get(s, e, wire, TX_LEAF_MAX * 2, &got)) == TS_CAPACITY || (!st && got != 2 * u)) st = TS_INVALID;
        if (st) goto out;
        st = TS_IO;
        if (!(list[i] = malloc(sizeof *list[i] + u * 2))) goto out;
        memset(list[i], 0, sizeof *list[i]);
        list[i]->refs = 1; list[i]->units = u;
        for (size_t j = 0; j < u; j++) list[i]->text[j] = (uint16_t)(wire[2 * j] | wire[2 * j + 1] << 8);
        list[i]->lines = newlines(list[i]->text, u);
        if (list[i]->lines != u32(e + 36)) { st = TS_INVALID; goto out; }
        memcpy(list[i]->hash, e, 32); list[i]->durable = 1;
    }
    *out = assemble(list, 0, n, &err);
    st = err ? TS_IO : TS_OK;
    if (err) { tx_release(*out); *out = NULL; }
out:
    if (list) for (size_t i = 0; i < n; i++) tx_release(list[i]);
    free(list); free(wire);
    return st;
}
